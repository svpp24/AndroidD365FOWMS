import SwiftUI

/// On-hand for one item: per warehouse, physical / available / reserved / ordered
/// (HSMobileOpsInquirySvc/getOnHand). The worker's own warehouse, or every warehouse.
struct OnHandView: View {
    @Environment(AppModel.self) private var model
    var initialItem: String?

    @State private var query = ""
    @State private var allWarehouses = false
    @State private var scanning = false
    @State private var busy = false
    @State private var error: String?
    @State private var item: String?
    @State private var rows: [OnHandRow] = []

    init(initialItem: String? = nil) {
        self.initialItem = initialItem
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 8) {
                        TextField("Item number", text: $query)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .submitLabel(.search)
                            .onSubmit { look(up: query) }
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
                    Toggle("All warehouses", isOn: $allWarehouses)
                        .font(Theme.font(15))
                        .onChange(of: allWarehouses) { if let item { look(up: item) } }
                }
                .card()

                if busy {
                    ProgressView().padding()
                }
                ErrorText(text: error)

                if let item, !busy, error == nil {
                    if rows.isEmpty {
                        Text("No stock of \(item) \(allWarehouses ? "in any warehouse" : "in this warehouse").")
                            .font(Theme.font(15))
                            .foregroundStyle(Theme.text2)
                            .card()
                    } else {
                        SectionTitle(text: item)
                        ForEach(rows, id: \.self) { row in
                            OnHandCard(row: row)
                        }
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("On-hand")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $scanning) {
            BarcodeScannerSheet(title: "Scan item") { look(up: $0) }
        }
        .task {
            if let initialItem, item == nil { look(up: initialItem) }
        }
    }

    private func look(up code: String) {
        let itemId = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !itemId.isEmpty else { return }
        query = ""
        item = itemId
        busy = true
        error = nil
        Task {
            do {
                let response: OnHandResponse = try await model.client.call(
                    "HSMobileOpsInquirySvc", "getOnHand",
                    OnHandRequest(
                        dataAreaId: model.company,
                        itemId: itemId,
                        warehouseId: allWarehouses ? "" : (model.warehouse?.id ?? "")
                    )
                )
                if response.success == false {
                    error = response.errorCode == "NOT_FOUND"
                        ? "\(itemId) is not an item in D365FO."
                        : "D365FO could not look up \(itemId) (\(response.errorCode ?? "error"))."
                    rows = []
                } else {
                    rows = response.rows ?? []
                }
            } catch {
                self.error = error.localizedDescription
                rows = []
            }
            busy = false
        }
    }
}

private struct OnHandCard: View {
    let row: OnHandRow

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Warehouse \(row.warehouseId ?? "-")\(row.siteId.map { " · Site \($0)" } ?? "")")
                .font(Theme.font(16, .bold))
                .foregroundStyle(Theme.text1)
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                GridRow {
                    figure("On hand", row.onHand)
                    figure("Available", row.available, color: Theme.green)
                }
                GridRow {
                    figure("Reserved", row.reserved)
                    figure("Ordered", row.ordered)
                }
            }
        }
        .card()
    }

    private func figure(_ label: String, _ value: Double?, color: Color = Theme.text1) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(Theme.font(13))
                .foregroundStyle(Theme.text3)
            Text((value ?? 0).formatted(.number.precision(.fractionLength(0...2))))
                .font(Theme.font(20, .bold))
                .foregroundStyle(color)
        }
    }
}
