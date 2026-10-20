# CheckLicense — vendor-controlled license status + device attestation

This is the one thing in the whole licensing design that a customer's own D365FO admin can
never read or edit — see `../licensing/README.md` for the full chain this sits behind and
`elegant-bubbling-curry` plan context for why it exists (instant revocation without a root-key
rotation, and a real tamper signal a patched APK can't forge).

**AOS calls this. The Android app never does.** That's the whole point — see the class doc in
`function_app.py`.

## What it needs (Azure resources, provisioned once)

1. **A Storage Account** with Table Storage enabled, and a table named `LicenseStatus` inside
   it. `PartitionKey` = `customerId` (the same opaque ID already used in certificates/licenses,
   see `../licensing/wms_license_tool.py`), `RowKey` = the literal string `"status"`, plus a
   `Status` column (`Active` / `Suspended` / `Revoked`). One row per customer.

   ```
   az storage account create --name <name> --resource-group <rg> --sku Standard_LRS
   az storage table create --name LicenseStatus --account-name <name>
   ```

2. **A Function App** (Python 3.11 runtime, Consumption or Flex Consumption plan is fine — this
   endpoint is low-traffic, called once per app launch/sign-in, not per request from a device).

   ```
   az functionapp create --name <func-name> --resource-group <rg> \
       --storage-account <name> --consumption-plan-location <region> \
       --runtime python --runtime-version 3.11 --functions-version 4 --os-type Linux
   ```

3. **A Google Cloud service account** with the Play Integrity API enabled on its project, and
   that service account added under **Play Console → Users and permissions** for this app with
   at least "View app information" access (Play Integrity–specific permission, see
   https://developer.android.com/google/play/integrity/setup). Download its JSON key, or — once
   this is running for real — switch to Workload Identity Federation instead of a long-lived
   key file (`_load_credentials()` in `playintegrity.py` already falls back to
   `google.auth.default()`, which supports this with no code change).

## Configuration (Function App settings — never in source control)

Copy `local.settings.json.template` to `local.settings.json` for local testing (git-ignored),
or set these as Application Settings on the deployed Function App:

| Setting | Value |
|---|---|
| `AZURE_TABLE_CONNECTION_STRING` | Connection string for the storage account above |
| `ANDROID_PACKAGE_NAME` | `com.d365fo.mobileops` |
| `RELEASE_SIGNING_CERT_SHA256` | Same value already baked into the Android app's `BuildConfig` — see `app/build.gradle.kts`'s `secret("RELEASE_SIGNING_CERT_SHA256")` |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | The service account JSON key, as a single-line string (or leave unset and use `GOOGLE_APPLICATION_CREDENTIALS` / Workload Identity instead) |

## Deploy

```
pip install -r requirements.txt -t .python_packages/lib/site-packages
func azure functionapp publish <func-name>
```

(Needs the Azure Functions Core Tools — `npm install -g azure-functions-core-tools@4` — and the
Azure CLI logged in to the right subscription. Neither is available in this environment; run
these from a machine with access to the actual Azure subscription.)

## Verify it's actually callable — do this before wiring anything into AOS

This is the first go/no-go checkpoint in the plan. Get the Function's key from the portal
(**Function App → Functions → CheckLicense → Function Keys**), then:

```bash
curl -X POST "https://<func-name>.azurewebsites.net/api/CheckLicense?code=<function-key>" \
     -H "Content-Type: application/json" \
     -d '{"customerId": "some-test-customer", "deviceId": "test-device", "integrityToken": null}'
```

Expected for a customer with no row yet: `{"licenseStatus": "Unknown", "integrityVerdict":
"UNVERIFIED", "reasons": [...]}`. Add a test row to the table (`az storage entity insert` or
the Storage Explorer) with `Status = Active` for that `customerId` and confirm `licenseStatus`
flips to `"Active"`. Only once this round-trips for real should `HSMobileOpsLicenseCloudClient`
be pointed at it.

A real `integrityToken` can only come from a genuine Android device running the app with the
Play Integrity dependency wired in (later stage of the plan) — test the table lookup in
isolation first with `integrityToken: null`, which is designed to short-circuit to
`"UNVERIFIED"` without ever calling Google.
