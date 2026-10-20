import XCTest
@testable import InventoryHub

/// The JSON shape D365FO custom services expect: parameters under `_request`, PascalCase names.
final class WireFormatTests: XCTestCase {
    func testRequestIsWrappedAndPascalCased() throws {
        let body = ServiceRequest(request: OnHandRequest(dataAreaId: "BSP", itemId: "BSR-11397-0", warehouseId: "AAC"))
        let data = try D365Client.encoder.encode(body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let inner = try XCTUnwrap(json["_request"] as? [String: Any])
        XCTAssertEqual(inner["DataAreaId"] as? String, "BSP")
        XCTAssertEqual(inner["ItemId"] as? String, "BSR-11397-0")
        XCTAssertEqual(inner["WarehouseId"] as? String, "AAC")
    }

    func testSignInResponseReads() throws {
        let json = """
        {"Success":true,"UserName":"001","DisplayName":"Pradeep Kumar","IsSupervisor":false,
         "Warehouses":[{"SiteId":"S1","WarehouseId":"AAC","WarehouseName":"Main store","IsDefault":true}],
         "ModulePermissions":[{"Module":"PO_RECEIVING","IsAllowed":false}]}
        """
        let response = try D365Client.decoder.decode(SignInResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.success, true)
        XCTAssertEqual(response.warehouses?.first?.warehouseId, "AAC")
        XCTAssertEqual(response.warehouses?.first?.title, "Main store (AAC)")
        XCTAssertEqual(response.modulePermissions?.first?.module, "PO_RECEIVING")
        XCTAssertNil(response.errorCode)
    }

    func testRefusalWording() throws {
        let json = #"{"Success":false,"ErrorCode":"INVALID_CREDENTIALS"}"#
        let response = try D365Client.decoder.decode(SignInResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.refusal, "That user name and PIN do not match")
    }

    func testBlockedModulesHideTheirGroup() {
        let groups = Modules.groups(blocked: ["INVENTORY_COUNT"])
        XCTAssertFalse(groups.contains { $0.title == "Inventory count" })
        XCTAssertTrue(groups.contains { $0.title == "Inquiries" })
    }

    func testOnHandRowsRead() throws {
        let json = #"{"Success":true,"Rows":[{"ItemId":"X","WarehouseId":"AAC","OnHand":12,"Available":10.5}]}"#
        let response = try D365Client.decoder.decode(OnHandResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.rows?.first?.available, 10.5)
        XCTAssertNil(response.rows?.first?.reserved)
    }
}
