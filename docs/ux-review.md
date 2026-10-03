# Usability review – confusing parts and extra taps

Walked on the device (2 Oct 2026): Home, PO receiving, Transfer, Inventory count (list, scan,
View lines, line choice), Supervisor variance, Change warehouse, Settings.
Priority: **H** = hurts every shift, **M** = noticeable, **L** = polish.

## Fewer taps

| # | Where | Today | Proposal | Saves |
|---|-------|-------|----------|-------|
| 1 | Receiving – step 1 | Must scan or type the full PO number | Show **today's POs for this warehouse** under the scan field (PO's of today data); one tap starts receiving | typing a 15-char number |
| 2 | Receiving / Transfer – manual entry | Tap field → separate screen → type → confirm | Type **in place** (keyboard opens on the hero field); keep the separate screen only for long lists | 2 taps per manual entry |
| 3 | Manual PO/TO entry | Full number required | Accept the **last digits** (e.g. `552000` → `BSP-JN-000552000`) and suggest matches as you type | most typing |
| 4 | Counting – journal list | Inventory count → journal card → *Continue counting* | With one open journal, **open it directly**; card tap = continue | 1 tap |
| 5 | Counting – each line | Scan → quantity dialog → OK | **Scan-to-count**: each scan of the same item adds 1 (or the pack size) with an undo snackbar; dialog only for typed quantities | 1–2 taps per line |
| 6 | Counting – line choice | Item on several lines → dialog every time | Remember the last chosen location/variant for the item in this session and pre-select it | 1 tap per repeat |
| 7 | Receiving – quantity | Type quantity every line | **"Receive all outstanding (n)"** already exists – make it the primary button when remaining ≤ the usual pack | 1 tap |
| 8 | Home | Modules in a long grid; frequent ones not first | Order tiles by the worker's **most-used** modules; hide modules they have no permission for (today greyed) | scroll |
| 9 | Change warehouse | Settings → Session → Change warehouse | Already one tap from the title line; also offer it on **sign-in** when several warehouses are assigned and none is default | – |

## Confusing parts

| # | Where | What confuses | Proposal | Pri |
|---|-------|---------------|----------|-----|
| 10 | Receiving header | "Step 1 of 5" – but steps change per item (batch/serial/location), so the count jumps | Show step **names** as a small progress row (PO · Item · Qty · Batch · Confirm) instead of a number | H |
| 11 | Camera button | Icon only, no text | Label it **"Scan with camera"** | M |
| 12 | Manual entry screen | Empty box, title "Scan purchase order" while typing | Title "Enter purchase order", placeholder "e.g. 552000 or BSP-PO-…" | M |
| 13 | Counting list | Rows show item number twice (ID and name are the same for many items: `Dm001 / DM001`) | Hide the name when it only differs by case | L |
| 14 | Counting scan box | Same field filters the list *and* takes scans; typed text silently filters, scans act | Hint under the field: "Scan to count · type to search" | M |
| 15 | Claim message | Dialog "This server doesn't support claiming journals yet – counting without a claim" on every *Get data* | Show once per device, as an info line, not a modal | H |
| 16 | Offline banner | "D365FO unreachable – work is saved on this device" (good) but screens that need the server (PO's of today, Get data) just spin, then "request timed out" | Fail fast when the banner is up: "Needs a connection – try again when online" | H |
| 17 | Errors | Some messages are technical ("request timed out", "NOT_AVAILABLE", "D365FO couldn't list today's orders (INVALID_DATE)") | Map every code to a plain sentence + what to do next | M |
| 18 | Variance screen | Status chips scroll sideways; header block pushes the list down | Collapse header into one line; status as a single filter dropdown | M |
| 19 | Decision dialog | Reason chips scroll sideways, codes not explained | Show description first, code second; vertical list when > 4 | L |
| 20 | Settings | Only Session / Admin Center / Logs after sign-in – "Session" holds the warehouse | Rename **Session → "My warehouse & sign-in"** | L |
| 21 | Watermark | "This device at risk" watermark covers every screen in debug builds | Debug-only today (rooted test device) – confirm it never shows on release builds | M |

## Suggested first batch

1, 2, 5, 10, 15, 16 – they cover the most frequent actions (start receiving, enter quantities,
count lines) and the two messages that interrupt every shift.

## Status (3 Oct 2026)

All 21 items are done.

- 1-20: built and checked on the device (batches 1 and 2, then 7, 8, 18, 19 and 9).
- 9: after sign-in, a worker with several warehouses and no default picks one ("Choose your warehouse") before Home.
- 21: the "This device at risk" watermark is not from this app - it is a "Security Mode" window drawn by the
  handheld's system UI (com.android.systemui) over every app, the firmware flagging developer mode / USB
  debugging on this test device. Release builds of the app cannot show it; production devices without
  debugging enabled will not either.

