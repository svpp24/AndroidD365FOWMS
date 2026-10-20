"""Makes the connection QR code that points Inventory hub devices at a D365FO environment.

The code carries the environment URL, Entra tenant, company and every app registration
(client ID + secret). Scan it on the device: on the "not configured" screen, or in
Settings > Connection profile > Authentication > Scan connection QR.

Treat the code like the secrets in it. Use --pin so a photographed code is useless without
the PIN, and give the PIN separately (by phone, not printed next to the code).

Example:
    python tools/make_connection_qr.py --label PROD \
        --url https://contoso.operations.dynamics.com --tenant <tenant-guid> --company BSP \
        --apps-file apps.txt --pin 482915 --out prod-connection.png

apps.txt holds one registration per line: "client-id, secret" (# comments allowed). Keep it
out of source control, and delete it and the PNG once the devices are set up.

Or give the whole connection as one JSON file (the same structure the app reads):
    python tools/make_connection_qr.py --json connection.json --pin 482915 --out prod-connection.png
    {"v":1,"label":"PROD","url":"https://...","tenant":"<guid>","company":"BSP",
     "apps":[{"id":"<client-id>","secret":"<secret>"}, ...]}
The app also reads that JSON as-is from a QR made by any offline QR tool - but without a PIN,
and never with an online generator, which would see the secrets.

Format (must match the app's ConnectionQr):
    HSMOCONN1:  + base64url(JSON)                                   - plain
    HSMOCONN1P: + base64url(salt16 | iv12 | AES-256-GCM(JSON))      - PIN; key = PBKDF2-HMAC-SHA256(PIN, salt, 120000)
"""
import argparse
import base64
import json
import os
import re
import sys
from pathlib import Path

import qrcode
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

PREFIX_PLAIN = "HSMOCONN1:"
PREFIX_PIN = "HSMOCONN1P:"
PIN_ITERATIONS = 120_000


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def read_apps(path: Path) -> list:
    apps = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = re.split(r"[,\s]+", line, maxsplit=1)
        if len(parts) != 2 or not parts[0] or not parts[1]:
            sys.exit(f"apps file: each line needs 'client-id, secret' - got: {parts[0]!r}")
        apps.append({"id": parts[0].strip(), "secret": parts[1].strip()})
    if not apps:
        sys.exit("apps file has no registrations")
    return apps


def seal(text: str, pin: str) -> str:
    salt, iv = os.urandom(16), os.urandom(12)
    key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=PIN_ITERATIONS).derive(pin.encode())
    return PREFIX_PIN + b64url(salt + iv + AESGCM(key).encrypt(iv, text.encode(), None))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--label", default="PROD", help="profile name shown on the device (PROD, UAT...)")
    ap.add_argument("--json", type=Path, help="the whole connection as one JSON file (instead of the four below)")
    ap.add_argument("--url", help="D365FO environment URL, https://...")
    ap.add_argument("--tenant", help="Microsoft Entra tenant (directory) ID")
    ap.add_argument("--company", default="", help="legal entity the devices work in, e.g. BSP")
    ap.add_argument("--apps-file", type=Path, help="file with one 'client-id, secret' per line")
    ap.add_argument("--pin", default="", help="6+ digit setup PIN; strongly recommended")
    ap.add_argument("--out", default="connection-qr.png", type=Path, help="PNG to write")
    args = ap.parse_args()

    if args.json:
        data = json.loads(args.json.read_text(encoding="utf-8"))
        apps = data.get("apps") or []
        if not apps or any(not a.get("id") or not a.get("secret") for a in apps):
            sys.exit("json: 'apps' needs at least one {\"id\": ..., \"secret\": ...} with both filled in")
        conn = {"v": 1, "label": data.get("label", args.label), "url": str(data.get("url", "")).rstrip("/"),
                "tenant": data.get("tenant", ""), "company": str(data.get("company", "")).upper(),
                "apps": [{"id": a["id"].strip(), "secret": a["secret"].strip()} for a in apps]}
    else:
        if not (args.url and args.tenant and args.apps_file):
            sys.exit("give --json, or all of --url, --tenant and --apps-file")
        conn = {"v": 1, "label": args.label, "url": args.url.rstrip("/"), "tenant": args.tenant,
                "company": args.company.upper(), "apps": read_apps(args.apps_file)}
    if not conn["url"].startswith("https://"):
        sys.exit("url must start with https://")
    if not conn["tenant"]:
        sys.exit("tenant is required")
    if args.pin and not re.fullmatch(r"\d{6,}", args.pin):
        sys.exit("--pin must be at least 6 digits (the device shows a number keypad)")

    payload = json.dumps(conn, separators=(",", ":"))
    text = seal(payload, args.pin) if args.pin else PREFIX_PLAIN + b64url(payload.encode())

    img = qrcode.make(text, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=4)
    img.save(args.out)
    print(f"Wrote {args.out} ({len(text)} characters, {len(json.loads(payload)['apps'])} app registrations).")
    if not args.pin:
        print("WARNING: no --pin - anyone who photographs this code has the secrets.")
    print("Delete the PNG and the apps file once the devices are set up.")


if __name__ == "__main__":
    main()
