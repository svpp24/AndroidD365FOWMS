# Mobile Operations handheld - test cases

User-perspective test cases for every process, screen, menu and button of the handheld app, including
the exception paths a warehouse or store worker meets. Run on the debug build so no sign-in is needed
(`DEV_SKIP_SIGN_IN`), against the dev environment, store **AAC**, with **Data posting Off** so nothing
a test does reaches D365FO unless the case says so.

**Result** column: `Pass`, `Fail` (with the issue number from the issue log at the end), `Fixed` (failed,
fixed and re-run), `Not run` (with the reason - usually that it would change D365FO data or needs a
role or setup the test user does not have).

Device: Android emulator, API 37, 720x1280 at 320 dpi (360x640 dp - the size of the fleet's handhelds).
Scans are sent the way the handheld scanner sends them (`com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED`).

Test data: PO **BSP-PO-000161766** (vendor 19721, BSR-1009-0 / BSR-1010-0, batch-tracked with GTINs
6291103652447 / 6291103652454), items BSR-11397-0 and HST002, store bins BIN-A1 / BIN-A2.

---

## 1. Start-up and sign-in

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| S-01 | First start, not configured | Fresh install, open the app | Logo, "Mobile Operations", "This device is not configured yet", the environment host, Scan connection QR, Connection settings | |
| S-02 | Connection settings before sign-in | Tap Connection settings | Settings with Connection & profile, Appearance, Lockout, Unsent work, Logs; back returns | |
| S-03 | Sign-in screen layout | Configured device, signed out | Logo, version, "Mobile Operations", user name and PIN in one card, Settings beside Sign in | |
| S-04 | Sign in disabled until both fields filled | Empty / name only | Sign in stays disabled | |
| S-05 | Wrong PIN | Enter a wrong PIN | Clear error at the top, nothing else changes | Not run - needs a real user's PIN |
| S-06 | Debug skip sign-in | Debug build with DEV_SKIP_SIGN_IN | Opens straight on Home as the dev user | |
| S-07 | Notification prompt | First start on Android 13+ | System asks once; app works whatever the answer | |

## 2. Navigation shell

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| N-01 | Bottom tabs | Tap Home, Enquiry, Activity, Settings | Each opens, the tab is highlighted, title is the tab's name (Home: greeting) | |
| N-02 | Scan button (red) | Tap it | On-hand opens with the scan card first | |
| N-03 | Back from a tab | On Enquiry/Activity/Settings press system back | Returns to Home, not out of the app | |
| N-04 | Back on Home | Press system back on Home | App goes to the background (no crash, no blank screen) | |
| N-05 | ⋮ menu | Open ⋮ on Home | Send feedback, Sign out (confirmed) | |
| N-06 | Top bar | Any screen | Title fully visible, never under the status bar, connection icon visible | |
| N-07 | Hardware scan on a tab with no scan field | Scan an item on Home | On-hand / the scan route handles it; nothing is lost silently | |

## 3. Home

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| H-01 | Layout | Open Home | Greeting with initials, "Store AAC · Change", search on the wallpaper band, group cards only (no counts, no tags) | |
| H-02 | Change store | Tap "Change" | Change warehouse opens; back returns | |
| H-03 | Search | Tap the search field | Search opens with its field focused | |
| H-04 | Purchase group | Tap Purchase | Opens PO receiving directly when it is the only purchase module, else the group's list | |
| H-05 | Transfers group | Tap Transfers | Group screen: Transfer - Ship, Transfer - Receive (and TO creation when allowed); back returns | |
| H-06 | Inventory count | Tap Inventory count | Count hub opens directly | |
| H-07 | Menu access | User without a module | Its card / row is not shown | |
| H-08 | Long-press a card | Long-press | Nothing breaks (info dialog or nothing) | |

## 4. Search

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| SR-01 | Find an item | Type BSR-11397 | The item with its on-hand; tap opens On-hand | |
| SR-02 | Find a PO | Type 161766 | The PO; tap opens PO receiving with it | |
| SR-03 | Find a transfer | Type a TO number | The TO; tap opens Ship or Receive | |
| SR-04 | Nothing found | Type ZZZZ | "Nothing found" message, no crash | |
| SR-05 | Scan into search | Scan an item barcode | Treated as the search | |
| SR-06 | Back | Back | Returns to Home | |

## 5. On-hand (scan button)

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| OH-01 | Layout | Open | "Scan item" card first, chip "Store AAC only" under it, switch, nothing else | |
| OH-02 | Scan an item number | Scan BSR-11397-0 | Item number and name, Available / On hand / Reserved / Ordered tiles per warehouse | |
| OH-03 | Scan a barcode | Scan 6291103652447 | Resolves to BSR-1009-0 | |
| OH-04 | Unknown item | Scan ZZZ-NOPE | Clear "not found" message; previous result cleared | |
| OH-05 | All warehouses | Switch off "This warehouse only", pick All | Rows per warehouse | |
| OH-06 | Type instead | Tap the field | Typing screen; submit looks up | |
| OH-07 | Back | Back | Returns where it was opened from | |

## 6. Enquiry

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| E-01 | Enquiry tab | Open | Location inquiry, PO's of today, PO lines, Transfer orders, TO's by Store, Picked serials (as allowed) - name and icon only | |
| E-02 | Location inquiry | Scan AAC | Location details; unknown location gives a clear message | |
| E-03 | PO's of today | Open | Today's POs at AAC; tap opens receiving | |
| E-04 | PO lines | Scan BSP-PO-000161766 | Lines with ordered / received / open | |
| E-05 | Transfer orders | Open, switch Ship / Receive | Lists with counts; tap opens the flow | |
| E-06 | TO's by Store | Open | Grouped by store | |
| E-07 | Picked serials | Open, change period | Serial list or empty message | |
| E-08 | Back on each | Back | Returns to Enquiry | |

## 7. PO receiving

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| R-01 | Start | Open PO receiving | Step tabs (PO, Item, Qty, Location, Confirm), "Scan purchase order" card first, "Due today" rows | |
| R-02 | Tap a due-today PO | Tap BSP-PO-000161766 | Item step; chips "PO …", "Vendor …" under the card | |
| R-03 | Scan a PO | Scan BSP-PO-000161766 | Same as R-02 | |
| R-04 | Last digits | Type 161766 | Completed to the full PO number | |
| R-05 | Unknown PO | Scan BSP-PO-999999999 | Clear error, stays on the PO step | |
| R-06 | PO of another store | Scan a PO for AGH | "… is for AGH, not AAC", stays | |
| R-07 | Item not on the PO | Scan BSR-11397-0 on 161766 | Red message, nothing added | |
| R-08 | GS1 label with batch, expiry, count | Scan (01)06291103652447(10)HB001(17)281231(30)1 | Line added: item, batch, expiry, qty; Scanned list shows it | |
| R-09 | Plain GTIN | Scan 6291103652454 | Quantity sheet: item as title, "x left of y" chip, number box, keypad, unit | |
| R-10 | Keypad | Enter 0, a decimal, over the open quantity | Confirm stays disabled / clear error; never accepts more than allowed | |
| R-11 | Cancel quantity | Cancel | Back on the item step, nothing added | |
| R-12 | Batch step | After quantity on a batch item | "Scan batch", chips show item and qty | |
| R-13 | New batch expiry | Unknown batch | Expiry step; wrong date format refused | |
| R-14 | Location step | Item needing a location | "Scan location", chip "Suggested …" when the order has one | |
| R-15 | Search PO items | Tap "Search PO items" | Search sheet of the order's lines; tap picks the line | |
| R-16 | Review | Tap Review | Scanned lines with +/- and remove; Scan more returns | |
| R-17 | Hold to post - tap | Tap (do not hold) Hold to post | "Keep holding until the bar is full"; nothing posts | |
| R-18 | Hold to post - hold | Hold 1 s on a test PO | Receipt posted (held on the device while posting is Off) | Not run - creates a receipt |
| R-19 | Start over with lines | ⋮ Start over | Asks before discarding; Keep keeps the lines | |
| R-20 | Back step by step | Back on each step | Goes one step back; from the PO step leaves the flow | |
| R-21 | Leave and resume | Leave with lines, open Activity | "Continue where you left off" lists the PO; tap resumes at the item step | |
| R-22 | Scan while the quantity sheet is open | Scan another item | Not taken as the quantity; the sheet stays or closes cleanly | |

## 8. Transfer - ship and receive

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| TS-01 | Ship start | Transfers > Transfer - Ship | Tabs, "Scan transfer order" card first | |
| TS-02 | Ship a TO | Scan an open TO from AAC | Chips "Transfer …", "Route AAC to …"; next step location or item | |
| TS-03 | Wrong direction | Scan a TO to AAC in Ship | Clear message (receive it instead) | |
| TS-04 | Item not on TO | Scan another item | Red message, nothing added | |
| TS-05 | Batch step | Batch item | Chip "Ordered batch …" when the order names one | |
| TS-06 | Review / Hold to ship tap | Tap Review, tap Hold to ship | Review list; tap only shows the hold hint | |
| TR-01 | Receive start | Transfer - Receive | Tabs, scan card first | |
| TR-02 | Receive a shipped TO | Scan a shipped TO to AAC | Chips; item step | |
| TR-03 | Batch not shipped | Scan a batch that was not shipped | "Batch … wasn't shipped" with what was | |
| TR-04 | Shipped batch chip | Batch step | Chip "Shipped batch … (qty)" | |

## 9. Order creation (when allowed for the user)

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| CP-01 | Create PO - vendor | Open | Tabs Vendor/Items/Create, vendor search card first, Your orders, Vendors | |
| CP-02 | Pick vendor | Tap a vendor | Items step; chips Vendor, To AAC, Delivery date | |
| CP-03 | Item not approved for vendor | Scan an item | Refused with the reason | |
| CP-04 | Discard | Discard | Asks; Keep keeps | |
| RT-01 | Return - scan original PO | Scan a PO | Vendor taken from it; reason and action dropdowns | |
| CT-01 | Create transfer - route | Pick From and To | Next enabled only with both | |
| CT-02 | Items | Scan item | Stock picker for batch items; quantity; line listed | |

## 10. Inventory count

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| IC-01 | Hub | Open Inventory count | Store count, Cycle count, Wall-to-wall count, Count accuracy (supervisor) - names only | |
| SC-01 | Store count start | Open Store count | Start button only (no counts list) | |
| SC-02 | Location step | After start | Scan card first, chips, then "Locations" rows; no counted items | |
| SC-03 | Unknown bin | Scan BIN-ZZ on the bin step | Counted as scanned; nothing added to the bin master | |
| SC-04 | Bin step list | After a location | "Bins in …" rows with counted lines | |
| SC-05 | Change bin / tabs | Tap the Bin tab, Change bin | Back to the bin step | |
| SC-06 | Hold to end count - tap | Tap | Hold hint only | |
| CC-01 | Cycle list | Open Cycle count | To count / Recount / Done tabs; journal cards with View lines and Continue | |
| CC-02 | Count screen | Continue counting | Scan card first; chips Journal, Location, Bin, "x of y counted" | |
| CC-03 | Location step list | Journal counted by location | Scan card, then Locations rows; no item list | |
| CC-04 | Bin step list | Store with bins | Scan card, then Bins rows; unknown bin counted as scanned | |
| CC-05 | Item not on journal | Scan another item | Message with "Not on this journal - record it" | |
| CC-06 | Not on this journal dialog | Open it | Item title, amber tag, labelled fields, Cancel / Record | |
| CC-07 | Undo | After a scan | Undo takes the last +1 back | |
| VL-01 | View lines | Tap View lines | All / Not counted / Counted tabs with counts, progress card, search | |
| VL-02 | Search lines | Type an item | Filters | |
| AC-01 | Count accuracy | Open (supervisor) | Period tabs, grouping pills, summary card | |
| SV-01 | Variance review | Supervisor, journal sent for review | Tally card, rows with tags, decisions in the dock | Not run - needs a journal in review |

## 11. Activity

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| A-01 | Tabs | Open Activity | Needs attention (red badge when failures), Registration queue | |
| A-02 | Continue where you left off | With an unfinished job | Row with In progress tag, Discard; tap resumes | |
| A-03 | Discard | Tap Discard | Asks or discards; the row goes | Not run on real jobs |
| A-04 | Failed posting | With a refused posting | Row with Refused tag, reason, Acknowledge / Resubmit | |
| A-05 | Registration queue | Tab 2 | Process pending, rows with status tags | |

## 12. Settings

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| ST-01 | Layout | Open Settings | "Signed in …" chip, Data up to date with Sync, rows with only real values, Sign out, Delete all app data, version | |
| ST-02 | Sync | Tap Sync | "Syncing…", then counts and time update; failure shows the reason | |
| ST-03 | My warehouse & sign-in | Open, back | Store and session details; back to Settings | |
| ST-04 | Data posting | Open | Switch state matches the row | |
| ST-05 | Unsent work | Open | Waiting items or "nothing waiting" | |
| ST-06 | Send feedback | Open | Feedback form; back | |
| ST-07 | Logs | Open | Log table; back | |
| ST-08 | Delete all app data | Tap, then Keep | Dialog lists unsent work; Keep changes nothing | |
| ST-09 | Sign out | Tap, then Cancel | Confirm dialog; Cancel stays signed in | |

## 13. Exceptions and robustness

| ID | Scenario | Steps | Expected | Result |
|---|---|---|---|---|
| X-01 | Scan while a dialog is open | Scan during any dialog | Not lost into the wrong field; warning beep | |
| X-02 | App killed mid-flow | Kill the app with lines scanned | Lines come back (Continue where you left off) | |
| X-03 | Large text | Appearance > larger text | Nothing cut off; long codes wrap after a hyphen, not mid-number | |
| X-04 | Long names | Vendor / item names | Wrap, never "…" | |
| X-05 | Offline | D365FO unreachable | Clear offline state; work kept on the device | Not run - needs the network off |

## 14. Performance

| ID | Measure | How | Target | Result |
|---|---|---|---|---|
| P-01 | Cold start to first screen | `am start -W`, perf build, 3 runs | Not worse than before the UX pass | |
| P-02 | Frames - Home, tabs, Activity, cycle list scroll | `dumpsys gfxinfo` on the perf build | Janky frames and 90th percentile not worse | |
| P-03 | Memory | `dumpsys meminfo` total PSS | Not worse | |
| P-04 | Network on screen open | Logs | Home: no figure calls; Inventory count hub: no journal list call | |

---

## Issue log

| # | Screen | Issue | Fix |
|---|---|---|---|
