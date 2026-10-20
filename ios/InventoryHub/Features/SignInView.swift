import SwiftUI

/// Worker sign-in against HSMobileOpsUser - the PIN goes to D365FO once and is never kept.
struct SignInView: View {
    @Environment(AppModel.self) private var model
    @State private var userName = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var confirmDisconnect = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Sign in")
                            .font(Theme.font(22, .bold, relativeTo: .title2))
                            .foregroundStyle(Theme.text1)
                        Text("\(model.config?.label ?? "") · Company \(model.company)")
                            .font(Theme.font(14))
                            .foregroundStyle(Theme.text3)
                        TextField("User name", text: $userName)
                            .textContentType(.username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .padding(12)
                            .background(Theme.inset, in: RoundedRectangle(cornerRadius: 8))
                        SecureField("PIN", text: $password)
                            .textContentType(.password)
                            .padding(12)
                            .background(Theme.inset, in: RoundedRectangle(cornerRadius: 8))
                            .onSubmit(signIn)
                        Button(action: signIn) {
                            if busy { ProgressView().tint(.white) } else { Text("Sign in") }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(busy || userName.isEmpty || password.isEmpty)
                        ErrorText(text: error)
                    }
                    .card()

                    Button("Connect to a different environment", role: .destructive) { confirmDisconnect = true }
                        .font(Theme.font(15))
                }
                .padding(16)
            }
            .background(Theme.background)
            .navigationTitle("Inventory hub")
            .confirmationDialog("Remove this connection?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
                Button("Remove connection", role: .destructive) { Task { await model.disconnect() } }
            } message: {
                Text("You will need the connection code again to use this iPhone.")
            }
        }
    }

    private func signIn() {
        guard !busy, !userName.isEmpty, !password.isEmpty else { return }
        busy = true
        error = nil
        Task {
            error = await model.signIn(userName: userName, password: password)
            password = ""
            busy = false
        }
    }
}
