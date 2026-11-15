# Token service — runs in the customer's Azure tenant

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
token for `api://AzureADTokenExchange` and gives it to Azure AD instead of a client secret. The
D365FO token that comes back belongs to the app registration, so D365FO accepts it through
*Microsoft Entra applications* exactly as before. Nothing to store, leak or renew.

Each device keeps to one registration, picked by a hash of its device ID, which spreads D365FO's
per-registration limits. If Azure AD throttles one, the device uses the next. App tokens are
cached until 5 minutes before they expire, so Azure AD sees about one request per registration
per hour.

## Set up (customer's Azure admin)

You need the Azure CLI, signed in (`az login`) to the customer's tenant, with rights to create
resources and app registrations. Then run:

```powershell
./setup.ps1 -Name contoso-mobileops -Location westeurope `
    -D365Urls https://contoso.operations.dynamics.com -AppCount 3
```

The script creates:

- a resource group;
- a storage account;
- a Linux Python 3.11 Function App (Consumption) with a system-assigned managed identity;
- the app registrations, each trusting that identity (no client secrets).

It then deploys the code. At the end it prints the token service URL and the client IDs.

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
| `IDENTITY_ENDPOINT`, `IDENTITY_HEADER` | Set by Azure for the managed identity. |
| `AzureWebJobsStorage` | Set by Azure. Holds the `UsedNonces` table that the replay check uses. |

## Test locally

```
python -m unittest discover -s tests
```

The tests use fake Azure AD and D365FO. The device keys are made exactly as Android's Keystore
makes them: EC P-256, SHA256withECDSA, DER signature.
