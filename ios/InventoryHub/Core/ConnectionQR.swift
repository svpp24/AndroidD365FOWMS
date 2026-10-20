import CommonCrypto
import CryptoKit
import Foundation

/// One app registration: the device authenticates to D365FO as this app (client credentials).
struct AppCredential: Codable, Equatable {
    var clientId: String
    var clientSecret: String
}

/// Where this device points: the D365FO environment, its Entra tenant, the company and the
/// app registrations. Kept in the Keychain (see `ConnectionStore`).
struct TenantConfig: Codable, Equatable {
    var label: String
    var resourceUrl: String
    var tenantId: String
    var company: String
    var apps: [AppCredential]

    var servicesRoot: String { resourceUrl + "/api/services/" }

    /// Nil when usable; otherwise what is missing, in the words the setup screen shows.
    var validationError: String? {
        if !resourceUrl.lowercased().hasPrefix("https://") { return "the environment URL must start with https://" }
        if tenantId.isEmpty { return "the tenant is missing" }
        if apps.isEmpty || apps.contains(where: { $0.clientId.isEmpty || $0.clientSecret.isEmpty }) {
            return "an app registration is missing its client ID or secret"
        }
        return nil
    }
}

/// The connection QR code, the same format the Android app reads (Android: ConnectionQr.kt,
/// made with tools/make_connection_qr.py):
/// - `HSMOCONN1:` + base64url(JSON) - plain;
/// - `HSMOCONN1P:` + base64url(salt 16 | iv 12 | AES-256-GCM ciphertext + tag), the key derived
///   from a setup PIN with PBKDF2-HMAC-SHA256 ([pinIterations]);
/// - the JSON itself, unencoded.
enum ConnectionQR {
    static let prefixPlain = "HSMOCONN1:"
    static let prefixPin = "HSMOCONN1P:"
    static let pinIterations: UInt32 = 120_000

    enum Result: Equatable {
        case ready(TenantConfig)
        case needsPin
        case invalid(String)
    }

    private struct Payload: Decodable {
        let v: Int?
        let label: String?
        let url: String
        let tenant: String
        let company: String?
        let apps: [App]
    }

    private struct App: Decodable {
        let id: String
        let secret: String
    }

    static func isConnectionCode(_ raw: String) -> Bool {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.hasPrefix(prefixPlain) || text.hasPrefix(prefixPin) || isRawJson(text)
    }

    private static func isRawJson(_ text: String) -> Bool {
        text.hasPrefix("{") && text.contains("\"apps\"") && text.contains("\"url\"")
    }

    static func read(_ raw: String, pin: String? = nil) -> Result {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let damaged = Result.invalid("This connection code is damaged. Scan it again.")
        let json: Data
        if text.hasPrefix(prefixPin) {
            guard let pin, !pin.isEmpty else { return .needsPin }
            guard let sealed = base64urlDecode(String(text.dropFirst(prefixPin.count))) else { return damaged }
            guard let opened = decrypt(sealed, pin: pin) else { return .invalid("Wrong PIN, or the code is damaged.") }
            json = opened
        } else if text.hasPrefix(prefixPlain) {
            guard let plain = base64urlDecode(String(text.dropFirst(prefixPlain.count))) else { return damaged }
            json = plain
        } else if isRawJson(text) {
            json = Data(text.utf8)
        } else {
            return .invalid("This is not a connection code.")
        }

        guard let payload = try? JSONDecoder().decode(Payload.self, from: json) else {
            return .invalid("This connection code could not be read.")
        }
        if (payload.v ?? 1) != 1 { return .invalid("This connection code is for a newer version of the app.") }
        if payload.apps.isEmpty { return .invalid("This connection code has no app registration.") }

        let trim: (String) -> String = { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        var url = trim(payload.url)
        while url.hasSuffix("/") { url.removeLast() }
        let label = trim(payload.label ?? "")
        let config = TenantConfig(
            label: label.isEmpty ? "UAT" : label,
            resourceUrl: url,
            tenantId: trim(payload.tenant),
            company: trim(payload.company ?? "").uppercased(),
            apps: payload.apps.map { AppCredential(clientId: trim($0.id), clientSecret: trim($0.secret)) }
        )
        if let problem = config.validationError { return .invalid("This connection code is incomplete: \(problem).") }
        return .ready(config)
    }

    static func base64urlDecode(_ text: String) -> Data? {
        var s = text.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s.append("=") }
        return Data(base64Encoded: s)
    }

    /// salt (16) | iv (12) | ciphertext | tag (16). Nil on a wrong PIN - GCM refuses to open.
    private static func decrypt(_ sealed: Data, pin: String) -> Data? {
        let bytes = [UInt8](sealed)
        guard bytes.count > 16 + 12 + 16 else { return nil }
        let salt = Array(bytes[0..<16])
        let iv = Array(bytes[16..<28])
        let body = Array(bytes[28...])
        guard let key = pbkdf2(pin: pin, salt: salt) else { return nil }
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: iv),
                ciphertext: body.dropLast(16),
                tag: body.suffix(16)
            )
            return try AES.GCM.open(box, using: SymmetricKey(data: key))
        } catch {
            return nil
        }
    }

    private static func pbkdf2(pin: String, salt: [UInt8]) -> [UInt8]? {
        var key = [UInt8](repeating: 0, count: 32)
        let status = CCKeyDerivationPBKDF(
            CCPBKDFAlgorithm(kCCPBKDF2),
            pin, pin.utf8.count,
            salt, salt.count,
            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), pinIterations,
            &key, key.count
        )
        return status == Int32(kCCSuccess) ? key : nil
    }
}
