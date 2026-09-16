# D365FO Mobile Operations — Android (non-WMS)

Offline-first Android app for warehouse staff performing D365FO inventory and order
operations on tenants **not** running Advanced Warehouse Management. All postings are
order-based or journal-based: product receipts, transfer order ship/receive, packing slips,
and inventory journals. No directed work, no waves, no license plates.

## Status

| Layer | State |
|---|---|
| Build, DI, encrypted Room, MSAL auth, OData/service clients | Complete |
| Offline sync engine (queue, backoff, dead letter, WorkManager) | Complete |
| Validation framework (4 layers) | Complete |
| Warehouse design system, HID + camera scanning | Complete |
| **Module 1 — PO Receiving & GRN** | **Complete, end to end** |
| Modules 2–8 | Room schema + DAOs in place; domain/UI not built |

The database ships with the full schema for all eight modules, so adding the next one does
not require a migration.

## Getting started

```bash
cp local.properties.template local.properties
```

Fill in `local.properties`:

- `sdk.dir` — Android SDK path
- `AAD_TENANT_ID`, `AAD_CLIENT_ID` — from the Azure AD app registration
- `D365_DEV_RESOURCE` / `D365_UAT_RESOURCE` / `D365_PROD_RESOURCE` — AOS base URLs
- `D365_CERT_PIN_PRIMARY` / `D365_CERT_PIN_BACKUP` — SHA-256 public key pins

Then update `app/src/main/res/raw/msal_config.json` and the `msal_signature_hash` string in
`strings.xml` with the values from your app registration.

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

## Running the demo bench (no tenant required)

The **debug** build ships a self-contained demo. Install it and the app comes up signed in
to a fake `USMF` / `WH1`, with three purchase orders and a cached item master.

Only the wire is faked. `ApiModule` exists once per build type — release creates the real
Retrofit clients, debug binds `FakeD365ODataApi` and `FakeD365ServiceApi`. Everything below
that boundary is production code: Room, the sync queue, the posting dispatcher, the
validation chain, WorkManager. A demo that mocked the repositories would prove nothing about
the offline-first behaviour, which is where the risk actually lives.

`DemoSeeder` doesn't write Room directly either — it calls `preloadForShift` and
`refreshOpenOrders`, so every debug launch re-exercises the DTO mapping, delta watermarks and
chunked writes.

Three chips on the worklist screen drive the failure modes:

| Chip | What it does |
|---|---|
| **Network** | Off makes both fakes throw `UnknownHostException`. Capture keeps working; the queue holds. |
| **Rejects** | The AOS returns a real-shaped nested OData error (closed period). Non-retryable → dead-letters immediately. |
| **Flaky** | The first attempt commits server-side then drops the response. The retry replays the same idempotency key and gets `alreadyPosted=true`. |

Supervisor PIN is `1234`. Serial `SN-88120` is already registered server-side, so entering it
on `PO-000491` line 2 fails the layer-4 check when online and is skipped when offline.

Watch it with:

```bash
adb logcat -s D365:* DemoSeeder:* -v time
```

## Architecture

```
presentation/  Compose screens, ViewModels with sealed UiState + StateFlow
domain/        Pure Kotlin models, use cases, repository interfaces (zero Android imports)
data/
  local/       Room entities, DAOs, SQLCipher-encrypted database
  remote/      Retrofit OData + custom AOS service clients, DTOs, interceptors
  sync/        Sync queue, posting dispatcher, WorkManager workers
  repository/  Offline-first repository implementations
  security/    MSAL tokens, Keystore, session lock, supervisor PIN
```

### The one rule

**Write to Room first, enqueue second, never await D365FO in a user-facing coroutine.**

`GoodsReceiptRepositoryImpl.submitForPosting` is the reference implementation: the status
update and the queue insert happen inside a single `database.withTransaction { }`, so there
is no interleaving where a receipt is marked submitted with nothing queued to post it, or a
queue entry exists for a receipt the UI still shows as an editable draft.

### Sync

- **Push** — `TransactionPushWorker` drains the queue serially. Retryable failures climb a
  1 → 5 → 15 → 60 minute ladder; non-retryable ones (closed period, blocked item) skip the
  ladder and dead-letter immediately for supervisor review.
- **Idempotency** — every queued posting carries a key the AOS service must honour, so a
  retry after an ambiguous timeout returns the original receipt rather than posting a second.
- **Pull** — master data delta-syncs on `ModifiedDateTime` watermarks, 500 records per
  request, written to Room page by page. Order status re-syncs hourly.
- **Leasing** — queue entries are leased rather than merely selected, so a worker killed
  mid-post cannot make an entry invisible forever.

### Validation

Four layers, cheapest first, stopping at the first hard failure:

| Layer | Budget | Example |
|---|---|---|
| 1 Format | none | numeric, positive, length |
| 2 Local lookup | <50 ms | barcode → item, location active |
| 3 Cached rules | <100 ms | UoM, over/under tolerance, tracking group |
| 4 Server | network | serial uniqueness, order status before posting |

`HARD` blocks. `SOFT` offers a supervisor PIN override — used where the physical goods on
the dock are the ground truth and the system is the thing that is wrong.

## Decisions worth knowing

**Authorization Code + PKCE, not client credentials.** The brief specified client
credentials, but a client secret shipped in an APK is extractable, and D365FO needs the
worker's own identity so product receipts carry a real `CreatedBy`. `TokenProvider` is an
interface — if a service identity is genuinely required for unattended sync, implement it
behind a backend broker rather than embedding a secret here.

**Goods receipts are their own aggregate, not an inventory journal.** In D365FO a product
receipt is a `PurchFormLetter` posting against `PurchLine`, not an `InventJournal`. Sharing
one table would force nullable purchase-order columns onto every journal line and make the
posting dispatcher ambiguous. `JournalEntity` still covers arrival, movement, adjustment and
counting, which genuinely are `InventJournalTable` postings.

**UoM conversion happens server-side.** Quantities are captured and posted in the purchase
unit with the unit symbol attached; D365FO converts during the form-letter posting, the same
path a desktop receipt takes. Converting on-device would mean shipping and maintaining the
conversion table on every handheld, and a device-side rounding decision that differs from the
server's produces a receipt whose posted quantity does not match what the worker was shown.

**Quantities are `BigDecimal` and stored as strings.** SQLite `REAL` is binary floating
point. A counting journal that drifts by 1e-9 fails reconciliation against D365FO, and
nobody can explain why.

**No `fallbackToDestructiveMigration`.** Wiping the database on a version bump would discard
captured, unposted transactions. A missing migration should fail in CI, not on a loading dock.

**Uncommitted local quantity counts toward tolerance.** `PurchaseOrderLine.outstandingQuantity`
subtracts what this device has captured but not yet posted — otherwise two offline receiving
sessions on the same order could jointly over-receive it.

## What the AOS side needs

Custom X++ services under `D365MobileOpsServices`:

| Operation | Wraps |
|---|---|
| `MobileReceivingService.postProductReceipt` | `PurchFormLetter` / `PurchFormletterParmDataProductReceipt` |
| `MobileDeviceService.registerDevice` | Device registration, supervisor approval |
| `MobileDeviceService.checkRegistration` | Approval state polling |

Each posting operation **must** be idempotent on the `X-Idempotency-Key` header and return
`alreadyPosted: true` on a recognised replay. Without that, a network timeout during a
posting can double-receive an order.

## Next module

The foundation is module-agnostic. Adding Transfer Order Pick means: domain models + use
cases, a repository over the existing `TransferOrderDao`, a `TransferShipPayload` branch in
`PostingDispatcher.dispatch`, a ViewModel, and screens reusing `QuantityStepper`,
`SyncStatusBar`, `StepIndicator` and `SupervisorOverrideDialog`.
