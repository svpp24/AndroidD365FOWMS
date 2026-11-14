"""
Token service - runs in the CUSTOMER's Azure tenant (README.md, setup.ps1). Handhelds set up
with a device setup code get their D365FO access tokens here instead of holding a client secret.

  POST /api/register  - a new device: setup code + its public key (passed on to D365FO)
  POST /api/token     - a signed request from a set-up device -> {"access_token", "expires_in"}

Anonymous on purpose: devices have no secret to send. register needs a valid one-off setup
code; token needs a request signed with a key D365FO holds for an active device.

Settings: TENANT_ID, D365_URLS (comma-separated environment URLs this service serves),
APP_REGISTRATIONS (JSON [{"id","secret"}], 1-10 - best as a Key Vault reference),
AzureWebJobsStorage (used for the replay check).
"""
import datetime
import hashlib
import json
import logging
import os
import time

import azure.functions as func
from azure.core.exceptions import ResourceExistsError
from azure.data.tables import TableServiceClient

import tokenservice

app = func.FunctionApp()

NONCE_TABLE = "UsedNonces"
NONCE_BUCKET_SECONDS = 600


class TableNonces:
    """One row per used nonce, partitioned by 10-minute bucket; the hourly cleanup drops old buckets."""

    def __init__(self, connection_string: str):
        self.service = TableServiceClient.from_connection_string(connection_string)
        self.service.create_table_if_not_exists(NONCE_TABLE)
        self.table = self.service.get_table_client(NONCE_TABLE)

    def use(self, device_id: str, nonce: str, timestamp: int) -> bool:
        row = hashlib.sha256(f"{device_id}\n{nonce}".encode()).hexdigest()[:40]
        try:
            self.table.create_entity({"PartitionKey": f"{timestamp // NONCE_BUCKET_SECONDS:012d}", "RowKey": row})
            return True
        except ResourceExistsError:
            return False

    def cleanup(self) -> int:
        cutoff = f"{int(time.time() - 3600) // NONCE_BUCKET_SECONDS:012d}"
        removed = 0
        for entity in self.table.query_entities(f"PartitionKey lt '{cutoff}'", select=["PartitionKey", "RowKey"]):
            self.table.delete_entity(entity["PartitionKey"], entity["RowKey"])
            removed += 1
        return removed


_service: tokenservice.TokenService | None = None
_nonces: TableNonces | None = None


def _setup() -> tokenservice.TokenService:
    global _service, _nonces
    if _service is None:
        config = tokenservice.Config.from_settings(
            os.environ.get("TENANT_ID", ""), os.environ.get("D365_URLS", ""), os.environ.get("APP_REGISTRATIONS", "[]"))
        _nonces = TableNonces(os.environ["AzureWebJobsStorage"])
        _service = tokenservice.TokenService(config, tokenservice.UrllibHttp(), _nonces)
    return _service


def _handle(req: func.HttpRequest, endpoint: str) -> func.HttpResponse:
    try:
        body = req.get_json()
        if not isinstance(body, dict):
            raise ValueError
    except ValueError:
        return _json(tokenservice.Result(400, {"errorCode": "INVALID_REQUEST", "message": "Request body must be a JSON object."}))
    try:
        service = _setup()
    except Exception:  # noqa: BLE001
        logging.exception("token service is not configured")
        return _json(tokenservice.Result(503, {"errorCode": "NOT_CONFIGURED", "message": "The token service is not configured."}))
    result = service.register(body) if endpoint == "register" else service.token(body)
    logging.info("%s device=%s -> %s %s", endpoint, str(body.get("deviceId"))[:40], result.http_status,
                 result.body.get("errorCode", ""))
    return _json(result)


@app.function_name(name="register")
@app.route(route="register", methods=["POST"], auth_level=func.AuthLevel.ANONYMOUS)
def register(req: func.HttpRequest) -> func.HttpResponse:
    return _handle(req, "register")


@app.function_name(name="token")
@app.route(route="token", methods=["POST"], auth_level=func.AuthLevel.ANONYMOUS)
def token(req: func.HttpRequest) -> func.HttpResponse:
    return _handle(req, "token")


@app.function_name(name="nonceCleanup")
@app.timer_trigger(schedule="0 17 * * * *", arg_name="timer", run_on_startup=False)
def nonce_cleanup(timer: func.TimerRequest) -> None:
    _setup()
    logging.info("nonce cleanup %s: %s rows removed", datetime.datetime.utcnow().isoformat(), _nonces.cleanup())


def _json(result: tokenservice.Result) -> func.HttpResponse:
    return func.HttpResponse(json.dumps(result.body), status_code=result.http_status,
                             mimetype="application/json", headers=result.headers)
