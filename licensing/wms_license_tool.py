#!/usr/bin/env python3
"""
wms_license_tool.py - root/customer keypair generation, certificate signing, license
signing, and QR generation for the offline, certificate-chain licensing system.

See README.md for the full workflow and the trust model this implements. In short:

  root keypair (generated once, ever, here)
    -> signs a per-customer CERTIFICATE binding that customer's own public key
       (WMSCERT1.<payload>.<signature>, delivered as a QR code, verified by the
       Android app against its compiled-in copy of the root PUBLIC key)
    -> the customer's own private key signs LICENSEs
       (WMSLIC1.<payload>.<signature>, pasted into F&O, verified by the app against
       the certificate's own customer public key - never against anything F&O asserts)

The root private key and every customer's private key are generated and stay ONLY in
this tool's output folders (root/, clients/<name>/) - see .gitignore. Nothing with
"-local." in its name is ever committed or handed to a customer. A customer receives
only a certificate string/QR (public) and license strings (public) - never a private
key.

Usage:
    python wms_license_tool.py generate-root
    python wms_license_tool.py generate-customer-keypair --client "Contoso Warehousing"
    python wms_license_tool.py sign-certificate --client "Contoso Warehousing" \\
        --tenant-id CONTOSO-PROD --not-after 2031-01-01
    python wms_license_tool.py sign-license --client "Contoso Warehousing" \\
        --expiry 2027-01-01 --max-devices 25
    python wms_license_tool.py verify \\
        --certificate "clients/Contoso Warehousing/certificate.txt" \\
        --license "clients/Contoso Warehousing/licenses/2027-01-01.txt"
"""
from __future__ import annotations

import argparse
import base64
import datetime
import json
import os
import sys
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
import qrcode
from qrcode.constants import ERROR_CORRECT_L

TOOL_DIR = Path(__file__).resolve().parent
ROOT_DIR = TOOL_DIR / "root"
CLIENTS_DIR = TOOL_DIR / "clients"

CERT_PREFIX = "WMSCERT1"
LICENSE_PREFIX = "WMSLIC1"

# The same "yyyy-MM-ddTHH:mm:ssZ" shape HSMobileOpsLicense expects, matching this
# project's own ISO8601 convention elsewhere in the AOS model.
DATE_FMT = "%Y-%m-%dT%H:%M:%SZ"


# -- base64url ------------------------------------------------------------------------
#
# Unpadded, RFC 4648 section 5 - JWS-compact-shaped, safe inside a QR/URL with no
# escaping. Android's Base64.URL_SAFE|NO_PADDING|NO_WRAP decodes this directly.

def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_decode(text: str) -> bytes:
    pad = (-len(text)) % 4
    return base64.urlsafe_b64decode(text + ("=" * pad))


# -- RSAKeyValue XML --------------------------------------------------------------------
#
# .NET's RSACryptoServiceProvider.ToXmlString(false) format - what HSMobileOpsLicense
# already parses via FromXmlString today, and what a customer's embedded public key is
# ALSO carried as (alongside PEM) inside a certificate payload, so AOS never needs an
# unverified modern .NET crypto API to consume it. `cryptography` has no native writer
# for this format, so it's built by hand from the raw public numbers - the only two
# integers RSAKeyValue XML actually carries.

def rsa_public_key_to_xml(public_key: rsa.RSAPublicKey) -> str:
    numbers = public_key.public_numbers()
    modulus_bytes = numbers.n.to_bytes((numbers.n.bit_length() + 7) // 8, "big")
    exponent_bytes = numbers.e.to_bytes((numbers.e.bit_length() + 7) // 8, "big")
    modulus_b64 = base64.b64encode(modulus_bytes).decode("ascii")
    exponent_b64 = base64.b64encode(exponent_bytes).decode("ascii")
    return f"<RSAKeyValue><Modulus>{modulus_b64}</Modulus><Exponent>{exponent_b64}</Exponent></RSAKeyValue>"


def rsa_public_key_from_xml(xml: str) -> rsa.RSAPublicKey:
    """The inverse of rsa_public_key_to_xml - what `verify` needs to check a license against
    the customer key a certificate carries, now that the certificate only carries the XML
    form (see sign-certificate's own doc on why the PEM copy was dropped). Plain substring
    search, not a real XML parser - two fixed tag pairs from a source this tool itself
    controls the shape of, same reasoning LicenseCrypto.parseRsaKeyValueXml uses on the
    Android side."""
    modulus_b64 = xml.split("<Modulus>", 1)[1].split("</Modulus>", 1)[0]
    exponent_b64 = xml.split("<Exponent>", 1)[1].split("</Exponent>", 1)[0]
    n = int.from_bytes(base64.b64decode(modulus_b64), "big")
    e = int.from_bytes(base64.b64decode(exponent_b64), "big")
    return rsa.RSAPublicNumbers(e, n).public_key()


# -- Keypair generation / IO -------------------------------------------------------------

def generate_rsa_keypair() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def write_keypair(private_key: rsa.RSAPrivateKey, dir_path: Path, name: str) -> None:
    dir_path.mkdir(parents=True, exist_ok=True)

    private_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_key = private_key.public_key()
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    public_xml = rsa_public_key_to_xml(public_key)

    (dir_path / f"{name}-private-key.local.pem").write_bytes(private_pem)
    (dir_path / f"{name}-public-key.pem").write_bytes(public_pem)
    (dir_path / f"{name}-public-key.xml").write_text(public_xml, encoding="utf-8")


def load_private_key(path: Path) -> rsa.RSAPrivateKey:
    return serialization.load_pem_private_key(path.read_bytes(), password=None)


def load_public_key_pem(text: str) -> rsa.RSAPublicKey:
    return serialization.load_pem_public_key(text.encode("utf-8"))


# -- Signing / verifying ------------------------------------------------------------------
#
# SHA256withRSA / PKCS#1 v1.5 (not PSS - no salt-length parameter to keep in sync across
# Python, X++ and Kotlin). Signed over the base64url PAYLOAD TEXT, not the decoded JSON
# bytes - matches HSMobileOpsLicense::verifySignature's existing convention exactly, so
# every platform's check is just "split on '.', verify the first segment's own bytes."

def sign_payload_b64(private_key: rsa.RSAPrivateKey, payload_b64: str) -> str:
    signature = private_key.sign(payload_b64.encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
    return b64url_encode(signature)


def verify_payload_b64(public_key: rsa.RSAPublicKey, payload_b64: str, signature_b64: str) -> bool:
    try:
        public_key.verify(
            b64url_decode(signature_b64),
            payload_b64.encode("ascii"),
            padding.PKCS1v15(),
            hashes.SHA256(),
        )
        return True
    except Exception:
        return False


def build_signed_string(prefix: str, payload: dict, private_key: rsa.RSAPrivateKey) -> str:
    payload_json = json.dumps(payload, separators=(",", ":"))
    payload_b64 = b64url_encode(payload_json.encode("utf-8"))
    signature_b64 = sign_payload_b64(private_key, payload_b64)
    return f"{prefix}.{payload_b64}.{signature_b64}"


def parse_signed_string(signed: str) -> tuple[str, dict, str, str]:
    parts = signed.split(".")
    if len(parts) != 3:
        raise ValueError(f"malformed signed string: expected 3 dot-separated parts, got {len(parts)}")
    prefix, payload_b64, signature_b64 = parts
    payload = json.loads(b64url_decode(payload_b64))
    return prefix, payload, payload_b64, signature_b64


def parse_date(text: str) -> datetime.datetime:
    """Accepts a bare date (2027-01-01) or a full ISO8601 - always returned UTC."""
    parsed = datetime.datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=datetime.timezone.utc)
    return parsed.astimezone(datetime.timezone.utc)


# -- Subcommands ----------------------------------------------------------------------

def cmd_generate_root(args: argparse.Namespace) -> None:
    existing = list(ROOT_DIR.glob("root-private-key.local.pem"))
    if existing and not args.force:
        print(f"ERROR: a root keypair already exists at {ROOT_DIR}.")
        print("Rotating it invalidates every customer's certificate at once - pass --force")
        print("only if that is genuinely intended.")
        sys.exit(1)

    private_key = generate_rsa_keypair()
    write_keypair(private_key, ROOT_DIR, "root")

    print(f"Root keypair written to {ROOT_DIR}")
    print("  root-private-key.local.pem  -- NEVER commit, never leaves this machine")
    print("  root-public-key.pem         -- bake into the Android app (LicenseRootTrustAnchor.kt)")
    print("  root-public-key.xml         -- bake into X++ (HSMobileOpsLicense.rootPublicKeyXml())")


def cmd_generate_customer_keypair(args: argparse.Namespace) -> None:
    client_dir = CLIENTS_DIR / args.client
    existing = list(client_dir.glob("customer-private-key.local.pem"))
    if existing and not args.force:
        print(f"ERROR: '{args.client}' already has a keypair at {client_dir}.")
        print("Re-running invalidates any certificate/license already issued to this")
        print("client - pass --force only if that is genuinely intended.")
        sys.exit(1)

    private_key = generate_rsa_keypair()
    write_keypair(private_key, client_dir, "customer")
    print(f"Customer keypair for '{args.client}' written to {client_dir}")


def cmd_sign_certificate(args: argparse.Namespace) -> None:
    root_private_key_path = ROOT_DIR / "root-private-key.local.pem"
    if not root_private_key_path.exists():
        print(f"ERROR: no root keypair at {ROOT_DIR}. Run generate-root first.")
        sys.exit(1)
    root_private_key = load_private_key(root_private_key_path)

    client_dir = CLIENTS_DIR / args.client
    public_pem_path = client_dir / "customer-public-key.pem"
    public_xml_path = client_dir / "customer-public-key.xml"
    if not public_pem_path.exists() or not public_xml_path.exists():
        print(f"ERROR: no customer keypair for '{args.client}'. Run generate-customer-keypair first.")
        sys.exit(1)

    not_before = datetime.datetime.now(datetime.timezone.utc)
    not_after = parse_date(args.not_after)
    if not_after <= not_before:
        print(f"ERROR: --not-after ({args.not_after}) is not in the future.")
        sys.exit(1)

    # Only the RSAKeyValue XML form travels in the certificate - carrying the PEM copy too
    # (the original design) roughly doubled the payload and produced a QR too dense to scan
    # reliably on a real device (confirmed live: version 35, near the practical maximum).
    # AOS already verifies against XML (RSACryptoServiceProvider.FromXmlString, confirmed
    # working); Android parses the same XML itself (LicenseCrypto.parseRsaKeyValueXml) rather
    # than needing a second encoding. customer-public-key.pem is still written to disk as a
    # human-readable reference - it just isn't embedded in the signed payload.
    payload = {
        "v": 1,
        "kid": args.kid,
        "customerId": args.client,
        "tenantId": args.tenant_id,
        "customerPublicKeyXml": public_xml_path.read_text(encoding="utf-8"),
        "notBefore": not_before.strftime(DATE_FMT),
        "notAfter": not_after.strftime(DATE_FMT),
    }
    cert_string = build_signed_string(CERT_PREFIX, payload, root_private_key)

    cert_path = client_dir / "certificate.txt"
    cert_path.write_text(cert_string, encoding="utf-8")

    # Error-correction L (not the qrcode default M) - fewer parity bytes means a lower QR
    # version for the same payload (confirmed: version 28/129 modules at M vs 24/113 at L for
    # this payload size), which is the difference between "scans reliably" and "doesn't scan
    # at all" on a real device at this payload size. Purely a QR-encoding choice - the signed
    # cert_string itself, and everything that verifies it, is unaffected.
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_L, box_size=12, border=4)
    qr.add_data(cert_string)
    qr.make(fit=True)
    image = qr.make_image(fill_color="black", back_color="white")
    qr_path = client_dir / "certificate-qr.png"
    image.save(qr_path)

    print(f"Certificate written to {cert_path}")
    print(f"  length: {len(cert_string)} characters, QR version: {qr.version}")
    print(f"QR image written to {qr_path}")
    print("Scan it once on a real device before printing/shipping it.")


def cmd_sign_license(args: argparse.Namespace) -> None:
    client_dir = CLIENTS_DIR / args.client
    private_key_path = client_dir / "customer-private-key.local.pem"
    if not private_key_path.exists():
        print(f"ERROR: no customer keypair for '{args.client}'. Run generate-customer-keypair first.")
        sys.exit(1)
    customer_private_key = load_private_key(private_key_path)

    tenant_id = args.tenant_id
    if tenant_id is None:
        cert_path = client_dir / "certificate.txt"
        if not cert_path.exists():
            print("ERROR: --tenant-id was not given and no certificate.txt exists yet to read it from.")
            sys.exit(1)
        _, cert_payload, _, _ = parse_signed_string(cert_path.read_text(encoding="utf-8").strip())
        tenant_id = cert_payload["tenantId"]
        print(f"Using tenantId '{tenant_id}' from {cert_path}")

    expiry = parse_date(args.expiry)
    issued = datetime.datetime.now(datetime.timezone.utc)
    if expiry <= issued:
        print(f"ERROR: --expiry ({args.expiry}) is not in the future.")
        sys.exit(1)

    payload = {
        "v": 1,
        "customerId": args.client,
        "tenantId": tenant_id,
        "expiryUtc": expiry.strftime(DATE_FMT),
        "maxDevices": args.max_devices,
        "issuedUtc": issued.strftime(DATE_FMT),
    }
    license_string = build_signed_string(LICENSE_PREFIX, payload, customer_private_key)

    licenses_dir = client_dir / "licenses"
    licenses_dir.mkdir(parents=True, exist_ok=True)
    license_path = licenses_dir / f"{expiry.date().isoformat()}.txt"
    license_path.write_text(license_string, encoding="utf-8")

    print(f"License written to {license_path}")
    print()
    print("Paste this into the F&O license import form (Certificate + License fields):")
    print()
    print(license_string)


def cmd_verify(args: argparse.Namespace) -> None:
    root_public_key_path = ROOT_DIR / "root-public-key.pem"
    if not root_public_key_path.exists():
        print(f"ERROR: no root public key at {root_public_key_path}. Run generate-root first.")
        sys.exit(1)
    root_public_key = load_public_key_pem(root_public_key_path.read_text(encoding="utf-8"))

    cert_string = Path(args.certificate).read_text(encoding="utf-8").strip()
    prefix, cert_payload, cert_payload_b64, cert_sig_b64 = parse_signed_string(cert_string)
    if prefix != CERT_PREFIX:
        print(f"FAIL: certificate has wrong prefix '{prefix}', expected '{CERT_PREFIX}'")
        sys.exit(1)
    if not verify_payload_b64(root_public_key, cert_payload_b64, cert_sig_b64):
        print("FAIL: certificate signature does not verify against the root public key")
        sys.exit(1)
    print(f"OK   certificate signature verifies against the root key")
    print(f"     customerId={cert_payload['customerId']}  tenantId={cert_payload['tenantId']}")

    now = datetime.datetime.now(datetime.timezone.utc)
    not_before = datetime.datetime.strptime(cert_payload["notBefore"], DATE_FMT).replace(tzinfo=datetime.timezone.utc)
    not_after = datetime.datetime.strptime(cert_payload["notAfter"], DATE_FMT).replace(tzinfo=datetime.timezone.utc)
    if not (not_before <= now <= not_after):
        print(f"FAIL: certificate is outside its validity window ({cert_payload['notBefore']} .. {cert_payload['notAfter']})")
        sys.exit(1)
    print(f"OK   certificate is within its validity window (until {cert_payload['notAfter']})")

    customer_public_key = rsa_public_key_from_xml(cert_payload["customerPublicKeyXml"])

    license_string = Path(args.license).read_text(encoding="utf-8").strip()
    prefix, license_payload, license_payload_b64, license_sig_b64 = parse_signed_string(license_string)
    if prefix != LICENSE_PREFIX:
        print(f"FAIL: license has wrong prefix '{prefix}', expected '{LICENSE_PREFIX}'")
        sys.exit(1)
    if not verify_payload_b64(customer_public_key, license_payload_b64, license_sig_b64):
        print("FAIL: license signature does not verify against the certificate's customer public key")
        sys.exit(1)
    print("OK   license signature verifies against the certificate's own customer public key")

    if (license_payload["customerId"], license_payload["tenantId"]) != (cert_payload["customerId"], cert_payload["tenantId"]):
        print("FAIL: license customerId/tenantId does not match the certificate")
        sys.exit(1)
    print("OK   license customerId/tenantId match the certificate")

    expiry = datetime.datetime.strptime(license_payload["expiryUtc"], DATE_FMT).replace(tzinfo=datetime.timezone.utc)
    if now > expiry:
        print(f"FAIL: license expired {license_payload['expiryUtc']}")
        sys.exit(1)
    days_left = (expiry - now).days
    print(f"OK   license valid - expires {license_payload['expiryUtc']} ({days_left} day(s) left)")
    print(f"     maxDevices={license_payload['maxDevices']}")
    print()
    print("Full chain verifies end to end.")


def cmd_azure_register(args: argparse.Namespace) -> None:
    """Sets one customer's row in the CheckLicense Function's LicenseStatus table - see
    ../azure-license-function/README.md for what that Function does with it. This is the
    operational lever for "revoke this customer right now": one command, no app rebuild, no
    root-key rotation, no per-device reactivation - see that README's own PartitionKey/RowKey
    shape, which this mirrors exactly (PartitionKey=customerId, RowKey='status').

    Deliberately a separate command from sign-certificate/sign-license, not folded into either -
    this writes to the VENDOR's own Azure subscription, a completely different trust boundary
    from the customer-specific signing operations above, and needs its own explicit
    --connection-string (or AZURE_TABLE_CONNECTION_STRING) to run at all.
    """
    try:
        from azure.data.tables import TableServiceClient
    except ImportError:
        print("ERROR: azure-data-tables is not installed. pip install azure-data-tables")
        print("(only needed for this one command - see requirements.txt)")
        sys.exit(1)

    connection_string = args.connection_string or os.environ.get("AZURE_TABLE_CONNECTION_STRING")
    if not connection_string:
        print("ERROR: no connection string given. Pass --connection-string or set AZURE_TABLE_CONNECTION_STRING.")
        sys.exit(1)

    if args.status not in ("Active", "Suspended", "Revoked"):
        print(f"ERROR: --status must be Active, Suspended or Revoked, got {args.status!r}")
        sys.exit(1)

    entity = {
        "PartitionKey": args.client,
        "RowKey": "status",
        "Status": args.status,
        "Notes": args.notes or "",
    }

    with TableServiceClient.from_connection_string(connection_string) as service:
        table_client = service.get_table_client("LicenseStatus")
        table_client.upsert_entity(entity)

    print(f"customerId '{args.client}' set to Status={args.status} in the LicenseStatus table.")
    if args.notes:
        print(f"  notes: {args.notes}")
    if args.status != "Active":
        print("The next sign-in or license check for this customer will be refused within a few")
        print("minutes (once AOS's own short grace-period cache, if any, expires).")


# -- CLI wiring -------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Offline licensing: root/customer keypairs, certificate + license signing, QR generation.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("generate-root", help="Generate the root RSA-2048 keypair. Run once, ever.")
    p.add_argument("--force", action="store_true", help="Overwrite an existing root keypair.")
    p.set_defaults(func=cmd_generate_root)

    p = sub.add_parser("generate-customer-keypair", help="Generate a per-customer RSA-2048 keypair.")
    p.add_argument("--client", required=True, help="Customer name - used as both the folder name and the certificate's customerId.")
    p.add_argument("--force", action="store_true", help="Overwrite an existing keypair for this client.")
    p.set_defaults(func=cmd_generate_customer_keypair)

    p = sub.add_parser("sign-certificate", help="Sign a customer certificate with the root private key; also writes the QR image.")
    p.add_argument("--client", required=True)
    p.add_argument("--tenant-id", required=True, help="Vendor-assigned opaque tenant/environment id, e.g. CONTOSO-PROD.")
    p.add_argument("--not-after", required=True, help="Certificate expiry, e.g. 2031-01-01.")
    p.add_argument("--kid", default="root-2026", help="Key id of the root key signing this certificate.")
    p.set_defaults(func=cmd_sign_certificate)

    p = sub.add_parser("sign-license", help="Sign a license with a customer's private key.")
    p.add_argument("--client", required=True)
    p.add_argument("--tenant-id", help="Defaults to the tenantId already recorded in this client's certificate.txt.")
    p.add_argument("--expiry", required=True, help="License expiry, e.g. 2027-01-01.")
    p.add_argument("--max-devices", required=True, type=int)
    p.set_defaults(func=cmd_sign_license)

    p = sub.add_parser("verify", help="Locally verify a certificate+license pair end to end - no F&O or Android involved.")
    p.add_argument("--certificate", required=True, help="Path to a certificate.txt file.")
    p.add_argument("--license", required=True, help="Path to a license .txt file.")
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser("azure-register", help="Set a customer's live status (Active/Suspended/Revoked) in the CheckLicense Azure Table.")
    p.add_argument("--client", required=True, help="customerId - same value used in sign-certificate/sign-license.")
    p.add_argument("--status", required=True, choices=["Active", "Suspended", "Revoked"])
    p.add_argument("--notes", help="Free-text reason, shown in HSMobileOpsLicenseCloudCache's ReasonsText.")
    p.add_argument("--connection-string", help="Storage account connection string. Defaults to $AZURE_TABLE_CONNECTION_STRING.")
    p.set_defaults(func=cmd_azure_register)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
