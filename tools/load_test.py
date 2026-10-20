"""Load test: simulates N handhelds calling the app's D365FO read services at a steady rate.

Reads only - PO lookup, on-hand, journal list, user warehouses - so it changes nothing in
D365FO. Point it at a dev/test environment only, never production.

Credentials come from the same connection.json the QR code is made from (client ID + secret),
read here and never printed. Every "device" uses one app registration, picked the same way the
app picks it (FNV-1a of the device ID), so a multi-registration pool spreads like the fleet does.

    python tools/load_test.py --json connection.json --devices 50 --minutes 3 --rate 4
        --devices  simulated handhelds
        --rate     calls per device per minute (a busy receiving device: ~3-6)
        --ramp     seconds over which the devices start (default 30) - 0 is a shift-start burst

Reports: requests, throughput, latency p50/p90/p99/max per operation, HTTP status counts
(429 = D365FO service protection throttling) and failures.
"""
import argparse
import json
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def fnv1a(text: str, size: int) -> int:
    h = 0x811C9DC5
    for ch in text:
        h ^= ord(ch)
        h = (h * 0x01000193) & 0xFFFFFFFF
    if h >= 0x80000000:  # same signed-int arithmetic as the Kotlin
        h -= 0x100000000
    return h % size


class Tokens:
    """One client-credentials token per app registration, refreshed before expiry."""

    def __init__(self, tenant, url, apps):
        self.tenant, self.scope, self.apps = tenant, url.rstrip("/") + "/.default", apps
        self.cache, self.lock = {}, threading.Lock()

    def get(self, index):
        with self.lock:
            tok, exp = self.cache.get(index, (None, 0))
            if tok and exp - time.time() > 300:
                return tok
            app = self.apps[index]
            body = urllib.parse.urlencode({
                "grant_type": "client_credentials", "client_id": app["id"],
                "client_secret": app["secret"], "scope": self.scope}).encode()
            req = urllib.request.Request(
                f"https://login.microsoftonline.com/{self.tenant}/oauth2/v2.0/token", data=body)
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    data = json.load(r)
            except urllib.error.HTTPError as e:
                detail = json.loads(e.read() or b"{}").get("error_description", "").splitlines()[:1]
                sys.exit(f"token request failed for registration {index + 1}: HTTP {e.code} {detail}")
            self.cache[index] = (data["access_token"], time.time() + int(data.get("expires_in", 3600)))
            return data["access_token"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", required=True, type=Path)
    ap.add_argument("--devices", type=int, default=20)
    ap.add_argument("--minutes", type=float, default=2)
    ap.add_argument("--rate", type=float, default=4, help="calls per device per minute")
    ap.add_argument("--ramp", type=float, default=30, help="seconds to start all devices")
    ap.add_argument("--warehouse", default="AAC")
    ap.add_argument("--purch-ids", default="BSP-PO-000161686,BSP-PO-000161680,BSP-PO-000161687")
    ap.add_argument("--items", default="HSV001,HST002,Dm001,BSR-47274-0")
    ap.add_argument("--user", default="002")
    args = ap.parse_args()

    conn = json.loads(args.json.read_text(encoding="utf-8"))
    apps = [a for a in conn["apps"] if a.get("id") and a.get("secret") and "PASTE" not in a["secret"]]
    if not apps:
        sys.exit("connection.json has no usable app registration (secret missing)")
    if "operations.dynamics.com" in conn["url"] and "--i-know-this-is-production" not in sys.argv:
        sys.exit("refusing: this looks like a production URL. Use a dev/test environment.")
    base = conn["url"].rstrip("/") + "/api/services/HSMobileOpsServiceGroup/"
    company = (conn.get("company") or "BSP").upper()
    tokens = Tokens(conn["tenant"], conn["url"], apps)
    for i in range(len(apps)):  # fail fast, before any load
        tokens.get(i)

    purch, items = args.purch_ids.split(","), args.items.split(",")
    ops = [
        ("getPurchaseOrder", "HSMobileOpsReceivingSvc/getPurchaseOrder",
         lambda: {"DataAreaId": company, "PurchId": random.choice(purch), "WarehouseId": args.warehouse}),
        ("getOnHand", "HSMobileOpsInquirySvc/getOnHand",
         lambda: {"DataAreaId": company, "ItemId": random.choice(items), "WarehouseId": args.warehouse}),
        ("listOpenJournals", "HSMobileOpsCountingSvc/listOpenJournals",
         lambda: {"DataAreaId": company, "WarehouseId": args.warehouse, "UserId": args.user}),
        ("getUserWarehouses", "HSMobileOpsUserWarehouseSvc/getUserWarehouses",
         lambda: {"DataAreaId": company, "UserId": args.user}),
    ]
    weights = [5, 3, 1, 1]  # receiving-heavy, like a goods-in shift

    lat, status, errors = defaultdict(list), Counter(), Counter()
    lock = threading.Lock()
    deadline = time.time() + args.ramp + args.minutes * 60
    interval = 60.0 / args.rate

    def device(n):
        dev_id = f"loadtest-device-{n:04d}"
        reg = fnv1a(dev_id, len(apps))
        time.sleep(random.uniform(0, args.ramp) if args.ramp > 0 else 0)
        while time.time() < deadline:
            name, path, body = random.choices(ops, weights)[0]
            req = urllib.request.Request(
                base + path, data=json.dumps({"_request": body()}).encode(),
                headers={"Authorization": "Bearer " + tokens.get(reg), "Content-Type": "application/json",
                         "X-Company": company})
            t0 = time.perf_counter()
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    r.read()
                    code = r.status
            except urllib.error.HTTPError as e:
                code = e.code
            except Exception as e:  # timeout, reset
                code = type(e).__name__
            ms = (time.perf_counter() - t0) * 1000
            with lock:
                lat[name].append(ms)
                status[code] += 1
                if code != 200:
                    errors[f"{name} {code}"] += 1
            # Poisson-ish think time around the target rate.
            time.sleep(max(0.0, random.expovariate(1 / interval)))

    started = time.time()
    print(f"{args.devices} devices, {args.rate}/min each, {args.minutes} min after a {args.ramp:.0f}s ramp, "
          f"{len(apps)} app registration(s), against {conn['url']}")
    with ThreadPoolExecutor(max_workers=args.devices) as pool:
        list(pool.map(device, range(args.devices)))
    elapsed = time.time() - started

    total = sum(status.values())
    print(f"\n{total} requests in {elapsed:.0f}s = {total / elapsed:.1f} req/s "
          f"({total / elapsed * 300:.0f} per 5 min; D365FO's limit is 6,000 per 5 min per registration)")
    print(f"{'operation':20} {'n':>6} {'p50':>7} {'p90':>7} {'p99':>7} {'max':>7}  (ms)")
    for name, values in sorted(lat.items()):
        v = sorted(values)
        pick = lambda q: v[min(len(v) - 1, int(q * len(v)))]
        print(f"{name:20} {len(v):>6} {pick(.5):>7.0f} {pick(.9):>7.0f} {pick(.99):>7.0f} {v[-1]:>7.0f}")
    print("status:", dict(status))
    if errors:
        print("failures:", dict(errors.most_common(8)))
    print(f"throttled (429): {status.get(429, 0)}")


if __name__ == "__main__":
    main()
