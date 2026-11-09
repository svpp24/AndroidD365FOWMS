# Prompt for Gemini: Inventory hub - inventory counting

Copy everything below the line into Gemini.

---

You are helping me with **Inventory hub**, a warehouse and store app I am building for
**Microsoft Dynamics 365 Finance & Operations (D365FO)**. Read this description of the app's
**inventory counting** feature carefully. It explains what the feature does, how it works end to
end, and how the screens are laid out. Use it as the ground truth when I ask you questions,
ask for design ideas, or ask you to write specifications, test cases or code. If something is not
covered here, say so instead of guessing.

## 1. The system in one paragraph

There are two apps with the same features: an **Android app** (Kotlin, Jetpack Compose; it runs on
Sunmi handhelds with a built-in barcode scanner, and on phones with the camera) and an **iPhone
app** (Swift, SwiftUI, camera scanning) that is being built. Both talk to D365FO through
**custom X++ services** in a D365FO model called **HSMobileOps** (every X++ object starts with
`HSMobileOps`). The device signs in to D365FO as an Entra ID app registration (client
credentials); the **worker** then signs in with a user name and PIN stored in the HSMobileOps user
table, and works in one **warehouse** (store) at a time. Workers can be counters, cycle count
supervisors, or external counters (a counting company's staff, who see fewer options).

**Golden rule:** the device never decides anything about stock. It records what a person counted
and sends it. D365FO creates journals and lines, compares counts, applies tolerances, and posts.
Counts are kept on the device (a local Room database) until D365FO has accepted them, so counting
works with no network and nothing is lost if the app is killed.

## 2. The kinds of count

| Kind | What it is | Where it comes from |
|---|---|---|
| **Cycle count** | Count the items that are due. | A nightly D365FO batch job creates one counting journal per enabled warehouse, using D365FO counting groups (period, zero on hand, minimum). A warehouse whose last journal is still open gets no new one. Optionally "count by assortment": every item in the store's retail assortment. |
| **Dual count** (blind) | Each line is counted by two different people, who can't see each other's count. | Counting groups set to "dual count". 1st count: recorded only. 2nd count by someone else: within tolerance = **Matched**, otherwise **Mismatch** and recount. 3rd count must equal an earlier one, otherwise a supervisor decides (**Approved** with a final quantity, or **Recount**). The journal can't post until every line is Matched, Approved or Empty confirmed. Tolerance is set per counting group / item group (quantity and %). |
| **Recount** | Lines sent back for counting again. | A supervisor's decision or a dual count mismatch. Shown on the Recount tab. |
| **Wall-to-wall** | Count everything in a warehouse while it is frozen. | A supervisor presses Start wall-to-wall in D365FO. The warehouse is **frozen** (receiving, shipping, picking, purchase returns and inventory journals are refused, on the handheld and in D365FO; sales invoices still post). The journal is built from on-hand, one count task per location. Counters pick a location, count it, End count completes it. "Location is empty" settles every line there at 0. Then pass 2 / pass 3 for dual count lines. Post or Cancel runs in batch and releases the freeze. |
| **Spot count** | A count on demand of one item and/or one location. | The worker taps + on the counting list. The server creates the journal; the app fetches it. |
| **Opportunity count** | A one-line blind count offered right after a pick or putaway. | The server decides whether the spot is eligible; the app just asks once per location/item per session. |
| **Triggered count** | Created by negative stock or a reported discrepancy. | "Report discrepancy" on Home or in picking. The app checks for new ones every 15 minutes and notifies the worker. |
| **Store count** (free count) | Count the whole store location by location, not against a journal. | The Stock count control center (section 5). |

## 3. How counting a journal works (step by step)

1. **Open Inventory count.** The app lists the open counting journals for the worker's warehouse
   (headers only, quick). Tabs: **Cycle**, **Recount**, **Wall-to-wall**, **Done**.
2. **Get data.** Downloads the journal's lines to the device. A small journal comes in one call
   (DIRECT mode). A large journal comes through a D365FO data-management export package
   (DMF mode, e.g. 16,000 lines in about 3 minutes). This runs in the background and is listed on
   the **Activity** page. Lines are **blind**: the device is not told D365FO's on-hand quantity.
3. **Claim.** For a journal split into ranges or locations (wall-to-wall), the counter claims one.
   The claim is kept alive with a heartbeat. A claim that expires without a heartbeat is treated
   as lost, and End count is blocked until the counter claims it again.
4. **Start counting.** Scan (hardware scanner, camera, or type and press Enter) an item number
   or barcode. A quantity popup opens with the number box already focused. OK saves, and the
   cursor goes straight back to the scan box for the next item.
   - Item on several lines (several locations / variants / batches): a picker asks which one,
     with a filter box ("Red M").
   - Item master with variants (config / size / colour / style): a variant chooser with a filter.
   - **Virtual bins:** if the store has bins set up, the app asks for a bin first. Counts go to
     that bin until another is scanned or "Change bin" is tapped. A line's count is what its bins
     add up to. An unknown bin is refused; a supervisor is offered "Add bin".
   - **By location:** a switch to count location-first: scan or choose a location, count what is
     in it, or tap "Location is empty" (settles its lines at 0).
   - **Verify only** lines: the counter only confirms the item is there ("Confirm present").
   - **Not on this journal:** an item scanned that the journal doesn't have is added straight to
     the list, marked in amber as "Not on this journal". The device never adds a journal line; it
     sends the item as an exception and D365FO adds the line. Unknown codes the item cache doesn't
     know are refused.
   - Tapping a row reopens the quantity popup to correct it. Undo removes the last scan.
5. **View lines.** A read-only list of every line, counted or not, with search and filters, from
   the journal card.
6. **End count.** Confirms first ("38 of 40 lines counted - send the counts?"). It records who,
   which device, when and which pass, then sends the counts in the background (WorkManager).
   With no connection it waits and retries. Sending is idempotent: a resend never counts twice.
   Large journals go in batches of 500, and a failed send carries on from the last batch.
   Results on the card: Submitted, Sent for review, Third count needed, "n unexpected items not
   sent", Waiting for connection, Held here (posting off), Posted - closed.
7. **Reopen counting.** Counts stay; fix or add, then End count again.
8. **Export as CSV** from the card's menu: the journal as it stands on the device, for Excel.

## 4. Supervisor features (cycle count supervisors only; the server checks every call)

- **Review variance:** per line, expected (system) vs initial, recount and final quantity, value
  and status; filters (status, ABC class, counter, needs decision); select lines and Approve,
  Recount (optionally assign to a user), Investigate or Reject with a D365FO counting reason code
  and a comment; then **Post journal** ("On-hand is adjusted. This can't be undone.").
- **Dual count review:** totals (Not counted, Counted once, Matched, Disagree, Sent for recount,
  Approved, Location empty) and each disagreement with every count side by side and who made it;
  Recount or Approve with a final quantity; Post journal.
- **Count accuracy report:** accuracy % by location, item, ABC class or counter for a date range,
  with the tolerance the server reports.
- **Done tab:** journals where this person has nothing left to do and what they wait for, plus the
  last 20 posted journals.

## 5. Store count and the Stock count control center

A free count of the whole store, reviewed by a supervisor in D365FO before anything reaches a
counting journal.

- **On the handheld (Store count, from the counting list's ⋮ menu):** the counter scans a
  location, then each item there and its quantity (variant / batch / serial when needed). Lines are
  kept on the device until sent. **Send** stages them, **End count** sends and ends this
  counter's count. If the supervisor transferred or cancelled the stock count while lines were
  still on the device, Send is refused and **"Move N line(s) to a new stock count"** takes them to
  the store's next stock count. A store with no supervisor refuses Store count (NO_SUPERVISOR).
- **In D365FO (the control center form):** one master stock count per store (`STORE-YYYYMMDD-n`),
  one staging journal per counter. **Duplicates** (same item + dimensions + batch + serial +
  location, by one counter or several) are flagged, and the supervisor decides each: **Keep this
  count**, **Add together** or **Reject - recount**. **Transfer to counting journal** (runs in
  batch) is refused while a duplicate is undecided; it creates a counting journal "Stock count
  <id>", or writes the counts onto an existing journal when the count was against one. Journals
  created this way are hidden from the handheld's list; the supervisor posts them in D365FO.
- Stores can also be set to send ordinary journal counts through the control center for review.

## 6. Screen layouts (Android, current design)

Style: light grey page (#F3F3F6), white borderless cards with 10 dp corners, indigo primary
colour (#232A7C), Nunito Sans font, large touch targets (gloves), text always wraps and is never
cut off. Bottom bar on the main pages: Home · Services · **Scan** (round teal button) · Activity ·
More.

### 6.1 Inventory count (the journal list)

```
┌──────────────────────────────────────────┐
│ ←  Inventory count               +    ⋮  │  + = New spot count
│                                          │  ⋮ = Store count, Count accuracy
│ [ Cycle ] [ Recount (3) ] [ Wall-to-wall ] [ Done (2) ]  ← tabs
├──────────────────────────────────────────┤
│ ┌──────────────────────────────────────┐ │
│ │ BSP-JN-000552032                 ⋮   │ │  ⋮ = Get data again, Reopen counting,
│ │ Cycle count · Pass 2 · Double-blind  │ │      Review variance, Export as CSV
│ │ Claimed by you                       │ │
│ │ Counted 38 of 40 ▓▓▓▓▓▓▓▓▓▓▓▓░       │ │
│ │ Waiting for connection               │ │  status line when there is one
│ │ [ View lines ]  [ Continue counting ]│ │
│ │ [            End count             ] │ │
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │
│ │ BSP-JN-000552040                     │ │
│ │ [            Get data              ] │ │  before the lines are on the device
│ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

### 6.2 Counting (Start / Continue counting)

```
┌──────────────────────────────────────────┐
│ ←  Counting                          +   │  + = record an item not on this journal
├──────────────────────────────────────────┤
│ ┌──────────────────────────────────────┐ │
│ │  ╭────╮  BSP-JN-000552032            │ │  round progress ring, % inside
│ │  │ 95%│  38 of 40 counted            │ │
│ │  ╰────╯  2 not on this journal       │ │
│ │                        By location ○ │ │  switch: location-first counting
│ └──────────────────────────────────────┘ │
│  Bin B-03                  [ Change bin ]│  only at stores with bins
│ ┌──────────────────────────────────────┐ │
│ │ Scan item                            │ │  "Scan a bin" / "Scan a location" first
│ │ ┌──────────────────────────┐ ┌──┐ ┌─┐│ │  when needed
│ │ │ Item or barcode          │ │ ⏎│ │📷││ │  typed text also filters the list
│ │ └──────────────────────────┘ └──┘ └─┘│ │
│ └──────────────────────────────────────┘ │
│ [All] [Physical] [Display] …  Undo       │  location-type filter chips
│ ○ To count  ● Counted  ⊕ Not on journal  │  legend
│ ┌──────────────────────────────────────┐ │
│ │ ⊕ DM001 · Not on this journal · 3    │ │  newest exception first, amber
│ │ ● BSR-11397-0  Blue shirt  Loc A-01 5│ │
│ │ ○ BSR-11398-2  Red shirt   Loc A-02  │ │
│ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

Popups on this screen: **Quantity** (item, bin and location, a focused number box, Cancel /
Confirm as two big buttons); **"<item> is on N lines"** (pick a location / variant / batch, with a
filter); **"Which <item>?"** variant chooser with a filter; **"Verify, don't count"** (Confirm
present); **"Is <location> empty?"** (Yes, it's empty); **"Add bin <bin>?"** (supervisors).

### 6.3 View lines

Top bar "View lines"; a search box ("Search item, name, location…"); filter chips with counts
(All / Counted / Not counted); a light text-only list of every line with its counted quantity.

### 6.4 Store count

```
┌──────────────────────────────────────────┐
│ ←  Store count                           │
├──────────────────────────────────────────┤
│ ┌──────────────────────────────────────┐ │
│ │ Scan a location  (then: Scan item)   │ │  same scan card as Counting
│ │ [ Location or item          ] ⏎  📷  │ │
│ └──────────────────────────────────────┘ │
│ [          Change location           ]   │
│ [ Move 4 line(s) to a new stock count ]  │  only after the count was closed
│ ┌──────────────────────────────────────┐ │
│ │ BSR-11397-0 · 5 PCS   Loc A-01   🗑  │ │  newest at the bottom, scrolls to it
│ │ Blue / M · not sent                  │ │
│ └──────────────────────────────────────┘ │
│ [ Send 4 ]              [ End count ]    │
└──────────────────────────────────────────┘
```

Before starting: a "Start store count" button. After End count: "Count ended" and Done.

### 6.5 Supervisor screens

- **Variance · <journal>:** a filter menu; Select all / Clear (n); a list of lines with expected,
  counted, recount, value and status; buttons Approve, Recount, Investigate, Reject (each opens a
  dialog with a reason code, a comment and, for a recount, who should do it); Post journal.
- **Dual count · <journal>:** the totals block, then each disagreement: "D365FO holds 12",
  "Count 1: 10 by 001", "Count 2: 14 by 002", buttons Recount and Approve (final quantity).
- **Count accuracy:** a date range preset, a group-by choice (location, item, ABC class,
  counter) and accuracy % rows.

## 7. Terms

- **Counting journal:** D365FO's inventory counting journal; posting it adjusts on-hand.
- **Line:** one item + dimensions (config, size, colour, style) + batch + serial + location.
- **Warehouse / store:** D365FO InventLocation; a worker works in one at a time.
- **Exception / not on this journal:** stock found that the journal doesn't list.
- **Claim:** a counter's hold on a journal range or location, kept alive by heartbeats.
- **DIRECT / DMF:** the two transfer modes, a single service call or a data-management package.
