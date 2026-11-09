# Stock counting setup

Phase 1: perpetual (cycle) counting. Later phases add to this guide.

## What runs

The nightly job **Mobile ops: create cycle counting journals** makes one counting journal per enabled warehouse. It holds the items due by their counting group. The lines come from D365FO's own "Create by counting group".

A warehouse whose last journal is still open gets no new one. Post or delete the open journal to start the next cycle.

## 1. Counting groups

Inventory management > Setup > Inventory > Counting groups.

| Counting code | When an item is due |
|---|---|
| Period | Its last count is at least the group's interval (days) ago, or it has never been counted |
| Zero on hand | Its stock has reached zero |
| Minimum | Its stock is at or below the minimum |
| Manual | Only when someone creates the count by hand |

## 2. Put items in a counting group

The counting group is on the item's warehouse record, not on the released product. D365FO looks for it in this order:

1. The item in this exact warehouse and location
2. The item in the warehouse
3. The item's default

**An item with no counting group is never due**, so it is not counted and D365FO gives no warning. Check coverage after setting this up.

## 3. Journal name

Inventory management > Setup > Journal names > Inventory. Create a name with journal type Counting, for example `CYC`. It keeps cycle counts apart from other counting journals, and it needs its own voucher number sequence or the shared one.

## 4. Choose the warehouses

Mobile ops > Setup > **Cycle count warehouses**. One row per warehouse:

| Field | Meaning |
|---|---|
| Warehouse | The warehouse |
| Cycle count | The nightly job makes a journal for it |
| Journal name | Blank uses the counting journal name in Inventory parameters |
| Include zero on hand | Also count items showing none here that have had stock here |
| Last run, Last journal, Lines, Last result | What the last run did. Read-only |

## 5. Locking

Inventory parameters > Journals > **Lock items during count**. On, an item in an open counting journal can't be moved until that journal is posted. D365FO applies this by itself and the job does not change it.

## 6. Schedule the job

Open once, in the company:

`?cmp=<company>&mi=SysClassRunner&cls=HSMobileOpsCountJournalCreateController`

This schedules the nightly job at 21:00 UTC (1 am in the Gulf) if there is none, and one run now. Change or withhold the job under System administration > Batch jobs; only a job that is gone is created again.

## 7. What to expect at warehouse size

Measured on the dev VM with all of AAC: 16,246 lines, 3,371 items, 7,633 lines with stock.

| Step | Time | Note |
|---|---|---|
| Job creates the journal | 34 min | Runs at night in batch |
| Get data on the handheld | About 3 min | |
| End count, submit from the handheld | About 1 min | Sent in batches of 500. A failed send carries on from the last batch |
| Transfer to counting journal | About 9 min | Runs on the supervisor's screen, which stays busy until it finishes |

Stock rows with no location and no stock are not in the journal, because there is nothing to count on a shelf. Rows with negative stock are in it, so expect differences on those lines.

Counting is not posting. Transfer only fills in the counted quantities. A posted journal changes stock and cannot be undone.

## 8. Transfer runs in batch

**Transfer to counting journal** in the Stock count control center now hands the work to a batch job and frees the screen at once. The status shows **Transferring**, then **Transferred**. If it fails, the status goes back to **Open** and **Transfer message** says why. Refresh the page to see the latest.

A batch server must be running. If the job is cancelled or lost, the stock count stays Transferring until you press Transfer again.

# Phase 2: dual counting

Two different people count each line, blind. Nothing reaches the journal until the line is settled.

| Count | Result |
|---|---|
| First | First counted. The journal is not touched |
| Second, by someone else | Within tolerance: **Matched**, and the second count goes on the journal. Otherwise **Mismatch**, and the line goes to the Recount tab |
| Third, by a third person or a supervisor | Equal to an earlier count: **Matched** with that value. Otherwise it waits for a supervisor |
| Supervisor | Approves a final quantity (**Approved**), or sends the line back (**Recount**) |

A dual count journal cannot post, or pass Validate, until every line is Matched, Approved or Empty confirmed. Untouched lines count as not settled. This holds in D365FO's own journal form too.

Dual count journals do not go through the Stock count control center, even at a store that reviews journal counts there.

## 1. Choose the counting groups

Mobile ops > Setup > **Dual count groups**. Tick **Dual count** on each counting group to be counted twice. A journal's lines are marked when the nightly job creates it, so a change applies from the next journal.

## 2. Set the tolerance

Mobile ops > Setup > **Dual count tolerance**. One row per counting group, item group or both. The most specific row wins: both, then counting group, then item group, then the blank row.

| Field | Meaning |
|---|---|
| Tolerance quantity | Largest difference in units that still matches |
| Tolerance % | Largest difference that still matches, as a percentage of the larger count |

With no row, or zeros on the row, the two counts must be equal. Use that for serial numbers and high-value items. The first two counts are compared with tolerance; a third count has to equal an earlier one exactly.

This is separate from **Mobile count tolerance**, which sets when a single count needs a supervisor's review.

## 3. Counters and supervisors

Mobile ops users with **Cycle count supervisor** on can break a tie on a line they counted themselves, and review and approve lines in the app under the journal's More actions > Review. Everyone else can only count lines they have not counted.

## 4. Finished counts on the handheld

The handheld's Inventory count list has a **Done** tab. It shows:

- journals where this person's counts are in and nothing is left for them, with what the journal is waiting for: other counters, or the supervisor;
- the last 20 counting journals posted for the warehouse, newest first, with when each was posted.

A supervisor's open journals stay on their usual tabs, because the supervisor still reviews them.

# Virtual bins

A bin says where in a location a count was made. It is for counting only, and it is a record: D365FO's on-hand stays at location level, and the app keeps no stock figure per bin.

## 1. Set up the bins

Mobile ops > Setup > **Count bins**. One row per store and bin. A store with no bins counts by location, as it did before. Bin names are kept in upper case, up to 20 characters.

## 2. Counting

The handheld asks for a bin first: scan it, or type it and press Enter. Counts then go to that bin until another is scanned, or **Change bin** is tapped. A line counted in two bins counts what they add up to, and that total is what goes to D365FO and what dual counting compares.

A bin not on the store's list is refused. A supervisor is offered **Add bin** instead, which adds it to the list for everyone.

## 3. See what was counted where

Mobile ops > Inquiries > **Counted by bin**.

# Counting by assortment

Mobile ops > Setup > **Cycle count warehouses** > **Count by assortment**. Off by default, and the journal then holds the items due by their counting group.

On, the journal holds every item in the store's retail assortment, with or without stock, and items with no stock go at the warehouse's default location. Stock of an item outside the assortment is still counted, and the line is marked **Outside assortment**. An item found on the floor outside the assortment is accepted and marked the same way.

Some items with no stock are left out, and the warehouse's **Last result** says how many of each:

- Items in variants, because the job cannot know which variant to count.
- Non-stock items such as services, because there is nothing to count.
- Items on hold for inventory transactions, because one such line would stop the whole journal from posting. An on-hold item that has stock stays on the journal: release the hold before posting.
- Items D365FO refuses a line for, such as an item with no dimension groups. Last result gives the first reason.

On a journal with dual counting, an assorted item with no counting group of its own is counted twice, like the lines around it. An item whose group has dual counting off keeps its single count.

# Phase 3: wall-to-wall counting

A wall-to-wall count counts everything a warehouse holds while the warehouse is frozen.

## Setup

1. Mobile ops > Setup > **Cycle count warehouses**: set **Wall-to-wall journal name** to a counting journal name (journal type Counting). A separate name such as W2W keeps these journals apart from cycle counts.
2. The supervisor needs the privilege **Maintain cycle count warehouses**, which now includes Start, Post and Cancel wall-to-wall.

## Running a count

1. **Start wall-to-wall** on the warehouse's row. The warehouse is frozen at once. A batch job builds the journal from on-hand: one line per stock record (item, location, batch, serial, variant and status) with physical stock that isn't zero, negative included. When the warehouse counts by assortment, its assorted items with no stock are added too, as for cycle counts. Each location becomes a count task. The row's **Wall-to-wall** column shows Building journal, then Counting.
2. Counters open the handheld's **Wall-to-wall** tab, pick a location, and count it per bin as usual; counting starts in that location. A counter's own location shows as **Yours** and can be picked again, for example after a refused End count. **End count** sends the counts and completes the location. D365FO refuses to complete a location while any of its lines has no count: count 0 where an item is not there. **Location is empty** settles every line there at 0 (Empty confirmed) and completes the location, unless someone has already counted stock there.
3. Stock found that is not on the journal is added as a new line and counted under the dual count rules.
4. A line needs two counts only when its counting group has dual counting on. Every other line settles at its first count. Second counts and recounts start once every location is complete: they cover the whole journal, and stay on the Wall-to-wall tab as Pass 2 and Pass 3. The supervisor's decisions work as in phase 2.
5. **Post wall-to-wall** once every line is settled and every location complete, or **Post journal** in the handheld's Review. Either posts in batch, and the warehouse is released when the journal has posted. D365FO refuses to post a wall-to-wall journal any other way, such as from the journal form, because the warehouse would stay frozen. Validate still works.
6. **Cancel wall-to-wall** ends the count without posting: in batch, its journal, counts and tasks are removed, then the warehouse is released.

## What the freeze blocks

While a count is Building, Counting or Posting:

- On the handhelds: PO receiving, transfer shipping, picking and receiving, and purchase returns. Each says so when the order is looked up, and D365FO refuses the posting whatever the device was told. A posting queued before the freeze waits, Pending, and posts after it is released.
- In D365FO: inventory journals with stock in the warehouse, on either side of a transfer, except the count's own journal; purchase product receipts; sales packing slips; transfer order shipping and receiving.
- Not blocked: invoices, including retail statement posting, so the store's sales keep posting.

The message every refusal gives names the warehouse, the count's journal and when it was frozen.

## Safe after a crash

The build, post and cancel each run as retryable batch jobs. A build that dies resumes the same journal and adds only what is missing. A post that dies checks whether the journal posted before trying again. Starting wall-to-wall again on a count whose build failed resumes it.
