import SwiftUI

/// The app's modules, as on Android (ServicesScreen.flatGroups). `workflow` matches the
/// WarehouseWorkflow name D365FO uses in ModulePermissions; `built` says whether the iOS app has the
/// screen yet - the rest show as coming in a later iOS build.
struct Module: Identifiable, Hashable {
    let title: String
    let icon: String
    let workflow: String?
    let built: Bool

    var id: String { title }
}

struct ModuleGroup: Identifiable {
    let title: String
    let icon: String
    let tint: Color
    let modules: [Module]

    var id: String { title }
    var summary: String { modules.map(\.title).joined(separator: ", ") }
}

enum Modules {
    static let onHand = Module(title: "On-hand", icon: "shippingbox", workflow: nil, built: true)

    static let all: [ModuleGroup] = [
        ModuleGroup(title: "Inbound", icon: "tray.and.arrow.down", tint: Color(hex: 0x2E6FD8), modules: [
            Module(title: "PO receiving", icon: "tray.and.arrow.down", workflow: "PO_RECEIVING", built: false),
            Module(title: "Transfer — Receive", icon: "arrow.down.to.line", workflow: "TRANSFER_RECEIVE", built: false),
            Module(title: "PO creation", icon: "doc.badge.plus", workflow: "PO_CREATE", built: false),
        ]),
        ModuleGroup(title: "Outbound", icon: "tray.and.arrow.up", tint: Color(hex: 0xC1501E), modules: [
            Module(title: "Transfer — Ship", icon: "arrow.up.to.line", workflow: "TRANSFER_SHIP", built: false),
            Module(title: "TO creation", icon: "doc.badge.plus", workflow: "TO_CREATE", built: false),
            Module(title: "Purchase return", icon: "arrow.uturn.backward", workflow: "PURCHASE_RETURN", built: false),
        ]),
        ModuleGroup(title: "Inventory count", icon: "checklist", tint: Color(hex: 0x15803D), modules: [
            Module(title: "Inventory count", icon: "checklist", workflow: "INVENTORY_COUNT", built: false),
        ]),
        ModuleGroup(title: "Inquiries", icon: "magnifyingglass", tint: Color(hex: 0x7C3AED), modules: [
            onHand,
            Module(title: "Location inquiry", icon: "mappin.and.ellipse", workflow: "LOCATION_INQUIRY", built: false),
            Module(title: "PO's of today", icon: "calendar", workflow: "PO_OF_TODAY", built: false),
            Module(title: "Transfer orders", icon: "arrow.left.arrow.right", workflow: "TRANSFER_ORDERS", built: false),
        ]),
    ]

    /// The groups this worker may open - D365FO module permissions are restrictions only.
    static func groups(blocked: Set<String>) -> [ModuleGroup] {
        all.compactMap { group in
            let allowed = group.modules.filter { $0.workflow.map { !blocked.contains($0) } ?? true }
            return allowed.isEmpty ? nil : ModuleGroup(title: group.title, icon: group.icon, tint: group.tint, modules: allowed)
        }
    }
}

/// Where a module leads: its screen, or a plain note while the iOS screen is still to be built.
struct ModuleDestination: View {
    let module: Module

    var body: some View {
        if module == Modules.onHand {
            OnHandView()
        } else {
            ContentUnavailableView(
                module.title,
                systemImage: module.icon,
                description: Text("This module is not in the iPhone app yet. Use a handheld for now.")
            )
            .background(Theme.background)
        }
    }
}
