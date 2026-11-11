# Google Play store listing – Inventory hub

Play Console → Grow → Store presence → **Main store listing**. Drafted 11 Nov 2026 from the app's features; text saved in Play Console as a draft (11 Nov).

## App details

| Field | Limit | Text |
|---|---|---|
| App name | 30 | Inventory hub |
| Short description | 80 | Receive, ship, transfer and count stock on Android for Dynamics 365 F&O |

### Full description (limit 4000)

```
Inventory hub puts warehouse and store work on an Android handheld, connected live to your company's Microsoft Dynamics 365 Finance & Operations (D365FO).

Scan, confirm and move on. Every receipt, transfer and count goes straight into D365FO, so stock levels are right the moment the work is done.

TRY IT FIRST
• Tap "Try the demo" on the first screen to use every main screen on sample data - no account or connection needed

RECEIVE
• Receive purchase orders line by line, by scanning the item, batch or serial
• See today's expected deliveries and what is still open
• Return goods to the vendor

SHIP AND RECEIVE TRANSFERS
• Pick and ship transfer orders between stores and warehouses
• Receive incoming transfers and record any differences
• Create purchase orders and transfer orders from the device

COUNT
• Wall to wall counts of the whole warehouse, location by location and bin by bin
• Blind counts of chosen items or categories, with no on-hand shown to the counter
• Hazard counts requested by an outside system, such as a product recall
• Several people can count the same count at once; the supervisor settles duplicates and creates the journal in D365FO
• Supervisors create counts from the device, by category or by item, and see progress by category

LOOK UP
• On-hand stock by item, location and batch
• What is in a location

BUILT FOR THE SHOP FLOOR
• Works with the built-in scanners of rugged handhelds and with the phone camera
• Large, clear screens made for quick, one-handed work
• Keeps working through short network drops and sends the work when the connection is back
• Fingerprint or face unlock
• Send feedback with a screenshot to your company's D365FO for its support team

REQUIREMENTS
Inventory hub is for companies that run Microsoft Dynamics 365 Finance & Operations with the Inventory hub components installed. Sign-in uses your company's own D365FO users; your administrator sets up the connection and decides which functions each user can use.

Your data goes only to your company's own D365FO system. Inventory hub has no ads and no tracking.

Inventory hub is an independent product and is not affiliated with or endorsed by Microsoft. Microsoft and Dynamics 365 are trademarks of the Microsoft group of companies.
```

## Graphics

| Asset | Required size | File |
|---|---|---|
| App icon | 512 × 512 PNG | `docs/play/play-store-icon-512.png` (done) |
| Feature graphic | 1024 × 500 PNG or JPG | `docs/play/feature-graphic-1024x500.png` (done) |
| Phone screenshots | 2–8; 9:16, at least 1080 × 1920 recommended | `docs/play/screenshots/01-06` (720 × 1280, from the built-in demo, no customer data) |

### Screenshots to capture (in this order)

1. Home: Receive, Ship, Count tiles
2. Purchase order receiving: scanning a line
3. Transfer ship
4. Count menu: Hazard, Wall to wall, Blind
5. Counting in a bin (Last counted, quantity)
6. Supervisor: a count by category with "counted so far"
7. On-hand enquiry
8. Sign-in screen with the logo

**Use demo data only.** The dev environment holds real company, warehouse and product names (for example the AAC warehouse's customer name). Screenshots are public, so they need a demo company, or the names hidden, before they are captured.

## Categorisation and contact

| Field | Value |
|---|---|
| App category | Business |
| Tags | Inventory management; Warehouse; Barcode scanner (pick from Play's list) |
| Contact email | inventoryhub.app@gmail.com |
| Website | Optional; leave empty, or use the policy site |
| Privacy policy | https://svpp24.github.io/inventoryhub-privacy/ |

## Notes

- Only features that are in the app are described. If a customer's administrator switches a function off, the user doesn't see it, which the Requirements paragraph covers.
- Claims that need to stay true:
  - "No ads and no tracking": no ads or analytics SDK. ML Kit diagnostics are covered in Data safety.
  - "Keeps working through short network drops": offline queue and offline counting.
- The Microsoft sentence covers using the name "Dynamics 365" to describe compatibility.
