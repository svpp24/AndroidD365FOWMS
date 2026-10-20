import SwiftUI

/// Home: the greeting with the warehouse, the item search (On-hand) with Scan, and the modules.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    let openServices: () -> Void
    @State private var query = ""
    @State private var scanning = false
    @State private var lookup: String?

    init(openServices: @escaping () -> Void) {
        self.openServices = openServices
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    Greeting()

                    if let warning = model.worker?.licenseWarning, !warning.isEmpty {
                        Label(warning, systemImage: "exclamationmark.triangle")
                            .font(Theme.font(14))
                            .foregroundStyle(Theme.amber)
                            .card()
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        Text("Find an item")
                            .font(Theme.font(17, .bold))
                            .foregroundStyle(Theme.text1)
                        HStack(spacing: 8) {
                            TextField("Item number or barcode", text: $query)
                                .textInputAutocapitalization(.characters)
                                .autocorrectionDisabled()
                                .submitLabel(.search)
                                .onSubmit { open(query) }
                                .padding(12)
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.primary, lineWidth: 1.5))
                            Button {
                                scanning = true
                            } label: {
                                Image(systemName: "barcode.viewfinder")
                                    .font(.title2)
                                    .frame(width: 48, height: 48)
                                    .foregroundStyle(.white)
                                    .background(Theme.scan, in: RoundedRectangle(cornerRadius: 10))
                            }
                            .accessibilityLabel("Scan")
                        }
                    }
                    .card()

                    HStack {
                        SectionTitle(text: "My Dashboard")
                        Button("More", action: openServices)
                            .font(Theme.font(15, .bold))
                    }

                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                        ForEach(Modules.groups(blocked: model.worker?.blockedModules ?? [])) { group in
                            NavigationLink(value: group.modules.first!) {
                                VStack(alignment: .leading, spacing: 8) {
                                    Image(systemName: group.icon)
                                        .foregroundStyle(.white)
                                        .frame(width: 34, height: 34)
                                        .background(group.tint, in: RoundedRectangle(cornerRadius: 8))
                                    Text(group.title)
                                        .font(Theme.font(15, .bold))
                                        .foregroundStyle(Theme.text1)
                                        .multilineTextAlignment(.leading)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                .frame(maxHeight: .infinity, alignment: .topLeading)
                                .card()
                            }
                        }
                    }
                }
                .padding(16)
            }
            .background(Theme.background)
            .navigationDestination(for: Module.self) { ModuleDestination(module: $0) }
            .navigationDestination(item: $lookup) { OnHandView(initialItem: $0) }
            .sheet(isPresented: $scanning) {
                BarcodeScannerSheet(title: "Scan item") { open($0) }
            }
        }
    }

    private func open(_ code: String) {
        let item = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !item.isEmpty else { return }
        query = ""
        lookup = item
    }
}

/// "Hi, Pradeep" with the warehouse they work in - wraps whole words, never truncates.
struct Greeting: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Hi, \(model.worker?.firstName ?? "")")
                .font(Theme.font(24, .heavy, relativeTo: .title))
                .foregroundStyle(Theme.text1)
            if let warehouse = model.warehouse {
                Text("Warehouse \(warehouse.id) · \(model.company)")
                    .font(Theme.font(14))
                    .foregroundStyle(Theme.text3)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
