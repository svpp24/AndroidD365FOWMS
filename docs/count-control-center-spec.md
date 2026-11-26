# Count control center: one journal per store and a by-store view

Status: design and decisions agreed (24 Nov 2026). Not built.

Mock: [mocks/count-control-center.png](mocks/count-control-center.png) ([html](mocks/count-control-center.html))

## Why

We count by location and bin across 600+ stores. Today each blind count creates its own counting journal. The control center lists one row per stock count, which can't be managed at this scale.

## What changes

### 1. One journal per store (blind counts)
- New setting on Count types (table `HSMobileOpsCountTypeSetup`): **One journal per store** (NoYes).
- When it is on, the journal for a blind count (`HSMobileOpsCountControl::transfer`, run in batch by `HSMobileOpsCountTransferBatch`) is created like this:
  - If the store (`InventLocationId`) has an open, unposted store journal made by Inventory hub, the lines are added to that journal.
  - Otherwise a new journal is created and becomes the store's open journal.
  - Adding lines to an existing journal already works for `SourceJournalId`; reuse that code.
  - Every blind count put into the journal gets the same `TransferredJournalId`.
- After a journal is posted, the next blind count for that store starts a new journal.
- Wall to wall and hazard counts don't change.

### 2. Counted twice, across all counts in a store journal
- The duplicate key is **store + location (WMSLocationId) + bin + item + dimensions**, counted more than once (by anyone, including the same person twice), in any of the blind counts that go into the same store journal.
- Those lines are held. The supervisor decides each one: **Keep selected**, **Add together** or **Reject both**.
  - These are the same three decisions the control center already offers within one count (`KeepButton`, `SumButton`, `RejectButton`, the `Line_Decision` and `Line_Included` fields).
- The store journal can't post until every bin counted twice is decided.
- Every decision is logged: who decided, when, which decision, and the quantities. The log appears in the Decision log section.

### 3. Count control center layout (form `HSMobileOpsCountControlCenter`)
Match the mock. Use standard D365 patterns only.
- **Action pane groups:**
  - New: Blind count, Hazard count.
  - Store journal: Open journal, Post now, Hold, Release.
  - Counted twice: Keep selected, Add together, Reject both.
  - Count: Look for duplicates, Cancel count.
  - View: By store, By count. By count is the old one-row-per-count list, kept as it is.
- **Summary tiles** (count tiles that filter the grid):
  - Stores with bins counted twice
  - Journals failed to create
  - Counters not ended after N h
  - Journals on hold
  - Posting tonight
  - Posted last 7 days
- **Filters:** quick filter, Region, Count type, From date.
- **Store grid**, one row per store journal. Columns: Store, Name, Region, Store journal, Blind counts, Lines, Counted twice, Counting now, Last count, Status.
- **FastTabs** for the selected store: Counted twice (open by default when there are any), Blind counts, Counted lines, Counters, Decision log. Each shows a summary line while collapsed.
- **FactBoxes:**
  - Store journal: store, journal, created, blind counts, lines, lines with variance, posting, plus a "won't post until…" message.
  - Selected bin: location/bin, item, on-hand, each count, and the total if added together.
- **Hold and Release:** a held store journal is skipped by auto-post until it is released.

## Decisions (24 Nov 2026)
1. **Counters not ended:** the alert shows after 24 hours (the tile reads "Counters not ended after 24h"). This is a fixed value, not a setting.
2. **Region filter:** uses D365 warehouse groups. Look up the standard table on the dev VM before building, and don't add a new grouping table. Name the filter and grid column "Warehouse group" instead of "Region".
3. **Same bin counted twice by the same person** (any blind counts in the store journal): this also goes to the supervisor. The duplicate key is store + location + bin + item + dimensions, whoever counted it. The person isn't part of the key.
4. **While any bin is undecided:** the whole store journal waits. It isn't posted, and Post now refuses and gives the reason. Auto-post skips that store until every bin is decided.
5. **Supervisor in the app:** uses Cycle count supervisor together with the user's default warehouse (see section 4).

## Rules
- Every new X++ object uses the `HSMobileOps` prefix.
- Mock before building UI. Don't add behaviour that isn't in this spec or the mock.
- The app must stay compatible with every app version. There are no app changes here; this work is D365 only.
- Deploy only to the dev VM, hash-checked. Test in company DAT with two or three blind counts in one store, including one bin counted by two people.
- No customer data in screenshots.

## 4. Count control in the handheld app (store supervisor)
Mock: [mocks/app-count-control.png](mocks/app-count-control.png) ([html](mocks/app-count-control.html)). Build it from the Harbour components (`Harbour.kt`).
- A new Home module, **Count control**, shown only to the supervisor of the signed-in store. It covers that store only.
- **Count control screen:**
  - Tiles: Bins counted twice, Counters still counting, Blind counts today, Lines with variance.
  - A card for the store journal, with its status and the "won't post until…" message.
  - Links to: Counted twice, Counters, Blind counts, Variance lines.
  - Hold journal and Post now. Both are hold-to-confirm. Post now refuses, giving the reason, while bins are still undecided.
- **Counted twice:** chips for To decide and Decided. One card per location and bin, showing each counter, their blind count, the time, the quantity, and the on-hand.
- **Decide a bin:** Keep one counter's count, Add together, or Reject both. Confirm with HoldButton. The decision goes to D365 through a new REST call, which writes the same decision log as the control center.
- **Counters:** each counter shows their blind count, handheld, start time, lines, and Ended or Still counting.
- The app must stay compatible: older apps simply don't show the module. New REST endpoints only; no changes to existing ones.
- **Who sees it** (decided 24 Nov 2026): Hub users whose existing **Cycle count supervisor** flag is on (`HSMobileOpsUser.IsCycleCountSupervisor`, checked with `HSMobileOpsCountDualEngine::isSupervisor`, which means active and not an external counter), for the store that is their **default warehouse** in Hub users › Warehouses. Every check runs on the server: the REST calls refuse a user who isn't a supervisor, or a store that isn't their default warehouse. Hiding the module in the app isn't enough.
- Not used: the `Supervisor` flag (`IsSupervisor`, which is for other purposes) and Count stores › Supervisor (`HSMobileOpsCountStore.SupervisorUserId`, the D365 user who reviews in the back office).
