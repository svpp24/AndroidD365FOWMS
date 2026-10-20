import Foundation
import Security

/// The device's secrets (the connection with its client secrets, the device ID) in the Keychain,
/// readable only on this device and only after the first unlock - never in UserDefaults.
enum Keychain {
    private static let service = "com.d365fo.mobileops"

    static func data(_ account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    @discardableResult
    static func set(_ data: Data?, for account: String) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        guard let data else { return true }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    /// A random ID made once per install, sent with sign-in for the D365FO device list
    /// (HSMobileOpsDevice), as the Android app does.
    static var deviceId: String {
        if let data = data("deviceId"), let id = String(data: data, encoding: .utf8) { return id }
        let id = UUID().uuidString.lowercased()
        set(Data(id.utf8), for: "deviceId")
        return id
    }
}
