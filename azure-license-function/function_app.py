"""
CheckLicense - the one HTTP endpoint AOS calls to get a live, vendor-controlled answer to
"is this customer's license currently valid, and is this a genuine, untampered install".

See ../licensing/README.md and ../aos/HSMobileOps/AxClass/HSMobileOpsLicense.xml for the rest
of the chain this sits behind. This Function is deliberately the ONLY thing in the whole
design that a customer's own D365FO admin cannot read or edit - the Storage Table backing it
and the Google service-account credential used to verify Play Integrity tokens both live in
this Function App's own configuration, in the vendor's own Azure subscription.

Request (POST, function-key auth - see host.json / the Function's own "Function" auth level):
    {"customerId": "...", "deviceId": "...", "integrityToken": "..." | null}

Response:
    {
      "licenseStatus": "Active" | "Suspended" | "Revoked" | "Unknown",
      "integrityVerdict": "PASS" | "FAIL" | "UNVERIFIED",
      "reasons": ["..."]
    }

"Unknown" means no row exists yet for that customerId - treated as NOT valid by the AOS side
(HSMobileOpsLicense::verifyWithAttestation), same as "Revoked". A brand new customer needs a
row created here (see ../licensing/wms_license_tool.py's azure-register step) before their
first sign-in will pass this check, same as they already need a certificate+license pair
imported into their own F&O before anything else works.

"UNVERIFIED" (rather than FAIL) is returned whenever the integrity check itself could not run
at all - no token was sent, or Google's endpoint could not be reached - as opposed to Google
positively saying the install looks tampered (FAIL). The AOS side decides policy for each of
these separately; this Function reports what it found, it does not decide what AOS should do
about it.
"""
import json
import logging
import os

import azure.functions as func
from azure.data.tables import TableServiceClient
from azure.core.exceptions import ResourceNotFoundError

from playintegrity import decode_integrity_token

app = func.FunctionApp()

TABLE_NAME = "LicenseStatus"


@app.function_name(name="CheckLicense")
@app.route(route="CheckLicense", methods=["POST"], auth_level=func.AuthLevel.FUNCTION)
def check_license(req: func.HttpRequest) -> func.HttpResponse:
    try:
        body = req.get_json()
    except ValueError:
        return _json_response({"error": "Request body must be JSON."}, status_code=400)

    customer_id = (body.get("customerId") or "").strip()
    device_id = (body.get("deviceId") or "").strip()
    integrity_token = body.get("integrityToken") or None

    if not customer_id:
        return _json_response({"error": "customerId is required."}, status_code=400)

    license_status, reasons = _lookup_license_status(customer_id)
    integrity_verdict, integrity_reasons = decode_integrity_token(integrity_token, device_id)
    reasons.extend(integrity_reasons)

    return _json_response({
        "licenseStatus": license_status,
        "integrityVerdict": integrity_verdict,
        "reasons": reasons,
    })


def _lookup_license_status(customer_id: str) -> tuple[str, list[str]]:
    """Reads the one row for this customer - see azure-register in wms_license_tool.py for
    how rows get here. Never raises outward: a Table outage or a missing connection string
    must come back as "Unknown" with a reason, not a 500 - AOS's own caller already has a
    short grace-period cache for exactly this kind of transient failure (see
    HSMobileOpsLicense::verifyWithAttestation's doc comment), and a 500 here would burn that
    grace period on a problem this Function itself should have degraded gracefully from.
    """
    connection_string = os.environ.get("AZURE_TABLE_CONNECTION_STRING")
    if not connection_string:
        logging.error("AZURE_TABLE_CONNECTION_STRING is not configured.")
        return "Unknown", ["Server misconfiguration: no table connection string."]

    try:
        with TableServiceClient.from_connection_string(connection_string) as service:
            table_client = service.get_table_client(TABLE_NAME)
            entity = table_client.get_entity(partition_key=customer_id, row_key="status")
            status = entity.get("Status", "Unknown")
            if status not in ("Active", "Suspended", "Revoked"):
                return "Unknown", [f"Unrecognised status value on file: {status!r}."]
            return status, []
    except ResourceNotFoundError:
        return "Unknown", [f"No license row on file for customerId {customer_id!r}."]
    except Exception as exc:  # noqa: BLE001 - genuinely must never propagate as a 500
        logging.exception("License table lookup failed")
        return "Unknown", [f"Could not reach the license table: {exc}"]


def _json_response(payload: dict, status_code: int = 200) -> func.HttpResponse:
    return func.HttpResponse(
        json.dumps(payload),
        status_code=status_code,
        mimetype="application/json",
    )
