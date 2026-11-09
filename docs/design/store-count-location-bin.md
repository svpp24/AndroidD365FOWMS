# Store count: location, then bin, then item

Decisions (user, 8 Nov 2026):

- Counting is not started from a D365FO journal. A supervisor plans the count and asks each
  employee to count a location. The counts go to the **Stock count control center**, which checks
  them for duplicates and differences, and only then moves them into a counting journal.
- A store has several locations: Stock, Damaged, Claim, Return and so on. A **default location is
  set on the counting menu**, so a count always starts there. To count another location the
  employee uses **Change location**, then counts it bin by bin.
- **Bins are per location** (Stock has its own bins, Damaged its own) and **for counting only**:
  a bin is a record of where in a location something was counted. It never becomes a D365FO
  location or stock dimension, and the journal is still at location level.

## Flow on the handheld (Store count)

1. **Store count** opens in the menu's default location, e.g. Stock - nothing to scan first.
   The header says "Location STOCK" with **Change location**.
2. **Scan a bin.** Only the bins set up for this location are accepted. The bins already counted
   here are listed with how many items each has.
3. **Scan items** in the bin and enter quantities, as now. Lines are kept on the device and sent
   with Send / End count.
4. **Change bin** to move to the next bin. **Cancel** goes back to the bin being counted.
5. **Change location**: scan or pick another location (Damaged, Claim, Return...), then its bins.
   **Cancel** goes back to where counting was.
6. **End count** sends the lines to the control center, as now.

```
┌──────────────────────────────────────────┐
│ ←  Store count                           │
│ ┌──────────────────────────────────────┐ │
│ │ Location STOCK     [Change location] │ │
│ │ Bin BIN-A1         [Change bin]      │ │
│ │ Bins done: BIN-A1 (8), BIN-A2 (4)    │ │
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │
│ │ Scan item                            │ │
│ │ [ Item or barcode          ]  ⏎  📷  │ │
│ └──────────────────────────────────────┘ │
│  BSR-11397-0 · 5 PCS   BIN-A1    🗑      │
│  ...                                     │
│ [ Send 4 ]              [ End count ]    │
└──────────────────────────────────────────┘
```

## Control center

- Each counted line carries its **bin**.
- **Duplicates**: the same item, dimensions, batch, serial, location **and bin**, counted twice
  (by one counter or two). The same item in two different bins of a location is not a duplicate:
  the bins add up.
- **Transfer** sums a location's bins into one counting journal line per item, dimensions and
  location. The bin goes no further.
- The Counted lines tab shows the bin, so the supervisor sees where each count was made.

## D365FO changes (model HSMobileOps)

| Object | Change |
|---|---|
| `HSMobileOpsCountBin` | Add `WMSLocationId`: the location the bin is in. Bin codes stay unique per store (index unchanged, so existing bins sync without conflict). Existing rows keep a blank location (any location) until set. |
| Count bins form | Location column; the lookup lists the store's locations. |
| `HSMobileOpsMenuLine` | Add `CountLocationId` - the default location for a counting line. |
| Handheld menus form | Default location column on counting lines. |
| `HSMobileOpsMenuSvc` / count bin service | Return the default location with the menu, and each bin's location. |
| `HSMobileOpsCountStageLine` | Add `BinId`. |
| `HSMobileOpsCountStageSvc.submitLines` | Takes the bin per line. |
| `HSMobileOpsCountControl` | Bin in the duplicate key; transfer sums the bins. |
| Control center form | Bin column on Counted lines. |

## App changes

- Store count: starts in the menu's default location; location card with Change location and
  Change bin, both with Cancel; bins validated per location; the bin sent with each line.
- The location default comes with the handheld menu (already downloaded at sign-in).
- Journal counting (cycle, spot, wall-to-wall) keeps location, then bin, then item, built on 8 Nov,
  using the same per-location bins.

## Open

- Where no default location is set on the menu line: ask for a location first (today's behaviour).
- Existing bins with no location: accepted in any location until a location is set on them.

## Status (8 Nov)

- Built: D365FO objects above (not deployed - the dev VM is down; needs a build + DB sync), app
  1.5.0 debug (store count location > bin > item, Change location / Change bin with Cancel,
  step trail, bins checked per location in store and journal counting, bin sent per line,
  menu's start location read from `CountLocationId`).
- Store count works offline: started, scanned and counted on the device; the stock count is opened
  in D365FO with the first Send that gets through.
- Tested on the emulator offline: AAC > BIN-A1 > item; Change bin / Change location > Cancel;
  BIN-B1 set to DAMAGED refused in AAC and accepted in DAMAGED.
- Deployed to the dev VM 8 Nov (compile 0 errors, 3 tables synced). Tested online: Store count
  starts in the menu's "Count starts in" (AAC); BIN-B1 set to AAC-DE in Count bins is refused in AAC
  and accepted after Change location to AAC-DE; a bin added from the app while counting AAC is
  saved with location AAC; staged lines carry BinId and the same item in BIN-A1 and BIN-A2 is not
  a duplicate (key ends |BIN|<bin>).
- Transfer tested 8 Nov on fresh stock count AAC-20261108-1 (old AAC-20261013-10 cancelled first, with
  the user's OK): BSR-11397-0 batch E0927 counted 3 in AAC > BIN-A1, 2 in AAC > BIN-A2, 1 in
  AAC-DE > BIN-B1 -> counting journal BSP-JN-000552046 (not posted) has 2 lines: AAC counted 5
  (on-hand 5) and AAC-DE counted 1 (on-hand 0). Bins are added together; the bin goes no further.
- Superseded - to test after deploy: the start location from the menu, bins' locations from D365FO, BinId on
  staged lines, duplicates per bin and transfer summing bins.
