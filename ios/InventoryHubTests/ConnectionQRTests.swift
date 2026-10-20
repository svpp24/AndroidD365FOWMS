import XCTest
@testable import InventoryHub

/// The vectors were made with the same steps as tools/make_connection_qr.py (salt 0..15,
/// iv 16..27, PIN 482915), so these prove the iPhone reads the codes the Python tool prints.
final class ConnectionQRTests: XCTestCase {
    private let plain = "HSMOCONN1:eyJ2IjoxLCJsYWJlbCI6IlRFU1QiLCJ1cmwiOiJodHRwczovL2V4YW1wbGUub3BlcmF0aW9ucy5keW5hbWljcy5jb20vIiwidGVuYW50IjoiMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAxIiwiY29tcGFueSI6ImJzcCIsImFwcHMiOlt7ImlkIjoiMTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTExIiwic2VjcmV0Ijoibm90LWEtcmVhbC1zZWNyZXQifV19"

    private let withPin = "HSMOCONN1P:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG9SaFLK3DFPHF4OUOMaMl65TYzGDyo6D8YTrHALishA2ErDyul-uI3umsngjEpVARBIBujDXf5gANXO4Wtz-pcDRtotPKPSmlZYaXjQh59MK6TdLKFihh9w7Xp9xt3LSs3UMqh2cAWzD9mYffh19zUrB8mXMAidSgpJOPb6O4XdGdyxoULdVKk8WD9ehdBC8mM10GqQMLgF888LVOHfVj8Co8COJ8RjH0IZ4FsFQySRTrzeRC6EGEm6RbuxGYJ_6OE5byYdJ87Yx05xbyR8PedWGluA8E95n206wdiV3U7aIroh9K--ia6LCm3k"

    private let expected = TenantConfig(
        label: "TEST",
        resourceUrl: "https://example.operations.dynamics.com",
        tenantId: "00000000-0000-0000-0000-000000000001",
        company: "BSP",
        apps: [AppCredential(clientId: "11111111-1111-1111-1111-111111111111", clientSecret: "not-a-real-secret")]
    )

    func testPlainCode() {
        XCTAssertEqual(ConnectionQR.read(plain), .ready(expected))
    }

    func testPinCodeAsksForThePin() {
        XCTAssertEqual(ConnectionQR.read(withPin), .needsPin)
    }

    func testPinCodeOpensWithTheRightPin() {
        XCTAssertEqual(ConnectionQR.read(withPin, pin: "482915"), .ready(expected))
    }

    func testWrongPinIsRefused() {
        XCTAssertEqual(ConnectionQR.read(withPin, pin: "000000"), .invalid("Wrong PIN, or the code is damaged."))
    }

    func testRawJson() {
        let json = #"{"url":"https://example.operations.dynamics.com/","tenant":"t","apps":[{"id":"a","secret":"s"}]}"#
        guard case .ready(let config) = ConnectionQR.read(json) else { return XCTFail("not read") }
        XCTAssertEqual(config.label, "UAT")
        XCTAssertEqual(config.servicesRoot, "https://example.operations.dynamics.com/api/services/")
    }

    func testOtherCodesAreNotConnectionCodes() {
        XCTAssertFalse(ConnectionQR.isConnectionCode("BSR-11397-0"))
        XCTAssertEqual(ConnectionQR.read("BSR-11397-0"), .invalid("This is not a connection code."))
    }

    func testHttpUrlIsRefused() {
        let json = #"{"url":"http://example.com","tenant":"t","apps":[{"id":"a","secret":"s"}]}"#
        guard case .invalid = ConnectionQR.read(json) else { return XCTFail("http accepted") }
    }
}
