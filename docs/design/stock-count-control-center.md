# Stock count control center

Decisions (12 Oct 2026): control center is a **D365FO form**; counters count **both** free (whole store,
location by location) and against existing counting journals; an item counted twice is **decided by the
supervisor**; Transfer creates **one counting journal per store** (a count against an existing journal goes
into that journal - it already is the store's journal, a second one would count the same stock twice).

## Data (D365FO, model HSD365MobileOps)

| Table | What |
|---|---|
| `HSMobileOpsCountMaster` | The master journal: one store's stock count. `MasterId` = `STORE-YYYYMMDD-n`, store, optional `SourceJournalId`, Status Open / Transferred / Cancelled, stored counters / lines / undecided duplicates, `TransferredJournalId`. |
| `HSMobileOpsCountStage` | One staging journal per counter in a master: user, device, Counting / Submitted, line count. |
| `HSMobileOpsCountStageLine` | A counted line: item, product dimensions, batch, serial, location, qty (inventory unit), counter, `ClientLineId` (resend-safe), `SourceLineNum` (journal counts), `DuplicateKey`, `IsDuplicate`, `Decision`, `Included`. |
| `HSMobileOpsCountStore` | Which stores a supervisor (D365FO user) manages; `StageJournalCounts` sends journal counts at the store through the control center. |

## Rules (`HSMobileOpsCountControl`)

- **Duplicate** = two or more lines with the same item + config/size/color/style + batch + serial + location
  in one master, by the same counter or different counters.
- **Decisions** on a duplicate group: *Keep this count* (only the selected line is transferred), *Add
  together* (all lines summed), *Reject - recount* (none; a recount arrives as a new undecided duplicate).
  A new line joining a decided group makes the group undecided again.
- **Transfer** is refused while any duplicate is undecided; asks before transferring with counters still
  counting. Free count: new counting journal "Stock count <MasterId>", one line per item/dims/location on the
  matching stock record (D365FO's on-hand, its inventory status) or a new dimension with on-hand 0 (inventory status:
  the store's default, the WHS parameters' default, else the status the item's own stock carries). Journal
  count: Counted written onto the source journal's lines; stock found that is not on it is added as lines.

## Handheld (`HSMobileOpsCountStageSvc`)

- `openStage(warehouse, sourceJournalId?)` - joins (or opens) the store's master and the counter's stage; refused
  (`NO_SUPERVISOR`) at a store without a supervisor in Stock count stores.
- `submitLines(stageId, lines, endCount)` - stages lines, keyed by `ClientLineId`; `endCount` submits.
- `getStageMode(warehouse)` - whether journal counts at the store are staged.

App: **Store count** (Inventory count > shelves icon) - scan location, then items (variant / batch / serial as
needed) and quantity; lines kept on the device until sent; Send / End count. Journal counts at a staged store:
End count sends the counted lines to the control center (`CountStagingRepository.submitJournal`) and says so.
A stock count transferred or cancelled while a counter still has lines on the device: Send is refused and
*Move N line(s) to a new stock count* takes them to the store's next stock count; with nothing left unsent the
count simply ends. Journals created for free store counts are left out of the handheld's journal list
(the supervisor posts them in D365FO).

## D365FO UI

Inventory hub > Counting > **Stock count control center** (SimpleListDetails): stock counts of the user's
stores, newest first; header with status and figures; tabs *Counted lines* (duplicates flagged, Duplicates
only filter, Keep / Add together / Reject) and *Counters*; action pane *Transfer to counting journal*, *Check
for duplicates*, *Cancel count*. Setup > **Stock count stores**. Privilege
`HSMobileOpsCountControlCenterMaintain` (in HSMobileOpsAdministrator).

## Three count types (10 Nov 2026)

The user's decision: the handheld offers only **Hazard**, **Wall to wall** and **Blind** counts, and none starts
with a journal.

- `HSMobileOpsCountMaster.MasterType` (`HSMobileOpsCountMasterType`). Existing store counts are Wall to wall.
  `openMaster` only joins wall to wall counts; a hazard or blind count is joined by `openStage` with `MasterId`
  (no stock count supervisor needed).
- **Hazard**: `HSMobileOpsCountRequestSvc.createHazardCount` (outside system, role `HSMobileOpsCountIntegration`):
  Reference, WarehouseId, DueUtc, ItemsJson. The same Reference returns the existing count; unknown items reject the
  request. `cancelHazardCount`, `getHazardCountStatus` (status plus results once the journal exists).
- **Blind**: control center > *New blind count*, then items or locations on *Requested items*
  (`HSMobileOpsCountItem`). Handhelds list it once it has rows.
- `submitLines` takes only lines a hazard or blind count asks for (`isRequested`), and records **on-hand** and
  **variance** per stock (`StockKey`: item, dimensions, batch, serial, location) on every line as it arrives
  (`updateVariance`). Duplicate decisions recompute the variance (`refreshVariances`).
- *Transfer* is now **Create journal**; **Count type setup** has Auto-post per type (`autoPost` after the batch
  creates the journal; the outcome goes to `JournalPosted` / `PostMessage`).
- Handheld: `listCounts`, `getCountItems`, `getCountResults`. App: Count menu = the three types
  (`CountListScreen`), counting reuses the store count screen with `masterId`, results in `CountResultsScreen`.
