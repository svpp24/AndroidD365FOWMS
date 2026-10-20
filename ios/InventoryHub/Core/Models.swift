import Foundation

// The HSMobileOps service contracts this app calls - the same ones as Android
// (app/.../data/remote/dto). Responses are all-optional: an older AOS package may not send a field.

// MARK: - Sign-in (HSMobileOpsUserSvc/signIn)

struct SignInRequest: Encodable {
    let dataAreaId: String
    let userName: String
    /// Sent once, never stored on the device and never returned.
    let password: String
    let deviceId: String
    let deviceBrand: String
    let deviceModel: String
    let platform: String
    let platformVersion: String
    let appVersionName: String
}

struct SignInResponse: Decodable {
    let success: Bool?
    let errorCode: String?
    let message: String?
    let userName: String?
    let displayName: String?
    let personnelNumber: String?
    let isSupervisor: Bool?
    let isCycleCountSupervisor: Bool?
    let mustChangePassword: Bool?
    let warehouses: [WarehouseAssignment]?
    let licenseWarning: String?
    let modulePermissions: [ModulePermission]?

    /// What the sign-in screen says for a refusal - the server's own wording where it gives one,
    /// matching the Android app (WorkerSessionRepository.signIn).
    var refusal: String {
        switch errorCode {
        case "INVALID_CREDENTIALS": return "That user name and PIN do not match"
        case "INACTIVE": return "This account has been deactivated"
        case "LOCKED_OUT": return message ?? "Too many failed attempts. Try again shortly."
        case "NO_WAREHOUSE": return "No warehouse is assigned to you. Ask a supervisor."
        case "NO_LICENSE", "LICENSE_INVALID", "LICENSE_EXPIRED", "DEVICE_LIMIT_REACHED":
            return message ?? "This app is not licensed. Contact your administrator."
        default: return message ?? "Could not sign in"
        }
    }
}

struct WarehouseAssignment: Codable, Hashable, Identifiable {
    let siteId: String?
    let warehouseId: String?
    let warehouseName: String?
    let isDefault: Bool?

    var id: String { warehouseId ?? "" }
    var title: String {
        let name = warehouseName ?? ""
        return name.isEmpty ? id : "\(name) (\(id))"
    }
}

struct ModulePermission: Codable, Hashable {
    let module: String?
    let isAllowed: Bool?
}

/// The signed-in worker, kept for the session only (memory), as on Android.
struct Worker: Equatable {
    let userName: String
    let displayName: String
    let isSupervisor: Bool
    let warehouses: [WarehouseAssignment]
    /// Explicit restrictions only - a module not listed is allowed.
    let blockedModules: Set<String>
    let licenseWarning: String?

    var firstName: String {
        let name = displayName.isEmpty ? userName : displayName
        return name.split(separator: " ").first.map(String.init) ?? name
    }
}

// MARK: - On-hand (HSMobileOpsInquirySvc/getOnHand)

struct OnHandRequest: Encodable {
    let dataAreaId: String
    let itemId: String
    /// Blank means every warehouse.
    let warehouseId: String
}

struct OnHandResponse: Decodable {
    let success: Bool?
    let errorCode: String?
    let rows: [OnHandRow]?
}

struct OnHandRow: Decodable, Hashable {
    let itemId: String?
    let siteId: String?
    let warehouseId: String?
    let onHand: Double?
    let available: Double?
    let reserved: Double?
    let ordered: Double?
    let totalAvailable: Double?
}
