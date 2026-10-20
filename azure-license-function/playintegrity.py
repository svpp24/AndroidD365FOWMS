"""
Decodes a Play Integrity Standard API token by calling Google's own decode endpoint - the
token itself is opaque and encrypted; only Google can tell us what it actually says. See
https://developer.android.com/google/play/integrity/verdicts for the verdict shapes below.

Needs a Google Cloud service account (JSON key, or preferably Workload Identity once this
Function App is actually deployed) that has been granted access to the Play Console project
for this app, with the `https://www.googleapis.com/auth/playintegrity` scope. That credential
lives only in this Function App's own configuration - see README.md's "Configuration" section
- never in AOS, never in the Android app.
"""
import json
import logging
import os

import google.auth
import google.auth.transport.requests
from google.oauth2 import service_account

_SCOPES = ["https://www.googleapis.com/auth/playintegrity"]
_DECODE_URL_TEMPLATE = "https://playintegrity.googleapis.com/v1/{package_name}:decodeIntegrityToken"

# The device-integrity tiers Google can report, weakest to strongest - see the verdicts doc
# linked above. Requiring just MEETS_DEVICE_INTEGRITY (not MEETS_STRONG_INTEGRITY) matches
# what most production apps require: genuine Android, not necessarily the newest hardware
# attestation, which not every otherwise-legitimate device supports yet.
_REQUIRED_DEVICE_VERDICT = "MEETS_DEVICE_INTEGRITY"


def decode_integrity_token(token: str | None, device_id: str) -> tuple[str, list[str]]:
    """Returns (verdict, reasons) where verdict is PASS / FAIL / UNVERIFIED. Never raises -
    every branch here feeds into the same response CheckLicense always returns, which in turn
    must never come back as a 500 just because a device's attestation was un-checkable.
    """
    if not token:
        return "UNVERIFIED", ["No integrity token was sent with this request."]

    package_name = os.environ.get("ANDROID_PACKAGE_NAME")
    expected_cert_sha256 = os.environ.get("RELEASE_SIGNING_CERT_SHA256")
    if not package_name:
        logging.error("ANDROID_PACKAGE_NAME is not configured.")
        return "UNVERIFIED", ["Server misconfiguration: no package name configured."]

    try:
        credentials = _load_credentials()
        session = google.auth.transport.requests.AuthorizedSession(credentials)
        response = session.post(
            _DECODE_URL_TEMPLATE.format(package_name=package_name),
            json={"integrityToken": token},
            timeout=10,
        )
        response.raise_for_status()
        payload = response.json().get("tokenPayloadExternal", {})
    except Exception as exc:  # noqa: BLE001 - a Google-side outage must degrade, not 500
        logging.exception("Play Integrity decode call failed")
        return "UNVERIFIED", [f"Could not verify the integrity token: {exc}"]

    return _evaluate_verdict(payload, package_name, expected_cert_sha256)


def _evaluate_verdict(payload: dict, expected_package: str, expected_cert_sha256: str | None) -> tuple[str, list[str]]:
    reasons: list[str] = []

    app_integrity = payload.get("appIntegrity", {})
    app_verdict = app_integrity.get("appRecognitionVerdict", "UNEVALUATED")
    if app_verdict != "PLAY_RECOGNIZED":
        reasons.append(f"App not recognised as a genuine Play Store install (verdict: {app_verdict}).")

    actual_package = app_integrity.get("packageName")
    if actual_package and actual_package != expected_package:
        reasons.append(f"Package name mismatch: got {actual_package!r}, expected {expected_package!r}.")

    if expected_cert_sha256:
        digests = [d.upper() for d in app_integrity.get("certificateSha256Digest", [])]
        if expected_cert_sha256.upper() not in digests:
            reasons.append("Signing certificate does not match the published release build - possible repackaging.")

    device_integrity = payload.get("deviceIntegrity", {})
    device_verdicts = device_integrity.get("deviceRecognitionVerdict", [])
    if _REQUIRED_DEVICE_VERDICT not in device_verdicts:
        reasons.append(f"Device did not meet the required integrity tier (got: {device_verdicts}).")

    if reasons:
        return "FAIL", reasons
    return "PASS", []


def _load_credentials() -> service_account.Credentials:
    """Prefers a JSON key given inline via app setting (simplest for a first deployment);
    GOOGLE_APPLICATION_CREDENTIALS (a file path) also works via the library's own default
    resolution if set instead and GOOGLE_SERVICE_ACCOUNT_JSON is left unset. Move to Workload
    Identity Federation (no key file at all) once this is running for real - see README.md.
    """
    raw_json = os.environ.get("GOOGLE_SERVICE_ACCOUNT_JSON")
    if raw_json:
        info = json.loads(raw_json)
        return service_account.Credentials.from_service_account_info(info, scopes=_SCOPES)

    credentials, _ = google.auth.default(scopes=_SCOPES)
    return credentials
