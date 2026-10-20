# Feedback from the app

Workers send problems, ideas and questions from the handheld. The team reviews them in D365FO, and the worker sees the status and reply in the app.

## In the app

- **⋮ > Send feedback**: the ⋮ menu at the right of the top bar on every screen once signed in (on Home it also holds Sign out). Send feedback captures a screenshot of that screen and opens **Send feedback** for it.
- **Settings > Send feedback** opens the same screen without a screenshot ("General").
- **The form:**
  - Problem / Idea / Question;
  - a message;
  - "Include screenshot" (on by default, with a preview).
- **What's sent with each piece of feedback:**
  - the screen name;
  - the app version, the warehouse, the device model and the device ID;
  - the last 60 lines of the app's own log.
- **Changing feedback:** an item still marked New says "Tap to change" in My feedback and can be edited (type and message) until the team picks it up. This needs a connection.
- **My feedback** lists the worker's own feedback with the status (New, In progress, Done, Won't do) and the team's reply.
- **Offline:** feedback is queued like an order (SyncQueue `SEND_FEEDBACK`) and shows "Waiting to send" until D365FO has it. Screenshots are kept as files on the device and deleted once sent.

## In D365FO

**Inventory hub > Inquiries > Mobile feedback** lists the feedback, newest first. Each record has:

| Tab | Contents |
|---|---|
| Header | Screen, Type, Worker |
| Feedback | Message; **Status** and **Reply**, both editable and shown to the worker |
| Screenshot | The screen as the worker saw it (JPEG) |
| Sent from | Worker, warehouse, app version, device, device ID, sent time, screenshot flag, feedback ID |
| App log | The app's recent log. A problem storing the screenshot is noted at the top. |

**Export feedback** (action pane) downloads `mobile-feedback-<date>.zip`. It covers the records with **Export** switched on (in each record's header; the switch stays on until you turn it off); with none switched on, the records the list shows, so filter first, for example on Status = New. The zip holds:

- `feedback.md`: one Markdown document. A short header explains the app and what each item contains, and asks for the items to be grouped and a fix or answer proposed for each. Then, per item: type, screen, status, who sent it, when and from where, app version and device, the message, the reply so far, and the app log in a collapsible block.
- `screenshots/<FeedbackId>.jpg`: one for each item that has a screenshot, named in `feedback.md`.

Give the whole zip (or `feedback.md` plus the screenshots) to any AI assistant, or to a developer. The export is read-only; nothing is marked as exported.

**Access:** privilege `HSMobileOpsFeedbackMaintain`, in the Mobile operations administrator role.

## Service: `HSMobileOpsFeedbackSvc`

| Operation | Request | Returns |
|---|---|---|
| `submitFeedback` | FeedbackId (also the idempotency key), UserName, DeviceId, WarehouseId, AppVersion, DeviceModel, Screen, FeedbackType, Message, Logs, ScreenshotBase64 | Success, AlreadyCreated |
| `updateFeedback` | FeedbackId, UserName, FeedbackType, Message | Success; NOT_EDITABLE once the team has moved it past New |
| `listMyFeedback` | UserName, Take (up to 100) | Items[]: FeedbackId, SubmittedUtc, Screen, FeedbackType, Message, Status, Response |

The table `HSMobileOpsFeedback` is shared across companies (not per company), like the device list.

**The screenshot is stored in the record (a container field), not as a document attachment.** Document attachments need the environment's file storage set up, and on the dev VM attaching failed because no client secret is configured for it.

## Tested on the dev VM and handheld (6 Oct 2026)

| Case | Result |
|---|---|
| Feedback from Home, with screenshot | Recorded in 1.2 s. Header, message and context correct. That attempt still used document attachments, so the screenshot wasn't stored; this led to the in-record storage above. |
| Idea from On-hand | Recorded; screen "On-hand"; the app log shows the attachment error that led to the change. |
| Question from PO confirmation, after the fix | "Screenshot attached" Yes; the Screenshot tab shows the screen. |
| Status Done and Reply "ok test" in D365FO | Shown in the app after refresh: DONE, "Reply: ok test". |
| Feedback form in D365FO | Newest first; Status and Reply editable; the other fields read-only. |
| Export feedback | 3 items, 1 with a screenshot: feedback.md (27 KB) and screenshots/e119….jpg (76 KB) |
