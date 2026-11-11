package com.d365fo.mobileops.data.demo

import com.d365fo.mobileops.data.remote.dto.CountCategoryDto
import com.d365fo.mobileops.data.remote.dto.CountItemDto
import com.d365fo.mobileops.data.remote.dto.CountRequestServiceResponse
import com.d365fo.mobileops.data.remote.dto.CountResultDto
import com.d365fo.mobileops.data.remote.dto.CountStageServiceResponse
import com.d365fo.mobileops.data.remote.dto.CountSummaryDto
import com.d365fo.mobileops.data.remote.dto.CountBinServiceResponse
import com.d365fo.mobileops.data.remote.dto.DeviceWorkServiceResponse
import com.d365fo.mobileops.data.remote.dto.ItemServiceResponse
import com.d365fo.mobileops.data.remote.dto.BarcodeServiceResponse
import com.d365fo.mobileops.data.remote.dto.ItemUnitsServiceResponse
import com.d365fo.mobileops.data.remote.dto.JournalListServiceResponse
import com.d365fo.mobileops.data.remote.dto.LocationServiceResponse
import com.d365fo.mobileops.data.remote.dto.MenuServiceResponse
import com.d365fo.mobileops.data.remote.dto.ODataCollection
import com.d365fo.mobileops.data.remote.dto.OnHandDto
import com.d365fo.mobileops.data.remote.dto.OnHandServiceResponse
import com.d365fo.mobileops.data.remote.dto.OrderServiceResponse
import com.d365fo.mobileops.data.remote.dto.OrdersForDateServiceResponse
import com.d365fo.mobileops.data.remote.dto.PostQueueServiceResponse
import com.d365fo.mobileops.data.remote.dto.PostServiceResponse
import com.d365fo.mobileops.data.remote.dto.ProductBarcodeDto
import com.d365fo.mobileops.data.remote.dto.PurchOrderServiceResponse
import com.d365fo.mobileops.data.remote.dto.ReleasedProductDto
import com.d365fo.mobileops.data.remote.dto.SearchServiceResponse
import com.d365fo.mobileops.data.remote.dto.SessionServiceResponse
import com.d365fo.mobileops.data.remote.dto.SignInServiceResponse
import com.d365fo.mobileops.data.remote.dto.SkuPageServiceResponse
import com.d365fo.mobileops.data.remote.dto.TransferListServiceResponse
import com.d365fo.mobileops.data.remote.dto.TransferServiceResponse
import com.d365fo.mobileops.data.remote.dto.UserWarehousesServiceResponse
import com.d365fo.mobileops.data.remote.dto.WarehouseDto
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Every demo answer must decode into the DTO the app reads it with, using the app's own Json settings. */
class DemoBackendTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun svc(svc: String, op: String, request: String = """{"DataAreaId":"demo"}"""): String {
        val reply = DemoBackend.respond("POST", "/api/services/HSMobileOpsServiceGroup/$svc/$op", null, """{"_request":$request}""")
        assertThat(reply.code).isEqualTo(200)
        return reply.json
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    private fun <T> payload(serializer: KSerializer<T>, payloadJson: String?): List<T> =
        json.decodeFromString(ListSerializer(serializer), payloadJson!!)

    @BeforeEach
    fun setUp() = DemoBackend.reset()

    @Test
    fun `sign-in, menu, warehouses and app control decode`() {
        val signIn = decode(SignInServiceResponse.serializer(), svc("HSMobileOpsUserSvc", "signIn", """{"DataAreaId":"demo","UserName":"demo","Password":"1234"}"""))
        assertThat(signIn.success).isTrue()
        assertThat(signIn.userName).isEqualTo(DemoBackend.USER)
        assertThat(signIn.isSupervisor).isTrue()
        assertThat(signIn.isCycleCountSupervisor).isFalse()
        assertThat(signIn.warehouses.single { it.isDefault }.warehouseId).isEqualTo(DemoBackend.WAREHOUSE)
        assertThat(signIn.modulePermissions.map { it.module }).contains("PURCHASE_RETURN")

        val menu = decode(MenuServiceResponse.serializer(), svc("HSMobileOpsMenuSvc", "getMenu"))
        assertThat(menu.success).isTrue()
        assertThat(menu.menus.map { it.menuId }).contains(menu.rootMenuId)
        assertThat(menu.menus.flatMap { it.lines }.map { it.module }).containsAtLeast("PO_RECEIVING", "TRANSFER_SHIP", "INVENTORY_COUNT", "PO_CREATE")

        val wh = decode(UserWarehousesServiceResponse.serializer(), svc("HSMobileOpsUserWarehouseSvc", "getUserWarehouses"))
        assertThat(wh.warehouses.single().warehouseName).isEqualTo("Demo Store")

        assertThat(decode(OrderServiceResponse.serializer(), svc("HSMobileOpsOrderCreateSvc", "getModulePermissions")).permissions).isNotEmpty()
        assertThat(decode(DeviceWorkServiceResponse.serializer(), svc("HSMobileOpsDeviceWorkSvc", "getAppControl")).success).isTrue()
        assertThat(decode(DeviceWorkServiceResponse.serializer(), svc("HSMobileOpsDeviceWorkSvc", "listWork")).items).isEmpty()
        assertThat(decode(SessionServiceResponse.serializer(), svc("HSMobileOpsSessionSvc", "startSession")).success).isTrue()
        assertThat(decode(SessionServiceResponse.serializer(), svc("HSMobileOpsSessionSvc", "checkSession")).signedInElsewhere).isFalse()
    }

    @Test
    fun `purchase orders of today, receipt remembered`() {
        val today = LocalDate.now().toString()
        val orders = decode(OrdersForDateServiceResponse.serializer(),
            svc("HSMobileOpsPurchListSvc", "getOrdersForDate", """{"DataAreaId":"demo","WarehouseId":"MAIN","DeliveryDate":"$today"}"""))
        assertThat(orders.orders.size).isAtLeast(2)
        val purchId = orders.orders.first().purchId

        val po = decode(PurchOrderServiceResponse.serializer(), svc("HSMobileOpsReceivingSvc", "getPurchaseOrder", """{"DataAreaId":"demo","PurchId":"$purchId"}"""))
        assertThat(po.success).isTrue()
        assertThat(po.vendorName).isEqualTo("Demo Supplier")
        val line = po.lines.first()

        val lines = """[{"LineNumber":${line.lineNumber},"ItemId":"${line.itemId}","Quantity":${line.remainingQty},"UnitId":"Pcs","LocationId":"MAIN","BatchId":"B2601"}]"""
        val post = decode(PostServiceResponse.serializer(), svc("HSMobileOpsPostingSvc", "postProductReceipt",
            """{"DataAreaId":"demo","IdempotencyKey":"k1","SourceId":"$purchId","UserName":"DEMO","DeviceId":"d","LinesJson":${kotlinx.serialization.json.JsonPrimitive(lines)}}"""))
        assertThat(post.success).isTrue()
        assertThat(post.documentNumber).isNotEmpty()

        val after = decode(PurchOrderServiceResponse.serializer(), svc("HSMobileOpsReceivingSvc", "getPurchaseOrder", """{"DataAreaId":"demo","PurchId":"$purchId"}"""))
        assertThat(after.lines.map { it.lineNumber }).doesNotContain(line.lineNumber)

        val queue = decode(PostQueueServiceResponse.serializer(), svc("HSMobileOpsPostingSvc", "getQueueStatus"))
        assertThat(queue.rows.single().status).isEqualTo("Registered")
    }

    @Test
    fun `transfers decode`() {
        val list = decode(TransferListServiceResponse.serializer(), svc("HSMobileOpsTransferListSvc", "listTransferOrders", """{"DataAreaId":"demo","WarehouseId":"MAIN"}"""))
        assertThat(list.orders.count { it.direction == "OUT" }).isEqualTo(2)
        assertThat(list.orders.count { it.direction == "IN" }).isEqualTo(1)
        val out = list.orders.first { it.direction == "OUT" }.transferId
        val inbound = list.orders.first { it.direction == "IN" }.transferId

        val ship = decode(TransferServiceResponse.serializer(), svc("HSMobileOpsTransferSvc", "getTransferOrder", """{"DataAreaId":"demo","TransferId":"$out","Mode":"Ship"}"""))
        assertThat(ship.actionable).isTrue()
        assertThat(ship.lines).isNotEmpty()
        val receive = decode(TransferServiceResponse.serializer(), svc("HSMobileOpsTransferSvc", "getTransferOrder", """{"DataAreaId":"demo","TransferId":"$inbound","Mode":"Receive"}"""))
        assertThat(receive.actionable).isTrue()
        assertThat(receive.lines.all { it.remainingQty > 0 }).isTrue()
    }

    @Test
    fun `counts, items, results, categories and staging decode`() {
        val list = decode(CountRequestServiceResponse.serializer(), svc("HSMobileOpsCountRequestSvc", "listCounts", """{"DataAreaId":"demo","WarehouseId":"MAIN"}"""))
        val counts = payload(CountSummaryDto.serializer(), list.payloadJson)
        assertThat(counts.map { it.type }).containsAtLeast("Hazard", "Blind", "WallToWall")
        val hazard = counts.first { it.type == "Hazard" && it.status == "Open" }
        assertThat(hazard.itemCount).isEqualTo("4")

        val items = payload(CountItemDto.serializer(),
            decode(CountRequestServiceResponse.serializer(), svc("HSMobileOpsCountRequestSvc", "getCountItems", """{"DataAreaId":"demo","MasterId":"${hazard.masterId}"}""")).payloadJson)
        assertThat(items).hasSize(4)
        assertThat(items.first().category).isEqualTo("Health Care")

        val finished = counts.first { it.status == "Posted" }
        val results = decode(CountRequestServiceResponse.serializer(), svc("HSMobileOpsCountRequestSvc", "getCountResults", """{"DataAreaId":"demo","MasterId":"${finished.masterId}"}"""))
        assertThat(results.posted).isTrue()
        assertThat(payload(CountResultDto.serializer(), results.payloadJson)).isNotEmpty()

        val cats = payload(CountCategoryDto.serializer(),
            decode(CountRequestServiceResponse.serializer(), svc("HSMobileOpsCountRequestSvc", "listCategories")).payloadJson)
        assertThat(cats.map { it.name }).containsExactly("Beauty Care", "Health Care", "Personal Care")
        assertThat(cats.all { it.hasChildren == "true" }).isTrue()

        val created = decode(CountRequestServiceResponse.serializer(), svc("HSMobileOpsCountRequestSvc", "createCount",
            """{"DataAreaId":"demo","CountType":"Blind","CategoriesJson":"[\"5637100003\"]"}"""))
        assertThat(created.success).isTrue()
        assertThat(created.masterId).isEqualTo("MAIN-DEMO-5")

        val stage = decode(CountStageServiceResponse.serializer(), svc("HSMobileOpsCountStageSvc", "openStage", """{"DataAreaId":"demo","MasterId":"${hazard.masterId}"}"""))
        assertThat(stage.stageId).isNotEmpty()
        val lines = """[{\"ClientLineId\":\"c1\",\"ItemId\":\"${items.first().itemId}\",\"Quantity\":5.0}]"""
        val submitted = decode(CountStageServiceResponse.serializer(), svc("HSMobileOpsCountStageSvc", "submitLines",
            """{"DataAreaId":"demo","StageId":"${stage.stageId}","LinesJson":"$lines","EndCount":true}"""))
        assertThat(submitted.accepted).isEqualTo(1)
        val again = payload(CountItemDto.serializer(),
            decode(CountRequestServiceResponse.serializer(), svc("HSMobileOpsCountRequestSvc", "getCountItems", """{"DataAreaId":"demo","MasterId":"${hazard.masterId}"}""")).payloadJson)
        assertThat(again.first().counted).isEqualTo("true")

        assertThat(decode(CountBinServiceResponse.serializer(), svc("HSMobileOpsCountTaskSvc", "getCountBins")).bins.map { it.binId })
            .containsExactly("A-01", "A-02", "B-01")
        assertThat(decode(JournalListServiceResponse.serializer(), svc("HSMobileOpsCountingSvc", "listOpenJournals")).success).isTrue()
    }

    @Test
    fun `on-hand, items, search, sku page and order creation decode`() {
        val onHand = decode(OnHandServiceResponse.serializer(), svc("HSMobileOpsInquirySvc", "getOnHand", """{"DataAreaId":"demo","ItemId":"DM-1001"}"""))
        assertThat(onHand.rows.first { it.warehouseId == "MAIN" }.onHand).isEqualTo(360.0)
        assertThat(decode(LocationServiceResponse.serializer(), svc("HSMobileOpsInquirySvc", "validateLocation", """{"DataAreaId":"demo","WarehouseId":"MAIN","LocationId":"back"}""")).exists).isTrue()

        val item = decode(ItemServiceResponse.serializer(), svc("HSMobileOpsItemSvc", "getItem", """{"DataAreaId":"demo","ItemId":"DM-1001"}"""))
        assertThat(item.batchActive).isTrue()
        val sku = decode(SkuPageServiceResponse.serializer(), svc("HSMobileOpsSkuPageSvc", "getSkuPage", """{"DataAreaId":"demo","Feed":"BARCODES"}"""))
        val barcode = Json.parseToJsonElement(sku.rowsJson).let { it as kotlinx.serialization.json.JsonArray }.first()
            .let { (it as kotlinx.serialization.json.JsonArray)[0].toString().trim('"') }
        assertThat(barcode).hasLength(13)
        assertThat(decode(BarcodeServiceResponse.serializer(), svc("HSMobileOpsItemSvc", "getBarcode", """{"DataAreaId":"demo","Barcode":"$barcode"}""")).itemId).isEqualTo("DM-1001")
        assertThat(decode(ItemUnitsServiceResponse.serializer(), svc("HSMobileOpsUnitSvc", "getItemUnits", """{"DataAreaId":"demo","ItemsJson":"[{\"ItemId\":\"DM-2001\"}]"}""")).items.single().inventoryUnit).isEqualTo("Pcs")
        assertThat(decode(SearchServiceResponse.serializer(), svc("HSMobileOpsSearchSvc", "search", """{"DataAreaId":"demo","WarehouseId":"MAIN","Query":"DM-1001"}""")).hits).isNotEmpty()

        assertThat(decode(OrderServiceResponse.serializer(), svc("HSMobileOpsOrderCreateSvc", "listVendors")).vendors).isNotEmpty()
        assertThat(decode(OrderServiceResponse.serializer(), svc("HSMobileOpsOrderCreateSvc", "getOrderItem", """{"DataAreaId":"demo","ItemId":"DM-2002"}""")).item?.itemId).isEqualTo("DM-2002")
        val po = decode(OrderServiceResponse.serializer(), svc("HSMobileOpsOrderCreateSvc", "createPurchaseOrder",
            """{"DataAreaId":"demo","IdempotencyKey":"o1","VendorAccount":"DV-0001","LinesJson":"[{\"ItemId\":\"DM-2002\",\"Qty\":10}]"}"""))
        assertThat(po.success).isTrue()
        assertThat(po.orderTotal).isEqualTo(21.5)
        val again = decode(OrderServiceResponse.serializer(), svc("HSMobileOpsOrderCreateSvc", "createPurchaseOrder",
            """{"DataAreaId":"demo","IdempotencyKey":"o1","VendorAccount":"DV-0001","LinesJson":"[{\"ItemId\":\"DM-2002\",\"Qty\":10}]"}"""))
        assertThat(again.alreadyCreated).isTrue()
        assertThat(again.documentNumber).isEqualTo(po.documentNumber)
        val toConfirm = decode(OrderServiceResponse.serializer(), svc("HSMobileOpsOrderCreateSvc", "listPurchaseOrdersToConfirm"))
        assertThat(toConfirm.orders).hasSize(1)
        assertThat(decode(OrderServiceResponse.serializer(), svc("HSMobileOpsOrderCreateSvc", "listReturnReasons")).reasons).isNotEmpty()
    }

    @Test
    fun `odata entities decode and filter`() {
        fun <T> odata(path: String, query: String?, serializer: KSerializer<T>): List<T> {
            val reply = DemoBackend.respond("GET", "/data/$path", query, null)
            assertThat(reply.code).isEqualTo(200)
            return json.decodeFromString(ODataCollection.serializer(serializer), reply.json).value
        }
        assertThat(odata("ReleasedProductsV2", null, ReleasedProductDto.serializer())).hasSize(13)
        assertThat(odata("ProductBarcodesV3", "%24filter=ItemNumber%20eq%20%27DM-1001%27", ProductBarcodeDto.serializer())).hasSize(1)
        assertThat(odata("Warehouses", null, WarehouseDto.serializer()).map { it.warehouseId }).containsExactly("MAIN", "STORE2")
        val onHand = odata("WarehousesOnHand", "\$filter=ItemNumber eq 'DM-1002' and InventoryWarehouseId eq 'MAIN'&\$top=10", OnHandDto.serializer())
        assertThat(onHand.single().physicalInventory).isEqualTo(240.0)
    }

    @Test
    fun `fallbacks never throw`() {
        assertThat(DemoBackend.respond("POST", "/api/services/X/Y/z", null, "not json").json).contains("\"Success\":true")
        assertThat(DemoBackend.respond("GET", "/data/Unknown", "\$top=1", null).json).isEqualTo("""{"value":[]}""")
        assertThat(DemoBackend.respond("GET", "/whatever", null, null).json).isEqualTo("{}")
        assertThat(svc("HSMobileOpsReceivingSvc", "getPurchaseOrder", "{}")).contains("NOT_FOUND")
    }
}
