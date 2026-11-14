"""python -m unittest discover -s tests (from azure-token-function). Azure AD and D365FO are fakes."""
import base64
import json
import sys
import unittest
import urllib.parse
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import tokenservice as ts  # noqa: E402

D365 = "https://contoso.operations.dynamics.com"
NOW = 1_800_000_000


class Device:
    """What DeviceKey does on Android: EC P-256, SHA256withECDSA, DER signature, base64."""

    def __init__(self, device_id):
        self.id, self.key = device_id, ec.generate_private_key(ec.SECP256R1())
        self.public = base64.b64encode(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def sign(self, text):
        return base64.b64encode(self.key.sign(text.encode(), ec.ECDSA(hashes.SHA256()))).decode()

    def register_body(self, code):
        return {"code": code, "deviceId": self.id, "publicKey": self.public,
                "signature": self.sign(ts.register_text(code, self.id, self.public)),
                "deviceBrand": "Zebra", "deviceModel": "TC22", "platform": "Android", "platformVersion": "14",
                "appVersionName": "1.5.8", "d365Url": D365 + "/", "company": "BSP"}

    def token_body(self, nonce="n1", timestamp=NOW):
        return {"deviceId": self.id, "resource": D365, "timestamp": timestamp, "nonce": nonce,
                "signature": self.sign(ts.token_text(self.id, D365, timestamp, nonce))}


class FakeCloud:
    """Azure AD (client credentials) and the D365FO setup service, as far as the service uses them."""

    def __init__(self):
        self.codes = {"GOODCODE": 1}
        self.devices = {}
        self.aad_calls = []
        self.d365_calls = []
        self.throttled_apps = set()

    def post(self, url, body, headers, timeout):
        if "login.microsoftonline.com" in url:
            form = urllib.parse.parse_qs(body.decode())
            app = form["client_id"][0]
            self.aad_calls.append(app)
            if app in self.throttled_apps:
                return 429, {"Retry-After": "20"}, b"{}"
            return 200, {}, json.dumps({"access_token": "tok-" + app, "expires_in": 3600}).encode()
        assert headers["Authorization"].startswith("Bearer tok-")
        op = url.rsplit("/", 1)[1]
        req = json.loads(body)["_request"]
        self.d365_calls.append(op)
        if op == "registerDeviceKey":
            if self.codes.get(req["Code"], 0) < 1:
                return 200, {}, json.dumps({"Success": False, "ErrorCode": "CODE_USED_UP", "Message": "used"}).encode()
            self.codes[req["Code"]] -= 1
            self.devices[req["DeviceId"]] = {"PublicKey": req["PublicKey"], "IsActive": True}
            return 200, {}, json.dumps({"Success": True, "Company": "BSP", "DeviceName": "Zebra TC22",
                                        "ActiveDevices": 7, "MaxDevices": 100, "Message": "Device ready - 7 of 100 devices."}).encode()
        device = self.devices.get(req["DeviceId"])
        if not device:
            return 200, {}, json.dumps({"Success": False, "ErrorCode": "DEVICE_UNKNOWN"}).encode()
        return 200, {}, json.dumps({"Success": True, "PublicKey": device["PublicKey"], "IsActive": device["IsActive"],
                                    "LicenseValid": True, "ErrorCode": "" if device["IsActive"] else "DEVICE_INACTIVE"}).encode()


class MemoryNonces:
    def __init__(self):
        self.used = set()

    def use(self, device_id, nonce, timestamp):
        if (device_id, nonce) in self.used:
            return False
        self.used.add((device_id, nonce))
        return True


def service(cloud, apps=2, clock=lambda: NOW):
    config = ts.Config.from_settings("tenant-1", D365 + "/", json.dumps(
        [{"id": f"app{i}", "secret": f"s{i}"} for i in range(apps)]))
    return ts.TokenService(config, cloud, MemoryNonces(), clock)


class TokenServiceTest(unittest.TestCase):

    def test_setup_then_token(self):
        cloud, device = FakeCloud(), Device("dev-1")
        svc = service(cloud)
        r = svc.register(device.register_body("goodcode"))
        self.assertEqual((200, True, "BSP", 7, 100), (r.http_status, r.body["success"], r.body["company"],
                                                     r.body["activeDevices"], r.body["maxDevices"]))
        r = svc.token(device.token_body())
        self.assertEqual(200, r.http_status)
        self.assertTrue(r.body["access_token"].startswith("tok-app"))
        self.assertEqual(3600, r.body["expires_in"])

    def test_refusals_from_d365fo_pass_through(self):
        cloud, a, b = FakeCloud(), Device("a"), Device("b")
        svc = service(cloud)
        svc.register(a.register_body("GOODCODE"))
        r = svc.register(b.register_body("GOODCODE"))
        self.assertEqual((200, False, "CODE_USED_UP"), (r.http_status, r.body["success"], r.body["errorCode"]))

    def test_register_needs_the_key_holder_and_a_known_environment(self):
        cloud, device, other = FakeCloud(), Device("dev-1"), Device("x")
        body = device.register_body("GOODCODE")
        body["signature"] = other.sign(ts.register_text("GOODCODE", "dev-1", device.public))
        self.assertEqual("BAD_SIGNATURE", service(cloud).register(body).body["errorCode"])
        body = device.register_body("GOODCODE")
        body["d365Url"] = "https://other.operations.dynamics.com"
        self.assertEqual("UNKNOWN_ENVIRONMENT", service(cloud).register(body).body["errorCode"])
        self.assertEqual([], cloud.d365_calls)

    def test_token_refusals(self):
        cloud, device, stranger = FakeCloud(), Device("dev-1"), Device("dev-2")
        svc = service(cloud)
        svc.register(device.register_body("GOODCODE"))
        self.assertEqual((403, "DEVICE_UNKNOWN"), (lambda r: (r.http_status, r.body["errorCode"]))(svc.token(stranger.token_body())))
        self.assertEqual("CLOCK_SKEW", svc.token(device.token_body(timestamp=NOW - 900)).body["errorCode"])
        forged = device.token_body()
        forged["signature"] = stranger.sign(ts.token_text("dev-1", D365, NOW, "n1"))
        self.assertEqual("BAD_SIGNATURE", svc.token(forged).body["errorCode"])
        self.assertEqual(200, svc.token(device.token_body("n9")).http_status)
        self.assertEqual("REPLAY", svc.token(device.token_body("n9")).body["errorCode"])

    def test_deactivated_device_is_refused_once_the_key_cache_expires(self):
        cloud, device, now = FakeCloud(), Device("dev-1"), [NOW]
        svc = service(cloud, clock=lambda: now[0])
        svc.register(device.register_body("GOODCODE"))
        self.assertEqual(200, svc.token(device.token_body("a")).http_status)
        cloud.devices["dev-1"]["IsActive"] = False
        now[0] += ts.DEVICE_KEY_CACHE_SECONDS + 1
        r = svc.token(device.token_body("b", timestamp=now[0]))
        self.assertEqual((403, "DEVICE_INACTIVE"), (r.http_status, r.body["errorCode"]))

    def test_tokens_are_cached_and_a_throttled_registration_is_skipped(self):
        cloud = FakeCloud()
        svc = service(cloud, apps=3)
        devices = [Device(f"d{i}") for i in range(6)]
        cloud.codes["GOODCODE"] = 6
        for d in devices:
            svc.register(d.register_body("GOODCODE"))
        calls = len(cloud.aad_calls)
        for i, d in enumerate(devices):
            self.assertEqual(200, svc.token(d.token_body(f"t{i}")).http_status)
        self.assertEqual(calls, len(cloud.aad_calls), "app tokens come from the cache")
        cloud.throttled_apps = {"app0", "app1"}
        svc._tokens.clear()
        for i, d in enumerate(devices):
            r = svc.token(d.token_body(f"u{i}"))
            self.assertEqual("tok-app2", r.body["access_token"])
        cloud.throttled_apps = {"app0", "app1", "app2"}
        svc._tokens.clear()
        r = svc.token(devices[0].token_body("v"))
        self.assertEqual((429, "20"), (r.http_status, r.headers["Retry-After"]))

    def test_config_needs_everything(self):
        with self.assertRaises(ValueError):
            ts.Config.from_settings("", D365, '[{"id":"a","secret":"b"}]')
        with self.assertRaises(ValueError):
            ts.Config.from_settings("t", D365, "[]")


if __name__ == "__main__":
    unittest.main()
