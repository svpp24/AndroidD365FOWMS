# Vendor-only license tooling

This folder is the vendor's private toolkit for the offline, certificate-chain licensing
system: one root keypair that anchors trust for every customer, a per-customer keypair
certified by the root, and per-period licenses signed by each customer's own key. See
`../aos/HSMobileOps/AxClass/HSMobileOpsLicense.xml` (AOS-side verification) and
`../app/src/main/java/com/d365fo/mobileops/data/security/license/` (Android-side
verification) for the two places these artifacts are actually consumed.

## Why a root key at all

The previous design trusted whatever public key a customer's own admin had pasted into
their D365FO environment. That's circular: a customer's own SysAdmin can edit any table in
their own tenant, including that one, so nothing stopped them generating their own keypair
and self-signing an unlimited license. The root key closes that: it is compiled into the
Android app and hardcoded in the AOS source, never stored in any table a customer can edit,
so a customer can no longer forge a valid credential no matter what they change in their own
environment - doing so would still require the root *private* key, which never leaves this
folder.

## Setup

```
pip install -r requirements.txt
```

Requires `cryptography`, `qrcode[pil]`, `Pillow` - pure Python, works on any OS (the
previous PowerShell tools only ran on Windows, via `RSACryptoServiceProvider`).

## Folder layout

```
licensing/
  wms_license_tool.py
  root/
    root-private-key.local.pem   # NEVER commit, share, or send this anywhere - ever
    root-public-key.pem          # safe to view - bake into the Android app (see below)
    root-public-key.xml          # safe to view - bake into X++ (see below)
  clients/
    Contoso Warehousing/
      customer-private-key.local.pem   # NEVER commit, share, or send this anywhere
      customer-public-key.pem
      customer-public-key.xml
      certificate.txt                  # the WMSCERT1... string, signed by the ROOT key
      certificate-qr.png                # what actually gets delivered to the customer
      licenses/
        2027-01-01.txt                  # each issued WMSLIC1... string, one per period
```

**Every file containing `.local.` must never be committed, emailed, or given to a customer
or partner.** Everything else in a client's folder (`certificate.txt`, `certificate-qr.png`,
the license `.txt` files, both `.pem`/`.xml` public keys) is safe to be fully visible -
that's the entire point of signing instead of sharing a secret.

## One-time setup: the root keypair

```
python wms_license_tool.py generate-root
```

Run this **once, ever**, on a machine you trust, and keep `root/root-private-key.local.pem`
safe forever - every certificate this tool will ever sign depends on it, for every
customer at once. Rotating it (re-running with `--force`) invalidates every certificate
already issued to every customer simultaneously; there is no partial rotation.

Take the two public outputs and bake them in:
- `root/root-public-key.pem` → `LicenseRootTrustAnchor.kt`'s compiled-in constant (Android)
- `root/root-public-key.xml` → `HSMobileOpsLicense::rootPublicKeyXml()` (X++, hardcoded, not
  a table field - see that class's own doc comment for why)

## Onboarding a new customer

```
python wms_license_tool.py generate-customer-keypair --client "Contoso Warehousing"
python wms_license_tool.py sign-certificate --client "Contoso Warehousing" \
    --tenant-id CONTOSO-PROD --not-after 2031-01-01
```

The first command generates that customer's own keypair (their private key stays here,
same as before - a customer never receives private key material, only certificates and
license strings). The second signs a certificate binding the customer's identity and public
key with the root private key, and writes `certificate-qr.png` - deliver that QR image to
the customer (email, printed sheet, whatever channel) for them to scan **once**, during
device activation, in the app's own activation screen.

`--tenant-id` is a vendor-assigned opaque string chosen once here, not the customer's Entra
tenant GUID - it exists purely so a certificate/license pair minted for one D365FO
environment (say, their UAT) can't be replayed against another (their Prod), and it survives
the customer ever changing their own Entra tenant. Certificates are long-lived (years,
`--not-after`) - only the license needs routine reissuing.

**Before shipping the QR**, scan it yourself on a real device to confirm it decodes
reliably - `sign-certificate` prints the certificate's exact length and QR version so you
can judge this before printing anything.

## Issuing a license

```
python wms_license_tool.py sign-license --client "Contoso Warehousing" \
    --expiry 2027-01-01 --max-devices 25
```

Signs a license (customer ID, tenant ID, expiry, device limit) with that customer's own
private key. `--tenant-id` defaults to whatever is already recorded in that client's own
`certificate.txt`, so it can't accidentally drift from the certificate - only pass it
explicitly if you're signing a license before that client has a certificate yet.

**The admin at that customer pastes both `certificate.txt`'s contents and the printed
license string into the F&O "Mobile app license" import form**, in the same action - AOS
verifies the certificate against its own hardcoded root key, extracts the now-trusted
customer public key from it, and verifies the license against *that*, storing both only on
success. The next app launch exercises the whole chain for real.

## Verifying before you ship anything

```
python wms_license_tool.py verify \
    --certificate "clients/Contoso Warehousing/certificate.txt" \
    --license "clients/Contoso Warehousing/licenses/2027-01-01.txt"
```

Runs the exact same chain AOS and the Android app will run - root verifies certificate,
certificate's own key verifies license, tenant/customer IDs match, nothing has expired -
entirely locally, before anything touches a real customer's environment or device.

## Key rotation

If a customer's private key is ever suspected exposed: `generate-customer-keypair --client
"..." --force`, then `sign-certificate` again (a fresh certificate for the new public key),
then `sign-license` again. Their old certificate/license stop verifying the moment the new
certificate is imported into their AOS and their devices re-activate against the new QR.
This never touches any other customer's keypair or the root key.

If the **root** key is ever suspected exposed, every customer's certificate must be
reissued and every device re-activated - this is the one operation with no blast-radius
containment, which is why `generate-root` refuses to run twice without `--force`.
