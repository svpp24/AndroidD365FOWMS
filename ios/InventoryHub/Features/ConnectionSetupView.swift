import SwiftUI

/// The not-configured screen: scan the connection QR code (or paste it) to point this iPhone at
/// D365FO. A PIN-protected code asks for its PIN, which the worker types.
struct ConnectionSetupView: View {
    @Environment(AppModel.self) private var model
    @State private var scanning = false
    @State private var pasted = ""
    @State private var pendingCode: String?
    @State private var pin = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Connect this iPhone")
                            .font(Theme.font(22, .bold, relativeTo: .title2))
                            .foregroundStyle(Theme.text1)
                        Text("Scan the connection code your administrator gives you. It points the app at your D365FO environment.")
                            .font(Theme.font(15))
                            .foregroundStyle(Theme.text2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .card()

                    if pendingCode != nil {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("This code is protected by a PIN")
                                .font(Theme.font(16, .bold))
                            SecureField("Setup PIN", text: $pin)
                                .keyboardType(.numberPad)
                                .textContentType(.oneTimeCode)
                                .padding(12)
                                .background(Theme.inset, in: RoundedRectangle(cornerRadius: 8))
                            Button("Unlock") { read(pendingCode ?? "", pin: pin) }
                                .buttonStyle(PrimaryButtonStyle())
                                .disabled(pin.isEmpty)
                        }
                        .card()
                    } else {
                        Button {
                            scanning = true
                        } label: {
                            Label("Scan connection code", systemImage: "qrcode.viewfinder")
                        }
                        .buttonStyle(PrimaryButtonStyle())

                        VStack(alignment: .leading, spacing: 10) {
                            Text("Or paste the code")
                                .font(Theme.font(16, .bold))
                            TextField("HSMOCONN1…", text: $pasted, axis: .vertical)
                                .lineLimit(3...6)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .padding(12)
                                .background(Theme.inset, in: RoundedRectangle(cornerRadius: 8))
                            Button("Connect") { read(pasted, pin: nil) }
                                .buttonStyle(PrimaryButtonStyle())
                                .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                        .card()
                    }

                    ErrorText(text: error)
                }
                .padding(16)
            }
            .background(Theme.background)
            .navigationTitle("Inventory hub")
            .sheet(isPresented: $scanning) {
                BarcodeScannerSheet(title: "Connection code") { read($0, pin: nil) }
            }
        }
    }

    private func read(_ code: String, pin: String?) {
        switch ConnectionQR.read(code, pin: pin) {
        case .ready(let config):
            error = nil
            self.pin = ""
            model.connect(config)
        case .needsPin:
            error = nil
            pendingCode = code
        case .invalid(let reason):
            error = reason
            if pin != nil { self.pin = "" } else { pendingCode = nil }
        }
    }
}
