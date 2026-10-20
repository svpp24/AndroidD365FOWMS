import Foundation

/// What went wrong talking to D365FO, in words a worker can act on.
enum AppError: LocalizedError, Equatable {
    case notConfigured
    case offline
    case authentication(String)
    case server(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: return "This device is not connected to D365FO yet."
        case .offline: return "No connection to D365FO. Check the network and try again."
        case .authentication(let message): return "D365FO did not accept this device: \(message)"
        case .server(let message): return message
        }
    }
}

/// The client-credentials token from Entra ID - the app registration itself is the caller, as on
/// Android (ClientCredentialsTokenProvider). Worker identity comes from HSMobileOpsUser sign-in,
/// never from this token. One token is cached per registration.
actor TokenProvider {
    private struct Token {
        let value: String
        let expires: Date
    }

    private struct TokenResponse: Decodable {
        let access_token: String
        let expires_in: Double
    }

    private struct TokenError: Decodable {
        let error: String?
        let error_description: String?
    }

    private var cache: [String: Token] = [:]
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func token(for config: TenantConfig) async throws -> String {
        guard let app = config.apps.first else { throw AppError.notConfigured }
        if let cached = cache[app.clientId], cached.expires.timeIntervalSinceNow > 120 { return cached.value }

        var request = URLRequest(url: URL(string: "https://login.microsoftonline.com/\(config.tenantId)/oauth2/v2.0/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.form([
            "grant_type": "client_credentials",
            "client_id": app.clientId,
            "client_secret": app.clientSecret,
            "scope": config.resourceUrl + "/.default",
        ])

        let (data, response) = try await httpSend(request, session: session)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let body = try? JSONDecoder().decode(TokenResponse.self, from: data) else {
            let aad = try? JSONDecoder().decode(TokenError.self, from: data)
            // AAD descriptions open with a code and correlation id; the first line is the readable part.
            let line = aad?.error_description?.split(separator: "\n").first.map(String.init)
            throw AppError.authentication(line ?? aad?.error ?? "Entra ID refused the token request")
        }
        cache[app.clientId] = Token(value: body.access_token, expires: Date().addingTimeInterval(body.expires_in))
        return body.access_token
    }

    func clear() {
        cache.removeAll()
    }

    private static func form(_ fields: [String: String]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return Data(fields.map { key, value in
            "\(key)=\(value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value)"
        }.joined(separator: "&").utf8)
    }
}

/// The HSMobileOps custom services (`/api/services/HSMobileOpsServiceGroup/...`) - the same
/// contracts the Android app calls (MobileOpsServiceApi.kt). Every call is a POST with the
/// parameters under `_request`; field names are PascalCase on the wire.
@MainActor
final class D365Client {
    private let tokens: TokenProvider
    private let session: URLSession
    /// Set by AppModel whenever the connection changes.
    var config: TenantConfig?

    init(tokens: TokenProvider = TokenProvider(), session: URLSession = .shared) {
        self.tokens = tokens
        self.session = session
    }

    /// The D365FO data contract style: `itemId` goes out as `ItemId`.
    nonisolated static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .custom { path in
            WireKey(path.last!.stringValue.prefix(1).uppercased() + path.last!.stringValue.dropFirst())
        }
        return encoder
    }()

    /// And `ItemId` comes back as `itemId`.
    nonisolated static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .custom { path in
            WireKey(path.last!.stringValue.prefix(1).lowercased() + path.last!.stringValue.dropFirst())
        }
        return decoder
    }()

    func call<Body: Encodable, Response: Decodable>(
        _ service: String,
        _ operation: String,
        _ body: Body,
        as type: Response.Type = Response.self
    ) async throws -> Response {
        guard let config else { throw AppError.notConfigured }
        let token = try await tokens.token(for: config)
        guard let url = URL(string: config.servicesRoot + "HSMobileOpsServiceGroup/\(service)/\(operation)") else {
            throw AppError.notConfigured
        }
        var request = URLRequest(url: url, timeoutInterval: 60)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try Self.encoder.encode(ServiceRequest(request: body))

        let (data, response) = try await httpSend(request, session: session)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200:
            do {
                return try Self.decoder.decode(Response.self, from: data)
            } catch {
                throw AppError.server("D365FO sent an answer this app could not read.")
            }
        case 401, 403:
            await tokens.clear()
            throw AppError.authentication("HTTP \(status)")
        case 429:
            throw AppError.server("D365FO is busy. Wait a moment and try again.")
        default:
            throw AppError.server("D365FO returned an error (HTTP \(status)).")
        }
    }

    func signOut() async {
        await tokens.clear()
    }

}

/// Network failures as `AppError.offline`, so screens can say so plainly.
func httpSend(_ request: URLRequest, session: URLSession) async throws -> (Data, URLResponse) {
    do {
        return try await session.data(for: request)
    } catch let error as URLError {
        switch error.code {
        case .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost, .timedOut, .dnsLookupFailed:
            throw AppError.offline
        default:
            throw AppError.server(error.localizedDescription)
        }
    }
}

/// A D365FO custom service takes its parameters under one `_request` member.
struct ServiceRequest<Body: Encodable>: Encodable {
    let request: Body

    private enum CodingKeys: String, CodingKey {
        case request = "_request"
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(request, forKey: .request)
    }
}

struct WireKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }

    init(_ string: String) {
        stringValue = string
    }

    init?(stringValue: String) {
        self.stringValue = stringValue
    }

    init?(intValue: Int) {
        return nil
    }
}
