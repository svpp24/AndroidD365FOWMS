# Mobile Operations – handheld UI redesign brief

Instructions for Claude Code. Read this whole file before changing anything.

## Goal

Rework the handheld app's screens so a fast warehouse worker can do every process by **scanning only**, on a **portrait** rugged handheld, with **large, calm, easy-to-read screens**. Data, repositories, D365FO calls and posting logic stay as they are – this is a presentation and flow change.

## Before you start

1. Read these files to learn the current structure. Do not rename or move them unless a step below says so.
   - `presentation/common/theme/Theme.kt` – colours, `ColorPalette`, typography, `WarehouseDimens`, `D365MobileTheme`
   - `presentation/common/components/` – especially `StepTrail.kt`, `StatusChip.kt`, `RaisedCard.kt`, `SquareButtons.kt`, `QuantityPicker.kt`, `NumericKeypad.kt`, `QuickNotice.kt`, `UnitChips.kt`
   - `presentation/common/AppNavGraph.kt` – `AppBottomBar`, `BOTTOM_TABS`, routes
   - `presentation/common/HomeScreen.kt`, `HomeDashboard.kt`
   - `presentation/common/scan/` and `domain/model/ScanResult.kt`, `domain/model/Gs1.kt`
   - `domain/model/ReceivingStep.kt`, `domain/model/TransferStep.kt`, `domain/model/DimensionRules.kt`
   - The modules under `presentation/modules/` (receiving, transfer, transferpick, transferreceive, cyclecounting, ordercreate, onhand, dataenquiry)
2. Before editing, write a short plan listing the files you will touch for the current phase, and wait for approval.
3. Work one phase at a time (see **Phases**). Each phase must build (`./gradlew assembleDebug`) and keep existing tests passing.

## Ground rules

- Kotlin + Jetpack Compose only; follow the existing code style and the comment style in `Theme.kt`.
- Do not change repositories, DAOs, network calls, sync, posting or licensing.
- No text truncation anywhere: no `maxLines = 1` with ellipsis on data (item codes, names, document numbers, messages). Let rows grow.
- Every interactive element ≥ `WarehouseDimens.MinTouchTarget` (56dp); primary buttons 64dp tall.
- Never use colour alone: every status has an icon and a word.
- No flashing or blinking animations. Errors are a steady colour band plus sound/vibration.
- Keep all existing user-visible strings that still apply; new strings in the same place as existing ones.

## 1. Theme

### 1.1 New palette – Industrial (default)

Add to `ColorPalette` in `Theme.kt` and make it the default `palette` in `D365MobileTheme` and anywhere the app reads the saved default:

```kotlin
INDUSTRIAL("Industrial", Color(0xFF1E3A8A), Color(0xFFDBEAFE)),
```

### 1.2 Comfort colours (fixed, not palette-dependent)

Add to `WarehouseStatusColors` (keep existing names working):

| Token | Hex | Use |
|---|---|---|
| `pageComfort` | `#F1F5F9` | Page background (replaces pure white page on flow screens) |
| `surface` | `#FFFFFF` | Cards, bars |
| `ink` | `#1E293B` | Main text |
| `inkMuted` | `#475569` | Labels, secondary text (7.6:1 on page) |
| `divider` | `#E2E8F0` | Dividers, card borders |
| `action` | `#EA6A12` | Primary action buttons (white text) |
| `okBand` / `okEdge` / `okText` | `#DCFCE7` / `#16A34A` / `#14532D` | Success band |
| `infoBand` / `infoEdge` / `infoText` | `#DBEAFE` / `#1E3A8A` / `#1E3A8A` | Info / document found |
| `warnBand` / `warnEdge` / `warnText` | `#FEF3C7` / `#D97706` / `#78350F` | Warning, needs a choice |
| `badBand` / `badEdge` / `badText` | `#FEE2E2` / `#DC2626` / `#7F1D1D` | Error, wrong item |
| `idleBand` / `idleEdge` | `#E2E8F0` / `#64748B` | Waiting |
| `scanButton` | `#B5222D` | Red scan FAB (keep existing value) |

Never use pure `#000000` or pure `#FFFFFF` as a full-screen background.

### 1.3 Typography

- Keep Nunito Sans for UI text.
- Bundle **Barlow Condensed** (SIL OFL, weights 600 and 700) in `res/font/` the same way `nunito_sans.ttf` is bundled, with its licence in `assets/licenses/`. Expose it as `CodeFont`.
- Use `CodeFont` for item codes, document numbers, locations/bins and quantities.
- Sizes (before `DisplayScale`): item/location code 42sp, quantity 66–72sp, band title 22–24sp, body 17–19sp, labels ≥ 15sp. Nothing below 12sp.

## 2. Components to build

Put new components in `presentation/common/components/`. Each gets a `@Preview`.

| Component | Replaces / extends | Spec |
|---|---|---|
| `FlowHeader` | top bar on flow screens | 52dp white bar: back arrow (44dp target), title, optional progress text (e.g. "1 of 2"), overflow ⋮ menu. |
| `StatusBand` | `QuickNotice` for scan results | Rounded 16dp card, 10dp coloured left edge, tinted background, 22–24sp bold title + 15–16sp line. Kinds: `Idle`, `Info`, `Ok`, `Warn`, `Bad`. Steady, no animation beyond a 150ms colour cross-fade. Shows **Undo** link for 5s after an `Ok` scan. |
| `NextCard` | ad-hoc item cards | White 16dp card, fills remaining height: small label ("Next · line 1 of 2"), code in `CodeFont` 42sp, description 18sp, bottom row with location (left) and big quantity (right, `CodeFont` 66sp). |
| `SendTray` | — | 52–64dp bar showing what is waiting to send / sending / sent (icon + two lines). Reads from `SyncStatusProvider`. Green border when sent. |
| `FlowActionBar` | per-screen bottom buttons | Fixed 64dp bar, 4 equal buttons: **Back · Lines · Undo · Keypad**, icon + label + hardware key hint (F1–F4). Accepts which one is highlighted (orange text, light orange background). |
| `HoldToConfirmButton` | confirm dialogs for irreversible posts | 64dp `action` orange button; fills over 1.2s while held; tap alone shows "Hold to …" hint; haptic at 100%. |
| `ListRow` | card rows in lists | Leading 28dp circle (status icon), title + subtitle(s) that wrap, trailing value or chip. One shared composable for every list. |
| `AppBottomBar` (update) | existing in `AppNavGraph.kt` | Keep the four tabs and the red rounded-square scan FAB; apply comfort colours; FAB 56dp. |

Retire `StepTrail` usages on flow screens (the progress text in `FlowHeader` and the status band replace it). Leave the file in place until all callers are gone, then delete.

## 3. Scan-only behaviour (all processes)

Implement a single **`ScanRouter`** in `presentation/common/scan/` used by every screen:

1. **Identify** (in this order): GS1 AIs via `Gs1.kt` (item + batch + expiry + serial) → document prefix (`BSP-PO-`, `BSP-TO-`, `BSP-JN-`) → location/bin from the on-device bin list → item barcode from the synced barcode table → unknown.
2. **Route by screen**: each screen declares what it accepts. On Home, a PO opens PO receiving, a transfer opens ship or receive (by direction), a bin starts a store count, an item opens On-hand. Inside a process, the expected type advances the step; another document/bin switches to it; an item not on the document shows a `Bad` band.
3. Deliver scans from DataWedge / Honeywell intents and the camera through this one entry point. Queue scans so rapid scanning never drops one.

Rules every process must follow:

- **The scan is the confirm.** No OK button after a scan.
- **Default quantities:** receiving / ship / receive transfer = full open line quantity; counting = +1 per scan; create PO = item minimum × multiple. Keypad (F4 or tapping the quantity) overrides.
- **Ask only when there is a real choice:** auto-fill batch/serial when only one is possible or when the GS1 label carries it; auto-pick the only location with stock; skip item lists when there is one open line. Use `DimensionRules.kt` and the step lists in `ReceivingStep.kt` / `TransferStep.kt` to decide which steps to skip.
- **Post when the work ends:** the last line (or scanning the next document) posts in the background through the existing posting path; `SendTray` shows the result. Use `HoldToConfirmButton` only for: End count, Post return, ending a transfer early.
- **No confirm dialogs.** Undo replaces "Are you sure?".

## 4. Screens

All flow screens use this layout, top to bottom: `FlowHeader` → `StatusBand` → `NextCard` → `SendTray` → `FlowActionBar`. Page background `pageComfort`.

| Process | Steps (worker action → app action) |
|---|---|
| **Home** | Big navy "Scan to start" card (PO · transfer · bin · item), 2×2 tiles (Receive, Ship, Count, Create) with counts, `AppBottomBar`. Scanning routes via `ScanRouter`. |
| **PO receiving** | Ready (Idle band) → scan PO (Info band, line 1) → scan item (Ok band, full qty, batch from label, next line) → … → last line posts → Ok band with GRN, ready for next PO. Wrong item = Bad band, nothing added. |
| **Ship transfer** | Scan transfer → scan items (pick location with stock, full line) → last line ships. Out of stock = Warn band with two buttons: **Skip line** / **Ship without it**. |
| **Receive transfer** | Scan transfer → scan items (batch/serial from shipment, location defaults) → last line posts. |
| **Store count** | Start (count number + last-used reason, tap to change) → scan bin label (sets location + bin) → scan items (+1 each) → scan next bin label (previous bin sends) → **Hold to end count**. |
| **Cycle count** | "Go to" next bin in walking order → scan bin → scan items (blind; +1 each) → scanning the bin label twice settles an empty bin at 0. Bins send one by one. |
| **Create PO** | Vendor list with last-used first (1 tap) → scan items (min × multiple) → order is created and confirmed when the worker leaves or scans a new vendor. |
| **Return to vendor** | Scan original PO (vendor known, reason/action default to last used) → scan items (only location with stock) → **Hold to post return**. |
| **Create transfer** | From = current store, To pre-selected if only one → Start → scan items → created on leave → **Ship it now** opens Ship on it. |
| **On-hand** | From the red scan FAB on any tab: scan → available (big, green), on hand / reserved, by-location rows. |
| **Activity** | Problems first as `Bad` bands with a one-tap fix (e.g. **Ship 5** / **Dismiss**); sent items listed below with green ticks. |
| **Lines (F2)** | List of the document's lines with received/ordered, using `ListRow`. Back returns to the flow. |

## 5. Navigation and hardware keys

- `FlowHeader` back arrow and **F1** = previous screen; work done is kept.
- **F2** Lines, **F3** Undo last scan, **F4** Keypad. Map them in the activity's key handling so they work without touching the screen.
- Hub screens (Home, Enquiry, Activity, Settings) show `AppBottomBar` with the red scan FAB; flow screens show `FlowActionBar` instead.

## 6. Phases

Do these in order, one pull request each. Stop after each phase and summarise what changed.

1. **Theme** – Industrial palette as default, comfort colours, Barlow Condensed bundled. No screen changes yet. *Check:* app builds, existing screens still render.
2. **Components** – `FlowHeader`, `StatusBand`, `NextCard`, `SendTray`, `FlowActionBar`, `HoldToConfirmButton`, `ListRow`, updated `AppBottomBar`, each with previews. *Check:* previews render at 100% and 130% text size without truncation.
3. **ScanRouter** – identification + routing + scan queue, with unit tests for each barcode type (GS1 with batch/expiry/serial, PO, TO, journal, bin, EAN, unknown) and for routing on Home vs inside a flow.
4. **PO receiving** – rebuild on the new layout and rules. *Check:* receiving a 2-line PO with GS1 labels takes 3 scans and 0 taps; wrong item adds nothing; posting happens after the last line.
5. **Ship and Receive transfer.**
6. **Store count and Cycle count.**
7. **Create PO, Return, Create transfer.**
8. **Home, On-hand, Activity, Lines**, then remove dead `StepTrail` usages.

## Acceptance checks for every screen

- Portrait only; readable on a 4–5" device at arm's length.
- No text truncated at 130% text size.
- No full-screen pure black/white; no flashing.
- Every status has icon + word; contrast ≥ 4.5:1.
- No OK/confirm tap after a successful scan.
- F1–F4 work.
- Works offline: scans save locally and `SendTray` shows them as waiting.

## Out of scope

Data model, D365FO services, sync/posting internals, licensing, the D365FO admin forms, tablet/landscape layouts.
