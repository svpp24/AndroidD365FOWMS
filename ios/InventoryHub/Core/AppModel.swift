import Foundation
import Observation
import UIKit

/// The app's state: is the device connected, who is signed in, which warehouse they work in.
@MainActor
@Observable
final class AppModel {
    enum Stage: Equatable {
        case needsConnection
        case signedOut
        case signedIn
    }

    private(set) var config: TenantConfig?
    private(set) var worker: Worker?
    var warehouse: WarehouseAssignment?

    let client = D365Client()

    var stage: Stage {
        if config == nil { return .needsConnection }
        return worker == nil ? .signedOut : .signedIn
    }

    var company: String { config?.company ?? "" }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
    }

    init() {
        if let data = Keychain.data("connection"),
           let saved = try? JSONDecoder().decode(TenantConfig.self, from: data) {
            config = saved
            client.config = saved
        }
    }

    // MARK: Connection

    func connect(_ config: TenantConfig) {
        Keychain.set(try? JSONEncoder().encode(config), for: "connection")
        self.config = config
        client.config = config
        worker = nil
    }

    func disconnect() async {
        await client.signOut()
        Keychain.set(nil, for: "connection")
        worker = nil
        warehouse = nil
        config = nil
        client.config = nil
    }

    // MARK: Worker

    /// Nil on success; otherwise the message the sign-in screen shows.
    func signIn(userName: String, password: String) async -> String? {
        let device = UIDevice.current
        let request = SignInRequest(
            dataAreaId: company,
            userName: userName.trimmingCharacters(in: .whitespaces),
            password: password,
            deviceId: Keychain.deviceId,
            deviceBrand: "Apple",
            deviceModel: Self.hardwareModel,
            platform: "iOS",
            platformVersion: device.systemVersion,
            appVersionName: Self.appVersion
        )
        do {
            let response: SignInResponse = try await client.call("HSMobileOpsUserSvc", "signIn", request)
            guard response.success == true else { return response.refusal }
            if response.mustChangePassword == true {
                return "Your PIN must be changed first. Change it on a handheld, then sign in here."
            }
            let warehouses = response.warehouses ?? []
            worker = Worker(
                userName: response.userName ?? request.userName,
                displayName: response.displayName ?? "",
                isSupervisor: response.isSupervisor ?? false,
                warehouses: warehouses,
                blockedModules: Set((response.modulePermissions ?? []).filter { $0.isAllowed == false }.compactMap(\.module)),
                licenseWarning: response.licenseWarning
            )
            warehouse = warehouses.first { $0.isDefault == true } ?? warehouses.first
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func signOut() {
        worker = nil
        warehouse = nil
    }

    /// "iPhone15,2" rather than UIDevice's generic "iPhone" - what the D365FO device list shows.
    private static var hardwareModel: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}
