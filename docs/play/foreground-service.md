# Google Play: foreground service declaration

Play Console → App content → **Foreground service permissions**. Drafted 11 Nov 2026 from the code.

## What the app has

- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`.
- One service: WorkManager's `SystemForegroundService`, type `dataSync` (AndroidManifest).
- One job uses it: **`CountSubmitWorker`**, which runs **End count** for a counting journal. It sends the counted quantities to D365FO and finishes the count there.
  - It starts when the user taps **End count**: `CountingJournalListViewModel` and `CountingScanViewModel` → `CountSubmissions.endCount` → WorkManager.
  - It also runs when the user accepts an **opportunity count** after a receipt or transfer (`OpportunityCounts`), and only if the build has `FEATURE_COUNT_OPPORTUNITY` on.
  - While it runs, the notification **"Submitting count"** shows the journal number (`SyncNotifier.countSubmitForegroundInfo`).
  - If Android stops the app, WorkManager restarts the job, and it resumes from the saved submission instead of sending twice.
- Not covered by it: receipts, transfers and the Hazard, Wall to wall and Blind counts. Those send while the screen is open, with no foreground service.

## Answers to paste

**Foreground service type:** Data sync

**Describe the feature that uses this foreground service type.** (Play asks for a short user-facing description.)

> When a warehouse or store worker finishes a stock count and taps "End count", the app sends the counted quantities to the company's ERP system (Microsoft Dynamics 365 Finance & Operations) and completes the count there. A count can have hundreds of lines and the ERP import takes from a few seconds to several minutes. The transfer runs as a data sync foreground service with a visible "Submitting count" notification, so it finishes even if the worker switches to another app or the screen turns off. Otherwise the count would be left half-submitted in the ERP.

**Is the task started by the user?** Yes. It starts only when the user taps "End count", or accepts the offer to count a location. The app never starts it on its own schedule.

**What is the impact on the user if the task is deferred or interrupted by the system?**

> The stock count stays half-submitted: the counted quantities don't reach the ERP, the count can't be finished or posted, and stock levels stay wrong until it's sent. The worker would have to reopen the app and wait for it to retry before moving on.

**Video:** Play asks for a link showing the feature. It can be an unlisted YouTube or Google Drive link that anyone with the link can view. Record about 30 seconds on a test device:
1. Open a counting journal, count a line or two and tap **End count**.
2. Show the **"Submitting count"** notification in the notification shade.
3. Switch to another app or turn the screen off, come back, and show the count finished.

## If you would rather not declare it

The new count types (Hazard, Wall to wall, Blind) don't use the service. Only the older counting-journal **End count** and the opportunity count do. If those are retired, the service and both permissions can be removed and this declaration dropped. That would mean changing the code and testing it again, so it isn't worth doing just for the first internal test.
