# Google Play Data safety form: proposed answers

App: Inventory hub (`com.d365fo.mobileops`), version 1.5.1. Drafted 11 Nov 2026 from the code.
Use this with [privacy-policy.md](privacy-policy.md).

## What the app does with data, in short

- The app's own traffic goes only to the customer's own D365FO (`*.operations.dynamics.com` or the customer's host) and to Microsoft Entra (`login.microsoftonline.com`). The Entra token is app-only (client credentials) and carries no user.
- There is no server of the developer's own, and no analytics, ads or crash-reporting SDK.
- Two Google SDKs: **ML Kit barcode scanning** (`com.google.mlkit:barcode-scanning` 17.3.0, on-device) and **Play Integrity** (`com.google.android.play:integrity` 1.4.0, licence check).
- Permissions:
  - INTERNET and ACCESS_NETWORK_STATE
  - CAMERA: barcodes, read on the device
  - USE_BIOMETRIC: unlock, handled by Android
  - VIBRATE and WAKE_LOCK
  - POST_NOTIFICATIONS: local notifications only
  
  No location, contacts, storage, microphone or phone permissions.

## Section 1: Data collection and security

| Question | Answer | Note |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | User ID, device ID, feedback and diagnostics leave the device. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | HTTPS/TLS only; there is no cleartext traffic. |
| Do you provide a way for users to request that their data is deleted? | **Yes** | Users are created and removed by their employer's administrator in D365FO. Users can't create accounts in the app, so Play's in-app account deletion requirement shouldn't apply; confirm when filling in the form. Point the deletion request route to the employer and to the contact email in the privacy policy (inventoryhub.app@gmail.com). |

## Section 2: Data types

Mark every type below as **Collected = Yes, Shared = No**. "Shared" means passed to a third party. The data goes to the customer's own D365FO, which serves the user's own employer and so counts as the app's back end, not a third party. ML Kit and Play Integrity are Google services acting for the app, and Play treats data sent to such service providers as not "shared".

| Category → type | Collected | Processed ephemerally? | Required or optional | Purposes | Why |
|---|---|---|---|---|---|
| Personal info → **User IDs** | Yes | No | Required | App functionality; Account management | The D365FO user ID is sent at sign-in and with each request, and work is recorded under it. |
| Device or other IDs → **Device or other IDs** | Yes | No | Required | App functionality; Fraud prevention, security, and compliance | Android ID is sent with requests (device binding, licensed devices, who counted on which device). |
| Photos and videos → **Photos** | Yes | No | Optional | App functionality (support) | Only the screenshot a user chooses to attach to Feedback. Camera frames for barcodes stay on the device, so they are **not** collected. |
| App activity → **Other user-generated content** | Yes | No | Optional | App functionality (support) | The feedback message a user writes. |
| App info and performance → **Crash logs** | Yes | No | Optional | App functionality; Analytics (troubleshooting) | The app's own log, attached only when the user sends Feedback. |
| App info and performance → **Diagnostics** | Yes | Yes | Required | App functionality | Basic SDK health data that Google's ML Kit may send (device model, OS and app version, performance). Not linked to the user. **Check this against Google's ML Kit data disclosure page before submitting.** |

Not collected: name, email, address, phone, other personal info, financial info, health and fitness, messages, audio, files and docs, calendar, contacts, location, web browsing, installed apps, search history, app interactions.

Notes:
- **Name:** the user's display name and role are only *received* from D365FO and shown on the device; the app doesn't send them, so Name is not collected. If Google reviewers disagree, add Personal info → Name (required, App functionality).
- **Inventory business data:** receipts, transfers and counts are company records, not personal data. They are linked to the user only through the user ID declared above.
- **Play Integrity:** Google's guidance has been that data handled by Play Integrity for app and device checks doesn't need a separate declaration. **Check the current Play Integrity data disclosure guidance;** if it does need declaring, it falls under Device or other IDs → Fraud prevention, security, and compliance, which is already declared.

## Other Play Console declarations the same release will ask for

- **Privacy policy URL:** https://svpp24.github.io/inventoryhub-privacy/ (GitHub Pages, public repository `svpp24/inventoryhub-privacy`, file `index.html` = `privacy-policy.html`). Change it there and here together.
- **Foreground service permissions:** none since 1.5.3 (End count runs as ordinary background work), so no foreground service declaration is needed.
- **App access:** all functionality needs sign-in to a company's D365FO. Reviewers need instructions and a demo user before a **production** release; internal testing doesn't need it.
- **Ads:** No. **Target audience:** 18+, staff only. **Content rating:** questionnaire, business/utility, no sensitive content.
- **Government app / financial features / health:** No.
