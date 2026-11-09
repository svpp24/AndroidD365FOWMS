# Plan: Mobile operations workspace

**Build status:** W1 is staged, not built into the model. See `aos/_staging-w1/README.md`. A first import (2026-11-07) failed to compile and was rolled back: it counted form data sources, which the standard tile model does not do. W1 was then redesigned on the standard pattern, copied from the cost administration workspace on the dev VM: one `AxQuery` per tile (store scope and the tile's ranges, in query range expressions that call static methods), a drill-down menu item whose `Query` property is that query, an `AxTile` of type Count on the menu item, and a tile button on the form. This is the mechanism section 5 first described. The form extensions and the filter class are gone. Nothing is compiled or deployed.

Status: plan only, nothing built. Written 2026-11-07 against the model as it stands with stock count phase 2 in progress (`HSMobileOpsCountAttempt`, `HSMobileOpsCountStatus` on `InventJournalTrans`, master status Transferring). Every new X++ object uses the `HSMobileOps` prefix (tiles, menu items, queries, forms); extensions are `<Element>.HSMobileOps`.

## 1. Purpose

Today the module is a flat menu (`AxMenu/HSMobileOps`, label "Inventory hub", with Counting / Inquiries / Setup submenus, hooked in by `MainMenu.HSMobileOps`). A supervisor has to open four forms to learn whether anything needs attention. The workspace is one landing page that answers "what needs me now?" with counts, and drills into the existing forms already filtered.

What the model already has that this builds on:

| Pattern | In the model today |
|---|---|
| Forms | 19. Patterns used: `SimpleListDetails` (control center), plain grids with action panes (post queue, feedback, sign-in log, device work). No `Workspace` pattern anywhere. |
| Menu items | 19 display, 17 action. Display items point at forms, with no `Query` property set. |
| Tiles / KPIs / cues / aggregate measurements / queries | None. No `AxTile`-style objects, no `AxQuery`, no `AxView`, no `AxAggregateDataEntity`. |
| Security | 2 roles (Administrator, Viewer), 5 duties, 20 privileges. Privileges are one per menu item or action (`...View`, `...Maintain`, `...Manage`). |
| Filtering from outside | None. Forms add sort fields in `init()` only. The control center is the one form that adds ranges in `init()` (stores the user supervises). |

Everything below is therefore new ground for this model. Check each step in the dev VM, not just in the XML.

### Scope decision: comprehensive

The user wants a comprehensive workspace, not a minimal landing page. So the plan covers the full set: every tile in section 3 (including the licence and dual count tiles), trend KPIs and charts, a desktop review form for dual count lines, and all existing forms reachable from it. Items I had marked "skip", "optional" or "out of scope" below are now in scope, and phasing in section 7 reflects it. The cost warnings still apply: comprehensive means more tiles to keep cheap, not scans.

## 2. Who uses it and what they act on

The model has two roles today, plus per-user flags on `HSMobileOpsUser` (`IsSupervisor`, `IsCycleCountSupervisor`, `IsExternalCounter`) that apply to the handheld, not to D365FO security.

| Persona | D365FO role | Must act on |
|---|---|---|
| Warehouse supervisor | `HSMobileOpsAdministrator` (the role description says this is the supervisor, not IT) | Failed and stuck postings; stock counts to transfer or stuck Transferring; dual count lines waiting for a supervisor decision; locked-out users; feedback to answer |
| Read-only viewer | `HSMobileOpsViewer` | Look at fleet, sign-ins, postings and counts. Acts on nothing |
| IT / system admin | Administrator role + System administrator | Licence status, devices not seen, app versions, batch jobs (Transfer, nightly cycle journal) |

Assumptions to confirm (see questions):
- A5. Decided by the user: **store scope.** `HSMobileOpsAdministrator` sees all stores. Every other signed-in D365FO user (the Viewer) sees only the stores assigned to them, on every tile, list, KPI and the activity feed. Scoping fields per table: `HSMobileOpsPostQueue.Warehouse`, `HSMobileOpsCountMaster.InventLocationId`, `HSMobileOpsDevice.LastWarehouseId`, `HSMobileOpsSignInLog.WarehouseId`, `HSMobileOpsFeedback.WarehouseId`, `HSMobileOpsDeviceWork.WarehouseId`, `HSMobileOpsCountWarehouse.Warehouse`. `HSMobileOpsUser` has no warehouse column (use `HSMobileOpsUserWarehouse` through `UserRecId`). Tile and list must use the same scope so they agree.
- A6. Decided by the user: the D365FO user maps to stores through the worker mapping that already exists. Chain: D365FO user (`curUserId()`) to person to worker through the standard relation (`DirPersonUser`, then `HcmWorker`), worker to `HSMobileOpsUser.PersonnelNumber` (`PersonnelIdx`), then `HSMobileOpsUserWarehouse` (`UserRecId`, `WarehouseId`). `HSMobileOpsCountStore.SupervisorUserId` is not used for scoping. One shared helper (`HSMobileOpsWorkspaceScope`) returns all stores for `HSMobileOpsAdministrator` and the mapped warehouses otherwise. A user with no worker or no handheld user sees no stores (empty workspace, with a message saying why), never everything. The control center adopts the same helper so admin sees all there too (decided; see section 5). Verify the exact standard relation and that every supervisor has a worker and a handheld user with warehouses.
- A7. Decided by the user: the Viewer does not see the licence tile (Administrator only).
- A1. Decided by the user: one workspace for all roles, with tiles and tabs hidden by security.
- A2. Decided by the user: "supervisor" means the D365FO `HSMobileOpsAdministrator` role, with no new role. Store scoping uses `HSMobileOpsCountStore` (supervisor user to store), as the control center does today. Workspace count tiles for counts should respect the same scoping, or they will disagree with the list they open.
- A3. "Journal waiting for a supervisor" means a line with `HSMobileOpsCountStatus == Mismatch` and `HSMobileOpsAttemptCount >= 3`. That is how `HSMobileOpsCountDualEngine` and `HSMobileOpsCountSupervisorService` treat it (under 3 attempts a Mismatch goes back to counters). `Recount` is a supervisor decision already taken, so it is "in progress", not "waiting".
- A4. Decided by the user: the Viewer also sees postings and counts. Today the Viewer has no post queue or count privilege (`HSMobileOpsPostQueueView` and `HSMobileOpsCountControlCenterMaintain` are only on the Administrator). Catch: `HSMobileOpsPostQueueView` also grants the Retry action, and the count privilege is a Maintain one (Transfer, Cancel), so neither can simply be added to a read-only role. Plan: new read-only privileges (for example `HSMobileOpsPostQueueInquire`, `HSMobileOpsCountControlCenterInquire`) for display only, and the forms must hide or disable action buttons for users without the action privileges. Verify how the control center behaves read-only.

## 3. Tiles

Tile counts run a `COUNT` on every open of the workspace and (in the standard pattern) refresh on a timer, so each must be an index-backed query on a small table. Cost column is the key column.

| # | Tile | Table and filter | Index | Cost |
|---|---|---|---|---|
| 1 | Postings failed | `HSMobileOpsPostQueue`, `Status == 'Failed'` | `StatusIdx` (Status, ReceivedUtc) | Cheap |
| 2 | Postings stuck | `HSMobileOpsPostQueue`, `Status in ('Pending','Processing')` and `ReceivedUtc` older than N minutes | `StatusIdx` | Cheap, but the "older than" needs a fixed date, which a static query range cannot compute. Use a range value expression `(ReceivedUtc < DateTimeUtil::addMinutes(...))` built in code, or accept a plain "Pending or Processing" tile. See risk R3 |
| 3 | Postings corrected by a supervisor | `HSMobileOpsPostQueue`, `ChangedBy` not empty and `Status` Pending or Failed | `StatusIdx` then filter | Cheap (in scope) |
| 4 | Stock counts open | `HSMobileOpsCountMaster`, `Status == Open` | `StoreStatusIdx` | Cheap (one row per count) |
| 5 | Stock counts transferring | `HSMobileOpsCountMaster`, `Status == Transferring` | `StoreStatusIdx` | Cheap. Overlaps with R2: a lost batch job leaves this state forever, so show age too |
| 6 | Dual count lines in Mismatch | `InventJournalTrans`, `HSMobileOpsCountStatus == Mismatch`, `HSMobileOpsAttemptCount >= 3` | **None.** The `InventJournalTrans.HSMobileOps` extension has no indexes (empty `<Indexes />`) | **Expensive.** `InventJournalTrans` holds tens of thousands of lines per journal (AAC: 16,246). An unindexed filter scans every journal line in the company, posted or not |
| 7 | Dual count lines recount | same, `Recount` | same | same |
| 8 | Journals with unsettled dual lines | `InventJournalTable` with `HSMobileOpsDualCount == Yes`, not posted, exists-join to trans | none | Journal level is small, but the exists-join to trans is the expensive part |
| 9 | Users locked out | `HSMobileOpsUser`, `LockedUntilUtc` in the future, or `FailedAttempts` at limit | none | Cheap (dozens of rows) |
| 10 | Devices not seen N days | `HSMobileOpsDevice`, `IsActive == Yes` and `LastSignInUtc` older than N days | none | Cheap (one row per device) |
| 11 | Devices active / users active | `HSMobileOpsDevice` active count, `HSMobileOpsUser` active count | none | Cheap. Informational. Licence limit relates (`HSMobileOpsDevice::checkDeviceLimit`) |
| 12 | Signed in now | `HSMobileOpsSignInLog`, `SignOutUtc` empty and `SignInUtc` recent | `SignInIdx` (SignInUtc) | Cheap if the date range is bounded, but "no sign-out" can also mean the app was killed (`EndReason`). Confirm what ends a session before showing this as a number |
| 13 | Feedback new | `HSMobileOpsFeedback`, `Status == New` | **None** (indexes: FeedbackId, SubmittedUtc, UserName) | Cheap-ish: feedback rows carry a bitmap and logs, but a count does not read them; table is small. Add a `Status` index only if it grows |
| 14 | Unsent device work | `HSMobileOpsDeviceWork`, `Archived == No` (and `Stale == Yes` for the stuck variant) | none | Cheap |
| 15 | Licence status | Read via the existing licence status class (`HSMobileOpsLicenseStatus`) | n/a | A text tile (valid / expires in N days / device limit used), not a count. Read only: this plan does not change licensing files or `HSMobileOpsUserService` |
| 16 | Cycle count warehouses with a last-run problem | `HSMobileOpsCountWarehouse`, `CycleEnabled == Yes` and `LastMessage` not empty or `LastRunUtc` older than 2 days | `WarehouseIdx` | Cheap. Catches a nightly job that stopped |
| 17 | Open cycle journals older than N days | `HSMobileOpsCountWarehouse.LastJournalId` joined to `InventJournalTable` not posted | none | Cheap (one row per warehouse). The setup guide says an open journal blocks the next cycle |
| 18 | Decisions needed on duplicates | `HSMobileOpsCountMaster`, `DuplicateCount > 0`, `Status == Open` | `StoreStatusIdx` | Cheap |

### KPIs and charts (in scope)

Trends need history, which the operational tables only partly hold, so use cheap grouped queries over bounded date windows rather than Entity Store aggregates, and add aggregates only if a window is too slow:

| KPI | Source | Window |
|---|---|---|
| Postings per day and failure rate | `HSMobileOpsPostQueue` by `ReceivedUtc` day and `Status` (`ReceivedIdx`) | 14 days |
| Median time from received to processed | `HSMobileOpsPostQueue` `ProcessedUtc - ReceivedUtc` | 14 days |
| Count first-pass match rate | `HSMobileOpsCountAttempt` (`JournalIdx`, `AttemptNo`) | Last N journals, never the whole table |
| Mismatch to approved turnaround | `HSMobileOpsCountAttempt` and `HSMobileOpsCountLineReview.ReviewedAtUtc` | Last N journals |
| Count accuracy by warehouse | existing `HSMobileOpsCountAccuracy*` service logic | Last N counts |
| Sign-ins per day, average session length | `HSMobileOpsSignInLog` (`SignInIdx`, `DurationMinutes`) | 14 days |
| Active devices by app version | `HSMobileOpsDevice.AppVersionName` | Now |
| Feedback opened vs closed | `HSMobileOpsFeedback` by `SubmittedUtc` and `Status` | 30 days |

Charts are workspace chart controls on a query or a small view (`HSMobileOpsPostingTrendView` and similar), not on the raw table. KPI tiles reuse the same views.

### The expensive tiles (6, 7, 8): options

The workspace must not scan `InventJournalTrans`. Choose one, in this order of preference:

1. **Add an index** in the existing `InventJournalTrans.HSMobileOps` extension: `(JournalId, HSMobileOpsCountStatus)` or `(HSMobileOpsCountStatus, JournalId)`. A count then reads only the matching entries. Needs a measurement on the AAC dataset before committing, because adding an index to a large standard table has its own cost (sync time, writes on every journal line save). This touches the table extension the phase 2 session owns, so it should wait for phase 2 to settle.
2. **Count per open journal, not the table.** Tile 6 = sum over journals that are open and dual (a handful) of an indexed count by `JournalId` (`JournalId` is already indexed on standard `InventJournalTrans` and in `HSMobileOpsCountAttempt.JournalIdx`). Works without a new index, but needs code in a tile data source (a view or a computed column), which is the more complicated route.
3. **Roll-up table** `HSMobileOpsCountJournalSummary` (journal, mismatch count, recount count, settled count, last updated) maintained by the dual-count engine when a line changes status. Cheap to read, and supports wall-to-wall later. Risk: drifts if any code path misses an update, so it needs a rebuild action. Heaviest to build.

**Decided by the user: no new index.** So option 1 is out. Use option 2: for each open dual journal (a handful), count by `JournalId` through the standard index, filter on status, and cache the result for a few minutes in a helper so the workspace refresh does not re-read the lines each time. Option 2 still reads every line of an open dual journal (AAC: 16,246), so measure it on AAC; if it is too slow, fall back to option 3 (roll-up table), which writes from the dual engine and so waits for phase 2 to settle. Store scope applies: only journals of the user's stores. Original recommendation, now superseded: option 1 if measured acceptable, otherwise option 3. Not a tile at all in phase 1 is also fine: link to the journal list filtered to open dual journals, and show no number.

### Counts versus lists

For tiles 1, 4, 5, 9, 10, 13 the same query serves the tile and the list it opens. For tiles 6-8 the number and the drill-down list can differ in cost: the list is paged on screen and cheap, the count is not.

## 4. Workspace layout

Form `HSMobileOpsWorkspace`, standard workspace pattern (`Workspace`, `PatternVersion 1.1`), a panorama with these sections.

| Section | Content | Links to |
|---|---|---|
| Freeze banner | Shown to **all roles** at the top of every tab while a wall-to-wall count is in progress. While it runs, every handheld process menu except inquiries is frozen for the affected warehouse (decided by the user). The banner lists the frozen warehouses, since when, the blocking journal (link to it), and the message "Cancel or close the journal to lift the freeze". There is no manual lift button: the freeze ends only when the wall-to-wall journal is cancelled or closed. Not buildable yet: it needs the wall-to-wall feature to expose a frozen flag per warehouse and the handheld to enforce it (enforcement is app and service work, not workspace work). Reserve the space and the query now | Wall-to-wall (W4) |
| Summary tiles | The counts of section 3, grouped as below | The filtered form |
| Receiving and posting (tab) | Tiles 1-3, 14. List: recent failed/stuck rows of `HSMobileOpsPostQueue` | `HSMobileOpsPostQueue` (Retry, Change quantity, Cancel stay on that form), `HSMobileOpsDeviceWork` |
| Counting (tab) | Tiles 4-8. List: stock counts of `HSMobileOpsCountMaster` not Transferred/Cancelled | `HSMobileOpsCountControlCenter` (Transfer, Cancel, Refresh live there) and the D365FO counting journal list for dual lines |
| Devices and users (tab) | Tiles 9-12. List: devices by last sign-in | `HSMobileOpsDevice`, `HSMobileOpsUser`, `HSMobileOpsSignInLog` |
| Activity (tab) | Merged newest-first feed, per-source views, "Right now" panel (signed-in workers, counters currently counting, device work not sent, postings in flight). See the Activity section above | `HSMobileOpsSignInLog`, `HSMobileOpsPostQueue`, `HSMobileOpsCountControlCenter`, `HSMobileOpsDeviceWork`, `HSMobileOpsFeedback` |
| Feedback (tab or tile only) | Tile 13. List: newest New feedback | `HSMobileOpsFeedback` |
| Related links | Setup forms: `HSMobileOpsCountWarehouse`, `HSMobileOpsCountDualGroup`, `HSMobileOpsCountDualTolerance`, `HSMobileOpsCountTolerance`, `HSMobileOpsCountStore`, `HSMobileOpsMenu`, `HSMobileOpsModuleSwitch`, `HSMobileOpsProcessSetup`, `HSMobileOpsCleanupSetup`, `HSMobileOpsSkuSyncSetup`, `HSMobileOpsAiSample`, `HSMobileOpsLicenseSetup` | each existing menu item |

The nightly job and the batch Transfer are not on the workspace; a "Batch jobs" link (standard System administration menu item) is useful, as is a link to the counting journals.

UX rule from the user (see memory: no truncated text): tile captions and list columns wrap, never ellipsize or clip. Test at the narrow widths a workspace is likely to be used at.

### Activity: everything happening in the app

The user wants the workspace to show all information about what is happening in the app, not only things needing action. That is an **Activity** tab (and a "now" strip on the summary), a single newest-first feed plus per-source views.

What the model already records, and so what the feed can show without new logging:

| Event | Source | Fields that tell the story |
|---|---|---|
| Sign in / sign out, offline sign-in, how a session ended | `HSMobileOpsSignInLog` | worker, warehouse, device, app version, `SignInUtc`, `SignOutUtc`, `EndReason`, `SignedInOffline` |
| Posting received, processing, failed, done, cancelled, corrected | `HSMobileOpsPostQueue` (+ `HSMobileOpsPostLog` for what was posted) | document type, source order, user, device, warehouse, status, error, GRN, `ChangedBy`, `ChangeNote` |
| Stock count started, counters joined and submitted, transferred | `HSMobileOpsCountMaster`, `HSMobileOpsCountStage` | store, started by, counters, status, transfer message and time |
| Individual counts and recounts on dual journals | `HSMobileOpsCountAttempt` | journal, line, attempt number, counter, device, time |
| Supervisor decisions on lines | `HSMobileOpsCountLineReview` | decision, reason code, comment, reviewed by and time |
| Count lines claimed by a counter | `HSMobileOpsLineClaim` | who is counting what right now |
| Work on a device not yet sent | `HSMobileOpsDeviceWork` | kind, reference, lines, captured, last sent, stale |
| Devices first seen, last seen, app version in use | `HSMobileOpsDevice` | first seen, last sign-in, user, warehouse |
| Feedback sent, answered | `HSMobileOpsFeedback` | type, screen, worker, status, reply |
| Nightly cycle journal runs | `HSMobileOpsCountWarehouse` | last run, journal, lines, result |

**Gaps** (nothing records these, so the feed cannot show them without new code):
- Administrator actions: unlock user, toggle user or device active, set default warehouse, set password, retry/cancel/change quantity (the last three leave `ChangedBy` on the posting only), module switches, tolerance and setup changes. D365FO's database log can cover setup tables, but it is not the same as an event feed.
- Read-only handheld activity: location, on-hand and serial inquiries, order lookups.
- Order creation (PO create, transfer create, PO confirm, purchase return) beyond what ends up in the post queue.
- Anything that was rejected before it reached the queue (validation errors, licence refusals, device limit).

Options for the gaps, in order of effort:
1. Ship the feed from the existing tables only (union of the sources above through a view or a small helper class). No new logging, no risk to the handheld path.
2. Add a single append-only table `HSMobileOpsActivityLog` (time, user, device, warehouse, area, event, reference, message), written by the existing action classes and services. Gives true "all info", but every service gets a write on its hot path, so it needs a bounded size (the cleanup setup, `HSMobileOpsCleanupSetup`, already exists to purge) and must never fail the request if the write fails. This touches many classes, including ones the stock count session is editing, so it is its own phase.
3. D365FO database logging on setup and user tables for admin changes. Configuration, no code.

Decided by the user: the existing records are enough. Option 1 only; no new activity table and no new logging. Activity retention follows the retention of the source tables (`HSMobileOpsCleanupSetup`).

Feed design: per-source lists with the same filter bar (warehouse, user, device, date range), plus a merged feed. The merged feed must not union unbounded tables: take the newest N rows per source (each is indexed by time: `ReceivedIdx`, `SignInIdx`, `SubmittedIdx`) and merge in memory or in a bounded view. Live in-progress state (counters currently counting, open sessions, device work not sent) goes in a "Right now" panel.

## 5. Which existing forms need to accept a filter

Preferred mechanism: a **query on the display menu item** (`Query` property pointing to an `AxQuery`, e.g. `HSMobileOpsPostQueueFailed`). The tile and the menu item share that query, the form opens filtered with no X++ in the form. This only works if the form's root datasource is the queried table. Where it works:

| Form | Table | Needs code? |
|---|---|---|
| `HSMobileOpsPostQueue` | `HSMobileOpsPostQueue` | No. Ranges merge with the form's `init()` sort. Verify that user filters still work on top |
| `HSMobileOpsFeedback` | `HSMobileOpsFeedback` | No |
| `HSMobileOpsSignInLog` | `HSMobileOpsSignInLog` | No |
| `HSMobileOpsDevice` | `HSMobileOpsDevice` | No |
| `HSMobileOpsUser` | `HSMobileOpsUser` | No |
| `HSMobileOpsDeviceWork` | `HSMobileOpsDeviceWork` | No |
| `HSMobileOpsCountControlCenter` | `HSMobileOpsCountMaster` | Yes (decided): replace its store-range code in the datasource `init()` with `HSMobileOpsWorkspaceScope`, so admin sees all stores and others see their mapped warehouses. The form is owned by the stock count session, so do it after phase 2 settles. Previously: possibly. Its datasource `init()` already adds a range per supervised store, so a store range from the tile could be combined with it or replaced by it. A status filter from the tile should be fine. Test with a supervisor who is in `HSMobileOpsCountStore` and one who is not |

Queries cannot express "older than 30 minutes" (static ranges) or "journals with Mismatch >= 3 attempts" across two tables cheaply. Where a tile needs those, use form-level filtering instead: pass a named value via `Args` (`args.parm()` or an enum) and have the form's `init()` read it (new code in each such form: small, but touches forms other sessions may have open). Only the post queue age and the locked-out users tile need this.

Dual count lines (tiles 6-8) open **D365FO's own journal lines form** (`InventJournalTransCount`/`InventJournalTrans`), not an HSMobileOps form: "Dual count journals do not go through the Stock count control center". No existing HSMobileOps form lists these lines for a supervisor on the desktop; today they review in the app. That is a gap: either link to the standard journal and rely on the `HSMobileOpsCountStatus` column being visible (field group or form extension on `InventJournalTable`/`InventJournalLines`, new `.HSMobileOps` form extension), or build a small desktop review form later (out of scope here, listed as an open question).

## 6. Objects to create

All prefixed `HSMobileOps`. Names are proposals.

| Object | Name | Notes |
|---|---|---|
| Form (Workspace pattern) | `HSMobileOpsWorkspace` | Panorama, tabs of section 4. Caption "Mobile operations" |
| Display menu item | `HSMobileOpsWorkspace` | Opens the form. Needed for the main menu and for the user's workspace list |
| Display menu items for drill-down (one per tile, with query) | `HSMobileOpsPostQueueFailed`, `HSMobileOpsPostQueueStuck`, `HSMobileOpsCountMasterOpen`, `HSMobileOpsCountMasterTransferring`, `HSMobileOpsUserLocked`, `HSMobileOpsDeviceNotSeen`, `HSMobileOpsFeedbackNew`, `HSMobileOpsDeviceWorkUnsent` | Same form as the existing item, plus a query. Must not duplicate the existing items' labels |
| Queries | `HSMobileOpsPostQueueFailed` etc. (same names as the menu items above) | `AxQuery`. First queries in the model. They also give tile counts |
| Tiles | Tile controls on the workspace form of type count, each bound to a drill-down menu item | There is no separate tile object in the form-pattern approach; the tile is a form control. If the platform version needs `AxTile`-style objects, name them `HSMobileOpsTile<Subject>` |
| Activity feed | `HSMobileOpsActivityFeedView` (bounded, newest N per source) and, if option 2, `HSMobileOpsActivityLog` table with its own cleanup hook in `HSMobileOpsCleanupSetup` | Option 1 needs no table. Option 2 touches many existing classes; its own phase |
| KPIs and charts | Views `HSMobileOpsPostingTrendView`, `HSMobileOpsCountAccuracyView`, `HSMobileOpsSignInTrendView`, `HSMobileOpsFeedbackTrendView` plus chart controls | See the KPI table in section 3. Start with views over bounded windows. Aggregate measurements and `AxAggregateDataEntity` need Entity Store refresh and an AOS deploy; use them only if a view is too slow. The existing `HSMobileOpsCountAccuracy*` classes are an app service, not a KPI source, but their logic can inform the view |
| Privileges | `HSMobileOpsWorkspaceView` (entry point: the workspace display item), plus view privileges for any drill-down item not already covered | Existing privileges that already cover the target forms: `HSMobileOpsPostQueueView`, `DeviceView`, `SignInLogView`, `UserView`, `DeviceWorkView`, `FeedbackMaintain`, `CountControlCenterMaintain` |
| Duty | `HSMobileOpsViewWorkspace` (or add the privilege to `HSMobileOpsInquireDevices`) | Duties here are small and named by action (Inquire/Maintain). A separate duty keeps the viewer role switchable |
| Role changes | `HSMobileOpsAdministrator`: add the workspace privilege or duty, and the drill-down privileges. `HSMobileOpsViewer`: add the workspace duty, and (per A4) the new read-only post queue and count inquire privileges, not the existing View/Maintain ones | Role files are plain XML; changing them is not an extension, it is an edit of the model's own objects, so the user should be aware that existing role assignments change on deploy |
| Menu | Add the workspace item at the top of the `HSMobileOps` menu (a new top-level element before Counting). Leave Counting/Inquiries/Setup as they are | Decided by the user: Inventory hub only, no entry in the standard Workspaces menu. That avoids touching a standard menu (risk R6) |
| Class | `HSMobileOpsWorkspaceScope` and `HSMobileOpsDualCountTally` | Store scoping (A5/A6) and the cached per-journal tally (option 2). No table or index change |
| Form (new) | `HSMobileOpsCountLineReview` desktop form (or a `SimpleList` form named `HSMobileOpsCountDualLines`) | Lists dual count lines by status for a supervisor, with approve / send back actions that call the same service logic the app uses (`HSMobileOpsCountSupervisorService`). Needs a read-only variant for the Viewer |
| Class (conditional) | `HSMobileOpsWorkspaceHelper` for any computed tile (stuck minutes, locked users) | Only if static queries cannot express a tile |
| Table (conditional) | `HSMobileOpsCountJournalSummary` (option 3) | Only if option 2 measures too slow |

Security note: a tile that counts rows of a table the user cannot read shows 0 or errors. Every tile in section 3 needs its drill-down form to be covered by a privilege on the role that sees the tile. Build a role-by-tile matrix as part of phase 1, then test each role.

## 7. Phasing

| Phase | Content | Depends on |
|---|---|---|
| W0 | Settle the remaining questions (section 10). Thresholds are decided: posting Pending/Processing over 15 minutes, count Transferring over 30 minutes, device not seen 7 days, trend window 14 days. Measure the option 2 tally on AAC (read-only SQL against AxDB). | Nothing, and does not touch the other session's deploy window |
| W1 | Workspace shell, all four tabs and the related-links section, tiles 1, 3, 4, 5, 9, 10, 11, 13, 14, 16, 17, 18 and 15 (read-only). Queries, drill-down menu items, new read-only inquire privileges, role changes, menu change. All use existing tables, no X++ in the existing forms | W0 answers on roles |
| W1a | Activity tab, option 1: per-source lists with a filter bar, "Right now" panel, merged bounded feed from existing tables | W1 |
| W1b | KPI views and charts (posting trend, sign-in trend, feedback trend, active devices by app version), bounded windows, timed on AAC-size data | W1 |
| W2 | Counting tab with dual count lines (tiles 6-8) using the chosen option, plus the desktop dual count review form (view, approve, send back; read-only for the Viewer) and count accuracy KPIs | Phase 2 stock count settled, index decision |
| W3 | Stuck tiles that need computed ranges (post queue age, Transferring age, signed in now) with `Args` handling in the target forms | W1 |
| W5 | Dropped: the activity log is not needed (existing records are enough) | |
| W4 | Room for planned features: per-store virtual bins (counting section, a "bins not set up" tile), journals from the retail assortment (a "journals not created" tile, a link to `HSMobileOpsCountWarehouse`), wall-to-wall count with warehouse freeze (a "warehouses frozen" tile with prominence, because a freeze blocks other users) | Those features |

Design for W4 now: keep tab content driven by a list of tile definitions (queries and menu items), not by hand-placed controls, so adding a tile later is one query plus one menu item plus one privilege. Do not hard-code "dual" into tile names; name by state (`...Settle`, `...Waiting`) where the later features may reuse them. Wall-to-wall and freeze will need one distinct visible state on the workspace; reserve space in the Counting tab header.

## 8. Testing

What can be tested, and how (dev VM only after the phase 2 session finishes deploying; deploys interrupt running postings, as noted in memory, and the VM is shared):

| What | How |
|---|---|
| Compile and sync | Model build on the VM. Do not run while another session is deploying |
| Tile counts match the list | Open each tile and compare the number to the row count of the filtered list. Do this per tile, with seeded data: a Failed row, a Pending row, a Transferring master, a locked user, a device not seen. Seed through the existing screens or services, not by SQL |
| Cost | Run the tile queries against the AAC dataset and time them (the 16,246-line journal). Tiles 6-8 must be measured with and without the index |
| Roles | Sign in as a user with only `HSMobileOpsViewer`, one with `HSMobileOpsAdministrator`, one with neither. Check no tile errors, no tile shows data the role cannot open, and no menu item is visible that cannot be opened |
| Store scoping | A supervisor listed in `HSMobileOpsCountStore` and one not; tile and list agree |
| Refresh | Fail a posting, check the tile after the workspace refresh interval; confirm the refresh does not slow the page |
| Responsiveness | Layout at narrow and wide browser widths; text wraps, nothing is cut off |
| Not testable here | The planned features (bins, assortment, wall-to-wall) |

This plan itself did not run anything: no build, no deploy, nothing on the VM or emulator.

## 9. Risks

- R1. **Expensive count on `InventJournalTrans`** (tiles 6-8). Mitigation: section 3 options. The worst case is a workspace that makes every open slow for every user.
- R2. **A lost batch job leaves a count in Transferring forever** (documented). The tile shows it, but "Transferring" for 2 minutes is normal and for 2 hours is not; the plain count does not tell the two apart. Needs an age (W3).
- R3. **Static queries cannot express "older than"**. Tiles that need it use code or form args, which means editing existing forms other sessions touch.
- R4. **Deploy side effects**. Deploying the model interrupts running postings (memory: parallel posting notes). Do the workspace deploy outside a posting window and tell the posting owner.
- R5. **Role edits change existing assignments**. Adding privileges to `HSMobileOpsAdministrator` is safe; adding the workspace to the Viewer role makes it the viewer's landing page. Confirm that is wanted.
- R6. **Menu change**. Decided: Inventory hub only, so no standard menu is touched. Keeping the existing `HSMobileOps` menu items means supervisors can still reach everything, which is the safe fallback.
- R7. **Workspace data refresh load**. Many users with many tiles each refreshing on a timer is a polling load on AxDB. Keep tiles to the cheap list in W1, add the dual tiles only after the measurement.
- R8. **Shared edits**. Phase 2 owns `InventJournalTrans.HSMobileOps`, the control center and the count services. Nothing in W1 touches them.
- R9. **Platform pattern details unverified**. The exact property names (count tile, query on menu item) are from general D365FO practice, not from this model, which has no workspace. Verify on the dev VM with one tile before building the rest.

## 10. Decisions and open questions

Decided by the user:
- Supervisor is `HSMobileOpsAdministrator`; no new role.
- One workspace for all roles, tiles and tabs hidden by security.
- Viewer also sees postings and counts, read-only.
- Administrator sees all stores. Others see only their stores, found through D365FO user, worker, handheld user, user warehouses. The control center follows the same rule.
- No index on `InventJournalTrans`; use the cached per-journal tally (roll-up as fallback).
- Supervisors can approve and send back from the desktop.
- Stuck: posting 15 minutes, Transferring 30 minutes; devices not seen 7 days; trend window 14 days.
- Locked-out users: count only, no unlock from the tile.
- Workspace lives in the Inventory hub only. Licence tile is Administrator only.
- KPIs, trend charts and the Activity tab (existing records only) are in scope.
- Wall-to-wall freeze: banner for all roles; all process menus frozen except inquiries; lifted only by cancelling or closing the journal.

Also decided:
- During a freeze only these handheld modules stay open: `LOCATION_INQUIRY` and the PO and transfer lookups (`PO_OF_TODAY`, `PO_LINES`, `TRANSFER_ORDERS`, `TRANSFERS_BY_STORE`). Everything else on the handheld is frozen for the affected warehouse. The freeze applies to the handheld only, not to D365FO's own forms.
- The control center change (admin sees all, via `HSMobileOpsWorkspaceScope`) goes in as its own small change after phase 2 settles.
- A user with no worker or handheld user mapping sees an empty workspace with a message.

- `PICKED_SERIALS` is frozen during a freeze (decided by the user).

Still open: nothing for the user. Remaining unknowns are verifications on the dev VM (section 9, R9, and the standard user-to-worker relation in A6).
