# Mobile Operations – UI redesign brief ("Focus" design)

This brief replaces the earlier version. Build exactly what is described here.

## 1. Goal

Restyle the handheld app (Kotlin + Jetpack Compose, `com.d365fo.mobileops`) to the **Focus** design: one item, one big number and one result per screen, for a fast warehouse worker on a portrait handheld with a hardware scanner.

The **behaviour of data does not change**. Only presentation, navigation and the scan/interaction rules below change.

## 2. How to work

1. Read this brief and the files listed in section 13 before planning.
2. Work phase by phase (section 12). For each phase, first reply with the files you will add or change and what changes in each. **Do not edit until I approve the plan.**
3. After each phase: build, fix warnings you introduced, and list what to check on the device.
4. If the code disagrees with this brief (a field, a status or a step that does not exist), stop and ask. Do not invent behaviour.

## 3. Ground rules

- Do not change repositories, DAOs, Room entities, network/D365FO services, sync, posting or licensing logic. ViewModels may get new UI state fields derived from existing data only.
- Portrait only. No tablet or landscape layouts.
- Never truncate data. Codes, descriptions and document numbers wrap; no ellipsis anywhere.
- Touch targets: min 56dp; primary buttons 64dp high.
- Status is always icon + word, never colour alone.
- No flashing, blinking or looping animation. No pure black (#000) or pure white text on colour; use the tokens.
- Text sizes in `sp`, honour the existing `DisplayScale`.

## 4. Interaction rules (most important)

1. **No instructions.** The app never tells the worker which item, bin or location to scan, and never shows "next item", "expected", "go to bin" or a suggested route. Screens show only the result of what he scanned and how much is left.
2. **Any order.** Every process accepts lines, items and bins in any order.
3. **Scan anywhere.** The hardware trigger works on every screen without tapping a field. Scans arrive through the existing DataWedge intent (`com.mobileops.SCAN`) and go through `ScanRouter` (section 8).
4. **Scan pill.** Each flow screen has one pill near the bottom reading **"Scan or type"**. It shows a barcode icon and a keyboard icon. Tapping it opens the keyboard; Enter is treated exactly like a scan.
5. **Quantity.** The quantity comes from the barcode/GS1 or defaults (1, or the item minimum when creating a PO). The worker changes it by **tapping the big number**, which opens a number-pad bottom sheet (`Set` confirms).
6. **Defaults, no extra steps.** Batch/serial come from the document or shipment; location/bin come from the scanned label; count number is generated and reason = last used; transfer route is pre-filled; single-choice steps are skipped.
7. **Auto-finish.** A receipt, transfer receipt or shipment posts automatically when nothing is left. Store count sends a bin when another bin is scanned. Cycle count sends each bin and the journal when complete.
8. **Hold to confirm** only for irreversible actions: **End count** and **Post return** (1-second hold, progress fill, no dialog).
9. **Errors** show in the result band as what went wrong ("Not on this PO", "Not on this journal", "Not enough stock"). Nothing is added. No suggestion of what to scan instead. No dialog to dismiss; the next scan clears it.
10. **Lines on demand.** Item/bin lists for a document are shown only on the Lines screen, opened with the **Lines** button.
11. **Two buttons maximum** at the bottom of a flow screen, normally **Lines** and **Undo** (undo last scan). Decisions use two buttons with the safe option left (white) and the action right (orange).

## 5. Theme

Add to `ColorPalette` and make it the default:

```kotlin
INDUSTRIAL("Industrial", Color(0xFF1E3A8A), Color(0xFFDBEAFE)),
```

Tokens (add to `Theme.kt` as a `FocusColors` object or extend the existing tokens; do not hard-code in screens):

| Token | Hex | Use |
|---|---|---|
| page | #F8FAFC | Screen background |
| surface | #FFFFFF | Buttons, menus, sheets |
| ink | #1E293B | Main text |
| inkSoft | #334155 | Descriptions |
| muted | #475569 | Secondary text, counts |
| caption | #64748B | Uppercase captions, units |
| divider | #E2E8F0 | Row dividers, scan pill background, progress track |
| navy | #1E3A8A | Brand, icons, big quantity, active progress |
| navyDim | #DBEAFE | Info band |
| navyText | #BFDBFE | Secondary text on navy panel |
| action | #EA6A12 | Primary/decision buttons, keyboard focus border |
| okBg / okIcon / okText | #DCFCE7 / #16A34A / #14532D | Success band |
| badBg / badIcon / badText | #FEE2E2 / #DC2626 / #7F1D1D | Error band, Activity count, Sign out |
| warnBg / warnIcon / warnText | #FEF3C7 / #D97706 / #78350F | Warning band, over/short quantity |
| infoBg / infoIcon / infoText | #DBEAFE / #1E3A8A / #1E3A8A | Info band |
| availableQty | #15803D | On-hand available number |

Fonts:
- Keep Nunito Sans for all text.
- Bundle **Barlow Condensed** (SemiBold, Bold) as `CodeFont` for item codes, document numbers, bin codes, counts and quantities.

Type scale (sp): top-bar title 18 bold · band title 19 extra-bold / band detail 14 · caption 14 bold uppercase, letter-spaced · code 50 CodeFont · description 19 semibold · big quantity 96 CodeFont · unit line 15 bold · list row title 19 bold or 26 CodeFont · list count 30 CodeFont · button 18 bold.

Shapes: buttons and bands 16dp corners; Home panel 24dp; scan pill fully rounded; bottom sheets 24dp top corners.

## 6. Screen anatomy

### Flow screen (`FocusScreen` scaffold)
Top to bottom:
1. **FlowTopBar** 56dp: back arrow (48dp target), title, right-aligned counter (e.g. `1 / 2`, `BIN-A1`, `7 / 18`).
2. **ProgressLine** 6dp, rounded, navy on divider. Only when the document has a known total.
3. **ResultBand**: rounded 16dp card, 20dp side margin, icon 28dp + title + detail line. Kinds: ok, bad, warn, info.
4. **FocusCenter** (fills remaining space, centred): caption, code, description, big quantity, unit line. The unit line ends with "· tap to change" when the quantity is editable. In a "job opened" state it shows the size of the job instead (e.g. caption `TO RECEIVE`, code `2 lines`, description `6 units`).
5. **RemainingLine**: e.g. `1 line left`, `11 bins left`, `1 bin sent`. Never names an item or bin.
6. **ScanPill** 60dp.
7. **ActionRow**: up to two 64dp buttons, 16dp gap, 20dp side padding.

### Home
- Header 56dp: `{user} · {warehouse}` left, `● Online` (green; grey "Offline" when offline) and a **hamburger** right.
- Hamburger menu: **Settings**, **Sign out** (red). Nothing else.
- **On-hand panel**: navy rounded card, package icon, title "On-hand", subtitle "How many, and where?", white scan pill "Scan or type item".
- List rows (64dp, icon, title, right-aligned count in CodeFont 30sp, chevron): **Receive** (open POs + incoming transfers), **Ship** (open transfers), **Count** (open counts + journals), **Create** (no count), **Activity** (count of failed sends in **red text**; no badge or pill; hidden when 0), **Enquiry** (grey icon, no count).
- **No bottom navigation bar and no red scan FAB.** Remove `AppBottomBar` and the FAB from `AppNavGraph`.
- Scanning on Home: item → On-hand; PO → PO receiving; transfer → ship or receive (by direction); journal → cycle count; bin → location inquiry.
- Modules switched off for the user stay hidden, as today.

### Sign in
- Hamburger at top right opening a menu with one item: **Connection settings**.
- Centered app logo `ic_app_logo` (≈120dp wide), app name **Mobile Operations**, `Version x.y.z · ● Online`. Keep the existing logo asset and app name.
- User and PIN fields (60dp, 16dp corners), orange **Sign in** button at the bottom. Badge scan into the User field still works.
- **Connection settings** screen: keep the existing fields and logic (from the current not-configured / connection flow); only restyle. Status band shows Connected / Not connected with last check time. Buttons: **Test** (white) and **Save** (orange).

## 7. Components to build (`presentation/common/focus/`)

`FocusScreen`, `FlowTopBar`, `ProgressLine`, `ResultBand`, `FocusCenter`, `RemainingLine`, `ScanPill`, `ActionRow`, `FocusButton` (primary/secondary), `HoldToConfirmButton`, `QuantityPadSheet`, `FocusListRow` (icon, title, optional subtitle, optional count, optional chevron, done/open/warn state), `FocusMenu` (hamburger dropdown), `HomeOnHandPanel`, `HomeRow`.

Retire from flow screens: `StepTrail`, `StatusChip`, old step markers, F1–F4 bar, send/sync tray. Keep the files if other screens still use them; remove them only when nothing references them.

## 8. ScanRouter

One router used by every screen:
1. Parse GS1 (`Gs1.kt`) → item, quantity, batch, expiry, serial.
2. Document prefixes: `BSP-PO-` purchase order, `BSP-TO-` transfer, `BSP-JN-` journal.
3. Bin label → location + bin.
4. Item barcode → item (+ unit and quantity from barcode).
5. Anything else → "Not recognised" bad band; nothing changes.

The current screen decides what a routed scan means (Home routes to a process; a flow screen adds a line, opens a bin or rejects it).

## 9. Screen catalogue

Each row is a state of a screen. "Band" = ResultBand kind + text. "Center" = FocusCenter content.

### Home and common
| Screen | Content |
|---|---|
| Sign in / menu / Connection settings | Section 6 |
| Home / Home menu / typing an item | Section 6 |
| On-hand | Top bar `On-hand`, counter = warehouse. Center: caption `AVAILABLE`, item code, description, available qty in availableQty colour, unit line `Pack · on hand 15 · reserved 3`. List `WHERE`: bin › qty. Scan pill for the next item. |
| Activity | Failed sends first: bad band (e.g. "Not shipped · BSP-TO-… · 5 of 6 left") with **Dismiss** / fix action (e.g. **Ship 5**). Then `SENT TODAY` list with green ticks. |
| Settings | List rows with values: Warehouse, Text size, Post immediately, Unsent work. |
| Enquiry | Rows: Location inquiry, Today's POs (count), PO lines, Transfer orders (count), Picked serials. Scanning works here too. |
| Location inquiry | Info band `BIN-A1 · 3 items · 16 units`; list of items with qty. |
| Today's POs / Transfer orders / Picked serials | Lists; tapping a PO or transfer opens its process. Transfers show `Ship` or `Receive`. |

### Receive
| Screen | Band | Center / other |
|---|---|---|
| Receive menu | – | Rows: Purchase orders (count), Transfers (count). |
| PO receiving – ready | info "Ready · No PO open" | caption `PURCHASE ORDERS TODAY`, count. Secondary button **Today's POs**. |
| PO found | info "PO found · {PO} · {vendor}" | `TO RECEIVE` · `2 lines` · `6 units`. Lines / Undo. |
| Scanned | ok "Scanned · Batch E0927 filled in" | `LAST SCANNED` item, qty, `1 line left`. |
| Typing | – | Keyboard open, pill with orange border. |
| Change quantity | – | Number pad sheet over dimmed screen. |
| Not on this PO | bad "Not on this PO · HSN010 · nothing added" | Last good scan stays in center. |
| Lines | – | List: done (green tick, `1/1`) and open (grey circle, `0/5`). |
| Over-receipt (above tolerance only) | warn "More than ordered · 2 scanned, 1 ordered" | qty in warn colour. Buttons **Keep 1** / **Accept 2**. |
| Posted | ok "Receipt posted · GRN … · 2 lines" | Last scanned; scanning the next PO starts again. |
| Transfer receive – found / received / posted | Same pattern; location defaults to own warehouse; batch/serial matched from shipment. | |

### Ship transfer
| Screen | Band | Center / other |
|---|---|---|
| Ship list | – | Open transfers with line counts. |
| Transfer found | info | `TO PICK` · `2 lines` · `2 units`. |
| Picked | ok "Picked · HSN002 · 1 Each" | Last scanned, `1 line left`. Stock checked on each scan. |
| Short pick (Lines screen) | warn "1 line not picked · No stock at AAC" | Lines list with warn row. Buttons **Keep picking** / **Ship {n} line(s)**. |
| Shipped | ok "Shipped · {TO} · to {warehouse}" | Ships automatically when nothing is left. |

### Count
| Screen | Content |
|---|---|
| Count menu | Store count (open count), Cycle count (open journals). |
| Open store counts | List of open counts (number, reason, bins). Primary button **New count**. Tapping one continues it. |
| Open cycle counts | List of journals with `done/total` bins. |
| Store count – new | info "New count · {number} · {reason}". Center `COUNTED · 0 bins`. Secondary **Change reason**. |
| Bin open | info "BIN-A1 open · AAC · from the label". |
| Counting | ok "Counted · {item} · now 3". Each scan +1; tap number to type (e.g. 48). |
| Bin lines | List of items counted in the open bin. |
| Another bin | ok "BIN-A1 sent · 2 items · BIN-A2 open"; `1 bin sent`. |
| End count | ok "Ready to end · 2 bins · 7 lines sent". **Hold to end count**. |
| Cycle count – journal open | info "{journal} · Blind count". Center `COUNTED · 7 of 18 bins · 11 left`. No bin list, no route. |
| Cycle counting / empty bin / not on journal / journal complete | Same pattern. Empty bin = scanning the bin label twice records 0. Bin not on journal = bad band, nothing counted. |

### Create
| Screen | Content |
|---|---|
| Create menu | Purchase order, Transfer order, Return to vendor (only those enabled for the user). |
| Create PO – vendor | Recent vendors first (star), pill "Type to search vendor". |
| Create PO – adding | ok "Added · Qty = item minimum"; last added item, qty editable. |
| PO created | ok "PO created · {PO} · 2 lines". Primary **Receive it now**. Created and confirmed when leaving the screen. |
| Create transfer – route | From/To rows pre-filled. Primary **Use this route**. |
| Create transfer – adding / not enough stock / created | Same pattern; **Ship it now** at the end. |
| Return – start | info "New return · Reason: {last used}". Secondary **Change reason**. Scan the original PO. |
| Return – adding | ok "Added · From {bin}". **Hold to post return**. |
| Return – posted | ok "Return posted · Packing slip …". |

## 10. Navigation

- Back arrow on every non-root screen returns to the previous screen; system back does the same.
- Home is the root. No bottom bar anywhere.
- Leaving a flow with unsent lines keeps them (existing behaviour); do not add a confirmation dialog unless one exists today.

## 11. Remove

`AppBottomBar` and `BOTTOM_TABS`, the red scan FAB, the Home hero band with the warehouse drawing and the search box, step trails/markers, `StatusChip` in flows, the F1–F4 action bar, the send tray. Activity, Settings and Enquiry stay as screens, reached as described in section 6.

## 12. Phases

1. **Theme**: INDUSTRIAL palette as default, colour tokens, CodeFont, type scale.
2. **Components**: section 7, with Compose previews for each state.
3. **ScanRouter + scan pill + quantity pad** wired to the existing DataWedge receiver.
4. **Sign in, Connection settings, Home, hamburger menus**; remove the bottom bar and FAB.
5. **PO receiving and transfer receive.**
6. **Ship transfer.**
7. **Store count and cycle count** (including open-count lists).
8. **Create PO, create transfer, return to vendor.**
9. **Enquiry, On-hand, Activity, Settings**; remove retired components.

## 13. Read first

`ui/theme/Theme.kt`, `presentation/common/` (StepTrail, StatusChip, RaisedCard, SquareButtons, QuantityPicker, NumericKeypad, UnitChips, QuickNotice, CountProgressRing), `AppNavGraph.kt`, `AppRoot.kt`, `HomeScreen.kt`, `HomeDashboard.kt`, `AppTopBar.kt`, `presentation/auth/` (WorkerLoginScreen, NotConfiguredScreen), `presentation/modules/` (every process screen and ViewModel), `Gs1.kt`, the DataWedge scan receiver, `res/drawable-nodpi/ic_app_logo.png`.

## 14. Acceptance checks

- No screen contains the words "next", "expected", "go to" or a suggested item/bin outside the Lines screens.
- Every process accepts items and bins in any order on a device test.
- Trigger scan works on every screen without tapping a field; typed input + Enter gives the same result.
- No text is truncated at the largest DisplayScale.
- Every tappable element is at least 56dp; bottom buttons 64dp.
- Home shows no bottom bar; the hamburger has only Settings and Sign out; the sign-in hamburger has only Connection settings.
- Activity count is red text in CodeFont, same size as the other counts.
- Receipts, transfer receipts and shipments post with no extra tap when nothing is left.
- End count and Post return need a 1-second hold.
- Existing unit/instrumentation tests still pass.

## 15. Out of scope

Data model, D365FO services, sync and posting logic, licensing, admin/configuration forms in D365FO, tablet or landscape layouts, dark mode.
