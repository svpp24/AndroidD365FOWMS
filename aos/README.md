# HSMobileOps — AOS service for the handheld

Replaces the OData read path for the scan-driven receiving flow.

Endpoint shape:

```
POST /api/services/HSMobileOpsServiceGroup/ReceivingService/getPurchaseOrder
POST /api/services/HSMobileOpsServiceGroup/InquiryService/validateLocation
POST /api/services/HSMobileOpsServiceGroup/InquiryService/validateSerial
POST /api/services/HSMobileOpsServiceGroup/InquiryService/getOnHand
POST /api/services/HSMobileOpsServiceGroup/CountingService/listOpenJournals
```

Same AAD bearer token as OData; same resource (the environment URL). Nothing new to authenticate.

## Why this exists

The PO step over OData cost **three entity queries** — header, lines, and posted receipts — and the
item step a fourth. Measured on this tenant:

| | Server time (`Server-Timing`) | Wall time |
|---|---|---|
| Single-key OData query | 112–152 ms | 234–509 ms |
| Batched OData order pull | 2456 ms | — |
| Batched OData line pull | 3402 ms | 3680 ms |

A D365FO data entity is a view, not a table. `PurchaseOrderLinesV2` joins `PurchLine`, `InventDim`,
`InventTable` and unit conversions, applies cross-company and row-level security, then serialises
through the OData pipeline — and you pay all of it for a single-key lookup. The same pipeline is
why a projection naming a column that does not exist returned **400 after 40 seconds**: the request
had already gone through IIS, the OData controller and the URI parser before anything checked.

`getPurchaseOrder` collapses the PO step and the item step into one call, reading tables directly.

Two things OData could not give us at all, and this can:

- **Received quantity.** `PurchaseOrderLinesV2` publishes only the ordered quantity, so the app had
  to sum `ProductReceiptLinesV2` — an extra query per order, and wrong if paging was not followed.
  Here it is `PurchQty - RemainPurchPhysical`, two columns on the row already read.
- **Dimension requirements.** Whether an item needs a batch or a serial came from a separate
  released-product call. Here it comes from `InventDimGroupSetup`, resolved once per item.

## Not throttling under load

D365FO throttles **by integrating application** when an environment is under pressure, returning
`429` with `Retry-After`. A throttled request has already cost a full round trip before it is
refused, so the goal is to stay under the limit rather than to recover from it.

### 1. Register the app with High priority — do this before go-live

`System administration > Setup > Priority based throttling` (in some versions, *Throttling priority
mapping*). Map the handheld's **AAD application (client) ID** to **High**.

This is the single most important step and it is configuration, not code. Priorities are consumed
in order under load: Low is shed first, High last. An interactive handheld that a person is
standing and waiting on should not be competing on equal terms with a nightly integration.

**Give the handheld its own AAD app registration**, separate from any other integration. Priority
is assigned per client ID, so sharing a registration with a bulk data job means they are throttled
as one thing — and the bulk job is what will trip the limit.

### 2. Fewest possible calls

Throttling counts requests, so the strongest defence is not making them. A whole receipt is:

| Step | Calls |
|---|---|
| Scan PO (covers item step too) | 1 |
| Quantity | 0 — validated client-side against the tolerances already returned |
| Location | 1 |
| Batch | 0 |
| Serial | 1 per unit |

Four calls for a typical untracked receipt, against roughly fifteen over OData.

### 3. The client already removed its background load

The Android app used to run a full master-data preload and a full order pull on every launch and
every sign-in. Those are gone from the scan-driven path — they now start only when the list-first
worklist screen is opened. Fleet-wide that is the difference between *N* devices × a preload every
four hours and *N* devices × one request per scan.

### 4. Read path holds nothing

- No `ttsbegin` anywhere on this path. A read that takes a lock makes every other session wait.
- Every `select` is keyed on an existing index and scoped to one legal entity via `changecompany`.
- No `crossCompany`. A cross-company read cannot use the company-partitioned indexes and scans
  instead — the shape that falls over when fifty handhelds scan at once.
- Explicit field lists, not `select *`. `PurchTable` is wide.
- `firstOnly` on every single-row read.
- `HSMobileOpsReceivingService::maxLinesPerOrder()` caps the response at 200 lines. An unbounded
  response is how one bad scan takes an AOS session out of circulation while every other device
  waits — AOS capacity is bounded by concurrent sessions, so call *duration* matters more than
  payload size.
- On-hand is grouped in SQL, so a per-bin result set collapses before it leaves the database.
  Note this bounds the *response*, not the *scan*: `InventSum` is among the largest tables in the
  application and an item held across thousands of bins is still read in full. A row ceiling
  backstops it, and narrowing to one warehouse — the enquiry screen's default — is what keeps it
  off that path.

### Where performance is *not* yet the priority

Being straight about the remaining hole: **barcode resolution is still on OData.** There is no
service method for it, so scanning an item by its barcode — the common case at the item step —
still pays the entity-view cost that this service exists to remove. Scanning by *item number*
costs nothing, because the lines are already in hand from `getPurchaseOrder`.

Adding `resolveBarcode` to `InquiryService` is the next worthwhile change. It is a keyed read of
`InventItemBarcode` and would look much like `validateSerial`.

### 5. Client-side

Already in place, in `RateLimitInterceptor` / `RetryAfterInterceptor`:

- A fair semaphore caps concurrent calls per device.
- `429` and `503` honour `Retry-After` with bounded attempts.
- Overlapping scans cancel the in-flight lookup rather than racing it.

**Postings are never retried at the transport layer.** The sync queue owns their retry policy
because it also owns the idempotency key that makes a retry safe.

### 6. What to watch

- `429` rate by client ID, in the environment's monitoring.
- Per-call server time. The platform returns `Server-Timing` on OData; whether it does so for
  custom services is **unverified** — check on first deployment, and if it is absent, time the
  call inside the service and log it, because a number that excludes device wifi is the only one
  worth tuning against.
- **Sandbox is not production.** `hssandbox` is a Tier-2 environment: shared, throttled, and
  materially slower than production. Measure there before concluding anything about capacity.

## Posting, and why every call carries an idempotency key

`PostingService` writes: product receipts, transfer shipments, transfer receipts.

**A handheld on warehouse wifi loses responses routinely.** The posting commits, the reply never
arrives, and the queue retries. With no replay guard the retry posts a second receipt, the stock is
booked in twice, and nobody notices until a count weeks later — by which point the trail is cold.

So the client mints one key per *captured transaction* (not per attempt, and it must survive an app
restart), and `HSMobileOpsPostLog` records it. Three properties make this actually safe rather than
merely likely to work:

1. **The log row is written inside the posting's own transaction.** Not after it.
2. **The unique index is the enforcement, not the lookup.** Checking for a row and then inserting
   is a race — two retries arriving together both find nothing and both post. Here the *insert*
   fails, and the posting rolls back with it.
3. **A replay returns the original document number with `AlreadyPosted = true`, and posts nothing.**
   That is a success: the client clears its queue entry knowing the work landed exactly once.

Two other rules worth knowing before you read the code:

- **Quantities come from the request and are never re-derived.** The operator counted something.
  Posting what the order expected instead would silently paper over a real short delivery.
- **Ceilings are re-checked server-side.** The device checked them too, but a queued request may be
  minutes old and somebody else may have shipped part of the same line since.

D365FO's own failure message is passed straight through in `Message`. A closed period, a stopped
line or a blocked item are answers a supervisor can act on; flattening them to "posting failed"
throws away the only useful part.

## Installing

Everything here is AOT metadata XML, ready to drop into a model — one `AxClass` per file, with the
X++ carried inside `<Declaration>` and `<Methods>` exactly as the designer writes it.

1. Create a model (e.g. `HSMobileOps`). `Descriptor/HSMobileOps.xml` here declares the references
   it needs - `ApplicationCommon`, `ApplicationFoundation`, `ApplicationPlatform`,
   `ApplicationSuite`, `Directory`, `UnitOfMeasure`.
2. Copy the folders into the model's metadata directory, keeping the structure:

```
PackagesLocalDirectory\HSMobileOps\HSMobileOps\AxClass\*.xml
PackagesLocalDirectory\HSMobileOps\HSMobileOps\AxService\*.xml
PackagesLocalDirectory\HSMobileOps\HSMobileOps\AxServiceGroup\*.xml
```

   The folder name must match the element type — the metadata provider locates objects by
   directory, not by anything inside the file.
3. Refresh the model in Visual Studio so it picks the objects up. They will not appear in the AOT
   until the model knows about them.
4. Assign the `HSMobileOpsAdministrator` role to whoever maintains handheld logins, and create the
   first user on the form before anyone tries to sign in from a device - the app has no bootstrap
   path and will refuse every credential until a row exists.
5. Build, then **Full build** of the service group so the WCF endpoints are generated.
6. Confirm the endpoint responds:

```bash
curl -X POST "https://<env>/api/services/HSMobileOpsServiceGroup/ReceivingService/getPurchaseOrder" -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"_request":{"DataAreaId":"usmf","PurchId":"00000925","WarehouseId":"100011"}}'
```

The parameter wrapper name (`_request`) matches the X++ method parameter. If the service is
regenerated with a different parameter name, the JSON key changes with it — check the generated
contract rather than assuming.

## User setup in D365FO

Handheld logins are maintained in D365FO, not in the app. There is deliberately no admin screen on
the device: the person who decides who may receive stock is not standing in the aisle holding a
scanner, and a handheld that can create its own accounts is a handheld that can grant itself
authority it was never given.

**Where it lives.** *Mobile operations > Setup > Mobile device users*, as its own module in the
navigation pane rather than buried inside Warehouse management. `AxMenu/HSMobileOps` is the menu;
`AxMenuExtension/MainMenu.HSMobileOps.xml` registers it on `MainMenu`, which is what the
navigation pane enumerates. Without that registration the menu exists in the AOT and appears
nowhere.

**The form** (`AxForm/HSMobileOpsUser.xml`) is a SimpleListDetails over `HSMobileOpsUser` with a
delayed-join child grid of `HSMobileOpsUserWarehouse`. The identity fields sit in the details
header; status and warehouses are FastTab pages. Three things about it are deliberate:

- `PasswordHash`, `PasswordSalt` and `HashIterations` are hidden and non-editable. They are on the
  data source because the table needs them, not because anyone should see them. A hash visible on
  a form is a hash that ends up in a screenshot in a support ticket.
- `AllowDelete` is `No`. Deactivation, never deletion — `HSMobileOpsPostLog` attributes every
  posting to a user row, so deleting the row orphans the audit trail at exactly the moment it
  matters. `HSMobileOpsUserAdmin::setActive` switches the account off instead.
- `IsDefault` is read-only in the grid. Two rows both flagged default is a state with no correct
  reading, so it is set through `HSMobileOpsUserAdmin::makeDefaultWarehouse`, which clears the
  others in the same transaction.

**Setting a password** never happens through a bound control. `HSMobileOpsSetPassword` opens a
dialog with two `Password`-EDT fields and rejects a mismatch, so the plaintext lives in a dialog
variable and not in a form data source that could be cached, filtered on, or exported. The reset
forces `MustChangePassword` and clears any lockout — a temporary password read out over the phone
should be good for exactly one sign-in.

Hashing has exactly one implementation (`HSMobileOpsPassword`). That is the reason user admin is a
class rather than form code: a form that generated its own salt, or picked a different iteration
count, would write rows the handheld could never authenticate against, and the failure would look
like a wrong password rather than like a bug.

### Security artifacts

| Object | Type | Grants |
| --- | --- | --- |
| `HSMobileOpsUserMaintain` | Privilege | The form and all four action menu items; full CRUD on both user tables; `Read` on the posting log |
| `HSMobileOpsUserView` | Privilege | The form read-only; `Read` on both user tables and the posting log |
| `HSMobileOpsMaintainUsers` | Duty | `UserMaintain` |
| `HSMobileOpsInquireUsers` | Duty | `UserView` |
| `HSMobileOpsAdministrator` | Role | `MaintainUsers` |

The posting log is read-only in both privileges, including the maintain one. It is the evidence
that a receipt was posted once and not twice, so it must not be editable by the people it holds to
account — if it can be quietly corrected, it stops being evidence.

It rides along on the two user privileges rather than standing as its own. A privilege with no
entry point fails the `BPErrorPrivilegeIsEmpty` best-practice rule, and there is no form for the
posting log to point one at — so a standalone log privilege would have to be a table grant with
nothing to attach to.

`HSMobileOpsInquireUsers` is in no role on purpose. It is there to be added to a customer's own
supervisor role, where the local judgement about who may look lives; inventing a second role here
would just be guessing at their org chart.

**Table permissions are all-or-nothing.** `UserView` grants `Read` on `HSMobileOpsUser`, and that
includes the hash columns for anything that reads the table directly. The form hides them, which
is enough against casual viewing and nothing else. If the hashes must be unreadable by inquiry
users, that needs field-level permissions on the table, which is not done here.

## Service security

`SysEntryPointAttribute` is left at its default, so row-level security and the caller's data
permissions are enforced. Do **not** set it to `false` to make these calls faster; the service
account would then read across every legal entity regardless of its assigned permissions.

The read services (`Receiving`, `Inquiry`, `Transfer`) take no lock and write nothing.
`HSMobileOpsPostingService` and `HSMobileOpsUserService` do write, under the idempotency contract
described above.

Two identities are in play and they answer different questions. The service principal is the
*device* — it is what AAD authenticates and what throttling is priced against. The worker login is
the *person* — it is what `HSMobileOpsPostLog` records. Neither substitutes for the other: a shared
handheld with only a service principal cannot tell you who received the pallet.

## Transfer postings are full-line only, on purpose

`postTransfer` refuses a request whose quantity is less than the outstanding quantity on any
line, returning `PARTIAL_UNSUPPORTED`.

This is not an oversight. The transfer engine takes its quantity from
`InventTransferParmTable.ShipUpdateQty`, whose values are `All` (the full outstanding quantity)
and `PickedQty`. Neither reads the per-line `QtyShipNow` the service writes. Posting a partial
down this path would therefore ship the **whole remaining line** — booking stock that was never
physically moved, and doing it silently, with the device reporting success. A refused posting is a
phone call; a silent over-ship is a discrepancy found at a count weeks later by someone with no way
to explain it.

Lifting the restriction means creating `InventTransferParmLine` rows carrying the per-line
quantities, then verifying against a real partial shipment on a dev box. Neither published source
consulted creates those rows, so the field names are unverified and the behaviour is untested. Until
someone does that test, full lines only.

Purchase receiving *is* capable of per-line quantities — `PurchFormLetter` supports it, unlike the
transfer engine — but it wasn't actually doing so until 4 September 2026. See below.

## postProductReceipt used to ignore captured quantities entirely

Found while investigating a concurrency question, not by testing: `postProductReceipt` called
`formLetter.update(purchTable, reference, date, PurchUpdate::All)` — the all-in-one convenience
overload. That overload builds its own parm lines internally and defaults every one of them to the
line's full remaining quantity, because nothing told it otherwise. `_request.parmLines()` was never
read. On an order fully received in one visit this happened to look right; on any partial visit —
which on a large order is every visit — it silently received **every outstanding line on the whole
order**, regardless of what was actually captured. This is exactly the failure the posting section
above warns against, and it directly contradicted this class's own header comment ("quantities are
taken from the request, never re-derived") — an oversight, not a considered simplification.

The fix builds `PurchParmTable`/`PurchParmLine` explicitly — one header row, one line per captured
quantity with `ReceiveNow` set from the request — and binds `PurchFormLetter` to that parm table
instead of letting it build its own. This mirrors what `postTransfer` already did correctly for
`InventTransferLine`. Each line's captured quantity is also checked against `RemainPurchPhysical`
*read inside the transaction*, the same live-quantity guard `postTransfer` already had — so a
second worker's overlapping capture on the same line is now rejected against the current remaining
amount rather than silently over-receiving it. That guard is a partial answer to concurrent workers
on one order, not a full one: it stops double-counting inventory, but it does nothing to warn a
worker their scan overlaps someone else's before they've done the physical counting.

**This pulls in a much larger unverified API surface than most of this model.** Sourced from two
independent, mutually corroborating examples ([1](https://community.dynamics.com/forums/thread/details/?threadid=f6f8b516-5b83-41e9-8282-36d0b157dd73),
[2](https://sangeethwiki.blogspot.com/2022/12/posting-invoiceproduct-receiptpacking.html)) rather
than guessed, but neither compiled against this exact application version. New names that have not
been checked here: `PurchFormletterParmData::newData`, `PurchParmUpdate`, `ParmJobStatus`,
`parmOnlyCreateParmUpdate`, `createData`, `PurchParmLine.initFromPurchLine`, `.setQty`,
`.setLineAmount`, and on `PurchFormLetter` itself: `.transDate`, `.proforma`, `.specQty`,
`.purchParmUpdate`, `.parmParmTableNum`.

The one genuine ambiguity between the two sources: the final call is `.run()` in one and
`.runOperation()` in the other. `.run()` is what's here; if the build names the other one, that is
the first thing to try. Also worth knowing before this looks like a leftover bug: `PurchUpdate::All`
appears in both the old, wrong call *and* the new, corrected one — same enum value, different
meaning in each context. In the simple `.update(...)` overload it meant "build your own parm lines
for the whole order's remaining quantity." Passed to `.specQty()` once the parm lines already exist
with `ReceiveNow` explicitly set, it means "process every parm line that was inserted" — which is
now only the ones this method built, from the request.

## Counting journals — list confirmed live; lines deliberately not started

Different shape from everything above it in this file. A counting journal is **created in
D365FO** — a planner running a cycle count, not the worker starting one from the device — and
scoped to the worker's assigned warehouse. `HSMobileOpsCountingService::listOpenJournals` follows
the same load reasoning as the rest of this service group: a direct, indexed, warehouse-scoped
read of journal headers, cheap because a warehouse has a handful of open counts, not thousands.

**Confirmed live against the DEV environment, 2026-09-23**, curled directly (client-side device
code sign-in, `DataAreaId=BSP`, `WarehouseId=AAC`):

```
HTTP 200, 3.56s
{"Success":true,"ErrorCode":"","Rows":[{"JournalId":"BSP-JN-000552000","JournalNameId":"InvCJ",
"Description":"Inventory Counting Journal","JournalDate":"2026-09-11T12:00:00","LineCount":231}]}
```

231 lines on one journal is a real, useful data point for the size this app has to plan around —
not a toy number, and a fair justification for not fetching those lines through this same
request/response shape.

Two real mistakes on the way to that result, both worth naming so they don't repeat:

- **`InventJournalType::Counting` doesn't exist — the enum value is `InventJournalType::Count`.**
  Wrong in both the header filter and the `InventJournalName` join condition; fixed in both places.
- **`exists join`/`notexists join` have to come last in a join chain.** They're pure semi-join
  filters with no field access, and the original query put `outer join inventJournalName` *after*
  the two `exists join`s — which compiled fine (AOT metadata doesn't catch this) but failed at
  runtime with `Cannot select a record in Inventory journal table (InventJournalTable)`, X++'s
  generic wrapper for a SELECT that fails at the SQL level. Reordered so `outer join` comes
  immediately after the base `where`, both `exists join`s last. First real evidence in this file
  that "compiles" and "the AOT accepted the metadata" are not the same claim as "the SQL is valid"
  — everything else here was curled before being trusted for exactly this reason; this one nearly
  wasn't, and it's the reason it should keep being the rule.

One more thing the live response caught: `JournalDate` comes back as `"2026-09-11T12:00:00"` — no
zone suffix, unlike OData's own datetime format. The Android side originally reused
`Formatters.odataToEpochMillis` (which requires the `Z` and would have silently parsed this to
`null`); fixed with a dedicated `Formatters.serviceDateTimeToEpochMillis` that treats the naive
value as UTC, matching `CreatedDateTime`'s real X++ type.

**The DMF half.** The source entity is settled: **`InventInventoryCountingJournalLineEntity`**, a
standard data entity confirmed present on this tenant — not a custom one, no `AxDataEntityView`
needed. Its field list is confirmed too, from Microsoft's Common Data Model docs: `JournalNumber`,
`LineNumber`, `ItemNumber`, `InventorySiteId`, `WarehouseId`, `WarehouseLocationId`,
`ItemBatchNumber`, `ItemSerialNumber`, `OnHandQuantity`, `CountedQuantity`, among others —
`CountedQuantity` is what the counted-data-back direction below writes.

**Export and import are no longer symmetric — deliberately, after working through a real
multi-worker concurrency question.** The original design shared one DMF project across every
worker in both directions, scoped per-request by rewriting a packed query. That closes the
*filter* race (two workers targeting different journals overwriting each other's scope) but not
the deeper one underneath it: two concurrent DMF *executions* sharing one project's staging
infrastructure, which is exactly the class of risk Microsoft's own docs flag for
`ImportFromPackage` ("parallel processing rules must be used... don't call this in parallel"),
just never documented for the synchronous `exportToFile` path this app uses on export. The fix
ended up different on each side, because the two directions have genuinely different concurrency
primitives available to them:

- **Export: a brand-new DMF definition group for every request**, never reused (named
  `HCE_<last 12 chars of journal id>_<8 hex of a GUID>`). Nothing is shared between two workers -
  different journals or even the same one - and a project half-configured by an earlier failed
  attempt can't poison a later one. Projects accumulate, one per request; nothing purges them yet.- **Import: still one shared project, but triggered as an enqueued batch job instead of run
  synchronously.** `DMFPackageImporter::PrepareDefinitionGroupForImport`/`importFromPackageV2` —
  confirmed from a real, shipped integration class (`DataManagementIntegrationUtils`, Personnel/HCM
  integration module) — hands the run to D365's own batch framework, which gets its concurrency
  safety from the framework itself rather than from project isolation. This is the same mechanism
  Microsoft's parallel-processing-rules configuration for `ImportFromPackage` ultimately routes
  through; enqueuing through code sidesteps needing that manual configuration step at all.

**Export: AOS builds the project, the app triggers it - the way Microsoft's Recurring Integrations
Scheduler does.** `HSMobileOpsCountingService::exportOpenJournals` only creates the request-private
project (`HSMobileOpsCountingExportSetup::ensureExportForJournal`), scopes it to one journal
(`filterExportToJournal`, a packed-`QueryData` rewrite) and returns its name. The app then generates
its own execution id (`{project}-{yyyy-MM-dd_HH-mm-ss}-{guid}`, RIS's format), calls the public
`ExportToPackage` OData action with it (`reExecute=false`), polls `GetExecutionSummaryStatus`
(`NotRun` -> `Executing` -> `Succeeded`) and fetches `GetExportedPackageUrl`. Verified live: from
`ExportToPackage` to a package URL took ~12 s, and the package's CSV has the expected columns
(`JOURNALNUMBER`, `LINENUMBER`, `COUNTEDQUANTITY`, `ITEMNUMBER`, `WAREHOUSELOCATIONID`, ... - **no
`ONHANDQUANTITY`**, so the count screen has no pre-filled "expected" value).

**Root cause of every earlier export failure: the entity name.** `DmfDefinitionGroupEntity.Entity`
holds DMF's own entity name - `Inventory counting journal lines` - not the AOT name
`InventInventoryCountingJournalLineEntity`. Rows written with the AOT name were invisible to the
Data Management UI (empty "Selected entities" grid) and to DMF: a synchronous
`DMFEntityExporter::exportToFile` threw `Entity ... not found`, and `ExportToPackage` on such a
project sat at `NotRun` indefinitely (200 s+, no errors) while a UI-built project ran in ~15 s.
Found by dumping every `DmfDefinitionGroupEntity` row of a UI-built project beside code-built ones:
the UI's own row carried the label and `SkipStaging=0`. `HSMobileOpsCountingExportSetup::entityName`
now resolves the DMF name from the AOT name via `DMFEntity.TargetEntity`, and `SkipStaging` is
false. RIS itself never creates a data project - it exports a pre-built one by name - so project
creation and scoping is the one part that stays in X++.

**Import is enqueued, not awaited — `HSMobileOpsCountingService::importCounts`.** Ensures the
shared `HSMobileOpsCountingImport` project exists (`HSMobileOpsCountingExportSetup::ensureImport`),
then calls `DMFPackageImporter::PrepareDefinitionGroupForImport` + `new
DMFPackageImporter().importFromPackageV2(packageUrl, groupName, executionId, true, true,
curExt())` and returns immediately with the executionId — deliberately not calling the reference
class's own `waitForImport` (which polls every 30s for up to 12 hours; fine for a batch job,
wrong for something an HTTP service method returns synchronously to a phone). The app polls the
same way it always would have: the public `GetExecutionSummaryStatus`/`GetExecutionErrors` OData
actions, documented at
https://learn.microsoft.com/en-us/dynamics365/fin-ops-core/dev-itpro/data-entities/data-management-api
— only the *trigger* moved off the public `ImportFromPackage` action, not the status check, since
reading a `DMFDefinitionGroupExecution` row carries none of the concurrency risk triggering one
does. `GetAzureWriteUrl` (uniqueFileName → writable blob SAS URL) is still used as-is for the
upload step ahead of `importCounts`.

**Both kinds of project are created entirely by code, on first use — no manual Data Management
workspace step, on either side.** `HSMobileOpsCountingExportSetup` went through several live
rounds to get here, each one replacing a guess with something confirmed:

1. A raw `DmfDefinitionGroupEntity` insert threw `validateWrite() refused ...` behind this
   class's own generic guessed message - masking the real reason. Removing the wrapper (same
   fix applied everywhere in this file: let native X++ exceptions propagate rather than guess
   at them) surfaced that the insert itself was the problem, not the message.
2. The next attempt got past the insert but failed inside `filterExportToOpenJournals` with
   `Object 'QueryRun' could not be created` - `DMFUtil::getDefaultQueryForEntityV3` (inherited,
   never independently confirmed) was returning an empty container. Confirmed via the same
   diagnostic technique: stop swallowing, check the container length directly.
3. Manually running "Generate" on the project in Data Management workspace made no difference,
   ruling out a missing staging table as the cause. Replaced with a bare `new Query()` +
   `addDataSource(tableNum(InventJournalTrans))` - the entity's real backing query doesn't need
   reconstructing for this method's one job (scoping to a `JournalId` range).
4. That got past query construction, but `DMFEntityExporter::exportToFile` then threw
   `Entity 'InventInventoryCountingJournalLineEntity' not found` - confirmed via the D365 UI
   that this is the entity's real technical name, ruling out a naming mistake. The actual cause:
   a hand-inserted `DmfDefinitionGroupEntity` row was never registered in DMF's own internal
   entity catalog (`DMFEntity`), which is a different, DMF-specific table from the general data
   entity list the UI picker searches.

**Fixed by switching to a real, shipped, production integration class**
(`DataManagementIntegrationUtils`, from the Personnel/HCM integration module) instead of
continuing to guess at raw table fields. It revealed two things this model had wrong from the
start: `DmfDefinitionGroup.OperationType` is the real export/import direction field - on the
definition group (parent), not the entity row - and `DMFEntityBase::addEntityForProcessingV3(...)`
is the real, complete "add this entity to this project" API, handling field mapping and DMF
catalog registration in one call instead of a raw insert. `HSMobileOpsCountingExportSetup::ensure()`
(export) and the new `ensureImport()` (import) both now go through
`Adapter`/`DefinitionGroupName`/`Description`/`OperationType`/`GenerateDataPackage` on
`DmfDefinitionGroup`, `DMFParameters::find(false, true, false)` (refreshes DMF's entity/data-
source-type catalog - likely what registration was actually missing), then
`addEntityForProcessingV3` with the same twelve positional arguments the reference class uses,
substituting only this app's own names - see that class's own doc comment for exactly which of
those twelve are independently confirmed versus reproduced faithfully from a working reference.
`ensureImport()` is called from inside `HSMobileOpsCountingService::importCounts` itself now
(there is no longer a separate `ensureImportSetup` service operation — folding the two into one
call removes a round trip the app used to need before every submit).

**No manual "scope to every open journal" workaround needed any more.** The earlier design (one
shared export project, scoped per-request) had to widen its filter to every currently-open journal
to survive two workers requesting *different* journals close together, because a narrower,
single-journal filter on a shared project raced: whichever `scopeExport` call landed last would
silently point the other worker's export at the wrong journal. Per-journal projects remove the
shared state that race depended on — `exportOpenJournals` filters straight to the one journal the
request actually asked for (`filterExportToJournal`), because there is no other worker's request
left that could still be sharing that project's `DmfDefinitionGroupEntity` row.

The counted-data-back direction reuses the export's own package rather than constructing DMF
package XML from scratch: the app downloads a journal's real exported package, unzips it, changes
only the `CountedQuantity` column per line in the CSV using the counted values captured on the
device, and re-zips it with the **same** `Manifest.xml`/`PackageHeader.xml` D365FO itself
generated — then uploads via `GetAzureWriteUrl` and imports via `HSMobileOpsCountingService::
importCounts` (enqueue) against the shared `HSMobileOpsCountingImport` project. Every identity/
dimension column (`JournalNumber`, `LineNumber`, `ItemNumber`, `WarehouseLocationId`,
`ItemBatchNumber`, `ItemSerialNumber`, ...) round-trips unchanged, so DMF's own target-matching has
exactly what it needs without this app ever having to know how that matching works internally.

**The Android side is being rewired against this new contract** — see the app's own plan/commit
history for `CountingJournalRepository`, `CountingJournalListViewModel` and the new
`CountingCaptureScreen`. None of it has been exercised against a real run yet: the export side got
as far as a real, specific AOS error (`ENTITY_NOT_REGISTERED`, since fixed by the
`addEntityForProcessingV3` switch above) before the design moved to per-journal projects and
enqueued import, so the full pipeline — export, download, Room import, count entry, enqueue
import, poll — has not yet had an end-to-end live run against the rebuilt AOS model. Whether
`DMFPackageImporter::PrepareDefinitionGroupForImport`/`importFromPackageV2` accept a remote blob
URL the same way the public `ImportFromPackage` action does, versus expecting a local
`SharedServiceUnitFileID` the way the reference class's own call site received it, is the
single biggest open question for the first live round on the import side.

### Counting extensions — claims, audit, DIRECT mode (`HSMobileOpsCountTaskSvc`)

New service `HSMobileOpsCountTaskSvc` (class `HSMobileOpsCountTaskService`), added to
`HSMobileOpsServiceGroup` — **full service-group build required**. The app only renders and
captures; every rule stays here. What is real and what is left for the X++ owner:

| Operation | State |
|---|---|
| `getJournalState` | Real: OPEN / POSTED / NOT_FOUND from `InventJournalTable`. The app calls it before reopening a submitted journal and refuses to reopen a posted one. |
| `getJournalLines` | Real read of `InventJournalTrans` + `InventDim` for DIRECT mode, optional location range, capped at 500 lines (`TOO_MANY_LINES`). Blind: no on-hand, counted or variance quantity. |
| `claimJournal` / `heartbeatClaim` / `releaseJournal` | Request and journal-state validation real; the claim itself is **TODO** (claim table, TTL, one holder per range). Returns `NOT_IMPLEMENTED` until then. |
| `submitCounts` (DIRECT) | Validation real; writing counts to `InventJournalTrans`, dedupe on `SubmissionId`, storing the audit and deciding what `MarkedOnly` means are **TODO**. Returns `NOT_IMPLEMENTED`. |

Changes to existing objects:

- `listOpenJournals` rows now carry `TransferMode` (always `DMF` today — **TODO**: choose `DIRECT`
  for small journals), `Claims` and `Ranges` (empty — **TODO**: fill from the claim/range tables).
- `exportOpenJournals` accepts `LocationFrom`/`LocationTo` and filters the export on the entity's
  `WarehouseLocationId` field (same packed-query mechanism as the journal filter) — one claimed
  range of a wall-to-wall journal. `ClaimToken` is carried; validating it is **TODO**.
- `importCounts` now refuses a posted journal (`JOURNAL_POSTED`) or a missing one
  (`JOURNAL_NOT_FOUND`), and receives the submission audit: `SubmissionId` (client UUID,
  idempotency key), `UserId`, `DeviceId`, `StartedAtUtc`, `SubmittedAtUtc`, `PassNumber`,
  `OriginType`, `ClaimToken`. Storing the audit and deduping on `SubmissionId` are **TODO**.

Error codes the app acts on: `CLAIMED_BY_OTHER`, `CLAIM_EXPIRED`, `CLAIM_LOST`, `JOURNAL_POSTED`,
`JOURNAL_NOT_FOUND`, `TOO_MANY_LINES`, `NOT_IMPLEMENTED`. App-sent timestamps are ISO 8601 UTC
strings. On the app, claims and DIRECT mode are behind `FEATURE_COUNT_CLAIMS` /
`FEATURE_COUNT_DIRECT` in `local.properties` (off by default).

**Location-first, empty locations, unexpected items.** `listOpenJournals` rows also carry
`LocationFirst` and `ZeroStockCheck` (both `false` today — **TODO**). New operation
`submitExceptions` on `HSMobileOpsCountTaskSvc` receives, after the counts of the same
`SubmissionId`, the items a counter recorded as "Not on this journal" (`ClientId` UUID, item,
location, quantity, batch, serial, note, who/when) and the locations confirmed empty (location,
who/when). The app never creates journal lines; storing these for the planner to resolve is
**TODO** (returns `NOT_IMPLEMENTED`). An empty location's lines themselves are counted as zero on
the device and go through the normal count import. If `submitExceptions` refuses, the counts stay
submitted and the app shows the exceptions as not sent, with a "Send again" action. App flags:
`FEATURE_COUNT_LOCATION_FIRST`, `FEATURE_COUNT_EXCEPTIONS`.

## Licensing

**Full replacement, this session — a certificate-chain design, not the earlier single-key-per-
client one.** The old design's trust anchor (`HSMobileOpsLicenseSetup.PublicKeyXml`) was an
ordinary, admin-editable table field inside the *customer's own* tenant — which meant a
customer's own SysAdmin could paste in their own keypair and self-sign an unlimited license;
nothing outside their own database was ever consulted. See `licensing/README.md` for the full
chain and the threat model it closes.

**Two signed artifacts now, not one, each with its own signer.** A **certificate**
(`WMSCERT1.<payload>.<signature>`) binds one customer/tenant identity to that customer's own
public key, signed by a **root** private key the vendor alone ever holds — its public half is
compiled into every Android build *and* hardcoded in `HSMobileOpsLicense::rootPublicKeyXml()`
(never a table field, which is the actual fix: a compiled constant cannot be edited by a
customer's own admin the way a database row could). A **license**
(`WMSLIC1.<payload>.<signature>`) carries the actual customer/tenant/expiry/device-limit grant,
signed by that same customer's own private key — the one the certificate just vouched for.
Both are JSON payloads, base64url-encoded, signed over the base64url text itself (not the
decoded bytes) — same "sign the encoded text" convention the old design already used, so
verification anywhere is just "split on the two dots, verify the first segment's own bytes."
See `HSMobileOpsLicense.xml`'s own class doc for the exact format and
`licensing/wms_license_tool.py` for the one tool that produces both.

**The app is the primary verifier, not this AOS.** The Android app performs this exact same
certificate-then-license chain itself, against its own compiled-in root key, before it will
even show a sign-in screen (`LicenseRepository`, `AppRoot`'s licensing gate) — activation is a
one-time QR scan of the certificate, and every launch re-verifies it and fetches the current
license from `HSMobileOpsLicenseSvc/getLicense`. This AOS's own `HSMobileOpsLicense::verify()`
runs the identical chain as a secondary, defense-in-depth check — both because
`HSMobileOpsUserService::signIn` still gates on it directly (a modified client could in
principle skip its own pre-flight checks and call `signIn` straight), and because
`HSMobileOpsLicenseSetup`'s own `insert()`/`update()` refuse to save a certificate/license pair
that would not actually verify together (customer/tenant match included, not just each
signature individually).

`HSMobileOpsLicenseSetup` stays `SaveDataPerCompany=No` — one pair for the whole environment.
An administrator pastes both strings into the **Mobile app license** form's two plain fields and
saves; `validateBeforeSave()` runs the full chain and throws (rolling the save back) on anything
that does not verify — the same shape the earlier design already used here for encrypting
`PublicKeyXml` on save, not a separate import action or unbound staging controls (an earlier
draft of this form used both and failed to even deserialize in Visual Studio — "cannot be
deserialized as ... AxForm" — so the design was simplified back to plain bound fields, which are
already proven to work in this exact form). `licensing/wms_license_tool.py` (Python, replacing
the old PowerShell scripts) mints the root keypair once, a keypair per customer, signs each
customer's certificate with the root key, renders it as a QR image, and signs each license with
that customer's own key.

**Device registration moved earlier, and gained a second, independent call site.**
`HSMobileOpsUserService::signIn`'s own inline license/device-limit gate is unchanged in
behaviour (kept as the safety net described above) but now calls the same
`HSMobileOpsDevice::checkDeviceLimit` helper a brand-new `HSMobileOpsLicenseSvc/registerDevice`
operation also calls — the app invokes that one at launch, before any worker has signed in, so
a device that will never pass the device-limit gate finds out immediately rather than only once
someone tries to sign in on it. Both call sites can never disagree, since neither duplicates the
gate logic. `getLicense`/`registerDevice` need a **full service-group build** to register, same
gotcha as every other service in this model — see "Verification status" below.

New `HSMobileOpsGetLicenseResponse`/`HSMobileOpsRegisterDeviceResponse.ErrorCode` values reuse
`HSMobileOpsSignInResponse`'s own vocabulary: `NO_LICENSE`, `LICENSE_INVALID`,
`LICENSE_EXPIRED`, `DEVICE_LIMIT_REACHED`.

**Not yet built or verified against the tenant** — written this session, awaiting the next
Visual Studio build. The single biggest open technical question: whether
`Newtonsoft.Json.Linq.JObject::Parse` (already confirmed present and working on this exact
server for `HSMobileOpsPostingService::parseLines`'s `LinesJson`) parses these certificate/
license payloads the same way — reused deliberately rather than risking an unverified JSON API,
but not yet exercised for *this* shape of JSON specifically. See "Verification status" below.

## Verification status

**The whole model compiled clean in Visual Studio on 3 September 2026** — every class, the two
tables, the EDT, the form, the menu, the menu extension, the four action menu items and the
security objects. That was the first build in which nothing was outstanding.

**`postProductReceipt` changed after that build, on 4 September, and has not been recompiled.**
See "postProductReceipt used to ignore captured quantities entirely" above — it carries a large
unverified API surface and needs its own build-and-fix round before this claim covers it again.

**Confirmed live, 7 September 2026: this is now blocking, not just unverified.** A real
`postProductReceipt` call from the handheld (`BSP-PO-000161679`, item `BSR-63310-0`, new batch
`TESTBATCH02`) came back `HTTP 500` after ~7.8 s:

```
Unable to cast object of type 'Newtonsoft.Json.Linq.JObject' to type
'Dynamics.AX.Application.HSMobileOpsPostLine'.
```

Replayed directly against the AOS with curl (bypassing the app entirely) to rule out an
Android-side cause — same request shape, same error. The local X++ source is not wrong: both
`HSMobileOpsPostLine.xml` and `HSMobileOpsPostRequest.xml`'s `parmLines` already carry the
correct two-argument `DataCollectionAttribute(Types::Class, classStr(HSMobileOpsPostLine))` —
without the second argument the AIF serializer has no concrete element type to bind each array
entry to, and hands the method a `JObject` instead, which is exactly this exception. That is the
textbook cause of this exact message, and the deployed service is still answering with it, which
means the currently-deployed build predates whatever last fixed that attribute (or predates the
whole `postProductReceipt`/contract rewrite). **The model needs a fresh Visual Studio build and
redeploy of at least `HSMobileOpsPostingSvc` (and, safest, the whole model) before any receipt
can post from a real device.** Nothing on the Android side can work around this — the request the
app sends is already correct.

**Ruled out as a stale-deployment issue, same day.** After importing the 9 objects changed since
3 September and doing a full class build, `getPurchaseOrder` came back with fresh fields
(`ClaimedByUserName`, `ConcurrencyMode`, …) proving the redeploy really landed — but
`postProductReceipt` threw the identical `InvalidCastException`, with a new `ActivityId` and a
sub-second response time. A **separate, explicit full build of just the `HSMobileOpsPostingSvc`
service node** (to regenerate its WCF/AIF endpoint metadata, distinct from compiling the class)
was tried next and made no difference either. Two confirmed rebuilds, identical fresh failure
both times: this is not a deployment gap, it is this D365FO version genuinely unable to bind an
inbound JSON array to a `List` of a custom `[DataContractAttribute]` class on a custom-service
parameter — every other service in this group only ever sends `List`-of-custom-class *outbound*
(in a response), which works fine throughout; `HSMobileOpsPostRequest.Lines` was the only inbound
case in the whole model.

**Fixed by not asking the platform to bind that list at all.** `HSMobileOpsPostRequest.Lines`
(`List`) is now `HSMobileOpsPostRequest.LinesJson` (`str`) — a JSON array carried as a string,
which always deserializes correctly regardless of element type. `HSMobileOpsPostingService` gained
a private `parseLines(str)` that parses it back into the same `List` of `HSMobileOpsPostLine` the
rest of the method already worked with, using `Newtonsoft.Json.Linq.JArray`/`JObject` directly —
proven present and loaded on this exact server, since it is literally what named itself in the
cast exception. `validate`, `postProductReceipt` and `postTransfer` all call it; the business logic
in each is otherwise unchanged. `HSMobileOpsPostLine` lost its `[DataContractAttribute]` (it is no
longer bound by AIF, just populated by hand) but kept every `parm*` method, so the rest of each
method's body didn't need to change shape. The Android side changed to match:
`PostRequestBody.lines: List<PostLineDto>` is now `linesJson: String`, built once in
`PostingDispatcher.post()` via `Json.encodeToString(ListSerializer(...), lines)`.

**Not yet compiled or deployed — needs the same import-and-build round as everything else here.**
The `Newtonsoft.Json.Linq` CLR interop calls (`JArray::Parse`, `.get_Item(...)`, `.get_Count()`,
`.ToString()`) are the standard, commonly-documented pattern for this in X++, but have not been
built against this application version. If Visual Studio reports a syntax error here, that is
expected until confirmed clean - the fix that matters (routing around inbound list binding
entirely) does not depend on getting every accessor name exactly right on the first try, but the
exact interop syntax does need a real compiler to confirm.

**The `DocumentStatus::Registration` fix above was wrong, and reverted.** Live testing showed a
brand new batch could not go straight to a product receipt - D365FO refused with "Cannot receive
quantity ... because inventory transactions with status Ordered, Arrived or Registered are
insufficient", and D365FO's own standard product-receipt screen needed the same step first
against this tenant's batch-tracked items. The fix was assumed to be switching
`DocumentStatus::PackingSlip` to `DocumentStatus::Registration` throughout. It was not: the user
supplied a real, verified reference - [posting a product receipt with item registration through
code](https://shabibax.wordpress.com/2023/06/22/posting-a-product-receipt-with-item-registration-through-code-dynamics-365-finance-and-operations-x/)
(shabibax.wordpress.com, June 2023) - and it stays on `DocumentStatus::PackingSlip` throughout,
using `PurchParmLine.ReceiveNow` (not `RegisteredNow`, which was guessed and wrong) and
`PurchUpdate::ReceiveNow` (not `PurchUpdate::All`). The real registration step happens in code,
between inserting the parm line and running `PurchFormLetter`: find the specific `InventTrans`
row the parm line's `InventTransId` points to, build a new `InventDim` from *that transaction's
own current dimension* with the batch and/or location added on top, resolve it via
`InventDim::findOrCreate`, and hand it to `InventTransWMS_Register` (`newStandard` /
`writeTmpInventTransWMS` / `updateInvent`) so the conversion happens against the real Ordered
transaction rather than asking the posting engine to find a dimension combination that was never
ordered. This is the new `HSMobileOpsPostingService::registerLine`, called right after
`purchParmLine.insert()` whenever the operator captured a batch or a location. Also caught while
matching the reference: `formLetter.parmParmTableNum(...)` was reading `purchParmTable.ParmId`
where it should read `purchParmTable.Num` - a genuine separate bug, both fields being `str` and
both compiling either way, which is exactly why it went unnoticed until checked against a real
sample. Confirmed live, 7 September 2026: a genuinely new batch registered and posted end to
end - `getPurchaseOrder`'s `OutstandingLineCount` for `BSP-PO-000161679` dropped from 6 to 5 and
the line disappeared from the outstanding list, proving the whole chain actually committed.

**`postProductReceipt` no longer posts a product receipt at all, on explicit instruction ("no
need to post product receipt lets register alone").** The `PurchFormLetter`/`PurchParmTable`/
`PurchParmLine` machinery above existed *only* to feed `PurchFormLetter`'s `PackingSlip` posting -
`registerLine`'s actual effect (`InventTransWMS_Register` converting the real `InventTrans` row
from Ordered to Registered, with the batch and location attached) never depended on any of it.
With the posting step removed, that whole machinery came out too: `postProductReceipt` now does
only the replay check, the purchase order/line lookup, the quantity check, and
`registerLine(purchLine, postLine, requested)` per line - called for every line, not only ones
carrying a batch or a location, since registering the quantity is the point either way.
`registerLine` itself now takes `PurchLine` directly instead of a `PurchParmLine`, reading
`InventTransId` and using the caller's quantity in place of `ReceiveNow`. The response's
`DocumentNumber` is always `''` now - there is no packing slip to name one from, and nothing on
the Android side reads that field, so this is not a breaking change there. What the *later*,
separate formal product-receipt step looks like - through D365FO's own screens, or a future app
feature - is not decided here; this method's job ends at registration. Not yet compiled or
deployed.

**Also fixed in an earlier pass, discovered while tracing this: captured batch and put-away
location were never applied to the posting at all.** `postProductReceipt` read only
`postLine.parmQuantity()` — `parmBatchId()`, `parmLocationId()` and `parmUnitId()` were declared
on the contract, sent by the app, and never once read anywhere in
`HSMobileOpsPostingService.xml`. A receipt that "succeeded" would have posted the right quantity
against no batch and no location, silently dropping exactly the two things the operator was
prompted to scan. The *mechanism* for applying them changed in the pass right above this one —
see `HSMobileOpsPostingService::registerLine` — but the underlying gap this closed is the same
one. **`postTransfer` deliberately was not given the same treatment** — a transfer's batch is normally fixed at the shipping side and carried
through, not chosen fresh at either end the way a purchase receipt assigns a new one, and applying
an `InventDim` override there needs checking against how `InventTransferUpdShip`/`UpdReceive`
actually use it, which has not been done. Tracked here as a known gap, not silently skipped.
**Serial application is also still missing** — a serial-tracked receipt needs one parm line per
unit, each carrying its own serial, not one parm line at the captured quantity; today's fix does
not touch that either.

**The services are deployed and answering.** Probed against the dev AOS on 3 September 2026 with a
client-credentials token. All four read services returned HTTP 200 with the expected contract:

| Call | Result | Time |
| --- | --- | --- |
| `HSMobileOpsReceivingSvc/getPurchaseOrder` | `NOT_FOUND` - "No purchase order ZZZ-NONE in BSP" | 900 ms |
| `HSMobileOpsTransferSvc/getTransferOrder` | `NOT_FOUND` - "No transfer order ZZZ-NONE in BSP" | 884 ms |
| `HSMobileOpsUserSvc/signIn` | `INVALID_CREDENTIALS` | 640 ms |
| `HSMobileOpsInquirySvc/validateLocation` | `NOT_FOUND` | 920 ms |

That establishes more than routing. The company name appearing in the message proves
`CompanyInfo::findDataArea` resolves and the `changecompany` block runs in the right legal
entity; the sign-in answer proves the user table exists and is queried, and that an unknown user
gets the deliberately non-enumerating message rather than "no such user".

Sub-second, against 2456-3402 ms for the batched OData pulls these replace.

### Tested against real data (BSP, 3 September 2026)

**Sign-in.** `001` in `BSP` returns `Success: true` with `DisplayName` demo1, personnel
1232141, `MustChangePassword: true` and one warehouse (`AAC`, default). The same credential in
`BSPO` is refused - the row is company-scoped. A wrong password is refused. PBKDF2 hashing, salt
storage and constant-time verification all work end to end. Sign-in costs ~1.8 s, which is the
120,000 iterations doing their job.

**Receiving.** `BSP-PO-000161418` (Confirmed, Backorder) came back receivable in 920 ms with the
vendor resolved, one outstanding line inline, both units (Pack / Pieces) and the tracking dimensions
(`BatchRequired: true`, `LocationRequired: true`). That single response exercises
`InventDimGroupSetup.isDimensionActive`, `InventTableModule::find` and the vendor lookup - the
three version-sensitive calls flagged below - so all three resolve on this application version.

The confirmation gate behaves exactly as the process flow requires:

| Approval status | Order status | Result |
| --- | --- | --- |
| Draft | Backorder | `NOT_CONFIRMED` |
| InReview | Backorder | `NOT_APPROVED` |
| Confirmed | Invoiced | `NOT_OPEN` |
| Confirmed | Backorder | receivable |

**Transfers.** All four orders, both directions, correct in every case:

| Order | State | Ship | Receive |
| --- | --- | --- | --- |
| BSP-TO-000000001 | Created, AAC→ABB | actionable, 1 line | `NOT_RECEIVABLE` |
| BSP-TO-000000002 | Shipped, WMS→AAC | `NOT_SHIPPABLE` | actionable, 1 line |
| BSP-TO-000000003 | Received | `NOT_SHIPPABLE` | `NOT_RECEIVABLE` |

The warehouse gate works: TO-000000001 at `AAC` is actionable, at `WMS` it is
`WRONG_WAREHOUSE`. This is the evidence for the `blockReason` rewrite - deciding from
outstanding quantities agrees with the header status in all six cases, without depending on how the
status enum is spelled.

Read calls settle at 355-920 ms once warm; the first call to each service pays 4-8 s of cold start.

Still unproven: no call has been made *from the app itself* - every result above came from a
scripted probe against the same endpoints the app now calls - and **nothing has been posted**. The
posting service has never run.

Two behaviours were chosen deliberately and will look like bugs if you meet them without knowing:

- `postTransfer` refuses a partial-quantity transfer with `PARTIAL_UNSUPPORTED` (see above).
- The handheld has no bootstrap path. Until a row exists on the Mobile device users form, every
  credential is refused - correctly, but indistinguishably from a broken sign-in.

Things still worth watching on first *run*, in the order I would check them:

- `AxMenuExtension/MainMenu.HSMobileOps.xml` — the extension element shape is the one artifact
  here I have the least confidence in, being the only file written without a reference sample to
  check against. If it does not import, delete the file and add the `HSMobileOps` menu to
  `MainMenu` in the designer; it is a thirty-second job and produces the identical result.
- `AxForm/HSMobileOpsUser.xml` — the `SimpleListDetails` pattern (version 1.3) is applied, and
  the top-level control tree now matches a working standard form control for control:
  `ActionPane` → `GridContainer` (Group, SidePanel, holding QuickFilter + Grid) → `DetailsHeader`
  (Group, FieldsFieldGroups) → `Tab` (FastTabs). The `DetailsHeader` between the list and the Tab
  is the part the pattern insists on; without it the Tab is rejected at that position.
- `Descriptor/HSMobileOps.xml` — `Layer` is `14` (usr) and `Id` is arbitrary. If the model already
  exists in Visual Studio, keep the existing descriptor and delete this one rather than merging.
- `Rfc2898DeriveBytes` interop in `HSMobileOpsPassword` — the CLR constructor overload taking an
  explicit hash algorithm is version-sensitive.
- `HSMobileOpsPostingService::postTransfer` now follows a published working pattern, so the
  header construction (`initParmDefault`, `RunBaseMultiParm::getSysParmId()`, single-argument
  `newParmBuffer(parmTable)`) is corroborated by two independent sources. The one call not
  corroborated for the receive side is `parmIsSomeThingPosted()`; it is documented on the ship
  class, and both derive from `InventTransferUpdate`, so it is expected on the base.
- `InventDimGroupSetup.isDimensionActive`, `InventTableModule::find`, and
  `PurchTable::isChangeManagementActivated`, whose signatures vary across application versions.
