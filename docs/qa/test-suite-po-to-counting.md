# Inventory hub — QA test suites: PO, Transfer Order, Inventory Counting

Scope: the Inventory hub handheld app (Android, release 1.0.23) against D365FO through the
HSMobileOps services, company `bsp`, warehouses **AAS** (BSP - Air Arabia Staff Head Quarters)
and **AAC** (BSP - American Academy of Cosmetic Surgery Hospital, transit `AAC-Transi`).

Test data used throughout:

| Data | Value |
|---|---|
| Worker | `001` (PIN set by the user; never typed by the tester) |
| Vendor | `19721` BinSina - Central Purchase (AED) |
| Untracked item | `Dm001` variant C1, unit Pieces, base price 100 AED |
| Batch item | `HST002` C1 S Red Slim (tracking group HSBatch) |
| Serial item | `HST003` (HSSerial) |
| Batch+serial item | `HST001` (HSBatchSer) |
| Pack-unit batch item | `BSR-47274-0` (Batch, unit Pack) |
| Bins | `AAS` (bulk, warehouse AAS), `AAC` (bulk, warehouse AAC) |

Every case below is written out individually. Where the app or the D365FO setup does not
support a scenario, the case is kept with the behaviour the business expects, and the results
section records it as a gap rather than dropping it.

---

## Suite 1 — Purchase Order creation and receiving

### 1.1 Happy path

| Test ID | Scenario Name | Preconditions | Action Steps | Test Data | Expected Outcome |
|---|---|---|---|---|---|
| PO-HP-01 | Single-item PO, standard vendor and price | Worker signed in at AAS; vendor 19721 active; Dm001 has a base purchase price | 1. Home → Inbound → PO creation. 2. Search vendor `19721`, select it. 3. Confirm header shows "to AAS" and today's delivery date. 4. Scan `Dm001`, choose variant C1. 5. Enter 5 on the keypad, Add line. 6. Check price preview. 7. Create and confirm purchase order. 8. Open Activity until the order leaves "Waiting to send". | Vendor 19721; Dm001 C1 × 5 | PO created in D365FO, Approved and Confirmed (DocumentState 40), warehouse AAS, line 5 × 100.00 AED = 500.00; app card shows CONFIRMED with confirmation number |
| PO-HP-02 | Multi-line PO with lines of different tracking types | As PO-HP-01 | 1. Create PO for 19721. 2. Add Dm001 C1 × 5. 3. Add HST002 C1 S Red Slim × 3. 4. Review per-line price and total. 5. Create and confirm. 6. Query PurchLine in D365FO. | Dm001 × 5; HST002 × 3 | One PO, two lines, each with its own price source ("Base purchase price" / "No price found"); total equals the sum of line amounts |
| PO-HP-03 | Multi-line PO with different tax groups, delivery modes and dates per line | Items with different item sales tax groups; delivery modes configured | 1. Create PO with three lines. 2. Set a different delivery date and mode of delivery per line. 3. Confirm. 4. Check PurchLine.DeliveryDate, DlvMode, TaxItemGroup. | 3 items, 3 dates | Each line keeps its own date/mode/tax group; tax calculated per line |
| PO-HP-04 | Full receipt split across bins | Confirmed PO with Dm001 × 5 at AAS; two bins exist | 1. PO receiving → scan PO. 2. Scan Dm001, enter 3, bin AAS. 3. Scan Dm001 again, enter 2, second bin. 4. Register. 5. Query InventTrans. | 3 + 2 into two bins | Two registered receipt transactions with their own wMSLocationId; line fully received |
| PO-HP-05 | Partial receipt: item A full, item B partial, item C backordered | Confirmed 3-line PO | 1. Receive A in full. 2. Receive part of B. 3. Do not scan C. 4. Register. 5. Query PurchLine remain qty. | A full, B partial, C 0 | A Received, B partly received with remainder open, C fully open (backorder); PO stays Open order |
| PO-HP-06 | Over-receipt within allowed tolerance | PO line whose item/line allows 2% over-delivery | 1. Receive 102 against 100 ordered. 2. Register. | 100 ordered, 102 received | Accepted without warning; D365FO registers 102 |
| PO-HP-07 | PO from approved purchase requisition / MRP | Approved PR or firmed planned order | 1. Release PR to PO in D365FO. 2. Receive it on the handheld. | PR-derived PO | Handheld receives it like any confirmed PO; PO references the PR |
| PO-HP-08 | Batch item receipt with new batch and expiry date | Confirmed PO line for HST002 | 1. Scan PO, scan HST002, quantity. 2. Scan new batch number. 3. Enter expiry DD-MM-YYYY. 4. Register. 5. Query InventBatch. | Batch `QA081002`, expiry 31-12-2027 | Batch created with ExpDate 2027-12-31; receipt registered with the batch |
| PO-HP-09 | Purchase return (return to vendor) with reason and action | Stock of the item in the bin | 1. Outbound → Purchase return. 2. Scan original PO. 3. Choose reason and return action. 4. Scan item, choose bin, quantity. 5. Return and post, confirm. | PO 161703, reason 6 Damaged, action Damaged, Dm001 × 1 from AAS | Return order created and its packing slip posted; stock leaves AAS; card shows POSTED with packing slip id |

### 1.2 Sad path

| Test ID | Scenario Name | Preconditions | Action Steps | Test Data | Expected Outcome |
|---|---|---|---|---|---|
| PO-SP-01 | Vendor inactive / on hold / deleted | Vendor marked on hold (Blocked = All) in D365FO | 1. PO creation → search the vendor. 2. Try to select it / create a PO. | Blocked vendor | Vendor not offered, or creation refused with a clear "vendor is on hold" message; nothing created |
| PO-SP-02 | Unknown SKU | PO draft open | 1. Scan a code that is no item. | `ZZNOSUCHITEM99` | "No item ZZNOSUCHITEM99"; no line added |
| PO-SP-03 | Discontinued / stopped item or item not purchasable from vendor | Item with purchase stopped | 1. Scan the stopped item. | Stopped item | Refused with the reason; no line |
| PO-SP-04 | Non-inventory (service) SKU | Service item | 1. Scan the service item on PO creation and on receiving. | Service item | Not receivable into a bin; clear message |
| PO-SP-05 | Over-receipt beyond tolerance | Line with no over-delivery allowed | 1. Receive more than ordered. | 9 against 5 | Refused: "Only 5 outstanding, and this line allows no over-delivery" |
| PO-SP-06 | Receipt against a PO for another warehouse | Worker in AAC, PO for AAS | 1. PO receiving → scan the PO. | PO 161703 (AAS) | Refused: "is for warehouse AAS - you are working in AAC" |
| PO-SP-07 | Receipt against a Draft / unconfirmed / closed PO | PO in Draft or fully received/invoiced | 1. Scan the PO in PO receiving. | Draft PO; Received PO | Refused with the PO status; nothing registered |
| PO-SP-08 | Zero quantity | Quantity keypad open | 1. Enter 0, then 0.0. | 0 / 0.0 | Add/Confirm disabled; "Enter a quantity above zero" |
| PO-SP-09 | Negative / non-numeric quantity | Quantity keypad open | 1. Try to type `-3` or letters. | -3, "abc" | Not possible: keypad has digits and decimal point only |
| PO-SP-10 | Item not on the PO at receiving | PO open in receiving | 1. Scan an item that is not on the PO. | HST003 | "HST003 is not an item on this order, and not a known barcode" |
| PO-SP-11 | Invalid expiry date | New batch | 1. Enter an impossible date. | 31-02-2027 | Refused with the format message; no batch created |
| PO-SP-12 | Expiry date in the past | New batch | 1. Enter a past expiry. | 01-01-2020 | Refused (already expired) or explicit warning requiring confirmation |
| PO-SP-13 | Currency mismatch | PO in AED, vendor invoice in EUR | 1. Post the invoice in EUR without exchange rate setup. | EUR invoice | D365FO refuses or converts by exchange rate; handheld receipt is quantity-only and unaffected |
| PO-SP-14 | Return more than available in the bin | Purchase return open | 1. Enter a return quantity above stock in the chosen bin. | 5 with 2 in bin AAS | "Only 2 available in bin AAS"; Add line disabled |

### 1.3 Exceptional and edge cases

| Test ID | Scenario Name | Preconditions | Action Steps | Test Data | Expected Outcome |
|---|---|---|---|---|---|
| PO-EX-01 | Partly damaged delivery to quarantine | Quarantine warehouse/status set up | 1. Receive good quantity to the bin. 2. Receive damaged quantity with a reason code to quarantine. | 8 good, 2 damaged | Two receipts; damaged stock in quarantine/blocked status with reason; not available for picking |
| PO-EX-02 | Unit of measure conversion | PO in a pack unit, dock counts each | 1. Receive in the alternate unit using the unit chips. | BSR-47274-0: 1 Pack = n InsUnit | Quantity converted by D365FO conversion; registered in purchase unit |
| PO-EX-03 | Substituted item shipped by vendor | Ordered item out of stock at vendor | 1. Scan the replacement item at receiving. | Substitute SKU | Not on the order → refused, or substitution workflow updates the PO line |
| PO-EX-04 | Blind receiving | Blind receiving required | 1. Receive without seeing expected quantity. | Any PO | Expected quantity hidden; count captured as entered |
| PO-EX-05 | Return to vendor at the dock before receipt | Delivery refused at the door | 1. Do not register; reject the delivery. | Whole delivery | No receipt posted; PO stays open; no inventory/financial movement |
| PO-EX-06 | Concurrent PO change while receiving | Two sessions | 1. Buyer cancels/reduces a line. 2. Worker registers the old quantity at the same moment. | Line reduced 5→2, worker registers 5 | Server re-checks outstanding at posting; registration refused for the excess, nothing double-booked |
| PO-EX-07 | Registration answer is slow / times out | AOS slow | 1. Register; D365FO takes long to answer. | Any | Item queued safely; message must not claim both "registered" and "did not answer" |
| PO-EX-08 | Draft left at another warehouse | Draft created in AAS, worker now in AAC | 1. Discard the draft / start another order. | — | New order is for the warehouse the worker is in now; delivery date not in the past |

---

## Suite 2 — Transfer Order shipment and receipt

### 2.1 Happy path

| Test ID | Scenario Name | Preconditions | Action Steps | Test Data | Expected Outcome |
|---|---|---|---|---|---|
| TO-HP-01 | Create a TO between two active warehouses | Worker assigned to AAS and AAC | 1. Outbound → TO creation. 2. From AAS, To AAC. 3. Continue to items. 4. Add Dm001 C1 × 2. 5. Add HST002 × 1 choosing "Any batch - the shipment picks it". 6. Create transfer order. | AAS→AAC | TO created (BSP-TO-000000028), 2 lines, ship/receive today, transit AAC-Transi; lines carry no batch |
| TO-HP-02 | Pick each scanned line live | Online; TO created | 1. Ship it now. 2. Scan bin AAS, item, quantity, batch. 3. Open "Picked so far". | Dm001 × 2, HST002 × 1 batch QA081002 | Each line shows "Picked in D365FO"; issue InventTrans StatusIssue = Picked with batch/bin; transfer line dims unchanged |
| TO-HP-03 | Change a picked quantity (unpick) | Line picked | 1. Tap − on Dm001 (2→1). | Dm001 | One unit back to unpicked in D365FO; device shows 1 |
| TO-HP-04 | Confirm picking posts the shipment | Lines picked | 1. Confirm picking. | — | "picking confirmed - N lines shipped"; issue Sold; transit receipt and issue created; QtyRemainShip reduced |
| TO-HP-05 | Receive into destination clears transit | TO shipped | 1. Switch to AAC. 2. Inbound → Transfer — Receive. 3. Scan TO, items, quantity, batch, bin AAC. 4. Post transfer. | Dm001 × 1, HST002 × 1 | Receipt Purchased at AAC with batch; transit issue Sold; on-hand at AAC up |
| TO-HP-06 | One-step vs two-step transfer | Direct transfer (journal) vs TO | 1. Compare a transfer journal move with a TO via transit. | — | Direct move posts immediately without transit; TO goes through transit |

### 2.2 Sad path

| Test ID | Scenario Name | Preconditions | Action Steps | Test Data | Expected Outcome |
|---|---|---|---|---|---|
| TO-SP-01 | Line quantity above available at source | TO creation | 1. Add a line for more than on hand at From. | Dm001 50 with 3 available | Refused at entry ("Only 3 available at the From warehouse") |
| TO-SP-02 | Ship more than outstanding | Shipping | 1. Enter more than still to ship. | 5 against 2 | "Only 2 left to ship on this line" |
| TO-SP-03 | Unknown bin | Shipping | 1. Scan a bin that does not exist. | ZZBIN99 | "No location ZZBIN99 in warehouse AAS" |
| TO-SP-04 | Batch with no stock for the item | Shipping batch item | 1. Scan a batch unknown for the item. | E0000 for HST002 | "Batch E0000 is not known for HST002 - check the batch label" |
| TO-SP-05 | Receive a TO that is fully received | TO-27 received | 1. Transfer — Receive → scan TO-27. | BSP-TO-000000027 | Refused, saying it is already received in full |
| TO-SP-06 | Ship a TO that is already shipped (stale saved copy, offline) | TO fully shipped, device holds an older copy | 1. Offline: scan, pick, confirm. 2. Reconnect. | BSP-TO-000000026 | Queued offline; on sync D365FO refuses ("1.00 requested but only 0.00 outstanding"); nothing shipped twice |
| TO-SP-07 | Ghost item at receipt | Receiving | 1. Scan an item not on the TO. | HST003 | "HST003 is not on this order" |
| TO-SP-08 | Over-receipt beyond shipped | Receiving | 1. Enter more than shipped. | 2 against 1 shipped | "Only 1 was shipped and is still to receive" |
| TO-SP-09 | Cross-company / mismatched legal entity | Warehouses in different companies | 1. Try to create a TO between them. | — | Not offered: TO creation lists only the worker's warehouses in the signed-in company |
| TO-SP-10 | Ship from wrong warehouse | Worker in AAC, TO ships from AAS | 1. Transfer — Ship → scan TO. | TO-28 | "ships from AAS, not from here" |

### 2.3 Exceptional and edge cases

| Test ID | Scenario Name | Preconditions | Action Steps | Test Data | Expected Outcome |
|---|---|---|---|---|---|
| TO-EX-01 | In-transit shrinkage | 500 shipped, 480 arrive | 1. Receive 480. 2. Report discrepancy for 20. | 480/500 | Partial receipt; 20 remain in transit; discrepancy recorded and a count/write-off raised |
| TO-EX-02 | Whole shipment lost | Shipped TO lost | 1. Declare loss in D365FO; write transit off. | — | Transit cleared by adjustment; financial loss posted |
| TO-EX-03 | Lot/serial divergence at receipt | Shipped batch QA081002 | 1. Receive scanning a different batch. | QA081001 | "Batch QA081001 was not shipped on BSP-TO-000000028 for HST002. Shipped: QA081002 (1)" |
| TO-EX-04 | Arrived at the wrong warehouse | TO to AAC scanned at AAS | 1. Transfer — Receive at AAS → scan it. | TO-28 at AAS | "is bound for AAC, not here" |
| TO-EX-05 | Transit damage | Some units broken | 1. Receive all; move broken units to a damaged/blocked status. | — | Manifest cleared; damaged units non-pickable |
| TO-EX-06 | Interrupted picking (device dropped) | Lines picked live | 1. Kill the app mid-pick. 2. Reopen / another device scans the TO. | — | Picks stay in D365FO; resumed session does not pick twice ("already picked"), posting picks only the shortfall |
| TO-EX-07 | Partial shipment (short-ship) | 2 to ship, 1 picked | 1. Confirm picking with 1 of 2. | Dm001 1/2 | 1 shipped, 1 left on the line (QtyRemainShip 1) |
| TO-EX-08 | Offline shipping | D365FO unreachable | 1. Scan, pick (stays unpicked), confirm. | — | Saved and queued immediately; posts (pick + ship) when D365FO is back |

---

## Suite 3 — Inventory counting

### 3.1 Happy path

| Test ID | Scenario Name | Preconditions | Action Steps | Test Data | Expected Outcome |
|---|---|---|---|---|---|
| CNT-HP-01 | Scheduled cycle count by ABC class | ABC classes on items; count plan | 1. Create counting journal for class A in D365FO. 2. Get data on the handheld. 3. Count all lines at system quantity. 4. End count, submit. | Class A items | All lines counted; zero variance; journal ready to post |
| CNT-HP-02 | Blind count | Journal downloaded | 1. Open journal lines. | Spot journal BSP-JN-000552010 | No expected quantity shown (lines show "-" until counted) |
| CNT-HP-03 | Multi-bin count of one SKU | Item stocked in several bins | 1. Count each bin line. | HST002 across bins | One line per bin/batch, each counted separately |
| CNT-HP-04 | Perfect reconciliation | Counted = on hand | 1. Count QA081002 as 1. 2. Submit. | 1 vs 1 | Counted 1, adjustment 0; item stays available |
| CNT-HP-05 | Ad-hoc spot count | Worker sees a discrepancy | 1. Inventory count → +. 2. Item HST002, location AAC. 3. Create spot count. 4. Count. 5. End count → Submit. | HST002 @ AAC | Journal created with one line per on-hand batch; counts written to D365FO; "Counts submitted to D365FO. ACCEPTED" |

### 3.2 Sad path

| Test ID | Scenario Name | Preconditions | Action Steps | Test Data | Expected Outcome |
|---|---|---|---|---|---|
| CNT-SP-01 | Count the same line twice | Line already counted | 1. Re-open the counted line and enter a new quantity. | 3 then 1 | Count replaced (not added); previous value pre-selected |
| CNT-SP-02 | Negative / non-numeric count | Count dialog open | 1. Type `-3` or letters. | -3 | Only digits accepted |
| CNT-SP-03 | Unmapped location | New spot count | 1. Location ZZBIN99. 2. Create. | ZZBIN99 | "No location ZZBIN99 in warehouse AAC" |
| CNT-SP-04 | Count on a posted / cancelled journal | Journal posted after download | 1. Submit counts for it. | Posted journal | Refused: JOURNAL_POSTED; device keeps the counts |
| CNT-SP-05 | Tracked item without batch/serial | Batch item line without batch | 1. Count a batch-controlled item where batch is not on the line. | — | Batch required before the count is accepted |
| CNT-SP-06 | Spot count with nothing to count | Item with no stock anywhere in warehouse, no location | 1. Create spot count for item only. | Item with 0 stock | "D365FO has no stock of … - name an item and a location to count one in" |

### 3.3 Exceptional and edge cases

| Test ID | Scenario Name | Preconditions | Action Steps | Test Data | Expected Outcome |
|---|---|---|---|---|---|
| CNT-EX-01 | Recount trigger above tolerance | HSMobileOpsCountTolerance configured (e.g. 5% / qty) | 1. Count 5 against 7. 2. Submit. | 28% variance | Line flagged for recount / supervisor review; not accepted silently |
| CNT-EX-02 | Inventory freeze during count | Freeze active on bin | 1. Try to pick/ship from the bin during the count. | — | Movement blocked while frozen |
| CNT-EX-03 | Live count with movements | No freeze | 1. Count. 2. Ship 5 from the bin before submit. | — | Variance computed against on-hand at count time; movement not double-counted |
| CNT-EX-04 | Ghost stock (system 0, found 1) | Item not in bin per D365FO | 1. Spot count item + location. 2. Count 1. | Item with no stock at bin | Line created with on-hand 0; counted 1 posts a positive adjustment |
| CNT-EX-05 | Expired/damaged split | 10 found, 3 damaged | 1. Count 7 good, 3 to damaged status with reason. | — | Two lines/statuses; reason recorded |
| CNT-EX-06 | Serial swap | Expect A,B; find B,C | 1. Count serials. | — | A adjusted out, B confirmed, C adjusted in |
| CNT-EX-07 | Network loss mid-count | Counting offline | 1. Count offline. 2. Reconnect. 3. Submit. | — | Counts kept on device; submitted on reconnect without loss or duplication |
| CNT-EX-08 | Discrepancy reported by a picker | Transfer — Ship ⋮ → Report discrepancy | 1. Report at a bin with reason and note. | Bin AAS | A counting journal for that spot is raised in D365FO and appears in the counting list |

---

## Execution results — 8 Oct 2026 (release 1.0.23 on SUNMI P2mini, AOS ps_bak84–90)

Legend: **Pass**, **Fail → fixed** (fixed in code; see note), **Gap** (feature or setup not present),
**Not run** (needs setup or a second actor that was not available).

### Suite 1

| Test ID | Result | Evidence / note |
|---|---|---|
| PO-HP-01 | Pass | BSP-PO-000161703 created and confirmed (DocumentState 40), AAS, Dm001 5 × 100 AED |
| PO-HP-02 | Pass | Same PO, line 2 HST002 × 3; "No price found" shown for HST002 (0.00) |
| PO-HP-03 | Gap | App sets one delivery date per order; no per-line date/mode on the handheld |
| PO-HP-04 | Not run | Only one bin per warehouse in this data (AAS / AAC) |
| PO-HP-05 | Pass | Dm001 3/5 registered, 2 open; HST002 3/3 registered |
| PO-HP-06 | Not run | No item or line with over-delivery % configured |
| PO-HP-07 | Gap | No PR/MRP flow on the handheld; a released PO receives like any other |
| PO-HP-08 | Pass | Batch QA081002, ExpDate 2027-12-31 in InventBatch |
| PO-HP-09 | Pass | Return BSP-PO-000161704 posted with packing slip |
| PO-SP-01 | Not run | No blocked vendor in the cached vendor list to try |
| PO-SP-02 | Pass | "No item ZZNOSUCHITEM99" |
| PO-SP-03 / 04 | Not run | No stopped / service item identified in the data |
| PO-SP-05 | Pass | "Only 5 outstanding, and this line allows no over-delivery" |
| PO-SP-06 | Pass | "BSP-PO-000161703 is for warehouse AAS - you are working in AAC" |
| PO-SP-07 | Pass | Fully received PO BSP-PO-000161702: "is not open for receiving" (Draft/unconfirmed not tried) |
| PO-SP-08 | Pass | 0 disables Add line; 0.0 → "Enter a quantity above zero" |
| PO-SP-09 | Pass | Keypad has no minus sign |
| PO-SP-10 | Pass | "HST003 is not an item on this order, and not a known barcode" |
| PO-SP-11 | Pass (message improved) | Refused; message now names the bad value |
| PO-SP-12 | **Fail → fixed** | 01-01-2020 was accepted silently. Now refused: "…is in the past - this batch would be received already expired" (app build, not yet installed) |
| PO-SP-13 | Gap | Handheld does not handle invoices |
| PO-SP-14 | Pass | "Only 2 available in bin AAS" |
| PO-EX-01 | Gap | No quarantine routing / damage reason at receipt on the handheld |
| PO-EX-02 | Not run | Unit chips present (Pack / InsUnit); not exercised |
| PO-EX-03 | Gap | No substitution workflow; substitute is refused as not on the order |
| PO-EX-04 | Gap | Receiving shows "N left of N ordered" — not blind |
| PO-EX-05 | Pass (by design) | Nothing posts until Register; discarding leaves the PO untouched |
| PO-EX-06 | Not run | Needs a second actor |
| PO-EX-07 | **Fail → fixed** | Screen showed "Registered in D365FO" together with "D365FO did not answer in time". The body text now matches a successful registration (app build) |
| PO-EX-08 | **Fail → fixed** | After discarding a draft left at AAS, the new PO was still made "to AAS" (BSP-PO-000161703) while working in AAC, with a stale delivery date. New orders now follow the current warehouse and today's date (app build) |

### Suite 2

| Test ID | Result | Evidence / note |
|---|---|---|
| TO-HP-01 | Pass | BSP-TO-000000028 AAS→AAC, 2 lines, line dims without batch |
| TO-HP-02 | Pass | HST002 issue StatusIssue 3 (Picked) batch QA081002 bin AAS; Dm001 picked |
| TO-HP-03 | Pass | Dm001 2→1: one issue trans back to unpicked |
| TO-HP-04 | Pass | "2 lines shipped"; issue Sold; transit receipt/issue created |
| TO-HP-05 | Pass | Receipt Purchased at AAC with batch; transit issue Sold; "2 lines posted" |
| TO-HP-06 | Gap | No direct transfer journal on the handheld |
| TO-SP-01 | Pass (by design) / **Fail → fixed** | Dm001 allows negative physical stock, so 50 with 3 available is accepted on purpose; the edit dialog refused the same quantity - now consistent, with a warning on the line (app build) |
| TO-SP-02 | Pass | "Only 2 left to ship on this line" |
| TO-SP-03 | Pass | "No location ZZBIN99 in warehouse AAS" |
| TO-SP-04 | Pass | "Batch E0000 is not known for HST002 - check the batch label" |
| TO-SP-05 | **Fail → fixed** | Said "has nothing in transit yet" for a fully received TO; now "is already received in full" (app build) |
| TO-SP-06 | Pass | Dead-lettered: "Line 1.00: 1.00 requested but only 0.00 outstanding" |
| TO-SP-07 | Pass | "HST003 is not on this order, and not a known barcode" |
| TO-SP-08 | Pass | "Only 1 was shipped and is still to receive" |
| TO-SP-09 | Pass (by design) | Only the worker's own warehouses are offered |
| TO-SP-10 | Pass (code) | Message exists ("ships from …, not from here"); not re-run today |
| TO-EX-01 | Gap → partly fixed | Report discrepancy now raises a count journal in D365FO (ps_bak89); no transit variance write-off |
| TO-EX-02 | Gap | Loss declaration is a D365FO back-office task |
| TO-EX-03 | Pass | "Batch QA081001 was not shipped … Shipped: QA081002 (1)" |
| TO-EX-04 | Pass (code) | "is bound for AAC, not here" |
| TO-EX-05 | Gap | No damaged-status receipt on the handheld |
| TO-EX-06 | **Fail → fixed** | TO-26: confirm after picks failed ("received quantity exceeds shipped") — server fixed (ps_bak84); re-scan after a lost session now reports "already picked" and posting picks only the shortfall (ps_bak85/86) |
| TO-EX-07 | Pass | Dm001 1 of 2 shipped, QtyRemainShip 1 |
| TO-EX-08 | Pass (fix pending install) | Queued offline with a clear message; took ~20 s on "Shipping…" — now immediate when D365FO is known down (app build) |

### Suite 3

| Test ID | Result | Evidence / note |
|---|---|---|
| CNT-HP-01 | Not run | Existing journals (231 / 2040 lines) are real counting work; not touched |
| CNT-HP-02 | Pass | Spot journal lines show "-" until counted; no expected quantity |
| CNT-HP-03 | Pass | HST002 at AAC: one line per batch (HSB-T2-002, QA081002) |
| CNT-HP-04 | Pass | QA081002 counted 1 vs 1 → Qty 0 |
| CNT-HP-05 | **Fail → fixed** | Spot count answered "This server does not support this yet" (createSpotCount was a stub). Implemented (ps_bak89): BSP-JN-000552010 created; submit was also a stub — implemented (ps_bak90); "Counts submitted to D365FO. ACCEPTED" |
| CNT-SP-01 | Pass | Re-opened line pre-selects the old count; new value replaces it |
| CNT-SP-02 | Pass | Digits only (also means no fractional counts — note for Pack items) |
| CNT-SP-03 | Pass | "No location ZZBIN99 in warehouse AAC" (after ps_bak89) |
| CNT-SP-04 | Pass (code) | Server answers JOURNAL_POSTED for posted journals |
| CNT-SP-05 | Pass (by design) | Spot lines carry the batch from on-hand |
| CNT-SP-06 | Pass (code) | NOTHING_TO_COUNT with explanation |
| CNT-EX-01 | Not run | Tolerance table empty in this environment — a 28% variance was accepted, which is correct for the setup |
| CNT-EX-02 / 03 | Gap | No freeze handling (FreezeState always NONE) |
| CNT-EX-04 | Pass (code) | Spot count of item + location with no stock creates a 0 on-hand line |
| CNT-EX-05 / 06 | Gap | No status split / serial swap resolution on the handheld |
| CNT-EX-07 | Not run | Device network settings are not toggled during testing |
| CNT-EX-08 | Fixed, not re-run | reportDiscrepancy was a stub; now raises a spot journal (ps_bak89) |

### Defects found and their state

| # | Area | Defect | State |
|---|---|---|---|
| 1 | TO ship | Confirm picking after live picks failed with "Total received quantity … exceeds shipped quantity" (receipt registration ran during shipment) | Fixed server-side, verified on TO-26 and TO-28 |
| 2 | TO ship | Re-scanning a line already picked in D365FO tried to pick again | Fixed server-side (shortfall + ALREADY_PICKED) and app |
| 3 | Sign-in | Offline sign-in waited 15 s for the connect timeout | Fixed in 1.0.22, installed |
| 4 | Posting | Postings waited ~20 s on "Shipping…" when D365FO was known down | Fixed in app (not installed) |
| 5 | PO create | New order kept the previous draft's warehouse and a past delivery date | Fixed in app (not installed) |
| 6 | PO receive | Expired batch expiry date accepted | Fixed in app (not installed) |
| 7 | PO receive | "Registered" screen also said "did not answer in time" | Fixed in app (not installed) |
| 8 | TO receive | "Nothing in transit yet" for a fully received TO | Fixed in app (not installed) |
| 9 | Counting | Spot count, direct count submit, discrepancy report were server stubs | Implemented and deployed (ps_bak89/90) |
| 10 | TO create | Add-line accepts more than available (edit dialog blocks it) - Dm001 allows negative physical stock, so adding is by design; the edit dialog now follows the same rule and the card warns when a line exceeds on-hand | Fixed in app (not installed) |
| 11 | UX | After an error notice is dismissed the quantity screen re-lays out and buttons move under the finger. Cause: the keypad screen closed before the quantity was checked, so the refusal landed on the step screen. The keypad now checks first and shows the reason under the number, staying open (PO receiving and both transfer directions) | Fixed in app (not installed) |
| 12 | PO receive | Expiry date not shown on the "Scanned so far" review list | Fixed in app (not installed) |
| 13 | TO ship | Offline, "Picked so far" still said each line is picked in D365FO as scanned | Fixed in app (not installed) - offline it says lines are picked when the shipment posts |

### Retest on release 1.0.25 — 9 Oct 2026

| Defect / case | Result | Evidence |
|---|---|---|
| PO-EX-08 new PO warehouse and date | Pass | New PO at AAC showed "to AAC · delivery 2026-10-09"; BSP-PO-000161705 created and confirmed |
| #11 keypad refusal | Pass | 9 against 2 ordered: "Only 2 outstanding, and this line allows no over-delivery" in red under the number, keypad stayed open |
| PO-SP-12 past expiry | Pass | 01-01-2020: "is in the past - this batch would be received already expired" |
| #12 expiry on review list | Pass | "AAC · Batch QA091001 · expires 30-06-2028" |
| PO-EX-07 result screen | Pass | "Registered in D365FO", no contradictory text |
| TO-SP-05 fully received TO | Pass | TO-27: "is already received in full - nothing left to receive" |
| TO-SP-01 / #10 TO line above on-hand | Pass | Dm001 50 with 3 at AAC: red warning on the line; edit dialog accepts 50 (draft discarded, no order created) |
| Partly shipped TO at receipt | **Fail → fixed (1.0.26)** | TO-28 (1 shipped and received, 1 never shipped) said "has nothing shipped to receive yet - it is Created"; now "has nothing in transit to receive - what is left on it has not been shipped yet" |
| #13 offline wording | Not rerun | Needs D365FO unreachable |

### Retest on release 1.0.26 — 9 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Partly shipped TO at receipt | Pass | TO-28: "has nothing in transit to receive - what is left on it has not been shipped yet" |
| Sign-in history across sessions | Pass | 001 on 1.0.23 ended "Timed out" ~15 min after the last touch (device clock UTC+5); 1.0.25 session ended "App closed" when 1.0.26 was installed; 1.0.26 session open |

### Offline item master and barcode sync, discrepancy reports — 9-10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Full SKU sync (items, barcodes, variants) | **Fail → rebuilt** | The DMF export took 6-7 min for 18,495 items; the app gave up at 5 min - 61 attempts, never completed. Replaced by the paged `HSMobileOpsSkuPageSvc/getSkuPage` (2,000 rows/page, daily delta, weekly full, resumable). Full sync: 20 pages in 23 s server-side |
| Offline barcode, counting (cache only) | Pass | 9502930970077 → BSR-47274-0 batch lines; 834580A0000859M1 → "BSR-83458-0 … is not on this journal" |
| Offline barcode, PO creation (airplane mode) | Pass | 9502930970077 → BSR-47274-0, unit Pack |
| Offline variants (airplane mode) | Pass | HSV003 (36 variants) offered from the variants table |
| CNT-EX-08 discrepancy from Transfer - Ship | **Fail → fixed (1.0.31/1.0.32)** | The dialog said "scan the location" but the scan went to the screen behind ("No transfer order AAC"); now a scan fills the dialog's Location, then Item |
| Discrepancy / spot count for a whole bulk bin | **Fail → fixed (ps_bak96)** | AAC holds 3,011 stock lines; now refused above 200: "AAC holds 3011 stock lines - too many for a spot count. Name the item to count" |
| Discrepancy with item (barcode) | Pass | BSP-JN-000552012 "Discrepancy DAMAGED - BSR-47274-0 at AAC by 001", 2 batch lines |
| Discrepancy refused - dialog closes | **Fail → fixed (1.0.33, not installed)** | After a refusal only OK was offered; now the form stays open with the reason |
| D365FO deploys while VS hosts the AOS | Found | AOS in IIS Express (Visual Studio) - iisreset reloaded nothing; deploys now refuse while IIS Express hosts it |

### Feedback round and retest on 1.0.34 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Feedback f77ebd0d - remove "All lines scanned · n scanned" | Fixed (1.0.34) | Nothing under "Scan item" in Transfer Ship/Receive and PO receiving; seen on TO-33 receive |
| Feedback 88c029a1 - default locations from warehouse master | Fixed (ps_bak97 + 1.0.34) | TO-33 AAS→AAC: ship took bin AAS without a scan (issue AAS/AAS), receipt went to AAC (StatusReceipt Purchased, bin AAC) without a scan |
| Discrepancy refusal visible | Pass (1.0.34) | "AAC holds 3011 stock lines…" in red at the top; form stays open |
| Feedback - variant picker does not say what each value is | Fixed (1.0.35) | Labels now "Config C1 · Size L · Colour Black · Style Regular" |
| Variant picker labels on device | Pass (1.0.35) | HSV003: "Config C1 · Size S · Colour Red · Style Slim" |
| Variant filter after the label change | **Fail → fixed, verified (1.0.36)** | "Black L" now lists only Size L Black. Before: Size S rows: "L" matched "Colour"/"Style". Filters (variant picker, receiving and transfer line search) now match dimension values from their start |
| Item scanned on a single-line order | **Fail → fixed (1.0.38)** | The order's only line is selected when the order is scanned; scanning the item then landed on the quantity step ("Only 2 outstanding" for barcode 9502930970077, "BSR-47274-0 is not a number"). Diagnostic build confirmed one delivery per scan. Now the current item's number or barcode is recognised at the quantity step ("… is already selected - enter the quantity"); receiving and transfer |
| PO-EX-02 unit of measure at receipt | **Fail → fixed (1.0.38)** | BSR-47274-0: 1 Pack = 48 InsUnit. The unit choices were only on the step screen behind the auto-opened keypad; now under the keypad too |
| Online order card says "as soon as it can be reached" | **Fail → fixed (1.0.37)** | BSP-PO-000161706 took ~1-2 min in D365FO; card now "Sending" / "Sent to D365FO - it creates it now" while online |
| PO-EX-02 receipt in another unit, in D365FO | Pass | 48 InsUnit entered → BSP-PO-000161706 registered 1 Pack, batch AN9U, bin AAC; 1 Pack still open |
| TO-SP-10 ship from the wrong warehouse | Pass | At AAC, TO-28: "ships from AAS, not from here" |
| TO-EX-04 receive at the wrong warehouse | Pass | At AAC, TO-25: "is bound for AAS, not here" |
| PO-SP-01 / 03 / 04 blocked vendor, stopped or service item | Not run - no data | bsp has no blocked vendor, no purchase-stopped item and no service item |
| Inquiries - Location inquiry | Pass | AAC: "Open for put-away"; ZZBIN99: "No location ZZBIN99 in AAC" |
| Inquiries - PO's of today | Pass | Lists BSP-PO-000161706 (OPEN, AAC) |
| Partly received PO reopened with Post immediately | **Fail → fixed (1.0.39)** | After 1 of 2 Pack was registered at once, reopening 161706 said "has already been staged for registration": the device's staged quantity was kept until an Activity poll and subtracted again from D365FO's already-reduced outstanding. Now the posting's staged lines are cleared as soon as D365FO registers them |
| PO 161706 last Pack after the staged-line fix | Pass (1.0.39) | Reopened and received the remaining Pack - order fully received |
| Inquiries - PO lines | Pass | BSP-PO-000161703: Dm001 ordered 5, received 3, outstanding 2, location AAS; the fully received line is left out (open lines only) |
| Inquiries - Transfer orders | Pass | 12 (10 to ship, 2 to receive); filters, search by number ("31") and store ("wms"); a row opens Transfer receive with the default bin |
| Inquiries - TO's by Store | **Fail → fixed (ps_bak98)** | BSP-TO-000000022 listed as "0 lines" to ship - a header with no lines. Line-less orders are now left out |
| Inquiries - Picked serials | Pass | SN-N2/T3/T1-0001 on TO-10, batch HSB-T1-001 shown; 7/30/90 days, search "t3" |
| On-hand - Total available | **Fail → fixed (ps_bak98)** | BSR-47274-0 at AAC: 23 on hand, 0 ordered, "Total available 46". AvailOrdered already includes the physical; it was added twice |
| On-hand - All warehouses | **Fail → fixed (ps_bak98)** | All-zero cards (ADF: 0 everywhere) listed; now left out |
| Activity - Acknowledge a failed posting | **Fail → fixed (1.0.41)** | The badge drops but the card looked unchanged; now "✓ Acknowledged" |
| Settings - Logs | **Fail → fixed (1.0.41)** | "Inventory hub isn't responding" - 3000 rows composed at once, main thread blocked 46 s. Now 200 per page with "Show more"; Copy all unchanged |
| Settings - My warehouse & sign-in, Data posting | Pass | AAC with AAS also assigned; Post immediately on |

### Retest on 1.0.41 / ps_bak98-99 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Settings - Logs opens | Pass (1.0.41) | Opens at once; "Show 200 more (6806 older)" |
| Logs - row layout | **Fail → fixed (1.0.42)** | Time wrapped ("12:11:2 / 1") and messages ended in "…"; time column 72 dp, messages wrap |
| On-hand - Total available | Pass (ps_bak98) | BSR-47274-0 at AAC: 23 / 23 |
| On-hand - All warehouses | Pass (ps_bak98) | ADF zero card gone; AFS (2) listed |
| TO's by Store - empty header | Pass (ps_bak98) | TO-22 gone: All 11, AAS 2 orders |
| Activity - Acknowledge | Pass (1.0.41) | TO-25 card "✓ Acknowledged", badge 3 → 2 |
| PO at another warehouse | Pass | 161703 at AAC: "is for warehouse AAS - you are working in AAC" |
| Due today lists fully registered POs | **Fail → fixed, verified (ps_bak99)** | 161706 offered, then "nothing left to receive" - open lines were read from RemainPurchPhysical, which only falls at product receipt. Now Ordered InventTrans; list shows only BSP-PO-000161707 |
| Test PO created | Pass | BSP-PO-000161707, 19721, BSR-47274-0 1 Pack, AAC, confirmed in ~2 min |
| Another PO scanned on a single-line order | **Fail → fixed (1.0.42)** | 161707 opens at Qty; scanning 161706 said '"BSP-PO-000161706" is not a number', hidden behind the keypad. The PO switch now also works at Qty, and the keypad closes when a scan is refused or changes the step (receiving and transfer) |

### Retest on 1.0.42 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Another PO scanned at the quantity pad | Pass (1.0.42) | 161707 open at Qty, 161706 scanned: pad closed, order switched, "BSP-PO-000161706 has nothing left to receive" visible |
| Receive test PO | Pass | BSP-PO-000161707: 1 Pack, batch AN9U, bin AAC - "Registered in D365FO" 12:35; then gone from Due today |
| Logs - row layout | Pass (1.0.42) | "12:36:46" on one line; full URLs wrap |
| Status bar after the camera sheet | **Fail → fixed (1.0.43)** | After Scan with camera → Cancel, the status bar stayed for the rest of the session; bars now re-hidden whenever the app window regains focus |
| Another TO scanned mid-transfer | Fixed (1.0.43) | Same gap as receiving: a TO scanned at bin, item or quantity was read as a bin/item/number; now opens that order (same direction), or asks to ship/receive the scanned lines first |

### Retest on 1.0.43 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Another TO scanned at the receive quantity pad | Pass (1.0.43) | TO-2 open at Qty, TO-31 scanned: pad closed, TO-31 opened at its open line |
| Another TO scanned while shipping | Pass (1.0.43) | TO-14 open at Qty, TO-1 scanned: TO-1 opened (bin AAC); nothing posted |
| Status bar after the camera sheet | Pass (1.0.43) | Bars show inside the camera sheet only, hidden again after Cancel |
| Receive batch - which batch was shipped | **Gap → fixed (1.0.44)** | Batch step gave no hint; only a wrong scan said "Shipped: E0927 (1)". Shipped batches now shown under "Scan batch" |
| Post a transfer once every line is scanned | **Gap → fixed (1.0.44)** | Only the small list icon in the top bar led to Post transfer; item step now has "Receive/Ship n lines scanned so far", like PO receiving |
| TO-31 receive, last line | Pass | BSR-11392-0 1 Pack, batch E0927, default bin AAC - "1 line posted in D365FO" |

### Retest on 1.0.44 / ps_bak100 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Test TO created | Pass | BSP-TO-000000034 AAC → AAS, BSR-11392-0 1 Pack, batch E0000 (only batch with stock at AAC; item is -7 overall) |
| Ship - "… scanned so far" button | Pass (1.0.44) | Opened Picked so far; Confirm picking → "picking confirmed - 1 line shipped in D365FO". Label now "Confirm n lines picked so far" (1.0.45), the list's own words |
| Ship - which batch the order was made for | **Gap → fixed (ps_bak100 + 1.0.45)** | TO created for batch E0000; the ship Batch step gave no hint. Line batch now sent (LineBatchId) and shown: "Ordered from batch E0000" |
| Receive - shipped batch hint | Pass (1.0.44) | At AAS: "Shipped: E0000 (1)" under Scan batch, default bin AAS |
| Receive - "Receive n lines scanned so far" | Pass (1.0.44) | → Post transfer → "BSP-TO-000000034 · 1 line posted in D365FO" |
| TO creation - "Ship it now" on old orders | **Fail → fixed (1.0.45)** | Offered on TO-33 (AAS → AAC) at AAC, after it was shipped and received. Now only on orders still to ship from this warehouse (D365FO's list) |

### Retest on 1.0.45 / ps_bak100 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| TO creation - Ship it now only when shippable | Pass (1.0.45) | Old orders (TO-33/34/32/28…) no action; new BSP-TO-000000035 keeps "Ship it now" after Start another order |
| Ship - ordered batch hint | Pass (ps_bak100 + 1.0.45) | TO-35 (AAC → AAS, BSR-47274-0, batch HSB-PAN-001): "Ordered from batch HSB-PAN-001" under Scan batch |
| Ship - another batch than ordered | **Gap → fixed (1.0.46)** | AN9U scanned on the HSB-PAN-001 line was picked in D365FO without a word. Now warned once ("This line is for batch HSB-PAN-001. Scan HSB-PAN-001 - or scan AN9U again to pick it instead") |
| Ship - remove a picked line | Pass | Trash on the AN9U line: back to "1 Pack still to ship" (unpicked in D365FO) |
| Ship TO-35 | Pass | HSB-PAN-001 → "picking confirmed - 1 line shipped in D365FO". Left in transit to AAS |

### Retest on 1.0.46 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Ship - another batch than ordered | Pass (1.0.46) | BSP-TO-000000036 for batch AN9U; HSB-PAN-001 scanned: "This line is for batch AN9U. Scan AN9U - or scan HSB-PAN-001 again to pick it instead"; AN9U then taken, shipped |
| Receive - another TO with lines held | Pass | TO-35 line scanned, TO-36 scanned: "Receive or remove the lines scanned on BSP-TO-000000035 before opening BSP-TO-000000036" |
| Receive TO-35 / TO-36 at AAS | Pass | Shipped batch hints HSB-PAN-001 / AN9U; both "1 line posted in D365FO" - nothing left in transit |
| Batch label scanned at the quantity step | **Gap → fixed (1.0.47)** | '"AN9U" is not a number'; now "AN9U is the batch - enter the quantity first, then scan it" for the line's known batch |
| Taps right after dismissing an error banner | Observed | Twice a tap just after OK on the red banner did nothing; a later tap worked. Not reproduced on purpose - watch for it |

### Retest on 1.0.47 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Batch label scanned at the quantity pad | **Partly → fixed (1.0.48)** | BSP-TO-000000037 (AAC → AAS, AN9U): no longer "not a number", but the notice showed behind the pad and was gone when it closed. The pad now also closes for quick notices (receiving and transfer) |
| TO creation - From / To | **Feedback → changed (1.0.48)** | A chip per warehouse clutters for a worker with many warehouses; now two dropdowns (selected name wraps) |
| BSP-TO-000000037 | Open | Created, not yet shipped |

### Retest on 1.0.48 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| TO creation - From / To dropdowns | Pass (1.0.48) | Two full-width dropdowns, names wrap; From AAS → To switched to AAC by itself; back to AAC → AAS. System bars hidden again after the menu closed |
| Batch scanned at the quantity pad - pad closes | Pass (1.0.48) | TO-37: pad closed at once |
| … but the notice | **Fail → fixed (1.0.49)** | The 1-second green "done" notice was gone before it could be read; guidance notices now amber with an info icon, 3.5 s |
| Ship TO-37 | Pass | AN9U → "picking confirmed - 1 line shipped in D365FO". In transit to AAS (for the 1.0.49 notice retest on receive) |

### Retest on 1.0.49 and uniform UI pass — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Hint notice at the quantity pad | Pass (1.0.49) | TO-37 receive at AAS: amber "AN9U is the batch - enter the quantity first, then scan it", readable |
| BSP-TO-000000037 | Pass | Received at AAS, "1 line posted in D365FO" - no test transfer left in transit |
| Feedback f0eda53c "controls merged" (Transfer - Ship, Qty) | **Fixed (1.0.50)** | The prompt card's bottom was cut off and "Ship all outstanding" sat against it: the fixed bottom group (quick-fill + 6 unit chips + camera) squeezed the scrolling card. Quantity controls moved into the scrolling group under the card (12 dp gap), receiving and transfer |
| Feedback 15d74b60 "clumsy" (PO receiving, Qty) | **Fixed (1.0.50)** | Same squeeze - "Enter quantity" title hidden, hint clipped. Same fix; more than 3 units are now one "Unit: Pack ▾" dropdown instead of three rows of chips |
| Uniform UI - buttons | Changed (1.0.50) | Seven screens used raw Material buttons (pill shape, no double-tap guard/haptic); all now the app's square buttons |
| Uniform UI - button height | Changed (1.0.50) | 29 buttons fixed at 56 dp now use the app's touch target, so they scale with text size like the rest |
| Uniform UI - single choice from D365FO lists | Changed (1.0.50) | One shared dropdown: TO creation From/To, units (> 3), purchase return reason and action |

### Retest on 1.0.50 and scan-area pass — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Quantity step - card and buttons apart | Pass (1.0.50) | TO-14 ship, Dm001 Config C1: card whole, rounded, gap above "Ship all outstanding" |
| Purchase return - reason / action dropdowns | Pass (1.0.50) | PO 161707: 9 reasons in one menu. Nothing created |
| Dropdown look | **Changed (1.0.51)** | Red outline read as another action; now a field (grey outline, dark value, dimmed placeholder) |
| Feedback "scan area should always stay in one place" | **Changed (1.0.51)** | Scan card now first under the top bar / step trail everywhere: receiving and transfer (order summary moved below it - it grew 1 → 5 rows and pushed the card down each step), PO / TO creation and Purchase return (vendor/route card, reason and action, history and the saved order now below), Location inquiry (warehouse field below), Counting (now the shared scan card, above progress, filters and the location bar). Error banners below the card; busy bars keep a fixed 4 dp slot |
| TO creation - "Made on this device" | Changed (1.0.51) | First and latest three, like PO creation and Purchase return |

### Retest on 1.0.51 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Scan card position - Transfer Ship | Pass (1.0.51) | Same place on the TO step and Qty step; summary under it |
| Scan card position - Purchase return | Pass (1.0.51) | Card first; vendor, reason, action below |
| Scan card position - Counting | Pass (1.0.51) | Shared card first; progress and "Count by location" below |
| Top bar height | **Fixed (1.0.52)** | Ship step 1 bar 8 px taller (labelled "Report" at the 56 dp button height) - capped at 48 dp. Counting title (journal description) wrapped to two lines - now the journal number |
| TO creation step 1 | **Fixed (1.0.52)** | History first pushed "Continue to items" off the screen. Creation screens now: the input first (scan card / vendor search / From-To + Continue), then "Made on this device", then long lists |
| Counting camera | Changed (1.0.52) | Camera icon inside the field → the same "Scan with camera" button in the card as the creation screens (shared HeroCameraButton) |
| Counting quantity dialog | **Changed (1.0.52)** | Used the system keyboard ("-", ","), unlike every other quantity entry; now the app keypad, whole numbers |

### Retest on 1.0.52 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Ship step 1 top bar | Pass (1.0.52) | Same height as other screens (divider 104 px) |
| TO creation step 1 | Pass (1.0.52) | From / To / Continue first, history below |
| Purchase return step 1 | Pass (1.0.52) | Scan card, vendor search, then history; leftover draft cleared (Change vendor, no lines) |
| Counting header | Pass (1.0.52) | Title BSP-JN-000552000 on one line; "Scan with camera" in the card |
| Counting - scan to count | Pass | BSR-70781-0 scanned → 1 counted, 12 of 231 |
| Counting - message text | **Fixed (1.0.53)** | "BSR-70781-0 · : 1 counted" - blank location left a stray "· "; blank parts skipped |
| Counting - quantity on the app keypad | Pass (1.0.52) | BSR-73383-0: 3 → 13 of 231 |
| Feedback "show progress in top" | **Changed (1.0.53)** | "n of N counted" and its bar above the scan card (fixed height, card stays put) |

### Retest on 1.0.53 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Counting - progress on top | Pass (1.0.53) | "13 of 231 counted" + bar above the scan card |
| Spot count screen | **Changed (1.0.54)** | Two plain fields, no scan card or camera; now the shared scan card first ("Scan item" → "Scan location"), camera, fields below for typing |
| Spot count - item only | Pass | BSR-47274-0 at AAC → BSP-JN-000552013, 3 lines (AN9U, B98E, HSB-PAN-001) |
| Spot count - scan picks the line | Pass | "BSR-47274-0 is on 3 lines" → AN9U → 1 of 3 counted |
| Count message without the batch | **Fixed (1.0.54)** | "BSR-47274-0 · AAC: 1 counted" - lines differing only by batch; batch / serial now named |
| Journal card - "Spot count" twice | **Fixed (1.0.54)** | Subtitle and amber badge; badge dropped when the description already says it |
| Discrepancy dialog - Note field | **Fixed (1.0.54)** | Label cut off at the bottom; now full width, 64 dp like the fields above |
| Dialog buttons | **Partly (1.0.54)** | Count prompt and discrepancy dialogs now two equal full-width buttons (shared DialogButtonRow), as the quantity dialog. 33 other dialogs in 16 files still use the small right-aligned slot buttons - open |
| Discrepancy dialog buttons | Pass (1.0.54) | Cancel / Report two equal full-width buttons - approved |
| Discrepancy Note still cut off | **Fixed (1.0.55)** | Dialog body squeezed the last field; body now scrolls |
| All dialogs - buttons | **Changed (1.0.55)** | 33 dialogs in 16 files → shared DialogButtonRow: passive choice left (outlined), action right (filled; red for Discard / Delete / Reset / Sign out); single-button dialogs one full-width button. Settings dialogs had Cancel on the right and the action on the left - now the same order everywhere. Seen: Reset connection? (Cancel / Reset) |
| Sign out dialog (1.0.55) | Pass | Two full-width buttons; cancelled |
| Discrepancy report end to end (1.0.55) | Pass | AAC + BSR-47274-0 by scan, Damaged → BSP-JN-000552014. Note field scrolls into view whole. The confirmation shows the scanned barcode, not the item number - open |
| Feedback "cancel should be on the right side" | **Changed (1.0.56)** | Every dialog: the action left, Cancel right (shared DialogButtonRow + counting quantity dialog); scanned-lines screen: Post / Confirm picking left, Back to scanning right. Seen: Reset connection? (Reset / Cancel) |

### Retest on 1.0.56 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Scanned lines - button order | Pass (1.0.56) | TO-14 ship: "Confirm picking" left, "Back to scanning" right |
| Remove a picked line | Pass | Dm001 removed → Picked so far (0), unpicked in D365FO; nothing shipped |
| Scanned lines - variant not shown | **Fixed (1.0.57)** | Dm001 (Config C1) card showed only "AAC"; variant now first on the line, receiving and transfer (lines restored after a restart still without it) |
| Discrepancy confirmation shows the barcode | **Fixed (1.0.57)** | "Item 9502930970077" → the item number it resolved to |

### Retest on 1.0.57 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Scanned lines - variant | Pass (1.0.57) | TO-14: "Config C1 · AAC"; PO 161708: "Config C1 · AAC". Pick removed again on TO-14 |
| Discrepancy confirmation - item | Pass (1.0.57) | "Location AAC · Item BSR-47274-0" → BSP-JN-000552015 |
| Dialog order in practice | Pass (1.0.57) | Report left / Cancel right; Post receipt left / Back to scanning right |
| Vendor search "19721" | **Fixed (1.0.58)** | Showed 31995, 21961 above 19721: each letter started its own search, an earlier one could finish last; and results were by name only. Now the latest search only, exact / starting account first (PO creation, Purchase return) |
| PO creation quantity dialog | **Fixed (1.0.58)** | "Add line" under the keypad and a full-width Cancel below it; now Add line / Cancel side by side, Remove this line above them |
| "1 Pieces" on order lines | **Fixed (1.0.58)** | PO creation / confirm lines use the shared unit wording ("1 Piece") |
| Test PO | Pass | BSP-PO-000161708 (19721, Dm001 Config C1, 1 Piece, 100.00 AED) created and confirmed; received → "Registered in D365FO" |

### Retest on 1.0.58 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Vendor search "19721" | Pass (1.0.58) | Only BinSina - Central Purchase (19721) |
| PO creation quantity dialog | Pass (1.0.58) | Add line left, Cancel right; cancelled, draft vendor cleared |
| Inquiries - Location, PO lines, On-hand camera | **Changed (1.0.59)** | "Scan with camera" was a separate button under the card; now inside it, as on creation screens and Counting |
| Inquiries - Picked serials refresh | **Changed (1.0.59)** | No refresh, unlike Transfer orders / TO's by Store; added to the top bar |
| Inquiries - lists | Pass | Transfer orders, TO's by Store, Picked serials, PO's of today: same search / chips / card layout |
| PO's of today - 161706/7/8 "OPEN" | Note | Fully registered but D365FO status stays Open (Backorder) until a product receipt is posted - correct as D365FO shows it |

### Retest on 1.0.59 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Inquiries - camera in the card | Pass (1.0.59) | Location inquiry, On-hand |
| Picked serials refresh | Pass (1.0.59) | Refresh in the top bar |
| Activity / Counting / Supervisor / Picked serials - typewriter font | **Changed (1.0.60)** | Document and item numbers in monospace, unlike every other card; normal font now (Logs keep it) |
| Activity - operation names | **Changed (1.0.60)** | "TransferReceipt", "TransferShipment", "Registration" → "Transfer receipt", "Transfer shipment", "PO receipt" |
| On-hand switch | **Changed (1.0.60)** | Switch left of its label; now label left, switch right, as Count by location |
| My warehouse - Change warehouse | **Changed (1.0.60)** | Text link next to an outlined button; now an outlined full-width button |

### Retest on 1.0.60 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Activity - font and labels | Pass (1.0.60) | Normal font; "PO receipt", "Transfer receipt", "Transfer shipment" |
| On-hand switch | Pass (1.0.60) | Label left, switch right |
| My warehouse - Change warehouse | Pass (1.0.60) | Outlined full-width, same as "Show the app tour again" |
| Uniform UI pass | Done | All areas reviewed; open choice: camera button placement in Receive / Ship (bottom of the screen) |
| Receive / Ship - camera placement | **Changed (1.0.61)** | "Scan with camera" moved from the bottom row into the scan card, as on every other scan screen; the item search button in the bottom row now reads "Search items" |
| Receive / Ship - camera in the card | Pass (1.0.61) | Ship TO step and item step (TO-5), PO receiving PO step: card with "Scan with camera", nothing pinned at the bottom. "Search items" not seen - only on orders whose lines are loaded on the device (TO-5 has 108) |

### Retest on 1.0.61 — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Two-line test TO | Pass | BSP-TO-000000038 AAC → AAS: Dm001 Config C1 1 Piece + BSR-47274-0 AN9U 1 Pack. "1 Piece" wording on the draft |
| Ship - Search items | Pass (1.0.61) | "Search items" full width at the bottom; picked Dm001 from it; "Unit: Pack" dropdown for BSR-47274-0; "picking confirmed - 2 lines shipped" |
| Receive at AAS | Pass (1.0.61) | Same layout; Config C1 and Batch AN9U on the scanned lines; "2 lines posted in D365FO" |
| TO creation cards say "Created" after shipping | **Fixed (1.0.62)** | TO-36/37 shown CREATED though shipped and received; now "Shipped" once D365FO no longer lists them to ship |
| "Search items" with every line scanned | Note | Still shown when nothing is left to pick - harmless, left as is |
| TO creation - Shipped label | Pass (1.0.62) | TO-38, 37, 36 "SHIPPED" |
| Counting list - spot / discrepancy journals unlabelled | **Fixed (ps_bak101)** | Server listed the journal *name's* description and always SCHEDULED. Now the journal's own description ("Spot count - BSR-47274-0 at AAC by 001", "Discrepancy DAMAGED - …") and origin SPOT / TRIGGERED; "Triggered" badge shows. Side effect: the triggered-count alert fired once for each existing discrepancy journal |

### Feedback 3c03d94e and a14afd70 (Inventory count) — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| "move the add button to the top bar" | **Changed (1.0.63)** | "+" in the top bar records an item not on the journal; the "No items recorded as not on this journal · Add" row is gone (a count shows only once items are recorded) |
| "count by location should be before item scan" | **Changed (1.0.63)** | Count by location now above the scan card, under the progress row |
| "clumsy" (after a scan) | **Changed (1.0.63)** | Scan message and Undo on one line - Undo was its own full-height row pushing the list down |
| Spot count 552013 counting | Pass | Batch named in the message ("· AAC-RCV · Batch B98E"); repeat scan adds to the line just picked (by design); Undo restores |
| Counting layout on device | Pass (1.0.63) | "+" in the top bar, Count by location above the card, no empty "not on this journal" row; message and Undo on one line |
| Counting title wraps with "+" | **Fixed (1.0.64)** | "BSP-JN-000552 / 013" on two lines grew the bar; journal number one size smaller |
| End count / Submit counts | Pass (1.0.63) | 552013: "End count?" Submit counts (left) / Keep counting (right) → "Counts submitted to D365FO." Not posted |
| Raw "ACCEPTED" under the submitted message | **Fixed (1.0.64)** | D365FO's pass result shown as a code; dropped when it only says accepted |
| Counting title / ACCEPTED | Pass (1.0.64) | "BSP-JN-000552000" on one line beside "+"; 552013 card shows only "Counts submitted to D365FO." |

### Feedback "no variant name in variant popup" — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Variant picker - variant name | **Changed (ps_bak102 + 1.0.66)** | SKU sync's VARIANTS feed now carries each variant's product name (EcoResProductTranslation, company language); stored on the device (DB 17), next sync is a full one; the picker shows the name above "Config C1", and the filter matches it. A name equal to the item's (D365FO's default copy) is not shown |
| Dm001 in bsp | Data note | All 5 variants are named "DM001" (copied from the master); configurations C1-C5 have no name or description; product numbers "Dm001 : C1 : : :". Nothing more to show for this item until names are set in D365FO |
| Purchase return dropdowns | Pass (1.0.64) | 161708: 9 reasons and 10 actions in their menus, long names wrap; draft not sent |
| Feedback "what variant am I choosing - colour, size, config or style" | **Changed (1.0.67)** | Picker title names the dimensions ("Dm001 - choose the configuration", "… choose the size and colour"); every variant label spells each one out ("Configuration: C1 · Size: L · Colour: Black") - picker, quantity dialog, scanned lines, counting |
| Variant picker wording | Pass (1.0.67) | "Dm001 - choose the configuration"; quantity dialog "DM001 · Configuration: C1 · Bin AAC · Pieces" |
| Feedback "config: is not required in values" | **Changed (1.0.68)** | Picker rows show the bare values ("C1", "L · Black") under the title that names them; labels elsewhere keep the dimension names |
| Purchase return end to end | Pass (1.0.67) | From test PO 161708: 19721, reason 6 - Damaged, action Damaged, Dm001 Configuration C1 1 Piece from bin AAC → "Return and post" (left) / "Not yet" (right) → BSP-PO-000161709 POSTED, packing slip BSP-PO-000161709 (~2 min) |
| Variant picker bare values | Pass (1.0.68) | TO creation, Dm001: "choose the configuration", rows C1 … C5; cancelled, no draft |
| Variant picker title | **Changed, verified (1.0.69)** | "Choose the configuration" - no item number (feedback); the item name stays under the title |
| Variant picker - item line | **Changed, verified (1.0.70)** | "DM001" under the title removed (feedback); title and the values only |

### Feedback "dont want dm001 - quantity", "quantity picker should be same across app" — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Test PO for async posting | Created | BSP-PO-000161710 (19721, Dm001 Configuration C2, 1 Piece, 100 AED), confirmed. "Post immediately" set off - to be received, then switched back on |
| One quantity picker | **Changed (1.0.71)** | Shared QuantityPicker popup: title "Quantity", item · variant · unit, hint, the number, keypad, "… all outstanding (n)" and the unit when they apply, action left / Cancel right. Used by Receive and Ship (replaces their full keypad screen at the quantity step), PO / TO creation, Purchase return, Counting and the quick opportunity count |
| Shared quantity popup on device | Pass (1.0.71) | Receive 161710 (opened itself, "1 Piece left of 1 ordered", "Receive all outstanding (1)", typed 1 → Confirm), Ship TO-14 ("Ship all outstanding (1)", Cancel leaves the step screen), Counting (existing 5 shown, first key replaces) |
| Post immediately off | Pass | 161710 → "Sent to D365FO … within a few minutes"; Activity Pending → Process pending → Registered 18:11. Setting switched back on |
| Counting popup button | **Changed (1.0.72)** | "OK" → "Confirm", as Receive / Ship |
| Counting popup "Confirm" | Pass (1.0.72) | |
| Popup line under the title | **Changed, verified (1.0.73)** | Order creation showed "PANADOL ADVANCE TABS 48S · Pack" (name), Receive/Ship "Dm001 · Configuration: C2 · Pieces" (item number); PO / TO creation and Purchase return now item number · variant · unit too |
| Feedback "remove 'Prices come from D365FO' under Scan item - info not required" | **Changed, verified (1.0.74)** | Information lines under scan cards removed: prices / stock checked / offline notes (PO, TO creation, Purchase return), "Shipping out of / Receiving into this warehouse", "The bin you pick from…", "Bulk storage…", "Bin in warehouse…", the packing-slip explanation, Counting "Scan to count · type to search". Kept only what is acted on: "Or pick … below", the order's suggested bin, shipped / ordered batch, serials to scan, and the quantity hints in the popup |

### Serial items and more info lines — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Test TO with a serial item | Created | BSP-TO-000000039 AAC → AAS: HSN002 serial SN-N2-0001, 1 Piece (stock picker listed the serial; quantity popup pre-filled 1) |
| Ship - serial not in stock | **Fail → fixed (1.0.75)** | ZZSER1 (does not exist) scanned at the Serial step: accepted and picked in D365FO (InventTrans Picked, serial ZZSER1 - the item allows negative stock). Removed the line → back to On order with SN-N2-0001. Shipping now checks the serial is in stock at the From warehouse ("Serial ZZSER1 is not in stock at AAC"); offline taken as scanned |
| On-hand - "Warehouse: All warehouses" | **Changed (1.0.75)** | Red outlined button → the shared dropdown field look (DropdownTrigger); its list stays a searchable dialog |
| TO creation / Purchase return stock picker | **Changed (1.0.75)** | "HSN002 - which stock?" → "Choose the stock"; "Available at the From warehouse." / "On hand at … - which goes back?" removed |
| TO creation route step | **Changed (1.0.75)** | "Ships …, received …. The transit warehouse comes from …" removed |
| Ship - serial not in stock | Pass (1.0.75) | TO-39: ZZSER1 → "Serial ZZSER1 is not in stock at AAC"; SN-N2-0001 accepted, picked |
| Scanned lines - which serial | **Changed (1.0.76)** | "AAC · 1 serial" → "AAC · Serial SN-N2-0001" (receiving and transfer; several: "3 serials (first, …)") |
| D365FO unreachable mid-post | Pass | Confirm picking on TO-39 at 19:02 as the dev VM shut down: "D365FO can't be reached … your work is saved on this device", "1 item is waiting to be sent"; queued. To check it is sent once the VM is back |
| SKU sync - time and memory | **Added (1.0.77)** | Each finished sync now records items / barcodes / variants, total and active seconds (without the gaps between 7-minute steps), peak app memory (Java heap + native, sampled per page) and the database size; shown in Settings > My warehouse & sign-in > Offline use, and logged at warning level ("SKU sync done (full): … in … s (… s active), peak memory … MB, database … MB") so it reaches Logs and feedback in release |
| SKU sync - only what handhelds use | **Written, deploy pending (ps_bak103)** | getSkuPage: service items never sent; full refresh leaves out items whose lifecycle state is not Active and blocked barcodes (and barcodes of left-out items) - the device drops them; a delta still sends changes to stopped / blocked. Baseline for the hash check: scratchpad base103. Not compiled yet - VM down |
| SKU sync - page size | **Changed (1.0.78)** | 2,000 → 5,000 rows per page (server maximum) |

### Sign-in screen and SKU sync — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| Queued TO-39 shipment after the VM came back | Pass | Activity: TO-39 Transfer shipment "Registered", processed 20:07 - sent by the app by itself |
| SKU feed filters | Deployed (ps_bak103, 0 errors) | bsp: items 18,495 → 18,488 sent, barcodes 16,470 → 16,467, variants 293 - few stopped / service / blocked rows in this test company |
| SKU sync guard | **Fixed (1.0.79)** | A forced run did nothing: the "once a day" guard came before the schema check, so the full sync for variant names never started. Schema now overrides it. Measurement still to do |
| Feedback "D365FO unavailable flashing on launch" | **Fixed, verified (1.0.81)** | Seen at ~1 s after launch on 1.0.80; the notice now shows only once offline has lasted 3 s. 1.0.81: no notice at 1 s or 2 s |
| Feedback "display the last used user and go to the password field" | **Changed (1.0.80)** | The last signed-in user name is kept (encrypted store) and filled in; the screen then starts in the PIN field with the keyboard up. Not seen yet - nobody signed in since the install |
| SKU sync via SQL change tracking | Parked | Plan agreed in outline (CT on InventTable, InventTableModule, InventItemBarcode, InventDimCombination, EcoResProductTranslation via the D365FO change-tracking framework; version numbers instead of dates; deletes sent; 7-day retention, "too old" → full sync; modified-date fallback). User: "think of this later" |
| Last user filled in | **Fail → fixed (1.0.82)** | After 001 signed in on 1.0.81 the release app was signed out (idle) and the name was empty: setting the session leaves the sign-in screen at once, which cancelled the screen's own save. Now saved by the session itself, online and offline sign-in |
| Multi-device - second device | Set up | Emulator blocked: "Virtualization Enabled In Firmware: No" (VT-x off in BIOS, managed laptop) - AVD MultiDev2 ready for when it is on. Debug 1.0.82 installed beside release 1.0.82 as the second device (own device ID, queue, session) |

### Multi-device (release + debug app on one handheld, both 001) — 10 Oct 2026

| Case | Result | Evidence |
|---|---|---|
| MD-1 same PO line, two devices | **Fail → fixed in code, deploy pending (ps_bak104)** | BSP-PO-000161711 (Dm001 C3, 2 pieces). A scanned 2 (unposted); B scanned 2 → queued (Post immediately off); A posted 2 → "Registered"; B's queued 2 also "Registered" 21:45 - 4 registered on a 2-piece line. Cause: posting checks purchLine.RemainPurchPhysical, which only falls at product receipt. Now checks the quantity still Ordered in InventTrans (in the purchase unit). Needs RDP to the VM to deploy |
| Home - sign-out button | **Changed (1.0.83, not installed)** | Moved to the far right corner, after feedback |
| Last user filled in (again) | **Fail → fixed, verified (1.0.83)** | 1.0.82: PIN focused but the name empty - the sign-in ViewModel lives on after a sign-in, and success had emptied it. Now refilled each time the screen opens: "001" in User name, cursor and keypad in PIN |
| MD-2 same TO line, two devices | Pass (protected) / UX fixed (1.0.84) | BSP-TO-000000040 (1 Pack AN9U). A picked; B still saw "1 Pack still to ship", scanned, D365FO answered ALREADY_PICKED and B also showed "Picked in D365FO". B confirmed (queued, shipped); A's confirm refused "Line 1.00: 1.00 requested but only 0.00 outstanding" - shipped once. Now an already-picked line is shown amber "Was already picked in D365FO - check no other device is shipping it" |
| MD-3 same counting journal, two devices | **Gap - open** | B on BSP-JN-000552000 (A counting it): "This server doesn't support claiming journals yet - counting without a claim." No claim service in HSMobileOps: two devices can count one journal, the later submission overwrites. Needs a server claim (journal + device + heartbeat expiry) - not built |
| MD-4 both offline, reconnect | Not run | Needs airplane mode on the handheld (a device setting I do not change) |
| Quantity popup - "all outstanding" | **Changed (1.0.84)** | Removed from the popup in Receive / Ship (feedback); the hint above still says what is left |

### 10 Oct (late) - journal claim (MD-3 fix), 1.0.85 + ps_bak105

| Change | Where | Status |
|---|---|---|
| claimJournal / heartbeatClaim / releaseJournal built on HSMobileOpsLineClaim (COUNT, line 0); holder = user + device; TTL 20 min, heartbeat 120 s | HSMobileOpsCountTaskService | Deployed (ps_bak105, 0 errors) - device test pending sign-in |
| submitCounts (DIRECT) and importCounts (DMF) refuse CLAIM_LOST while another device holds a live claim | CountTaskService / CountingService | Deployed - pending test |
| Journal list fills Claims ("Name (001) on another device") | HSMobileOpsCountingService.listOpenJournals | Deployed - pending test |
| PO receipt check uses Ordered InventTrans qty (MD-1 over-receipt) | HSMobileOpsPostingService.orderedQty | Deployed (ps_bak105) - retest on a fresh test PO |
| Submit refused for claim -> local claim marked lost, banner names the holder, Claim again offered | CountSubmitRunner / CountClaimManager | 1.0.85 installed (release + debug), unit test added |

### 10 Oct (late) - device results, ps_bak107, 1.0.86 built

| Test | Result |
|---|---|
| MD-3 claim: A Get data on BSP-JN-000552014 | PASS - "Claimed by you · until 23:02"; row in HSMOBILEOPSLINECLAIM (001, device 91e6…, TTL 20 min) |
| MD-3 claim: B (same 001 login) sees and tries the journal | PASS - list "Claimed by Abdul Muqsith (001) on another device"; Get data refused "Already claimed by … on another device" |
| Heartbeat | PASS - expiry moved 23:02 -> 23:06 -> 23:08 |
| Release after End count | NOT VERIFIED - DMF import of 552014 failed: "Item BSR-47274-0 is locked for updates in warehouse AAC because it is being counted" (other open test journals count the same item). App showed only "The run failed in D365FO" - GetExecutionErrors returned nothing |
| Blank range labels ("range ·", "( - )") | Fixed in 1.0.86 (built, not installed) |
| MD-1 retest PO 161712 (A immediate + B queued, 2 s apart) | Qty right (2 registered) but BOTH reported Registered - second was a silent no-op. Race: orderedQty read before the first commit |
| Fix: pessimisticLock on the PurchLine during registration | ps_bak107 deployed, 0 errors (ps_bak106 failed compile: forUpdate + pessimisticLock not allowed; nothing restarted) |
| MD-1 retest PO 161713 (B registered 2, then A 2) | PASS - A refused "Line 1.00: 2.00 requested but only 0.00 outstanding", under Failed postings |

### 10 Oct (late) - 1.0.86 on device, 1.0.87 installed

| Test | Result |
|---|---|
| Blank range label | PASS - "Claimed by you · until 00:03" |
| Claim after app update, before sign-in | FAIL in 1.0.86 - the heartbeat ran with no signed-in user, D365FO did not see the holder (user + device) and A marked its own claim lost. Fixed in 1.0.87: no heartbeat while nobody is signed in |
| Claim again (same holder) | PASS - "Claimed again - you can end the count now." |
| New spot count (BSP-JN-000552016, Dm001, 3 lines) | FAIL in 1.0.86 - downloaded without a claim, so a second device could take it. Fixed in 1.0.87: the spot count is claimed before download (unit test updated) |
| Open journals sharing items | 552003-552007 and 552011-552015 share items - D365FO's counting lock refuses their imports; 552000 and 552008 do not |

### 11 Oct - one active device per worker (feedback: "same user should not be in two devices as active session")

Decision (user): the second sign-in takes over; the first device is signed out at its next check.

| Change | Where | Status |
|---|---|---|
| HSMobileOpsSessionSvc: startSession / checkSession / endSession; one HSMobileOpsLineClaim row per worker (SESSION, user, device), 12 h TTL | HSMobileOpsSessionService + AxService + service group | Deployed ps_bak108, 0 errors |
| App: sign-in records the device; checks every 2 min (and 5 s after start); signed in elsewhere -> signed out, banner on sign-in screen; sign-out ends the session | ActiveSessionGuard, WorkerSessionRepository, WorkerLoginScreen | 1.0.88 installed (release + debug), 3 unit tests |
| Open: B (debug, 1.0.87) showed only Inbound / Outbound tiles for 001 while A showed all; server rows allow every module | Home menu / module permissions | Not yet explained |
| To test | | A signs in, B signs in -> A signed out within ~2 min with the banner |

### 11 Oct - emulator, takeover, plain database (1.0.89 - 1.0.91)

| Item | Result |
|---|---|
| Emulator MultiDev2 (API 37.1, 16 KB pages) running release; set up by connection QR via virtual-scene camera | Done |
| One active device: emulator signed in as 001, then A signed in as 001 | PASS - emulator signed out within 2 min: "001 signed in on another device, so this device was signed out. Sign in again to use it here." |
| Database: only connection settings + login accounts encrypted (SecureStore); app DB plain SQLite | 1.0.89/1.0.90 converted every device (A log: "database: plain SQLite"); 1.0.91 removes SQLCipher, the DB key and its backup |
| Release APK size | 23.9 MB (16 KB SQLCipher) -> 13.2 MB |
| In-app log | keeps the last 5 minutes |

### 11 Oct - unfinished work on another device (1.0.94 + ps_bak110)

| Change | Status |
|---|---|
| Every session call reports this device's unfinished jobs (flow sessions of the worker) and counts in progress (counting journals started, not in D365FO) | Deployed / installed |
| peekSession: before a takeover the new device is told what the previous device still holds; dialog "Work on another device" - Continue here / Sign out | Deployed / installed - to test |
| Sign-out with unfinished work keeps the (ended) session row with the summary, so the next sign-in elsewhere is still warned | Deployed - to test |
| The device signed out by a takeover lists what it still holds in its notice | Installed - to test |
| ps_bak109 failed compile (utcdatetime declaration ran into the field name); fixed as ps_bak110, 0 errors | |

| Test (11 Oct) | Result |
|---|---|
| A (001, 4 counts in progress) reported "0;4" to D365FO; emulator signed in earlier with nothing pending | A took over without a question (nothing pending elsewhere); emulator signed out with the plain notice - PASS |
| Emulator signs in as 001 while A holds 4 counts | "Work on another device - You have 4 counts in progress on another device..." shown - PASS |
| Continue here | A signed out within 2 min: "...This device still holds 4 counts in progress for 001 - they stay here until 001 signs in here to finish them." - PASS |
| "Sign out" in the takeover question (A, emulator holds 1 count) | A signed out; emulator kept 001's session ("0;1") - PASS |
| Claim release after End count, sent before sign-in (552018) | FAIL - release went with no user, refused, claim stayed until expiry. Fixed: releaseJournal matches token + device (ps_bak111, 0 errors) |
| Claim release after End count, signed in (552019) | PASS - counts submitted, claim row gone |
| "Post immediately" mistaken for the posting switch | 1.0.95: signed-in Data posting page says when posting is off and where to switch it on |
| Known gap | Counts in progress are per device, so another user signed in on that device reports them too |

### 11 Oct - unattended multi-device run (emulator = 001, handheld = 002)

| Scenario | Result |
|---|---|
| Offline PO receipt queued on emulator (PO 161714, 2 pcs), reconnect | PASS - registered 04:55 UTC on reconnect. Minor: the emulator's result screen still said "D365FO did not answer in time... safely queued" after it had gone in |
| 002 has no PO receiving tile (Inbound = PO creation only) | Noted - PO variant of MD-4 not possible with 002; ran it on a TO |
| MD-4 on TO-41 (AAC->ABB, 1 pack, batch AN9U): emulator ships offline (queued), handheld ships online | PASS - handheld Registered 05:06:59; emulator's queued shipment refused on reconnect "Line 1.00: 1.00 requested but only 0.00 outstanding", shown as Failed under Activity |
| Claim expires while the device is offline (552020, emulator offline 09:11-09:33, claim until 09:30) | PASS - "Your claim expired while this device was offline. Your counts are kept on this device. Claim the journal again to submit them."; End count disabled; after reconnect Claim again -> "Claimed again", partial count (1 of 3) submitted, claim gone from the list |
| Same TO line, both online, different users (TO-42; emulator 001 picks first, handheld 002 scans after) | PASS - handheld shows amber "Was already picked in D365FO - check no other device is shipping it"; emulator confirms -> shipped; handheld confirms -> "D365FO refused BSP-TO-000000042: Line 1.00: 1.00 requested but only 0.00 outstanding", under Failed postings |
| Handheld 002 had been left on warehouse ABB after the side session's sign-in | Switched back to AAC for TO-42 |
| Not runnable unattended | Same-user scenarios after a sign-out (needs a PIN); 002 has no PO receiving, Transfer receive or counting, so receipt and counting conflicts need two full users |
| End count queued offline, app process killed (no session), background submit on reconnect (552021) | PASS - counted line in D365FO, claim released (ps_bak111 release by token + device) |
| Receipt result screen said "D365FO did not answer in time" for a receipt saved offline and already sent | Fixed in 1.0.96: "This is queued for D365FO and posts automatically - see Activity for its status" |

### 12 Oct - Stock count control center (design: docs/design/stock-count-control-center.md)

| Step | Result |
|---|---|
| D365FO objects: 3 enums, 4 tables, staging service, control class + 6 actions, 2 forms, menu, privilege | ps_bak112: 2 form-pattern errors (quick filter missing on the list; custom filter group without sub-pattern) - fixed as ps_bak113, 0 errors, tables + security synced; all 4 tables in AxDB |
| App 1.0.97: Store count screen; journal counts staged at stores with "Review journal counts"; 2 new runner tests | Built, unit tests pass; installing on the emulator |
| To test | Store count from two counters incl. one item counted twice -> control center shows duplicate -> Keep / Add together / Reject -> Transfer creates journal "Stock count <id>" |
| Store count AAC-20261013-1: handheld 001 (BSR-47274-0/AN9U x5 then x4, Dm001/C1 x3) + emulator 002 (BSR-47274-0/AN9U x2, Dm001/C2 x1) | PASS on the devices - both joined the same stock count; "1 item(s) ... counted more than once" after each send; Dm001 C1 vs C2 not a duplicate; both ended |
| Store count had no typed scan field | Fixed 1.0.98 ("Scan, or tap to type" field) - typing AAC worked on the emulator |
| "Count ended" message closed with the screen | Fixed 1.0.99 (summary + Done) - built, not installed |
| Control center review + Transfer in D365FO | Waiting for the user (D365FO web) |
| Control center form (AAC-20261013-1) | PASS - header (2 counters, 5 lines, 1 duplicate), Counted lines with Duplicate/Decision/Goes to the journal, Counters both Submitted |
| Keep this count on one BSR-47274-0/AN9U line | PASS - the group shows "Keep one count", only that line goes to the journal, duplicates to decide 0 |
| Transfer to counting journal | PASS - "3 counted line(s) transferred to counting journal BSP-JN-000552023"; status Transferred, by Admin. Journal "Stock count AAC-20261013-1", not posted: BSR-47274-0/AN9U/AAC on-hand 3 counted 4 (+1); Dm001/C1/AAC on-hand 9 counted 3 (-6, status Available); Dm001/C2/AAC on-hand 6 counted 1 (-5) |
| Store count AAC-20261013-2: 001 (BSR/AN9U x3, Dm001/C1 x2) + 002 (BSR/AN9U x1, Dm001/C1 x2) | PASS - "2 item(s) ... counted more than once"; new "Count ended" summary with Done shown on both |
| Add together on BSR-47274-0/AN9U | PASS - both lines "Add together", both go to the journal |
| Reject - recount on Dm001/C1 | PASS - both lines "Rejected - recount", neither goes to the journal; duplicates to decide 0 |
| Transfer | PASS - "1 counted line(s) transferred to counting journal BSP-JN-000552024": BSR-47274-0/AN9U/AAC counted 4 (3+1), on-hand 3; Dm001 absent |
| Stock count stores: AAC / Admin / Review journal counts | Saved from the form (HSMOBILEOPSCOUNTSTORE row) |
| Journal review: spot count BSP-JN-000552025 (Dm001 C1/C2/C3) counted on the handheld at AAC (Review journal counts on) | PASS - End count staged it: "Sent to the supervisor for review in AAC-20261013-3"; journal itself untouched |
| BUG: journal lines staged without product dimensions (device copy lacks them) -> C1/C2/C3 looked like one item counted 3x | Fixed ps_bak114 (0 errors): a journal count takes item + dimensions from its journal line (submit and Check for duplicates); Transfer groups journal counts per journal line. Check for duplicates repaired AAC-20261013-3 (C1, C2, C3, no duplicates) |
| Transfer of a journal count | PASS - "3 counted line(s) transferred to counting journal BSP-JN-000552025" (its own journal): line 1 C1 counted 1, line 2 C2 counted 1, line 3 C3 counted 1; not posted |
| Tag counting mode | Parked by the user (13 Oct) |
| Minor | The journal card says "Counts submitted to D365FO." above the staged note - should read "Sent for review" |
| "Sent for review" wording (1.1.0) | PASS on the handheld - the BSP-JN-000552025 card reads "Sent for review - the supervisor moves the counts into the journal." |
| Transfer while counting: emulator 002 sent Dm001/C3 x3 to AAC-20261013-4 (still counting); supervisor Transfer | PASS - "1 counter(s) have not ended their count yet. Transfer what has been counted so far?" Yes -> "1 counted line(s) transferred to counting journal BSP-JN-000552026" |
| ...then 002 sends BSR-47274-0/AN9U x2 | PASS - refused: "AAC-20261013-4 has been transferred by the supervisor - these counts were not added. Your counts are kept on this device." |
| BUG: after that every Send was refused - the kept lines had nowhere to go | Fixed 1.1.1: "Move N line(s) to a new stock count" (unsent lines join the store's next stock count; sent ones stay with the old one) - installed on both, waiting for sign-in |
| Move to a new stock count (1.1.1, emulator 002) | PASS - Send refused on AAC-20261013-4, "Move 1 line(s) to a new stock count" -> "Counting for AAC-20261013-5", Send -> "1 line(s) sent to AAC-20261013-5" |
| Cancel while counting: handheld 001 joins AAC-20261013-5 (BSR-47274-0/AN9U x1 - flagged as counted twice with 002's x2); supervisor Cancel count | PASS - "Cancel AAC-20261013-5? Nothing is transferred and counters can no longer add to it." Yes -> Cancelled |
| ...then 001 sends Dm001/C4 x5 | PASS - "AAC-20261013-5 has been cancelled by the supervisor - these counts were not added..." + Move -> AAC-20261013-6, sent |
| BUG: 002 (everything already sent) taps End count on the cancelled count -> "Move 0 line(s)" and no way out; a stale "1 line(s) sent" note under the error | Fixed 1.1.2: nothing unsent -> the count is over ("Count ended" + Done, the supervisor's reason); the stale note is cleared; after a move with a location set it no longer says "Scan a location". Built; on the emulator, waiting for sign-in |
| Transfer of AAC-20261013-6 (Dm001/C4, no stock at AAC) | BSP-JN-000552027: on-hand 0, counted 5 - BUG: inventory status blank (AAC is not WHS-enabled, no default status on the store or WHS parameters) - posting would fail |
| Fix ps_bak115 (0 errors): new dimension takes the status the item's own stock carries (at the store, else anywhere) | PASS - AAC-20261013-7 (Dm001/C5 x2) -> BSP-JN-000552028: C5, AAC, status Available, on-hand 0, counted 2. (552027 keeps its blank status - test journal, do not post) |
| 1.1.2 on the emulator (002): End count on the cancelled AAC-20261013-5 with everything sent | PASS - "Count ended - AAC-20261013-5 has been cancelled by the supervisor." + Done; reopening Store count starts fresh |
| 1.1.2: AAC-20261013-8 cancelled after 002 sent Dm001/C1; 002 then sends Dm001/C2 | PASS - refusal shown without the stale "sent" note; Move -> "Counting for AAC-20261013-9." (location AAC kept, no "Scan a location"); End count -> 1 line with the supervisor in AAC-20261013-9 (left Open, not transferred) |
| Control center journals listed on the handheld ("Stock count AAC-20261013-4/6/7" with Get data - a counter could count them again) | Fixed ps_bak116 (0 errors, user 13 Oct: hide them): the journal list leaves out a journal a free store count was transferred into. PASS on the emulator - 552026/27/28 gone after refresh; 552025 (a journal count, its own journal) still listed |
| 1.1.2 on the handheld | Installed; waiting for sign-in |
| Handheld 1.1.2 (001): journal list after ps_bak116 | PASS - ends at BSP-JN-000552025; 552026/27/28 hidden |
| Transfer with an undecided duplicate: 001 joins AAC-20261013-9 and counts Dm001/C2 x1 (002 had C2 x1) | PASS - "1 item(s) ... counted more than once" on End count; Transfer refused: "1 item(s) in AAC-20261013-9 were counted more than once and are not decided yet. Keep one count, add them together or reject them first." |
| Keep this count on 002's line, Transfer | PASS - only 002's line goes to the journal; "1 counted line(s) transferred to counting journal BSP-JN-000552029"; 552029 not listed on the handheld after refresh |
| Store with no supervisor (user, 13 Oct: refuse Store count there) | Fixed ps_bak117 (0 errors): openStage refuses (NO_SUPERVISOR) unless Stock count stores has a row with a supervisor for the store - free and journal counts |
| Emulator 002 at ABB (no Stock count stores row): Start store count | PASS - "Store ABB has no stock count supervisor in D365FO - ask for one to be set up in Stock count stores before counting." Nothing opened |
| Emulator 002 back at AAC (supervisor Admin): Start store count | PASS - "Counting for AAC-20261013-10." (left open, no lines yet) |
| Decided duplicate reopened: AAC-20261013-10 002 Dm001/C3 x2 + 001 x4 -> Add together (0 to decide); 002 then counts C3 x1 | PASS - all three lines "Not decided", duplicates to decide 1 |
| Offline (emulator network off): BSR-47274-0/AN9U x3 counted (item lookup works offline), Send | PASS - "No connection - check Wi-Fi and try again. Your counts are kept on this device."; line stays "On this device" |
| Remove an unsent line (Dm001/C1 x5) | PASS - removed, "Send 1" |
| Back online, Send | PASS - "1 line(s) sent to AAC-20261013-10" |
| BUG: a line added while the list was scrolled appeared above the visible list (newest goes on top, the list kept its position) | Fixed 1.1.3: the list scrolls to the newest line |
| GAP: after an offline Send the counter had to tap Send again (the offline notice says work is sent when the connection is back) | Fixed 1.1.3: a Send that failed for want of a connection goes again by itself when D365FO answers; the message says so. Built, tests pass, not installed |
| Both counters ended AAC-20261013-10 | PASS - 3 lines (002) + 1 line (001), duplicate pending. Reject + Transfer not done - RDP session taken over by another connection |
| Top bar: Sign out and Send feedback moved into a ⋮ menu (user, 14 Oct) | 1.1.4: one "More options" button on every screen (Send feedback; Home adds Sign out, still confirmed; the tour's last step points at it). Built, tests pass, installed on both - waiting for sign-in |
| 1.1.4 ⋮ menu (both devices) | PASS - Home: ⋮ with Send feedback + Sign out; Send feedback opens the form with a clean screenshot of Home (menu not in it); Sign out asks "Sign out?", Cancel keeps the session; Store count: ⋮ with Send feedback only |
| 1.1.3 newest line in view: list scrolled down, BSR-47274-0/AN9U added | PASS - the list jumps to the new line on top |
| Rejoin after End count: emulator Start store count again on AAC-20261013-10 | Rejoins the same stock count (as the End count dialog says: "unless you start it again") |
| Note | Handheld shows a "This device at risk. Don't deal" watermark over the app - drawn by the device's system, not the app |
| 1.1.3 automatic resend: emulator offline, Send 5 -> back online, no tap | PASS - "...kept on this device and sent when the connection is back." then by itself "5 line(s) sent to AAC-20261013-10. 2 item(s) ... counted more than once" |

### 14 Oct - Mobile feedback (11 New items, 8-12 Oct)

| Feedback | Fix |
|---|---|
| Activity (12 Oct): "1 unexpected item could not be sent: This server does not accept this kind of submission yet" | submitExceptions was never built (always NOT_IMPLEMENTED). Built: each unexpected item becomes a journal line on its stock record (or a new dimension, on-hand 0); a resend sets the same count, no second line; items with product dimensions or unknown items are reported and kept on the device. AOS - not deployed yet |
| Mobile feedback form (12 Oct): change the status once fixed; select feedback to export | List is multi-select; Export takes the ticked records (two or more), else the filtered list. AOS - not deployed yet. Statuses set once the fixes are deployed |
| Inventory count (12 Oct): export the journal as Excel CSV | 1.1.5: journal card ⋮ > Export as CSV (Android save dialog; UTF-8 with BOM; journal, line, item, name, dimensions, location, batch, serial, counted, by, at) |
| Counting (11 Oct): "Counting" in the top bar, journal number above the progress bar | 1.1.5 |
| Counting (10 Oct): + in the top bar, count by location before item scan; "clumsy" | Already done (feedback 3c03d94e); with the title change above |
| Transfer - Ship (10 Oct): "controls merged" (own ⋮ beside feedback) | 1.1.5: one ⋮ per screen - Report discrepancy and Start over join Send feedback in it |
| PO receiving (10 Oct): "clumsy" | 1.1.5: the ✕ (Start over) moved into the ⋮ menu |
| Transfer - Receive (9 Oct): remove "All lines scanned · 1 scanned" | Already done (f77ebd0d) - nothing under Scan item |
| Transfer - Receive (9 Oct): use the warehouse's default receiving / issue location | Already done - the order's default issue / receipt location is filled in |
| Transfer - Ship (8 Oct): top bar buttons congested | 1.1.4/1.1.5: feedback, sign out and the screen's own actions in one ⋮ |
| Deploy | ps_bak118 failed (MultiSelectionHelper not available to the model; the list pattern needs single-select) - nothing applied. ps_bak119 (0 errors, HSMobileOpsFeedback synced): exceptions -> journal lines; Export field (SelectedForExport) - export takes records with Export on. ps_bak120 (0 errors): Export switch in the record header (the list showed it as plain "No") |
| Statuses | All 11 set to Done with a reply in D365FO (SQL: 11 Done, 11 with a reply) |
| Still to test on a device | 1.1.5 menus / Counting title / Export as CSV; unexpected item -> journal line |
| 1.1.5 Counting screen (handheld, spot count BSP-JN-000552030) | PASS - top bar "Counting" with + and ⋮; BSP-JN-000552030 above "0 of 2 counted" |
| 1.1.5 Export as CSV (card ⋮) | PASS - Android save dialog (Downloads, BSP-JN-000552030.csv) -> "BSP-JN-000552030 saved as CSV."; file UTF-8 with BOM, header + 2 lines (batches AN9U, HSB-PAN-001) |
| 1.1.5 ⋮ on Transfer ship before an order | PASS - Report button + ⋮ with Send feedback only. With an order open: not tested (TO-37 already received; test POs 161710-14 have nothing left to receive in the app) |
| Unexpected item (ps_bak119): AN9U counted 3, BSR-11397-0/E0000 x1 recorded as not on the journal, End count at AAC (Review journal counts on) | PASS - no "could not be sent"; SQL: line 3 BSR-11397-0, AAC, E0000, on-hand 0, counted 1; NumOfLines 3. Counts staged in AAC-20261014-1 |
| GAP: at a review store the unexpected item went straight into the journal, past the supervisor | Fixed 1.1.6: sent to the control center with the counts (no source line - Transfer adds it as a line), then marked sent. End count text now says "added to the journal as new lines". Built, tests pass |
| Minor | After End count the card said "Claimed by ... (001) on another device" (the list's copy of the released claim) until the list was refreshed; the server claim was released (SQL) |
| 1.1.6 unexpected item at a review store: spot count BSP-JN-000552031, AN9U x3 + BSR-11397-0/E0000 x2 not on the journal, End count | PASS - dialog: "...sent with the counts and added to the journal as new lines."; staged in AAC-20261014-2 (both lines, journal still 2 lines) |
| Supervisor Transfer of AAC-20261014-2 | PASS - "2 counted line(s) transferred to counting journal BSP-JN-000552031"; SQL: line 3 BSR-11397-0, AAC, E0000, on-hand 0, counted 2; not posted |
| Scanned / typed item not on the journal (user, 14 Oct: "it has to be added as not on this journal") | 1.1.7: opens "Not on this journal" with the item filled in (and the selected location, or the journal's only one) instead of an error to tap. Built, tests pass |
| 1.1.7 scan of an item not on the journal (spot count BSP-JN-000552032, handheld 001) | PASS - scanning BSR-11397-0 opens "Not on this journal" with item BSR-11397-0 and location AAC filled in |
| 1.1.7 typed item not on the journal (HST002 + Enter) | PASS - same dialog, HST002 / AAC filled in |
| 1.1.7 scan of an item on the journal (BSR-47274-0) | PASS - unchanged: "BSR-47274-0 is on 2 lines - pick the batch" |
| Dm001 (configurations C1-C5) recorded as not on the journal | The dialog had no configuration - the line would reach the journal without one. Not recorded |
| User, 14 Oct: no form - add the item, mark it in the list; a legend for the marks | 1.1.8: a scanned / typed item not on the journal is added at once (1, or one more on the same item / variant / location row) and listed first with an amber ⊕ "Not on this journal"; tap it to set the quantity or remove it; a legend (To count / Counted / Not on this journal) above the list; an item with variants asks only which one (the scanned barcode's or the only one is taken without asking); the + form keeps a variant choice. Variant stored on the device (Room 18) and sent (ConfigId/SizeId/ColorId/StyleId). 3 new unit tests |
| ps_bak121 (0 errors) | submitExceptions takes the variant; refuses only an item whose variant is missing |
| 1.1.8 scan of BSR-11397-0 (not on BSP-JN-000552032) twice | PASS - "BSR-11397-0 · 1 - not on this journal, added to the list." then 2; row with amber ⊕ "Not on this journal · Loc AAC", legend To count / Counted / Not on this journal |
| 1.1.8 scan of Dm001 (5 configurations) | PASS - "Which Dm001?" with C1-C5; C4 -> row "Not on this journal · Configuration: C4 · Loc AAC" |
| 1.1.8 tap the row, quantity 5 | PASS - row shows 5 |
| BUG: the new row sat half under the legend (list kept its scroll) | Fixed 1.1.9: newest row first, list scrolls to it |
| BUG: End count with only items not on the journal: "Nothing has been counted yet - there is nothing to submit", Submit disabled | Fixed 1.1.9: allowed ("Only the items not on this journal are sent"); End count accepts it |
| Counting header (user, 14 Oct: round progress with the percentage inside, presentable) | 1.1.9: one card - progress ring with %, journal number, "n of m counted", "n not on this journal", By location switch on the right (was three rows) |
| 1.1.9 counting header | PASS - one card: ring 0% -> 50% after AN9U x3, BSP-JN-000552032, "1 of 2 counted", "2 not on this journal" (amber), By location switch; rows not on the journal in full view under the legend |
| 1.1.9 typed HST002 + Enter | PASS - "Which HST002?" (variant question). BUG: HST002 has many variants - the list ran off the dialog and did not scroll (5 reachable); the typed text stayed in the scan box and filtered the list. Fixed 1.1.10: scrolling list (max 360dp) with a filter when more than 5; box cleared after the item is added or the choice cancelled |
| End count of BSP-JN-000552032 (AN9U x3; not on the journal: BSR-11397-0 x5, Dm001 C4 x1) at AAC | PASS - "Only the counted lines are sent" + 2 items; staged in AAC-20261014-3 (3 lines, Dm001 with configuration C4) |
| Transfer AAC-20261014-3 | PASS - "3 counted line(s) transferred to counting journal BSP-JN-000552032"; SQL: line 3 BSR-11397-0 AAC counted 5 (no batch - plain scan), line 4 Dm001 C4 AAC counted 1. Status of the Dm001 C4 line blank: it matched the C4 stock record BSP-JN-000552027 made before ps_bak115 (blank status) - data from the earlier bug |

### 20 Oct - Restyle (user: "apply this design to the android app", from the mock in the design canvas)

| Change | |
|---|---|
| Theme | 1.1.11: Indigo (#232A7C, dim #E1E0F6) replaces Maroon as the app's fixed palette; page #F3F3F6, text #14183A / #3E4360 / #5A5F7A, softer borders |
| Bottom bar | Outline icons, active tab navy + bold (no filled circle) |
| Home | "Hello <first name>" with initials in the bar; illustrated band with a search field (opens On-hand), the warehouse and Change; "My Services" - the D365FO menu as white cards with a coloured badge, one line and an outlined Open (Planned / Not enabled when unusable); "Your Most Used" shortcuts (device usage); "Report / Suggest" (Send feedback). Menu, permissions and order unchanged. Menu folders use the same cards |
| Not done | Font (the mock's Nunito Sans would have to be bundled - system font kept); floating Scan button |
| 1.1.11 on the emulator (002) | Colours, bottom bar, band + search OK. Fixed in 1.1.12: greeting wrapped ("Hello / Demo1Superviosr"); "Inventory management" broke mid-word beside the badge; folders said only "1 item"; the shelf drawing sat behind the warehouse name |
| 1.1.12 on the emulator | PASS - greeting on one line; badge above the title; folders list their contents ("Transfer — Ship, TO creation, Purchase return"); drawing on the right; "Change warehouse" button; Home opens at the top. Folder screen (Inventory management) same cards. Store count and Transfer ship in the new colours |
| Inventory count top bar: four icons broke "Inventory count" mid-word | Fixed 1.1.13: Store count and Count accuracy moved into the ⋮ menu (+ New spot count stays) |
| Store count lines on the bare background | 1.1.13: a white card per line |
| User, 20 Oct: "lots of things not matching with mock screen" | 1.1.14: band = search + My Dashboard / More; dashboard widgets with figures (counts in progress, POs due today, transfers to ship, Activity) and outlined actions; Your Most Used always shown; What's New; bottom bar Home / Services / Activity / More + round Scan button (On-hand); new Services screen (accordion of the D365FO menu); every scan card white with an outlined field (was the accent gradient) |
| 1.1.14 on the emulator (002) | Dashboard: Inventory count 0 journals, Transfers 9 to ship, Activity 1 posting need you (PO receiving hidden - not permitted for 002). Services accordion OK; Transfer ship white scan card OK. Issues: AAC chip broke the name mid-word; Home again opened scrolled down; More was the old Settings list |
| Fixed 1.1.15 | Warehouse under the name ("Warehouse AAC · Change"); Home held at the top until scrolled; More = greeting bar, no back arrow, grouped lists (Settings: My warehouse & sign-in, Data posting; Support: Send feedback, Logs) with icons and chevrons, red outlined Sign out (asks first) |
| Still open | Font (Nunito Sans needs bundling - waiting for the user's OK to download it); Report / Suggest has one card |
| 1.1.15 on the emulator | PASS - Home opens at the top; "Hello Demo1Superviosr" with "Warehouse AAC · Change" under it; More: greeting bar, Settings / Support groups with icons, red outlined Sign out |
| 1.1.16 | Font: Nunito Sans bundled (variable TTF from github.com/google/fonts, SIL OFL - res/font/nunito_sans.ttf, licence in assets/licenses), every text style - PASS on the sign-in screen. Report / Suggest: Discrepancy card (opens the existing discrepancy report; only when that feature is on) + Share feedback. Shelf drawing moved left of More; Activity widget says "Refused" for refused postings |
| 1.1.16 on the emulator (002) | Nunito Sans on every signed-in screen; Report / Suggest: Discrepancy + Share feedback; drawing clear of More; Activity "Refused · 1 posting". "Offline" seen: D365FO dev VM was down (connect timeouts since 18:00) - correct |
| Second reference video (Services / More detail) | 1.1.17: Services - one group open at a time, sliding open, its tasks in a light inset panel with dividers (no chevrons); More - the row's value on the right (warehouse code, Data posting On/Off), the app logo under the version |
| 1.1.17 on both (001 handheld, 002 emulator) | PASS - Home opens at the top on both; dashboard live: 001 Inventory count 4 journals, PO receiving 0 due today, Transfers 9 to ship, Activity 0 waiting; 002 without PO receiving, Activity "Refused · 1 posting". Services: one group open at a time, inset panel. More: AAC / On values, logo. Dashboard Open -> Inventory count list; Scan button -> On-hand, BSR-47274-0 -> 17 available at AAC |
| Seen on 1.1.17 | Result cards still outlined; Activity tab had a back arrow, plain headings |
| Fixed 1.1.18 | Cards borderless app-wide (shared raised modifier; counting rows and header card too); Activity: greeting bar, no back arrow, bold section headings |
| 1.1.18 on both | PASS - Activity: greeting bar, bold "Counting", borderless cards; counting list borderless; counting screen (BSP-JN-000552000): ring 6%, "15 of 231 counted · 1 not on this journal", white scan card, legend, borderless rows; Store count (from the list's ⋮) white scan card + line cards |
| BUG: a code that is no item at all became a "Not on this journal" row ("ddgter122" on BSP-JN-000552000) | Fixed 1.1.19: added only when it is a known item (item cache); otherwise "<code> is not an item in D365FO - nothing added." (with no item cache yet, still added) |
| 1.1.19 on the handheld (BSP-JN-000552000) | PASS - scan ZZXX999: "ZZXX999 is not an item in D365FO - nothing added."; scan BSR-11397-0 (real item, not on the journal): "BSR-11397-0 · 1 - not on this journal, added to the list." |

### 20 Oct — restyle checks, 1.1.19 / 1.1.20 (handheld, 001)
- Transfer orders inquiry → BSP-TO-000000004 opens Transfer — Ship with transfer, bin and item filled in (single line). The quantity dialog's third unit chip sits below the fold on a 720px screen, but the dialog body scrolls, so it is reachable. Not posted; Start over used.
- Ship ⋮ menu: Report discrepancy, Start over, Send feedback. Pass.
- Home Discrepancy card opens the Report discrepancy dialog (not submitted). Pass.
- Greeting "Warehouse AAC · Change" opens Change warehouse (AAC current, AAS). Pass.
- 1.1.20: removed the More button from the My Dashboard band (user request; Services is a tab already). Installed on both devices; Home verified on the handheld.
- 1.1.21–1.1.23: dashboard band background redrawn edge to edge (rack plus forklift, not a box grid); icons switched to the mock's own line icons (components/LineIcons.kt: badges, bottom bar, More rows, search field).
- 1.1.23: the dashboard search bar opens a new Search screen (items, orders, journals), no longer On-hand. Item suggestions come from the device; scan/Enter calls the new AOS HSMobileOpsSearchSvc.search, which returns a PO/TO/journal by number, or the item plus its open PO lines, transfer lines and counting journal lines at the warehouse. Tapping an order opens it in Receive/Ship/Receive transfer. AOS objects prepared (deploy ps_bak122), NOT yet deployed: the VM RDP session wasn't connected.
- HSMobileOpsSearchSvc deployed (ps_bak122 copied the files but failed to compile on a generated attribute; ps_bak123 fixed it: Errors 0, iisreset). Tested on the handheld (1.1.23):
  - BSR-10113-0: item card (AAC available -2 / on hand -2 Pack), BSP-TO-000000004 To WMS, to ship 1 Pack, BSP-JN-000552022 counted 2 Pack. Pass.
  - Tapping the TO opens Transfer — Ship on BSP-TO-000000004 (Start over, not posted). Pass.
  - BSP-TO-000000002 (from WMS, to receive), BSP-JN-000552032 (spot count, 4 lines, open), BSP-PO-000161711 (BinSina, open). Pass.
  - Partial "BSR-10113" found nothing → 1.1.24 lists matching items ("Did you mean") instead; also "Vendor 19721" and "2 Pack counted" labels. 1.1.24 installed, not yet re-tested (devices signed out).
- Search scroll crash on BSR-47274-0 (duplicate LazyColumn key: one journal holding the item twice at one location) fixed in 1.1.25: rows keyed by position, same journal/location merged with counted summed. Verified on the emulator (11 journals, scrolls).
- PO lines in search used RemainPurchPhysical, which never drops because the app registers without a packing slip, so search showed lines Receiving refused ("nothing left to receive"). Fixed on the AOS (ps_bak124, Errors 0): search uses HSMobileOpsReceivingService.orderedQtyByLine / stagedQtyByLine (made internal), and a PO searched by number reads "Received" when nothing is left. Verified: BSR-47274-0 no longer lists the registered POs; BSP-PO-000161707/10/11 read Received.
- 1.1.26: tapping a result that can't be opened says why ("... is received in full - nothing to do on it here"). Verified on the emulator.
- 1.1.27: search respects Menu access (a result for a module the worker can't use says so instead of opening it). 1.1.28: a journal on the device opens at its lines instead of the journal list. Both installed on the emulator; not yet verified (signed out). Handheld disconnected from USB at 1.1.26.
- Found: release builds keep the session in memory only, so every install signs the device out (by design).
- 21 Oct, emulator (002, 1.1.28): journal result BSP-JN-000552020 opens straight at its lines (1 of 3 counted). Pass. BSP-TO-000000002 (to receive) as 002, who lacks Transfer — Receive: "Transfer — Receive isn't on your menu". Pass. More, Services and Activity tabs checked with the line icons. Pass.
- 1.1.29 built (Inventory folder badge green like the mock, it was brown), not installed yet: an install signs out. Handheld not visible to adb on 21 Oct.
- 21 Oct, handheld (001, 1.1.26): scanning BSR-47274-0 into Search shows 17 Pack and 11 journals, no stale PO lines (AOS fix confirmed). The keyboard stayed open over the results after a hardware scan; fixed in 1.1.30 (a finished search closes it). BSP-PO-000161712/13 read Received; no open test PO left to try Receiving from search. Receive and PO creation screens checked: PO creation's vendor search was still the old grey field; 1.1.30 puts it (and Purchase return's) in the white "Choose vendor" card.
- 1.1.29 emulator: Inventory management badge green. Pass.
- 1.1.30 installed on both devices (signs them out).
- 21 Oct, Pixel 8 Pro (3B111FDJG003YY, new device, 001, 1.1.30, 1344x2992): Home scales well (forklift band, line icons, widgets). Search BSR-10113-0: keyboard closed after the search (mInputShown=false), results fully visible. Pass. PO creation: vendor search in the white "Choose vendor" card. Pass. The grey ring on Search's Back arrow after the search is key-navigation focus from the adb Enter key event; not expected from a touch user.
- Pixel 8 Pro, continued: counting journal list and the counting screen (BSP-JN-000552005, 2040 lines, claimed by 001 on this device; nothing counted) lay out well; variant rows wrap. Ship quantity dialog fits without scrolling on this screen. Opened from search on BSP-TO-000000004; Cancel and Start over, not posted. Pass.
- 21 Oct, 1.1.31: own visual identity, so the app isn't a copy of the reference app: petrol palette (#0B5563) instead of navy/lavender; solid petrol dashboard band with the rack/forklift in faint white and a "Today" heading; red (logo) rounded-square Scan button instead of a round teal one; "Good morning, <name>" with a rounded-square avatar; dashboard widgets with the figure left-aligned and a filled action; sections renamed Quick access / Tips / Tell us; tabs Home / Modules / Activity / Settings; "All modules". Installed on the handheld and emulator; not yet seen (sign-in needed).
- Feedback de97da6a (handheld, 1.1.31, Inventory count: "use the same styling for this screen as well"): 1.1.32 gives the journal cards the counting screen's progress ring beside "X of Y counted", bold journal numbers, and a tonal "Get data" button instead of a solid one on every card. Installed on the handheld and emulator; not yet seen (sign-in needed). Feedback status still New until verified.
- 1.1.33: journal list made compact on request: 44dp ring left of the journal number, Get data as a small tonal button at the card's right for journals not yet on the device, tighter padding, smaller status text, "1 line" singular, "Reopen" instead of "Reopen counting".
- Feedback 01a61693 (handheld, 1.1.32, Home: "make tiles vertical"; confirmed with the user it means the dashboard widgets): 1.1.33 stacks them one per row - badge, title and label left, figure and a small tonal action right. Installed; not yet seen.
- 21 Oct feedback round (1.1.33, handheld) fixed in 1.1.34:
  - 4fd56ed0 Home "give the count and open marker together": widget right side is one tonal marker "4 journals ›" (action word kept as the click label).
  - 55cbfd35 "100 is over layered with green ring": ring text sized to the ring, thinner stroke under 56dp, a green tick at 100%.
  - dccf2a1e "too much info on each card": journal card meta is description · "X of Y counted" (or lines); no date, own-claim timer, class/tolerance; statuses shortened (Submitted, Sent for review, Posted - closed, "1 unexpected item not sent", Send again).
  - User request: six short tips in a swipe row (title + one line) instead of one long card; Tell us texts shortened.
  Installed on the handheld and emulator; not yet seen.
- 1.1.35: minimal text everywhere (user, 21 Oct: "everywhere make the text minimal"). About 230 messages, prompts and descriptions shortened across Home (module summaries and long-press descriptions; D365FO table names removed from that dialog), Settings, Receive, Ship/Receive transfer, counting (journal list, counting screen, store and spot count, dialogs), order creation, Activity, Feedback, Search, sign-in, connection status, tour, onboarding, and the data-layer error messages workers see. Behaviour unchanged; tests updated to the new wording (all pass). Installed; not yet seen.

## 8 Nov 2026 - Counting screen layouts by count type (1.4.13, emulator, user 002, AAC)

| Case | Result |
|---|---|
| Triggered count with several lines (BSP-JN-000552045, 8 lines, from Report discrepancy) | Standard layout: progress ring, scan card, list. Pass |
| Triggered count with one line (552045 cut to 1 line on the device only, at a store without bins) | Focused card: TRIGGERED, Location AAC, item, name and batch, large focused Quantity found box, Confirm and end count. No list, ring or + button. Confirm saved the count, started End count and returned to the list. Pass. The DMF import then failed because the device held 1 of the journal's 8 lines, an artefact of the test setup |
| Wall-to-wall (552011 marked wall-to-wall on the device only) | No By location switch; Location AAC with a full-width amber "Location is empty" above the scan card (it was cut off below it at first, fixed); Yes, it's empty on a one-location task returned to the list with End count. Pass |
| Store with bins (AAC) | A one-line triggered count keeps the list, which asks for a bin first (by design) |

The device database was restored afterwards. No wall-to-wall count was started in D365FO, which would have frozen AAC.

## 8 Nov 2026 - Inventory count sub-menus and scan message (1.4.14 release, emulator, user 002, AAC)

| Case | Result |
|---|---|
| Home > Inventory count | Opens the Inventory count page: Store count (no journal needed, becomes a journal in the Stock count control center), Spot count (12 open), Cycle count (7 open), Wall-to-wall count. 12 + 7 matches Home's 19 open. Pass |
| Spot count | Spot journals only; tabs To count / Done; + New spot count in the top bar. Pass |
| Cycle count | Planned and triggered journals, no spot or wall-to-wall; tabs To count / Recount / Done (4); no +. Pass |
| Wall-to-wall count | Tabs To count / Done; "No wall-to-wall count in progress." Pass |
| Store count | Opens Store count ("Count AAC, location by location", Start store count). Pass |
| Scan in a bin on a 360x640dp screen (552008, BIN-A1, HSN001) | The header scrolls to "HSN001 · AAC: 1" and Undo after the scan - they were below the fold before. Undo restored 5. Pass |
