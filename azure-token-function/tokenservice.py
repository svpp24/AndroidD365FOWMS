"""The token service's logic (no Azure Functions imports - testable alone). Contract: ../docs/device-setup.md.

A handheld set up with a device setup code holds no client secret. It holds an EC P-256 key in
its Keystore; D365FO holds the public key (HSMobileOpsDeviceSetupSvc). This service holds the
customer's app registrations and hands out D365FO access tokens to devices that prove they hold
their key and that D365FO still lists as active.
"""
import base64
import hashlib
import json
import logging
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Protocol

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

CLOCK_SKEW_SECONDS = 300
DEVICE_KEY_CACHE_SECONDS = 300
SERVICE_PATH = "/api/services/HSMobileOpsServiceGroup/HSMobileOpsDeviceSetupSvc/"


@dataclass
class AppRegistration:
    client_id: str
    secret: str


@dataclass
class Config:
    tenant_id: str
    d365_urls: list[str]
    apps: list[AppRegistration]

    @staticmethod
    def from_settings(tenant_id: str, d365_urls: str, apps_json: str) -> "Config":
        apps = [AppRegistration(a["id"].strip(), a["secret"].strip()) for a in json.loads(apps_json)
                if a.get("id") and a.get("secret")]
        urls = [normalize(u) for u in d365_urls.split(",") if u.strip()]
        if not tenant_id.strip() or not urls or not apps:
            raise ValueError("TENANT_ID, D365_URLS and APP_REGISTRATIONS are all required")
        return Config(tenant_id.strip(), urls, apps)


@dataclass
class Result:
    http_status: int
    body: dict = field(default_factory=dict)
    headers: dict = field(default_factory=dict)


class Http(Protocol):
    def post(self, url: str, body: bytes, headers: dict, timeout: int) -> tuple[int, dict, bytes]: ...


class NonceStore(Protocol):
    def use(self, device_id: str, nonce: str, timestamp: int) -> bool:
        """False when this nonce was used already (a replayed request)."""


class Throttled(Exception):
    def __init__(self, retry_after: int):
        super().__init__(f"throttled, retry after {retry_after}s")
        self.retry_after = retry_after


class Unavailable(Exception):
    pass


def normalize(url: str) -> str:
    return url.strip().rstrip("/").lower()


def register_text(code: str, device_id: str, public_key: str) -> str:
    return f"HSMODEV1-REGISTER\n{code.strip().upper()}\n{device_id}\n{public_key}"


def token_text(device_id: str, resource: str, timestamp: int, nonce: str) -> str:
    return f"HSMODEV1-TOKEN\n{device_id}\n{resource}\n{timestamp}\n{nonce}"


def verify_device_signature(public_key_b64: str, text: str, signature_b64: str) -> bool:
    """SHA256withECDSA, DER signature - what Android's Keystore makes (DeviceKey.sign)."""
    try:
        key = serialization.load_der_public_key(base64.b64decode(public_key_b64))
        if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
            return False
        key.verify(base64.b64decode(signature_b64), text.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, TypeError, InvalidSignature):
        return False


class UrllibHttp:
    def post(self, url, body, headers, timeout):
        request = urllib.request.Request(url, body, headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers or {}), e.read()


class TokenService:
    def __init__(self, config: Config, http: Http, nonces: NonceStore, clock=time.time):
        self.config, self.http, self.nonces, self.clock = config, http, nonces, clock
        self._tokens: dict[tuple[int, str], tuple[str, float]] = {}
        self._keys: dict[str, tuple[dict, float]] = {}
        self._lock = threading.Lock()

    # -- app registrations -------------------------------------------------------------

    def _app_index(self, device_id: str, shift: int = 0) -> int:
        """Each device keeps to one registration (spreading D365FO's per-registration limits)."""
        digest = int.from_bytes(hashlib.sha256(device_id.encode()).digest()[:4], "big")
        return (digest + shift) % len(self.config.apps)

    def _aad_token(self, index: int, resource: str) -> tuple[str, float]:
        """A client-credentials token of registration [index], cached until 5 minutes before it expires."""
        with self._lock:
            cached = self._tokens.get((index, resource))
        if cached and cached[1] - 300 > self.clock():
            return cached
        app = self.config.apps[index]
        body = urllib.parse.urlencode({
            "grant_type": "client_credentials", "client_id": app.client_id, "client_secret": app.secret,
            "scope": resource + "/.default"}).encode()
        status, headers, raw = self.http.post(
            f"https://login.microsoftonline.com/{self.config.tenant_id}/oauth2/v2.0/token", body,
            {"Content-Type": "application/x-www-form-urlencoded"}, 30)
        if status == 429:
            raise Throttled(int(headers.get("Retry-After") or 30))
        if status != 200:
            logging.error("Azure AD refused registration %s: %s %s", app.client_id, status, raw[:300])
            raise Unavailable(f"Azure AD answered {status}")
        data = json.loads(raw)
        token = (data["access_token"], self.clock() + int(data.get("expires_in", 3600)))
        with self._lock:
            self._tokens[(index, resource)] = token
        return token

    def _token_for(self, device_id: str, resource: str) -> tuple[str, float]:
        """The device's own registration; the next one when Azure AD throttles it."""
        last: Throttled | None = None
        for shift in range(len(self.config.apps)):
            try:
                return self._aad_token(self._app_index(device_id, shift), resource)
            except Throttled as e:
                last = e
        raise last or Unavailable("no registration")

    # -- D365FO ------------------------------------------------------------------------

    def _d365(self, resource: str, device_id: str, operation: str, request: dict) -> dict:
        token, _ = self._token_for(device_id, resource)
        status, headers, raw = self.http.post(
            resource + SERVICE_PATH + operation, json.dumps({"_request": request}).encode(),
            {"Authorization": "Bearer " + token, "Content-Type": "application/json"}, 60)
        if status == 429:
            raise Throttled(int(headers.get("Retry-After") or 30))
        if status != 200:
            logging.error("D365FO %s answered %s: %s", operation, status, raw[:300])
            raise Unavailable(f"D365FO answered {status}")
        return json.loads(raw)

    def _device_key(self, resource: str, device_id: str) -> dict:
        key = (resource, device_id)
        with self._lock:
            cached = self._keys.get(key)
        if cached and cached[1] > self.clock():
            return cached[0]
        answer = self._d365(resource, device_id, "getDeviceKey", {"DeviceId": device_id})
        with self._lock:
            self._keys[key] = (answer, self.clock() + DEVICE_KEY_CACHE_SECONDS)
        return answer

    def _allowed(self, url: str) -> str | None:
        url = normalize(url or "")
        return url if url in self.config.d365_urls else None

    # -- endpoints ---------------------------------------------------------------------

    def register(self, body: dict) -> Result:
        code, device_id = str(body.get("code") or ""), str(body.get("deviceId") or "").strip()
        public_key, signature = str(body.get("publicKey") or ""), str(body.get("signature") or "")
        resource = self._allowed(body.get("d365Url"))
        if not code or not device_id or not public_key or len(device_id) > 200:
            return Result(400, {"success": False, "errorCode": "INVALID_REQUEST", "message": "Setup code, device and key are required."})
        if not resource:
            return Result(400, {"success": False, "errorCode": "UNKNOWN_ENVIRONMENT",
                                "message": "This token service does not serve that D365FO environment."})
        if not verify_device_signature(public_key, register_text(code, device_id, public_key), signature):
            return Result(400, {"success": False, "errorCode": "BAD_SIGNATURE", "message": "The device key signature is not valid."})
        try:
            answer = self._d365(resource, device_id, "registerDeviceKey", {
                "Code": code.strip().upper(), "DeviceId": device_id, "PublicKey": public_key,
                "DeviceBrand": str(body.get("deviceBrand") or "")[:60], "DeviceModel": str(body.get("deviceModel") or "")[:60],
                "Platform": str(body.get("platform") or "")[:20], "PlatformVersion": str(body.get("platformVersion") or "")[:20],
                "AppVersionName": str(body.get("appVersionName") or "")[:40]})
        except Throttled as e:
            return Result(429, {"errorCode": "BUSY", "message": "The token service is busy. Try again shortly."}, {"Retry-After": str(e.retry_after)})
        except Unavailable as e:
            return Result(503, {"errorCode": "D365_UNAVAILABLE", "message": f"D365FO could not be reached: {e}"})
        with self._lock:
            self._keys.pop((resource, device_id), None)
        return Result(200, {
            "success": bool(answer.get("Success")), "errorCode": answer.get("ErrorCode") or "",
            "message": answer.get("Message") or "", "company": answer.get("Company") or "",
            "deviceName": answer.get("DeviceName") or "", "activeDevices": answer.get("ActiveDevices") or 0,
            "maxDevices": answer.get("MaxDevices") or 0})

    def token(self, body: dict) -> Result:
        device_id = str(body.get("deviceId") or "").strip()
        nonce, signature = str(body.get("nonce") or ""), str(body.get("signature") or "")
        resource = self._allowed(body.get("resource"))
        try:
            timestamp = int(body.get("timestamp"))
        except (TypeError, ValueError):
            timestamp = 0
        if not device_id or not nonce or len(nonce) > 64 or not signature or not timestamp:
            return Result(400, {"errorCode": "INVALID_REQUEST", "message": "deviceId, timestamp, nonce and signature are required."})
        if not resource:
            return Result(400, {"errorCode": "UNKNOWN_ENVIRONMENT", "message": "This token service does not serve that D365FO environment."})
        if abs(self.clock() - timestamp) > CLOCK_SKEW_SECONDS:
            return Result(401, {"errorCode": "CLOCK_SKEW", "message": "The device clock is wrong. Set the date and time automatically."})
        try:
            device = self._device_key(resource, device_id)
        except Throttled as e:
            return Result(429, {"errorCode": "BUSY", "message": "The token service is busy."}, {"Retry-After": str(e.retry_after)})
        except Unavailable as e:
            return Result(503, {"errorCode": "D365_UNAVAILABLE", "message": f"D365FO could not be reached: {e}"})
        if not device.get("Success") and device.get("ErrorCode") == "DEVICE_UNKNOWN":
            return Result(403, {"errorCode": "DEVICE_UNKNOWN", "message": "This device has not been set up. Scan a device setup code."})
        if not verify_device_signature(device.get("PublicKey") or "",
                                       token_text(device_id, str(body.get("resource")).strip().rstrip("/"), timestamp, nonce), signature):
            return Result(401, {"errorCode": "BAD_SIGNATURE", "message": "The device key signature is not valid. Set the device up again."})
        if not device.get("IsActive"):
            return Result(403, {"errorCode": "DEVICE_INACTIVE", "message": "This device has been deactivated in D365FO."})
        if not device.get("LicenseValid"):
            return Result(403, {"errorCode": device.get("ErrorCode") or "LICENSE_INVALID",
                                "message": device.get("Message") or "The app's license is not valid."})
        if not self.nonces.use(device_id, nonce, timestamp):
            return Result(401, {"errorCode": "REPLAY", "message": "This request was already used."})
        try:
            token, expires_at = self._token_for(device_id, resource)
        except Throttled as e:
            return Result(429, {"errorCode": "BUSY", "message": "Azure AD is busy."}, {"Retry-After": str(e.retry_after)})
        except Unavailable as e:
            return Result(503, {"errorCode": "AAD_UNAVAILABLE", "message": str(e)})
        return Result(200, {"access_token": token, "expires_in": max(0, int(expires_at - self.clock()))})
