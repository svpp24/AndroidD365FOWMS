# Device setup without a client secret

A new handheld joins the fleet by scanning a **device setup code**. It never receives a client
secret. It makes its own key pair, registers the public key with D365FO through the customer's
**token service**, and from then on gets its D365FO access tokens from that service.

The old **connection QR code** (`HSMOCONN1`, with client IDs and secrets) still works while a
fleet changes over. The app decides per connection: a connection with a token service address
uses the device key; any other connection uses its client secret (`RoutingTokenProvider`).

## Parts

| Part | Where | What it does |
|---|---|---|
| Device setup codes | D365FO, Mobile devices > Device setup codes | Make a code (label, company, number of devices, days valid, token service address). It is shown once, as a QR code. Only its SHA-256 is stored. Revoke makes a code expire now. |
| `HSMobileOpsDeviceSetupSvc` | D365FO service, `HSMobileOpsServiceGroup` | `registerDeviceKey` and `getDeviceKey`. They are called only by the token service, never by the app. |
| Token service | Azure Function in the **customer's** tenant | `POST /api/register` and `POST /api/token`. It holds the app registrations (1–10, to spread D365FO's per-registration limits). |
| App | `DeviceSetupQr`, `DeviceKey`, `DeviceSetup`, `TokenServiceClient`, `DeviceTokenProvider`, `DeviceDeactivation` | Scans the code, makes the key, registers, gets tokens, and wipes itself when deactivated. |

## QR code

`HSMODEV1:` + base64url (no padding) of the JSON:

```json
{"v":1,"label":"Store 12","url":"https://<d365fo host>","company":"BSP",
 "token":"https://<token service host>","code":"<32 hex characters>"}
```

The code is 128 random bits (`HSMobileOpsDeviceSetupCodeNew`). D365FO keeps
`SHA-256(UTF-8(upper(trim(code))))` as lower-case hex in `HSMobileOpsDeviceSetupCode.CodeHash`.

## Device key

The key is EC P-256 in the Android Keystore (alias `hsmo_device_key_v1`), held in StrongBox
where the device has one. It is not exportable. Each setup makes a new key.

- **Public key:** base64 of the X.509 SubjectPublicKeyInfo (DER).
- **Signatures:** `SHA256withECDSA` over the UTF-8 text, as base64 of the DER (ASN.1)
  signature. In .NET, verify with
  `ECDsa.VerifyData(data, sig, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence)`.

## Token service API

### `POST /api/register`

Request (from the app):

```json
{"code","deviceId","publicKey","signature","deviceBrand","deviceModel",
 "platform","platformVersion","appVersionName","d365Url","company"}
```

`signature` is over `"HSMODEV1-REGISTER\n" + upper(trim(code)) + "\n" + deviceId + "\n" + publicKey`.

The service:

1. Verifies the signature with `publicKey`. This proves the device holds the key.
2. Takes a token for `d365Url` with one of its app registrations.
3. Calls `.../api/services/HSMobileOpsServiceGroup/HSMobileOpsDeviceSetupSvc/registerDeviceKey`
   with `{"_request":{"Code","DeviceId","PublicKey","DeviceBrand","DeviceModel","Platform","PlatformVersion","AppVersionName"}}`.

D365FO then checks:

- the code is known, not expired and not used up;
- the license is valid;
- for a new device, or one that was deactivated, a slot is free under the license's device limit.

If all checks pass, D365FO stores the key on `HSMobileOpsDevice`, marks the device active and
counts the use.

The service answers with:

```json
{"success","errorCode","message","company","deviceName","activeDevices","maxDevices"}
```

| `errorCode` | Meaning |
|---|---|
| `CODE_UNKNOWN` | The code is not known. |
| `CODE_EXPIRED` | The code has expired. |
| `CODE_USED_UP` | The code has set up all the devices it allows. |
| `DEVICE_LIMIT_REACHED` | No free slot under the license. |
| a license error code | The license is not valid. |
| `INVALID_REQUEST` | A required field is missing. |

The app shows `message`.

### `POST /api/token`

Request:

```json
{"deviceId","resource","timestamp","nonce","signature"}
```

- `timestamp` is in Unix seconds.
- `resource` is the D365FO URL.
- `signature` is over `"HSMODEV1-TOKEN\n" + deviceId + "\n" + resource + "\n" + timestamp + "\n" + nonce`.

The service:

1. Rejects the request if `timestamp` is more than 5 minutes from its own clock, or if `nonce`
   was already seen in that window.
2. Gets the device's key from `getDeviceKey` (`{"_request":{"DeviceId"}}`). It may cache the
   answer for up to 5 minutes. A deactivation then takes effect within 5 minutes.
3. Refuses with **403** if D365FO says so:
   - `DEVICE_INACTIVE`: the app wipes its data;
   - `DEVICE_UNKNOWN`;
   - or the license is not valid.
4. Verifies the signature.
5. Answers `{"access_token","expires_in"}` from one of its app registrations.
   - It spreads devices over the registrations, for example by a hash of `deviceId`.
   - On a 429 from Azure AD or D365FO it moves the device to another registration.
6. When it is itself overloaded, it answers **429** with `Retry-After`. The app waits and retries.

Error body: `{"errorCode","message"}`.

## Lost or stolen device

In D365FO, open Mobile devices > Activate / deactivate.

1. Within 5 minutes the token service gives the device no more tokens.
2. On its next token request the app gets `DEVICE_INACTIVE` (`DeviceDeactivation`).
3. The app then removes:
   - its local database, **including any unsent work**;
   - its device key;
   - its connection credentials.
4. It says: "This device has been deactivated in D365FO." Setting it up again takes a new setup code.

Before the token service exists, deactivating only marks the device inactive, as before.

## Checked on the dev VM (14 Nov)

- New device setup code shows a 276×276 QR code. Its payload decodes to the fields above.
- `registerDeviceKey` results:
  - an unknown code gives `CODE_UNKNOWN`;
  - the right code gives "Device ready - 6 of 100 devices";
  - the same code again gives `CODE_USED_UP`.
- `getDeviceKey` results:
  - it returns the same public key, active, with a valid license;
  - an unknown device gives `DEVICE_UNKNOWN`;
  - after Activate / deactivate it gives `DEVICE_INACTIVE`.
- App: `DeviceSetupQrTest` (parsing, config, the signed texts).

Not yet built: the token service (Azure Function) and its setup script. The app's register and
token calls are untested until the token service exists.
