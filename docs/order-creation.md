# PO creation, TO creation and purchase return

Three Home tiles create documents in D365FO from the handheld:

- **PO creation**: choose a vendor, scan the items, enter the quantities, then **Create and confirm purchase order**. D365FO creates the order and confirms it in the same step, and the card shows the confirmation number.
- **TO creation**: choose From and To (both must be the worker's own warehouses), scan items, enter quantities, then create the order. You can ship it straight away with Transfer – Ship.
- **Purchase return** (return to vendor): pick the vendor, or scan the original PO and its vendor is picked. Then choose a return reason and a return action, scan what goes back with its bin, batch or serial, and enter the quantity. **Return and post** makes D365FO create a return order, confirm it and post its packing slip, so the stock leaves the warehouse.

All three work offline. The document is saved on the device and sent as soon as D365FO can be reached. D365FO then prices a PO, checks the stock at From for a TO, and checks the stock for a return.

Sending happens in the background: the screen returns at once with a **Waiting** card, and the card updates itself when D365FO answers. Creating and confirming takes D365FO 20–40 s when warm and over a minute right after an AOS restart. These calls get a 180 s read timeout (`LongPostingTimeoutInterceptor`), so a slow AOS is not cut off and then resent while the first call is still running.

The separate **PO confirmation** tile is gone, because creating a PO now confirms it. Its screen is still in the app: "Confirm it now" on a PO that was created but could not be confirmed (it needs approval first) opens it.

## Prerequisites (D365FO)

1. **Model deployed:**
   - HSMobileOpsOrderCreateService and the contracts HSMobileOpsOrder{Request,Response,Line,Vendor,Item,Summary} and HSMobileOpsReturnReason;
   - service `HSMobileOpsOrderCreateSvc` in `HSMobileOpsServiceGroup`;
   - enum values `HSMobileOpsModule::PO_CREATE` (10), `TO_CREATE` (11), `PO_CONFIRM` (12) and `PURCHASE_RETURN` (13).
2. **Menu access:** in Inventory hub > Setup > Mobile device users > Menu access, add a row for "PO creation", "TO creation" and/or "Purchase return" with Allowed ticked.
   - Menu access is an allow list. Once a worker has any rows, the app shows only the modules ticked Allowed. A worker with no rows sees every module except the order modules.
   - The order modules always need an Allowed row, and the server checks this too.
   - A change shows on the handheld the next time Home opens while online (at most every 30 seconds); no sign-out is needed.
3. **PO creation confirms only approved orders.** With change management on, a new PO that lands In review is created but not confirmed, and the card says so ("Not confirmed").
4. **TO creation needs two or more warehouses** in the worker's Warehouses grid. The From warehouse must have a transit warehouse (Warehouses > Transit warehouse).
5. **Price override is for supervisors only** (IsSupervisor on the mobile user). The worker uses "Set the price instead" when editing a line, and only while online.
6. **Purchase return setup:**
   - return reason codes (Procurement > Setup > Return reason codes);
   - return actions (Return action defaults; BSP has 13, e.g. Damaged, Expired, Return-Sup).
   - The worker picks one of each, and the action goes on every line. D365FO will not post a return line without an action. When none is picked, the server uses Procurement parameters > Return action default (blank in BSP), or the only action if there is just one.
7. **Number sequences:** purchase orders (return orders included), transfer orders and confirmations use the company's normal number sequences.
   - The return's RMA number is the vendor's, when entered (up to 10 characters).
   - Otherwise it is the return order's number part (BSP-PO-000161698 gives 161698), which is how BSP users type it.
   - The packing slip ID is the return order number, so it is always unique.

## Screens

| Screen | Steps |
|---|---|
| PO creation | **Vendor**: search the saved vendor list (330 in BSP, refreshed every 6 h or with the refresh button). **Lines**: scan or type an item; pick a variant if needed; enter a quantity (keypad, with the item's min/max/multiple). The line card shows D365FO's price and discount while online. Then **Create and confirm purchase order**. |
| TO creation | **Route**: From and To chips (To is pre-selected when there is only one other warehouse). **Lines**: scan an item; pick a variant; pick a batch or serial for tracked items; enter a quantity. While online, the available stock at From is shown and a larger quantity is refused. Then **Create transfer order**, then **Ship it now**. |
| Purchase return | **Vendor**: scan the original PO (its vendor is picked) or search the vendor list. **Return**: reason chips and action chips from D365FO (saved for offline); optional vendor RMA number. **Lines**: scan an item; pick a variant; while online, pick the stock it comes from (one row per bin, batch and serial, with what is available; a single row is picked for you); enter the quantity, up to what is there. Then **Return and post**, with a confirmation dialog. |

Drafts are kept per worker on the device, so leaving the screen loses nothing. Documents made on the device are listed with a status:

- **Waiting**: saved, not sent yet.
- **Confirmed** (PO) / **Created** (TO) / **Posted** (return): shows the D365FO number, and the confirmation number or packing slip.
- **Not confirmed** / **Not posted** (amber): created in D365FO, but a later step did not go through. The message gives D365FO's reason, and a resend of the same document finishes it.
- **Refused**: shows D365FO's reason.

## Service: `api/services/HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/<operation>`

Every operation takes `{"_request": { ... }}` with `DataAreaId`, and returns `Success`, `ErrorCode` and `Message`.

| Operation | Request | Returns |
|---|---|---|
| `getModulePermissions` | UserName | Permissions[] (Module, IsAllowed) |
| `listVendors` | Skip, Take | Vendors[], HasMore. Vendors on hold are left out. |
| `getOrderItem` | ItemId | Item: units, active dimensions, min/max/multiple/standard qty, Variants[] |
| `priceLines` | VendorAccount, WarehouseId, DeliveryDate, LinesJson | Lines[] with Price, DiscPct, LineAmount, PriceSource; OrderTotal; CurrencyCode |
| `createPurchaseOrder` | IdempotencyKey, UserName, VendorAccount, WarehouseId, DeliveryDate, LinesJson | DocumentNumber, ConfirmationNumber, AlreadyCreated, Lines[] (priced), OrderTotal. Message "Created; not confirmed - …" when confirming failed |
| `getStock` | ItemId, ConfigId/SizeId/ColorId/StyleId, WarehouseId, GroupByLocation | Lines[] per batch / serial (and bin with GroupByLocation: LocationId) with Available |
| `createTransferOrder` | IdempotencyKey, UserName, FromWarehouseId, ToWarehouseId, ShipDate, ReceiveDate, LinesJson | DocumentNumber, TransitWarehouseId, AlreadyCreated |
| `listReturnReasons` | — | Reasons[] (Code, Description), Actions[] (Code, Description) |
| `createPurchaseReturn` | IdempotencyKey, UserName, VendorAccount, WarehouseId, ReturnReasonCode, ReturnActionId, RmaNumber, LinesJson (with LocationId) | DocumentNumber (the return order), PackingSlipId, AlreadyCreated. Message "Confirmed; packing slip not posted - …" or "Created; not confirmed - …" when a later step failed |
| `listPurchaseOrdersToConfirm` | UserName, WarehouseId, Skip, Take | Orders[] (PurchId, vendor, delivery date, line count, total, approval status, created by), HasMore |
| `getPurchaseOrder` | UserName, PurchId | Orders[0] (summary), Lines[] with prices, OrderTotal |
| `confirmPurchaseOrder` | IdempotencyKey, UserName, PurchId | DocumentNumber (the PO), ConfirmationNumber, AlreadyCreated |

**Lines travel as a JSON string, `LinesJson`, not as an array.** The AOS JSON binding cannot read a list of a custom class on the way in: it fails with "Unable to cast JObject to HSMobileOpsOrderLine". This is the same issue as `HSMobileOpsPostRequest.LinesJson`. `HSMobileOpsOrderRequest.parmLines()` parses the string.

**Idempotency:**

- A create that repeats an `IdempotencyKey` returns the first document's number with `AlreadyCreated: true`.
- The key is recorded in HSMobileOpsPostLog as `PurchaseOrderCreate`, `TransferOrderCreate`, `PurchaseReturnCreate` or `PurchaseOrderConfirm`.
- The document is committed before confirming and posting. A resend of a PO or return then finishes whatever is left (confirmation, return action, packing slip), and each step is skipped when it is already done.

**Purchase return, in D365FO terms:**

- `PurchTable`: PurchaseType ReturnItem, ReturnItemNum (RMA), ReturnReasonCodeId.
- `PurchLine`: negative PurchQty and ReturnActionId. Its InventDim carries the bin, batch and serial picked. On a warehouse-management warehouse it also carries the inventory status of that stock (`stockStatus`). Without the status, posting fails with "Inventory dimension Inventory status must be specified".
- Every line is checked against what is physically available before anything is created (`availableAt`).
- Then: PurchFormLetter PurchaseOrder (confirm), then PurchFormLetter PackingSlip.

### Error codes

| Code | Meaning | App handling |
|---|---|---|
| INVALID_REQUEST | Missing company, key, vendor or warehouse | Refused |
| NOT_PERMITTED | No allowed Menu access row | Refused |
| USER_NOT_FOUND / VENDOR_NOT_FOUND / ITEM_NOT_FOUND / WAREHOUSE_NOT_FOUND | Unknown record | Refused |
| REASON_NOT_FOUND | The return reason does not exist | Refused |
| ACTION_REQUIRED | No return action given, and no default to use | Refused |
| VENDOR_ON_HOLD | Vendor is blocked | Refused |
| SAME_WAREHOUSE | From = To | Refused |
| WAREHOUSE_NOT_ASSIGNED | From, To or the return's warehouse is not in the worker's Warehouses | Refused |
| NO_TRANSIT_WAREHOUSE | From has no transit warehouse | Refused |
| INSUFFICIENT_STOCK | A line asks for more than is available (at From for a TO; in the bin / batch / serial for a return) | Refused, with the shortage in the message |
| PRICE_FAILED | Prices could not be read (preview only) | Ignored; D365FO prices the order when it creates it |
| CREATE_FAILED | D365FO threw an error while creating | Refused, with D365FO's own messages |
| NOT_FOUND | No such purchase order | Refused |
| NOT_APPROVED | The PO is in Draft / In review / Rejected | Refused |
| CONFIRM_FAILED | The confirmation (PurchFormLetter) failed | Refused, with D365FO's own messages |
| RETRY | Deadlock or update conflict | Sent again automatically |

### JSON samples

`createPurchaseOrder` request:

```json
{"_request": {
  "DataAreaId": "BSP", "UserName": "002", "DeviceId": "…",
  "IdempotencyKey": "po-3f6c0c1e-…", "VendorAccount": "19721",
  "WarehouseId": "AAC", "DeliveryDate": "2026-10-06",
  "LinesJson": "[{\"ItemId\":\"Dm001\",\"ConfigId\":\"C1\",\"Qty\":3.0,\"UnitId\":\"Pieces\",\"Price\":0.0,\"PriceOverride\":false}]"
}}
```

Response:

```json
{"Success": true, "ErrorCode": "", "Message": "", "DocumentNumber": "BSP-PO-000161694",
 "ConfirmationNumber": "BSP-PO-000161694-1", "AlreadyCreated": false,
 "CurrencyCode": "AED", "OrderTotal": 300.0,
 "Lines": [{"LineNumber": 1, "ItemId": "Dm001", "Qty": 3, "UnitId": "Pieces", "Price": 100,
            "LineAmount": 300, "PriceSource": "Base purchase price"}]}
```

`createPurchaseReturn` request:

```json
{"_request": {
  "DataAreaId": "BSP", "UserName": "002", "IdempotencyKey": "…",
  "VendorAccount": "19721", "WarehouseId": "AAC",
  "ReturnReasonCode": "6", "ReturnActionId": "Damaged", "RmaNumber": "",
  "LinesJson": "[{\"ItemId\":\"Dm001\",\"ConfigId\":\"C1\",\"LocationId\":\"AAC\",\"Qty\":1.0,\"UnitId\":\"Pieces\"}]"
}}
```

Response:

```json
{"Success": true, "DocumentNumber": "BSP-PO-000161697", "PackingSlipId": "BSP-PO-000161697", "AlreadyCreated": false}
```

Refusal example:

```json
{"Success": false, "ErrorCode": "INSUFFICIENT_STOCK", "Message": "Not enough stock at AAC: Dm001 C1: 7 asked, 5 available"}
```

## Offline behaviour

**What works offline:**

- Vendors come from the saved list.
- Items come from the device's barcode and item cache. An item has to have been looked up once while connected.
- A scanned original PO, and the return reasons and actions, come from the saved copy of D365FO's last answer.
- The document is queued: SyncQueue operations `CREATE_PURCHASE_ORDER`, `CREATE_TRANSFER_ORDER` and `CREATE_PURCHASE_RETURN`, sent by OrderSender.

**What is unavailable offline:**

- **Prices:** the line shows "Priced by D365FO when the order is sent".
- **Price override.**
- **The stock check:**
  - For a TO, D365FO checks the stock at From when the order arrives, and refuses it with INSUFFICIENT_STOCK if it is short.
  - A return line made offline names no bin. D365FO checks the warehouse's stock and takes the stock's status.

When the document is sent, the card changes from Waiting to its result.

While D365FO is unreachable, lookups no longer wait out a timeout: they are answered from the saved copy at once (`AosReachabilityInterceptor`). A background probe notices recovery (3 s after the drop, then every 15 s), and the queue then sends what is waiting.

Two things do **not** mark D365FO unreachable:

- a call the app cancelled itself;
- a call that connected but answered slowly (a cold AOS, or a long posting).

## Test cases

| # | Case | Expected | Status |
|---|---|---|---|
| 1 | Worker without a Menu access row | No PO/TO/return tiles; the server returns NOT_PERMITTED | Tiles hidden ✓ |
| 2 | Allow PO creation and TO creation for 002 | Both tiles appear right after sign-in | ✓ (also after a fresh sign-in, without a restart) |
| 3 | Vendor search "19721" | BinSina - Central Purchase | ✓ (typing keeps focus as the recent orders above disappear, 1.0.9) |
| 4 | Variant item HSV001 | Variant picker; line shows C1 S Red Slim | ✓ |
| 5 | Online pricing, item without a price | 0.00 AED, "No price found" | ✓ |
| 6 | Create PO | BSP-PO-000161688, 1 line, HSV001 C1 S Red Slim × 6 at AAC, Approved / Open order | ✓ |
| 7 | Item with a trade agreement for the vendor | Agreement price, source "Trade agreement - this vendor" (or price group / all vendors) | Not testable in BSP: its 1,738 purchase price agreements all carry 0.00. The base-price path is verified (Dm001 → 100.00, source "Base purchase price"). |
| 8 | Supervisor overrides a price (Dm001, D365FO price 100.00 → 12.50) | The line keeps the entered price in D365FO | BSP-PO-000161689 at 12.50 ✓ |
| 9 | Vendor on hold | Not listed; a create returns VENDOR_ON_HOLD | To test |
| 10 | TO, item with no stock at From (HSV001 at AAC is −270) | "No stock of … at AAC" | ✓ |
| 11 | TO quantity above available (7 of 5) | "Only 5 available at the From warehouse"; Add disabled | ✓ |
| 12 | Create TO | BSP-TO-000000012, AAC → ABB, Dm001 C1 × 2 | ✓ |
| 13 | Ship it now | Transfer – Ship opens TO-12 at its line | ✓ |
| 14 | Batch- or serial-tracked item in a TO (BSR-10718-0 at AAC) | Stock picker per batch/serial; the line carries it | Picker showed Batch 1624 · 3 available; the quantity step showed the batch, and 6 was refused. Stopped before creating an order (real item) ✓ |
| 15 | PO offline (Wi-Fi off) | Saved as Waiting, no prices; Created with prices once online | Vendor and item (Dm001 C2 × 3) from the device cache, saved as Waiting. Created on its own as BSP-PO-000161690 once Wi-Fi was back, priced 300.00 AED ✓ |
| 16 | TO offline, then the stock is short | Waiting, then Refused with INSUFFICIENT_STOCK | To test on the device |
| 17 | Same order sent twice (retry) | One D365FO order; AlreadyCreated on the second send | Covered by idempotency |
| 18 | PO confirmation list at AAC (screen kept for "Confirm it now") | Approved, unconfirmed POs only, with line count, total, delivery date, creator | 3 listed ✓ |
| 19 | Open a PO | Header, approval status, lines with D365FO prices, total | ✓ |
| 20 | Confirm BSP-PO-000161688 (cold AOS) | Confirmed, confirmation number shown | The call timed out after 60 s and the queue retried; the server answered "already confirmed" (idempotent). Confirmation BSP-PO-000161688-1, status Confirmed in D365FO ✓ |
| 21 | "Confirm it now" on a created PO | PO confirmation opens on that PO | BSP-PO-000161689 confirmed (BSP-PO-000161689-1) in 0.8 s ✓ |
| 22 | Worker without a PO_CONFIRM row | No confirm link; the server returns NOT_PERMITTED | Same mechanism as #1 |
| 23 | Confirm a PO that is in review | NOT_APPROVED; the app shows "only an approved order can be confirmed" | BSP-PO-000160874 (In review): no Confirm button, the message shown ✓. The server-side refusal is covered by unit tests |
| 24 | Confirm offline | Waiting, then Confirmed once online | Same queue as #15 (tested) |
| 25 | Release build 1.0.3 (R8), signed in as 002 | Everything works as in debug | PO BSP-PO-000161691 priced 100.00 ("Base purchase price"), created and confirmed (BSP-PO-000161691-1); TO BSP-TO-000000013 with the stock check; vendor list and permissions downloaded ✓ |
| 26 | Create and confirm a PO (warm AOS, 002) | One step: Confirmed with the confirmation number | BSP-PO-000161694, Dm001 C1 × 3, 300.00 AED, "Confirmation BSP-PO-000161694-1"; the screen returned at once, the card went from Waiting to Confirmed after 37 s ✓ |
| 27 | Create and confirm on a cold AOS (just restarted) | No duplicate; confirmed | BSP-PO-000161693, confirmed in D365FO. Before the 180 s timeout the app cut the call at 60 s and the resend met the first call half-done, so the card showed Created without the number. Fixed by the long timeout (1.0.9) |
| 28 | Pricing answer arriving after Create | The draft does not come back | Before 1.0.9 the late price answer re-saved the draft (a second create would duplicate the PO); the price lookup is now cancelled on create and on discard ✓ |
| 29 | Purchase return tile | Only with an Allowed "Purchase return" row | 002 (row added) sees it; PO confirmation tile gone ✓ |
| 30 | Return: scan the original PO | Its vendor is picked | BSP-PO-000161694 → 19721 BinSina ✓ |
| 31 | Return reasons and actions | D365FO's lists, reasons in number order | 1, 2 … 10; 13 actions ✓ |
| 32 | Return stock picker | One row per bin / batch / serial with what is available | Dm001 C1 at AAC: one row "Bin AAC", 6 available, picked automatically ✓ |
| 33 | Return and post (online) | Return order created, confirmed, packing slip posted | BSP-PO-000161697: Received, action Damaged, status Available, packing slip posted ✓ (161695 and 161696, from before the action / status fixes, stayed confirmed and not posted) |
| 34 | Return offline (simulated outage, debug) | Waiting; posted once D365FO answers | Vendor from the saved PO, reasons/actions from the saved copy, no bin. Sent 10 s after the outage ended, BSP-PO-000161698 posted, RMA 161698, packing slip BSP-PO-000161698 ✓ |
| 35 | Return more than is in the bin | Refused on the device (quantity above available), and by the server (INSUFFICIENT_STOCK) | Keypad limit shown ("6 available") ✓; server check by code review |
| 36 | Release build 1.0.9 | Installs over 1.0.8; cold start | Installed; cold start 1.0–1.2 s ✓. Needs a sign-in to test further |
