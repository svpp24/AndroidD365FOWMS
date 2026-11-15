# Token service — runs in the customer's Azure tenant

C# (.NET 10, isolated worker) Azure Function on the **Flex Consumption** plan. `TokenFunction/`
is the app, `TokenFunction.Tests/` its tests.

Handhelds that are set up with a **device setup code** get their D365FO access tokens here. The
handhelds hold no client secret. The full contract and the security model are in
[../docs/device-setup.md](../docs/device-setup.md).

| Endpoint | Caller | What it does |
|---|---|---|
| `POST /api/register` | a new handheld | Checks the device's signature. Passes the setup code and public key to D365FO `HSMobileOpsDeviceSetupSvc/registerDeviceKey`. |
| `POST /api/token` | a set-up handheld | Checks the clock (±5 min) and the request signature against the key D365FO holds (cached 5 min). Refuses a device that is not active or a license that is not valid. Refuses a replayed request. Then answers with a D365FO token. |
| timer `nonceCleanup` | hourly | Drops replay-check rows older than an hour. |

**Anonymous.** Devices have nothing secret to send:

- register needs a valid one-off setup code;
- token needs a request signed with a key that D365FO holds for an active device.

**App registrations, no secrets.** 1–10 app registrations, listed by client ID. Each trusts
the Function App's managed identity (a federated credential): the function gets its identity's
token for `api://AzureADTokenExchange` and gives it to Entra ID instead of a client secret. The
D365FO token that comes back belongs to the app registration, so D365FO accepts it through
*Microsoft Entra applications* exactly as before. Nothing to store, leak or renew.

Each device keeps to one registration, picked by a hash of its device ID, which spreads D365FO's
per-registration limits. If Entra ID throttles one, the device uses the next. App tokens are
cached until 5 minutes before they expire, so Entra ID sees about one request per registration
per hour.

## Set up (customer's Azure admin)

You need the Azure CLI, signed in (`az login`) to the customer's tenant, with rights to create
resources and app registrations. Then run:

```powershell
./setup.ps1 -Name func-contoso-mobileops-token-prod-weu -ResourceGroup rg-contoso-mobileops-prod-weu `
    -Location westeurope -D365Urls https://contoso.operations.dynamics.com -AppCount 3
```

The script creates:

- a resource group;
- a storage account;
- a .NET 10 isolated Function App on Flex Consumption, with a system-assigned managed identity;
- the app registrations, each trusting that identity (no client secrets).

It then deploys `token-function.zip` (from `publish.ps1`; built on the spot when missing). At the end it prints the token service URL and the client IDs.

Then, in D365FO:

1. **System administration › Setup › Microsoft Entra applications.** Add each client ID with
   the same user the handhelds' integration uses today.
2. **Mobile devices › Device setup codes › New device setup code.** Enter the URL as *Token
   service*. Handhelds scan the QR code.

Re-running the script is safe. `-AppCount 5` adds registrations. If the Function App is ever
recreated (a new identity), re-run the script: it replaces the trust. `-RemoveSecrets` deletes
client secrets made by an older version of this script.

| Setting | Value |
|---|---|
| `TENANT_ID` | The customer's tenant. |
| `D365_URLS` | Comma-separated list of the D365FO environments this service may hand out tokens for. A request for any other URL is refused. |
| `APP_REGISTRATIONS` | `["client-id", ...]` - not secret. (`[{"id","secret"}]` from an older setup still works.) |
| `AzureWebJobsStorage` | Set by Azure. Holds the `UsedNonces` table that the replay check uses. A connection string, or the storage reached with the identity (`AzureWebJobsStorage__accountName`) - then the identity needs *Storage Table Data Contributor* on the account. |

## Staying inside the free grant

Flex Consumption gives each subscription 100,000 GB-s and 250,000 executions a month free
(on-demand). A token request takes about 0.2 s on a 512 MB instance, about 0.1 GB-s; a handheld
asks about once an hour. 100 handhelds: about 75,000 executions and 7,000 GB-s a month.

- **Always ready: 0.** Always-ready instances are billed from the first second; the free grant
  is for on-demand only. The first request after a quiet spell takes a few seconds longer (cold
  start); the app retries, so a worker doesn't notice.
- **Instance memory: 512 MB.** Halves the GB-s of the 2048 MB default.
- **Maximum instances: low** (setup.ps1: 10). Caps the bill if something floods the endpoints.
- **Application Insights:** the first 5 GB a month of logs are free; set a daily cap (e.g.
  0.1 GB) under *Usage and estimated costs*.
- **Storage account:** not free, but a few cents a month (Standard LRS, a small table).

## Build and test

Needs the .NET 10 SDK.

```powershell
dotnet test TokenFunction.Tests
./publish.ps1        # tests, then token-function.zip
```

The tests use fake Entra ID and D365FO. The device keys are made exactly as Android's Keystore
makes them: EC P-256, SHA256withECDSA, DER signature.
