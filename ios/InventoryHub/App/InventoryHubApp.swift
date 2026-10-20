import SwiftUI

@main
struct InventoryHubApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(Theme.primary)
        }
    }
}

/// Connect the device, then sign the worker in, then the app - the same gates as Android's AppRoot.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        switch model.stage {
        case .needsConnection: ConnectionSetupView()
        case .signedOut: SignInView()
        case .signedIn: MainTabView()
        }
    }
}

/// Home, Services and More, as on Android; Scan sits on Home rather than as a floating button.
struct MainTabView: View {
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            HomeView(openServices: { tab = 1 })
                .tabItem { Label("Home", systemImage: "house") }
                .tag(0)
            ServicesView()
                .tabItem { Label("Services", systemImage: "square.grid.2x2") }
                .tag(1)
            MoreView()
                .tabItem { Label("More", systemImage: "ellipsis.circle") }
                .tag(2)
        }
    }
}
