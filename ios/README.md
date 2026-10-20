# Inventory hub for iPhone

The native Swift (SwiftUI) version of Inventory hub. It is a separate app from the Android one
(`app/`): nothing is shared at the code level. Both call the same D365FO HSMobileOps services
(`aos/`), so the server side is unchanged.

- **Target:** iPhone, iOS 17+. Barcodes are scanned with the camera (VisionKit `DataScannerViewController`).
- **Project:** `project.yml` (XcodeGen). The `.xcodeproj` is generated and never committed.
- **Build:** GitHub Actions on macOS (`.github/workflows/ios.yml`). It generates the project, runs the
  unit tests on an iPhone simulator, builds an unsigned device build, and uploads a simulator `.app`.
  No Mac is needed to develop it.

## What is built

| Area | iOS | Android equivalent |
| --- | --- | --- |
| Connection QR (plain, PIN, raw JSON) | `Core/ConnectionQR.swift` | `ConnectionQr.kt` |
| Entra client-credentials token | `Core/D365Client.swift` (`TokenProvider`) | `ClientCredentialsTokenProvider.kt` |
| HSMobileOps service calls (`_request`, PascalCase) | `Core/D365Client.swift` | `MobileOpsServiceApi.kt` |
| Secrets and device ID | Keychain (`Core/Keychain.swift`) | `SecureStore` |
| Worker sign-in and warehouse | `Core/AppModel.swift`, `Features/SignInView.swift` | `WorkerSessionRepository.kt` |
| Home, Services, More | `Features/` | `HomeScreen`, `ServicesScreen`, `SettingsScreen` |
| On-hand | `Features/OnHandView.swift` | On-hand module |

Still to come, module by module: PO receiving, transfers (ship and receive), PO and TO creation,
purchase return, inventory counting and Store count, the inquiries, offline posting queue
(Activity), D365FO handheld menus, barcode-to-item lookup, the licence certificate activation, and
feedback.

## Running it on an iPhone

Installing on a real iPhone needs an Apple Developer account (for signing). Once there is one,
add the signing certificate and provisioning profile as GitHub secrets and extend the workflow to
upload to TestFlight. Until then, the simulator build from the workflow can be run on any Mac.
