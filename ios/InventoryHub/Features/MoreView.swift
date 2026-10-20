import SwiftUI

/// More: the warehouse, the connection, the app version and sign out (Android: SettingsScreen).
struct MoreView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmSignOut = false
    @State private var confirmDisconnect = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            List {
                Section("Settings") {
                    if let warehouses = model.worker?.warehouses, warehouses.count > 1 {
                        Picker("Warehouse", selection: $model.warehouse) {
                            ForEach(warehouses) { warehouse in
                                Text(warehouse.title).tag(Optional(warehouse))
                            }
                        }
                    } else {
                        LabeledContent("Warehouse", value: model.warehouse?.id ?? "-")
                    }
                }
                Section("Device") {
                    LabeledContent("Environment", value: model.config?.label ?? "-")
                    LabeledContent("Company", value: model.company)
                    LabeledContent("Device ID") {
                        Text(Keychain.deviceId)
                            .font(Theme.font(13))
                            .textSelection(.enabled)
                    }
                    Button("Remove connection", role: .destructive) { confirmDisconnect = true }
                }
                Section("Support") {
                    LabeledContent("Version", value: AppModel.appVersion)
                    LabeledContent("Signed in as", value: model.worker?.userName ?? "-")
                }
                Section {
                    Button(role: .destructive) {
                        confirmSignOut = true
                    } label: {
                        Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
                            .frame(maxWidth: .infinity)
                    }
                }
            }
            .font(Theme.font(16))
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .navigationTitle("More")
            .confirmationDialog("Sign out?", isPresented: $confirmSignOut, titleVisibility: .visible) {
                Button("Sign out", role: .destructive) { model.signOut() }
            }
            .confirmationDialog("Remove this connection?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
                Button("Remove connection", role: .destructive) { Task { await model.disconnect() } }
            } message: {
                Text("You will need the connection code again to use this iPhone.")
            }
        }
    }
}
