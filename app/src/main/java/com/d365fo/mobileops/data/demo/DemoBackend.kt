package com.d365fo.mobileops.data.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * In-memory fake D365FO for demo mode. Thread-safe.
 *
 * The demo interceptor hands every request to [respond], which answers it the way the HSMobileOps
 * AOS services and the OData endpoints would - same member names, same shapes - from a small,
 * entirely fictional company: one store ([WAREHOUSE], "Demo Store"), a second store to transfer
 * to, a dozen household and pharmacy items, a few purchase and transfer orders and some counts.
 *
 * What the worker does (receipts, shipments, counts, new orders) is remembered in memory, so the
 * app shows it back - a received line stops being outstanding, a count shows its items counted.
 * [reset] puts everything back as it was. Nothing here ever throws: an unexpected request gets a
 * plain "success" answer instead.
 */
object DemoBackend {
    const val USER = "DEMO"
    const val COMPANY = "demo"
    const val WAREHOUSE = "MAIN"
    const val SITE = "1"

    private const val USER_NAME = "Demo User"
    private const val STORE2 = "STORE2"
    private const val TRANSIT = "TRANSIT"
    private const val UNIT = "Pcs"
    private const val BATCH = "B2601"
    private const val CURRENCY = "USD"
    private const val VENDOR = "DV-0001"

    data class Reply(val code: Int, val json: String)

    /**
     * method = "GET"/"POST"/..., path = URL encoded path e.g.
     * "/api/services/HSMobileOpsServiceGroup/HSMobileOpsMenuSvc/getMenu" or "/data/WarehousesOnHand",
     * query = raw query or null, body = request body text or null.
     */
    fun respond(method: String, path: String, query: String?, body: String?): Reply {
        val decodedPath = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        return try {
            synchronized(lock) {
                when {
                    decodedPath.contains("/api/services/") -> service(decodedPath.substringAfter("/api/services/"), requestOf(body))
                    decodedPath.contains("/data/") -> odata(decodedPath.substringAfter("/data/"), query)
                    else -> ok(obj())
                }
            }
        } catch (t: Throwable) {
            fallback(decodedPath)
        }
    }

    /** Clears in-memory state (counts created, receipts posted...) when demo mode starts. */
    fun reset() {
        synchronized(lock) { state = State() }
    }

    // =============================================================================================
    // Master data - fixed for the whole demo
    // =============================================================================================

    private class Category(val id: String, val name: String, val parent: String = "")

    /** The retail product hierarchy: three first-level categories, each with sub-categories. */
    private val categories = listOf(
        Category("5637100001", "Health Care"),
        Category("5637100002", "Personal Care"),
        Category("5637100003", "Beauty Care"),
        Category("5637100011", "Pain Relief", "5637100001"),
        Category("5637100012", "Vitamins and Supplements", "5637100001"),
        Category("5637100013", "First Aid", "5637100001"),
        Category("5637100021", "Oral Care", "5637100002"),
        Category("5637100022", "Hair Care", "5637100002"),
        Category("5637100023", "Bath and Body", "5637100002"),
        Category("5637100031", "Skin Care", "5637100003"),
        Category("5637100032", "Lip Care", "5637100003"),
    )

    /** One released item. [main] and [back] are its opening on-hand in the store's two locations. */
    private class Item(
        val id: String,
        val name: String,
        val category: String,
        val batch: Boolean,
        val price: Double,
        val main: Double,
        val back: Double,
    ) {
        /** EAN-13 in the 200-299 "in-store" range, so it can never clash with a real product. */
        val barcode: String = ean13("2001234" + id.filter(Char::isDigit).padStart(4, '0').takeLast(4) + "0")
    }

    private val items = listOf(
        Item("DM-1001", "Paracetamol 500mg Tablets 24s", "5637100011", true, 2.49, 120.0, 240.0),
        Item("DM-1002", "Ibuprofen 200mg Tablets 16s", "5637100011", true, 3.19, 80.0, 160.0),
        Item("DM-1003", "Vitamin C 1000mg Effervescent 20s", "5637100012", true, 5.99, 45.0, 90.0),
        Item("DM-1004", "Multivitamin Daily Tablets 30s", "5637100012", true, 7.49, 36.0, 72.0),
        Item("DM-1005", "Antiseptic Cream 30g", "5637100013", false, 3.99, 24.0, 48.0),
        Item("DM-1006", "Adhesive Bandages Assorted 20s", "5637100013", false, 2.29, 60.0, 100.0),
        Item("DM-2001", "Liquid Hand Soap 250ml", "5637100023", false, 1.89, 72.0, 144.0),
        Item("DM-2002", "Fluoride Toothpaste Mint 100ml", "5637100021", false, 2.15, 90.0, 180.0),
        Item("DM-2003", "Daily Care Shampoo 400ml", "5637100022", false, 3.75, 40.0, 80.0),
        Item("DM-2004", "Deodorant Roll-on 50ml", "5637100023", false, 2.95, 55.0, 110.0),
        Item("DM-3001", "Moisturising Body Lotion 200ml", "5637100031", false, 4.50, 30.0, 60.0),
        Item("DM-3002", "Lip Balm Original 4g", "5637100032", false, 1.49, 100.0, 200.0),
        Item("DM-3003", "Cotton Pads 80s", "5637100031", false, 1.29, 70.0, 120.0),
    )

    private val warehouses = listOf(
        Triple(WAREHOUSE, "Demo Store", SITE),
        Triple(STORE2, "Second Store", SITE),
    )

    private val locations = listOf("MAIN", "BACK")

    private val vendors = listOf(
        Triple(VENDOR, "Demo Supplier", "Net 30"),
        Triple("DV-0002", "Sample Wholesale Co.", "Net 45"),
        Triple("DV-0003", "Example Health Distributors", "Net 30"),
    )

    private fun item(code: String?): Item? {
        val c = code?.trim().orEmpty()
        if (c.isEmpty()) return null
        return items.firstOrNull { it.id.equals(c, true) || it.barcode == c }
    }

    private fun topCategory(item: Item): Category? {
        var c = categories.firstOrNull { it.id == item.category } ?: return null
        while (c.parent.isNotBlank()) c = categories.firstOrNull { it.id == c.parent } ?: break
        return c
    }

    /** [categoryId] and every category under it. */
    private fun subtree(categoryId: String): Set<String> {
        val out = mutableSetOf(categoryId)
        var grew = true
        while (grew) {
            val next = categories.filter { it.parent in out }.map { it.id }
            grew = out.addAll(next)
        }
        return out
    }

    private fun itemsIn(categoryId: String): List<Item> = subtree(categoryId).let { ids -> items.filter { it.category in ids } }

    // =============================================================================================
    // In-memory state - what the worker changes
    // =============================================================================================

    private class PoLine(val lineNumber: Double, val itemId: String, val ordered: Double, var received: Double = 0.0)

    private class Po(
        val id: String,
        val vendor: String,
        /** Null: always "today", whatever day the demo runs. */
        var date: String?,
        val lines: MutableList<PoLine>,
        var confirmed: Boolean = true,
        val createdBy: String = "Admin",
    )

    private class ToLine(val lineNumber: Double, val itemId: String, val qty: Double, var shipped: Double = 0.0, var received: Double = 0.0)

    private class To(val id: String, val from: String, val to: String, val lines: List<ToLine>) {
        val status: String
            get() = when {
                lines.all { it.received >= it.qty } -> "Received"
                lines.all { it.shipped >= it.qty } -> "Shipped"
                else -> "Created"
            }
    }

    private class Count(
        val masterId: String,
        val type: String,
        val reference: String,
        val description: String,
        var status: String,
        val journalId: String = "",
        val itemIds: List<String> = emptyList(),
        val counted: MutableSet<String> = mutableSetOf(),
        var lineCount: Int = 0,
        var counterCount: Int = 0,
        var myStatus: String = "",
        /** itemId, counted, on hand - finished counts only. */
        val results: List<Triple<String, Double, Double>> = emptyList(),
    )

    private class State {
        /** On-hand of [WAREHOUSE]: item -> location -> quantity. */
        val stock: MutableMap<String, MutableMap<String, Double>> =
            items.associate { it.id to mutableMapOf("MAIN" to it.main, "BACK" to it.back) }.toMutableMap()

        val pos = mutableListOf(
            Po("DPO-000101", VENDOR, null, mutableListOf(
                PoLine(1.0, "DM-1001", 48.0), PoLine(2.0, "DM-1002", 24.0), PoLine(3.0, "DM-2002", 36.0),
            )),
            Po("DPO-000102", VENDOR, null, mutableListOf(
                PoLine(1.0, "DM-2001", 24.0), PoLine(2.0, "DM-2003", 12.0),
            )),
            Po("DPO-000103", "DV-0002", null, mutableListOf(
                PoLine(1.0, "DM-1005", 12.0), PoLine(2.0, "DM-1006", 30.0), PoLine(3.0, "DM-3001", 18.0), PoLine(4.0, "DM-3002", 50.0),
            )),
            /** Approved, waiting for PO confirmation. */
            Po("DPO-000150", VENDOR, null, mutableListOf(
                PoLine(1.0, "DM-1003", 24.0), PoLine(2.0, "DM-1004", 24.0),
            ), confirmed = false, createdBy = USER),
        )

        val transfers = mutableListOf(
            To("DTO-000201", WAREHOUSE, STORE2, listOf(ToLine(1.0, "DM-1001", 24.0), ToLine(2.0, "DM-2002", 12.0), ToLine(3.0, "DM-3002", 20.0))),
            To("DTO-000202", WAREHOUSE, STORE2, listOf(ToLine(1.0, "DM-2004", 10.0), ToLine(2.0, "DM-3003", 15.0))),
            To("DTO-000301", STORE2, WAREHOUSE, listOf(
                ToLine(1.0, "DM-1003", 12.0, shipped = 12.0), ToLine(2.0, "DM-2003", 6.0, shipped = 6.0), ToLine(3.0, "DM-3001", 8.0, shipped = 8.0),
            )),
        )

        val counts = mutableListOf(
            Count("MAIN-DEMO-1", "Hazard", "DCC-000001", "Shelf A damaged - check stock", "Open",
                itemIds = listOf("DM-1001", "DM-1002", "DM-2002", "DM-3001")),
            Count("MAIN-DEMO-2", "Blind", "DCC-000002", "Weekly blind count - personal care", "Open",
                itemIds = listOf("DM-2001", "DM-2003", "DM-3002")),
            Count("MAIN-DEMO-3", "WallToWall", "DCC-000003", "Quarterly wall to wall count", "Open"),
            Count("MAIN-DEMO-4", "Hazard", "DCC-000004", "Leaking stock in the back room", "Posted",
                journalId = "DEMO-JNL-0001", itemIds = listOf("DM-1003", "DM-1004", "DM-2004"),
                counted = mutableSetOf("DM-1003", "DM-1004", "DM-2004"), lineCount = 3, counterCount = 1, myStatus = "Submitted",
                results = listOf(Triple("DM-1003", 88.0, 90.0), Triple("DM-1004", 72.0, 72.0), Triple("DM-2004", 112.0, 110.0))),
        )

        val bins = mutableListOf("A-01" to "MAIN", "A-02" to "MAIN", "B-01" to "BACK")
        val stages = mutableMapOf<String, String>()
        val queue = mutableListOf<JsonObject>()
        val documents = mutableMapOf<String, JsonObject>()
        val feedback = mutableListOf<JsonObject>()
        var seq = 0
        var countSeq = 4

        fun next(): Int = ++seq
    }

    private val lock = Any()
    private var state = State()

    // =============================================================================================
    // Routing
    // =============================================================================================

    /** `HSMobileOpsServiceGroup/<Svc>/<operation>` with the `_request` object already unwrapped. */
    private fun service(rest: String, r: JsonObject): Reply {
        val parts = rest.substringBefore('?').trim('/').split('/')
        val svc = parts.getOrNull(parts.size - 2).orEmpty()
        val op = parts.lastOrNull().orEmpty()
        return when (svc) {
            "HSMobileOpsUserSvc" -> userSvc(op, r)
            "HSMobileOpsSessionSvc" -> ok(obj("Success" to true, "SignedInElsewhere" to false))
            "HSMobileOpsMenuSvc" -> menu()
            "HSMobileOpsUserWarehouseSvc" -> ok(obj("Success" to true, "Warehouses" to assignedWarehouses()))
            "HSMobileOpsDeviceWorkSvc" -> deviceWork(op, r)
            "HSMobileOpsSignInLogSvc" -> ok(obj("Success" to true))
            "HSMobileOpsLicenseSvc" -> ok(obj("Success" to true, "ServerUtcNow" to nowUtc()))
            "HSMobileOpsPurchListSvc" -> ordersForDate(r)
            "HSMobileOpsReceivingSvc" -> receiving(op, r)
            "HSMobileOpsPostingSvc" -> posting(op, r)
            "HSMobileOpsTransferListSvc" -> transferList(op, r)
            "HSMobileOpsTransferSvc" -> transfer(op, r)
            "HSMobileOpsCountRequestSvc" -> countRequest(op, r)
            "HSMobileOpsCountStageSvc" -> countStage(op, r)
            "HSMobileOpsCountTaskSvc" -> countTask(op, r)
            "HSMobileOpsCountingSvc" -> if (op == "listOpenJournals") ok(obj("Success" to true, "Rows" to emptyList<Any>())) else fallback(rest)
            "HSMobileOpsInquirySvc" -> inquiry(op, r)
            "HSMobileOpsPriceCheckSvc" -> priceCheck(r)
            "HSMobileOpsItemSvc" -> itemSvc(op, r)
            "HSMobileOpsUnitSvc" -> itemUnits(r)
            "HSMobileOpsSearchSvc" -> search(r)
            "HSMobileOpsSkuPageSvc", "HSMobileOpsSkuSvc" -> skuPage(r)
            "HSMobileOpsOrderCreateSvc" -> orderCreate(op, r)
            "HSMobileOpsFeedbackSvc" -> feedback(op, r)
            else -> fallback("/api/services/$rest")
        }
    }

    private fun fallback(path: String): Reply = when {
        path.contains("/data/") -> ok(obj("value" to emptyList<Any>()))
        path.contains("api/services/") || path.contains("HSMobileOps") -> ok(obj("Success" to true, "success" to true))
        else -> ok(obj())
    }

    // =============================================================================================
    // Worker identity: sign-in, menus, warehouses, permissions, app control
    // =============================================================================================

    /** Any user name and PIN sign in as the demo supervisor (not a cycle count supervisor). */
    private fun userSvc(op: String, r: JsonObject): Reply = when (op) {
        "signIn" -> ok(
            obj(
                "Success" to true,
                "UserName" to USER,
                "DisplayName" to USER_NAME,
                "PersonnelNumber" to "DEMO-0001",
                "IsSupervisor" to true,
                "IsCycleCountSupervisor" to false,
                "MaxOverPercent" to 0.0,
                "MaxOverQty" to 0.0,
                "MustChangePassword" to false,
                "Warehouses" to assignedWarehouses(),
                "ModulePermissions" to permissions(),
            ),
        )
        "getWorkerDirectory" -> ok(obj("Success" to true))
        else -> ok(obj("Success" to true))
    }

    private fun assignedWarehouses() = listOf(
        obj("SiteId" to SITE, "WarehouseId" to WAREHOUSE, "WarehouseName" to "Demo Store", "IsDefault" to true),
    )

    /** Every module allowed - including the order modules, which need an explicit allow. */
    private val modules = listOf(
        "PO_RECEIVING", "TRANSFER_SHIP", "TRANSFER_RECEIVE", "INVENTORY_COUNT", "LOCATION_INQUIRY", "PO_OF_TODAY",
        "PO_LINES", "TRANSFER_ORDERS", "TRANSFERS_BY_STORE", "PICKED_SERIALS", "PO_CREATE", "TO_CREATE", "PO_CONFIRM",
        "PURCHASE_RETURN",
    )

    private fun permissions() = modules.map { obj("Module" to it, "IsAllowed" to true) }

    /** One root menu with a sub-menu per area; module names are WarehouseWorkflow names. */
    private fun menu(): Reply {
        fun module(name: String) = obj("LineType" to "MODULE", "Module" to name)
        fun sub(id: String) = obj("LineType" to "MENU", "SubMenuId" to id)
        return ok(
            obj(
                "Success" to true,
                "RootMenuId" to "DEMO-MAIN",
                "Menus" to listOf(
                    obj("MenuId" to "DEMO-MAIN", "Name" to "Demo Store", "Lines" to listOf(
                        sub("DEMO-RECEIVE"), sub("DEMO-TRANSFER"), sub("DEMO-COUNT"), sub("DEMO-ORDERS"), sub("DEMO-ENQUIRY"),
                    )),
                    obj("MenuId" to "DEMO-RECEIVE", "Name" to "Receiving", "Lines" to listOf(
                        module("PO_RECEIVING"), module("PO_OF_TODAY"), module("PO_LINES"),
                    )),
                    obj("MenuId" to "DEMO-TRANSFER", "Name" to "Transfers", "Lines" to listOf(
                        module("TRANSFER_SHIP"), module("TRANSFER_RECEIVE"), module("TRANSFER_ORDERS"), module("TRANSFERS_BY_STORE"),
                    )),
                    obj("MenuId" to "DEMO-COUNT", "Name" to "Counting", "Lines" to listOf(module("INVENTORY_COUNT"))),
                    obj("MenuId" to "DEMO-ORDERS", "Name" to "Orders", "Lines" to listOf(
                        module("PO_CREATE"), module("TO_CREATE"), module("PO_CONFIRM"), module("PURCHASE_RETURN"),
                    )),
                    obj("MenuId" to "DEMO-ENQUIRY", "Name" to "Enquiry", "Lines" to listOf(
                        module("LOCATION_INQUIRY"), module("PICKED_SERIALS"),
                    )),
                ),
            ),
        )
    }

    /** Unsent-work backup and module switches: nothing switched off, nothing stored server-side. */
    private fun deviceWork(op: String, r: JsonObject): Reply = when (op) {
        "syncWork" -> ok(obj("Success" to true, "Stored" to parseArray(r.s("ItemsJson")).size, "DisabledModules" to emptyList<Any>()))
        else -> ok(obj("Success" to true, "Items" to emptyList<Any>(), "DisabledModules" to emptyList<Any>()))
    }

    // =============================================================================================
    // Purchase orders and receiving
    // =============================================================================================

    private fun today(): String = LocalDate.now().toString()

    private fun Po.deliveryDate(): String = date ?: today()

    private fun Po.open(): Boolean = lines.any { it.received < it.ordered }

    private fun vendorName(account: String) = vendors.firstOrNull { it.first == account }?.second ?: account

    /** PO of today: confirmed orders delivering on the asked date (blank date: all of them). */
    private fun ordersForDate(r: JsonObject): Reply {
        val date = r.s("DeliveryDate")
        val wh = r.s("WarehouseId")
        val rows = state.pos.filter { it.confirmed && (date.isBlank() || it.deliveryDate() == date) && (wh.isBlank() || wh.equals(WAREHOUSE, true)) }
            .map {
                obj(
                    "PurchId" to it.id, "VendorAccount" to it.vendor, "Status" to if (it.open()) "Backorder" else "Received",
                    "HasOpenLines" to it.open(), "SiteId" to SITE, "WarehouseId" to WAREHOUSE, "DeliveryDate" to it.deliveryDate(),
                )
            }
        return ok(obj("Success" to true, "Orders" to rows))
    }

    private fun units() = listOf(obj("UnitId" to UNIT, "Factor" to 1.0, "Decimals" to 0))

    private fun poLine(l: PoLine): JsonObject {
        val it = item(l.itemId)
        val remaining = (l.ordered - l.received).coerceAtLeast(0.0)
        return obj(
            "LineNumber" to l.lineNumber, "ItemId" to l.itemId, "ItemName" to it?.name.orEmpty(),
            "MobileDescription1" to it?.name.orEmpty(), "PurchUnit" to UNIT, "InventUnit" to UNIT,
            "OrderedQty" to l.ordered, "ReceivedQty" to l.received, "RemainingQty" to remaining, "UncommittedQty" to 0.0,
            "SiteId" to SITE, "WarehouseId" to WAREHOUSE, "LocationId" to "MAIN",
            "BatchRequired" to (it?.batch == true), "SerialRequired" to false, "LocationRequired" to true,
            "BatchMandatory" to true, "SerialMandatory" to false, "LocationMandatory" to true, "SerialPerUnit" to true,
            "OverDeliveryPct" to 10.0, "UnderDeliveryPct" to 100.0,
            "LineStatus" to if (remaining > 0) "Backorder" else "Received", "Stopped" to false, "Units" to units(),
        )
    }

    private fun receiving(op: String, r: JsonObject): Reply {
        val po = state.pos.firstOrNull { it.id.equals(r.s("PurchId"), true) }
            ?: return ok(obj("Success" to false, "ErrorCode" to "NOT_FOUND", "Message" to "Purchase order ${r.s("PurchId")} is not in the demo company."))
        val outstanding = po.lines.filter { it.received < it.ordered }
        return when (op) {
            "getLinesForItem" -> {
                val want = item(r.s("ItemId"))?.id ?: r.s("ItemId")
                val lines = outstanding.filter { it.itemId.equals(want, true) }
                ok(obj("Success" to true, "ErrorCode" to if (lines.isEmpty()) "NOT_ON_ORDER" else null, "Lines" to lines.map(::poLine)))
            }
            else -> {
                if (!po.confirmed) {
                    return ok(obj("Success" to false, "ErrorCode" to "NOT_APPROVED", "Message" to "${po.id} is not confirmed yet."))
                }
                if (outstanding.isEmpty()) {
                    return ok(obj("Success" to false, "ErrorCode" to "NOTHING_OUTSTANDING", "Message" to "Everything on ${po.id} has been received.", "PurchId" to po.id))
                }
                ok(
                    obj(
                        "Success" to true, "PurchId" to po.id, "VendorAccount" to po.vendor, "VendorName" to vendorName(po.vendor),
                        "PurchStatus" to "Backorder", "ApprovalStatus" to "Confirmed", "Receivable" to true,
                        "SiteId" to SITE, "WarehouseId" to WAREHOUSE, "DeliveryDate" to po.deliveryDate(), "CurrencyCode" to CURRENCY,
                        "OutstandingLineCount" to outstanding.size, "LinesIncluded" to true, "Lines" to outstanding.map(::poLine),
                        "RequireLocationCapture" to false,
                    ),
                )
            }
        }
    }

    // =============================================================================================
    // Posting: product receipts, transfer shipments and receipts, the post queue
    // =============================================================================================

    private fun posting(op: String, r: JsonObject): Reply = when (op) {
        "postProductReceipt" -> post(r, "Registration", "DGRN") { source, lines ->
            val po = state.pos.firstOrNull { it.id.equals(source, true) } ?: return@post false
            lines.forEach { l ->
                val line = po.lines.firstOrNull { it.lineNumber == l.d("LineNumber") } ?: po.lines.firstOrNull { it.itemId.equals(l.s("ItemId"), true) }
                line?.let { it.received += l.d("Quantity") }
                addStock(l.s("ItemId"), l.s("LocationId"), l.d("Quantity"))
            }
            true
        }
        "postTransferShipment" -> post(r, "TransferShipment", "DSHP") { source, lines ->
            val to = state.transfers.firstOrNull { it.id.equals(source, true) } ?: return@post false
            lines.forEach { l ->
                toLine(to, l)?.let { it.shipped += l.d("Quantity") }
                addStock(l.s("ItemId"), l.s("LocationId"), -l.d("Quantity"))
            }
            true
        }
        "postTransferReceipt" -> post(r, "TransferReceipt", "DRCV") { source, lines ->
            val to = state.transfers.firstOrNull { it.id.equals(source, true) } ?: return@post false
            lines.forEach { l ->
                toLine(to, l)?.let { it.received += l.d("Quantity") }
                addStock(l.s("ItemId"), l.s("LocationId"), l.d("Quantity"))
            }
            true
        }
        "pickTransferLine" -> ok(obj("Success" to true))
        "processPending", "getQueueStatus" -> {
            val keys = r.s("IdempotencyKeys").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val rows = state.queue.filter { keys.isEmpty() || it.s("IdempotencyKey") in keys }.reversed().take(50)
            ok(obj("Success" to true, "Rows" to rows))
        }
        else -> ok(obj("Success" to true))
    }

    private fun toLine(to: To, l: JsonObject): ToLine? =
        to.lines.firstOrNull { it.lineNumber == l.d("LineNumber") } ?: to.lines.firstOrNull { it.itemId.equals(l.s("ItemId"), true) }

    /** One posting, idempotent on its key: the second send answers AlreadyPosted with the same document. */
    private fun post(r: JsonObject, documentType: String, prefix: String, apply: (String, List<JsonObject>) -> Boolean): Reply {
        val key = r.s("IdempotencyKey")
        state.documents[key]?.takeIf { key.isNotEmpty() }?.let { done ->
            return ok(obj("Success" to true, "AlreadyPosted" to true, "DocumentNumber" to done.s("GrnId"), "QueueStatus" to "Registered"))
        }
        val source = r.s("SourceId").ifBlank { r.s("Reference") }
        val lines = parseArray(r.s("LinesJson")).filterIsInstance<JsonObject>()
        if (!apply(source, lines)) {
            return ok(obj("Success" to false, "ErrorCode" to "NOT_FOUND", "Message" to "$source is not in the demo company."))
        }
        val document = "$prefix-" + (state.next() + 1000).toString().padStart(6, '0')
        val now = nowUtc()
        val row = obj(
            "SourceId" to source, "DocumentType" to documentType, "Status" to "Registered", "ReceivedUtc" to now, "ProcessedUtc" to now,
            "UserName" to r.s("UserName").ifBlank { USER }, "LinesJson" to r.s("LinesJson"), "IdempotencyKey" to key,
            "GrnId" to document, "LineCount" to lines.size,
        )
        state.queue += row
        if (key.isNotEmpty()) state.documents[key] = row
        return ok(obj("Success" to true, "DocumentNumber" to document, "QueueStatus" to "Registered", "QueueMessage" to "Posted in the demo company."))
    }

    private fun addStock(itemId: String, location: String, qty: Double) {
        val id = item(itemId)?.id ?: return
        val loc = location.uppercase().takeIf { it in locations } ?: "MAIN"
        val row = state.stock.getOrPut(id) { mutableMapOf() }
        row[loc] = ((row[loc] ?: 0.0) + qty).coerceAtLeast(0.0)
    }

    private fun onHand(itemId: String, warehouse: String): Double = when {
        warehouse.equals(WAREHOUSE, true) -> state.stock[itemId]?.values?.sum() ?: 0.0
        warehouse.equals(STORE2, true) -> item(itemId)?.let { (it.main / 2).toInt().toDouble() } ?: 0.0
        else -> 0.0
    }

    // =============================================================================================
    // Transfer orders
    // =============================================================================================

    private fun transferList(op: String, r: JsonObject): Reply {
        val wh = r.s("WarehouseId").ifBlank { WAREHOUSE }
        val search = r.s("Search")
        val rows = state.transfers
            .filter { it.from.equals(wh, true) || it.to.equals(wh, true) }
            .filter { search.isBlank() || it.id.contains(search, true) }
            .map {
                obj(
                    "TransferId" to it.id, "FromWarehouseId" to it.from, "ToWarehouseId" to it.to, "Status" to it.status,
                    "ShipDate" to today(), "ReceiveDate" to LocalDate.now().plusDays(1).toString(), "LineCount" to it.lines.size,
                    "Direction" to if (it.from.equals(wh, true)) "OUT" else "IN",
                )
            }
        return ok(obj("Success" to true, "Orders" to rows))
    }

    private fun transfer(op: String, r: JsonObject): Reply {
        val to = state.transfers.firstOrNull { it.id.equals(r.s("TransferId"), true) }
            ?: return ok(obj("Success" to false, "ErrorCode" to "NOT_FOUND", "Message" to "Transfer order ${r.s("TransferId")} is not in the demo company."))
        val receive = r.s("Mode").equals("Receive", true)
        val outstanding = to.lines.filter { if (receive) it.shipped > it.received else it.shipped < it.qty }
        fun line(l: ToLine): JsonObject {
            val it = item(l.itemId)
            return obj(
                "LineNumber" to l.lineNumber, "ItemId" to l.itemId, "ItemName" to it?.name.orEmpty(), "UnitId" to UNIT,
                "TransferQty" to l.qty, "ShippedQty" to l.shipped, "ReceivedQty" to l.received,
                "RemainingQty" to if (receive) l.shipped - l.received else l.qty - l.shipped,
                "FromSiteId" to SITE, "FromWarehouseId" to to.from, "ToSiteId" to SITE, "ToWarehouseId" to to.to, "LocationId" to "MAIN",
                "BatchRequired" to (it?.batch == true), "SerialRequired" to false, "LocationRequired" to true,
                "BatchMandatory" to true, "SerialMandatory" to false, "LocationMandatory" to true, "SerialPerUnit" to true,
                "Units" to units(),
                "ShippedBatches" to if (it?.batch == true && l.shipped > 0) listOf(obj("BatchId" to BATCH, "Qty" to l.shipped)) else emptyList<Any>(),
            )
        }
        if (op == "getLinesForItem") {
            val want = item(r.s("ItemId"))?.id ?: r.s("ItemId")
            val lines = outstanding.filter { it.itemId.equals(want, true) }
            return ok(obj("Success" to true, "ErrorCode" to if (lines.isEmpty()) "NOT_ON_ORDER" else null, "TransferId" to to.id, "Lines" to lines.map(::line)))
        }
        return ok(
            obj(
                "Success" to true, "TransferId" to to.id, "TransferStatus" to to.status, "Actionable" to outstanding.isNotEmpty(),
                "Message" to if (outstanding.isEmpty()) "Nothing left to ${if (receive) "receive" else "ship"} on ${to.id}." else null,
                "FromSiteId" to SITE, "FromWarehouseId" to to.from, "ToSiteId" to SITE, "ToWarehouseId" to to.to,
                "OutstandingLineCount" to outstanding.size, "LinesIncluded" to true, "Lines" to outstanding.map(::line),
                "RequireLocationCapture" to false, "DefaultIssueLocation" to "MAIN", "DefaultReceiptLocation" to "MAIN",
            ),
        )
    }

    // =============================================================================================
    // Counts: the Stock count control center (Hazard, Blind, Wall to wall)
    // =============================================================================================

    private fun countRequest(op: String, r: JsonObject): Reply = when (op) {
        "listCounts" -> {
            val rows = state.counts.map {
                obj(
                    "MasterId" to it.masterId, "Type" to it.type, "Reference" to it.reference, "Description" to it.description,
                    "DueUtc" to LocalDate.now().plusDays(1).toString() + "T17:00:00", "StartedUtc" to today() + "T08:00:00",
                    "Status" to it.status, "JournalId" to it.journalId, "ItemCount" to it.itemIds.size.toString(),
                    "CounterCount" to it.counterCount.toString(), "LineCount" to it.lineCount.toString(), "MyStatus" to it.myStatus,
                )
            }
            ok(obj("Success" to true, "PayloadJson" to JsonArray(rows).toString()))
        }
        "getCountItems" -> {
            val count = findCount(r.s("MasterId")) ?: return countNotFound()
            val rows = count.itemIds.mapIndexed { i, id ->
                val it = item(id)
                obj(
                    "LineNum" to (i + 1).toString(), "ItemId" to id, "ItemName" to it?.name.orEmpty(),
                    "Category" to it?.let(::topCategory)?.name.orEmpty(), "Counted" to (id in count.counted).toString(),
                )
            }
            ok(obj("Success" to true, "MasterId" to count.masterId, "Status" to count.status, "PayloadJson" to JsonArray(rows).toString()))
        }
        "getCountResults" -> {
            val count = findCount(r.s("MasterId")) ?: return countNotFound()
            val rows = count.results.map { (id, counted, onHand) ->
                obj(
                    "ItemId" to id, "ItemName" to item(id)?.name.orEmpty(), "BatchId" to if (item(id)?.batch == true) BATCH else "",
                    "LocationId" to "MAIN", "Counted" to counted.toString(), "OnHand" to onHand.toString(), "Variance" to (counted - onHand).toString(),
                )
            }
            ok(
                obj(
                    "Success" to true, "MasterId" to count.masterId, "Reference" to count.reference, "Status" to count.status,
                    "JournalId" to count.journalId, "Posted" to (count.status == "Posted"), "PayloadJson" to JsonArray(rows).toString(),
                ),
            )
        }
        "listCategories" -> {
            val parent = r.s("CategoryId")
            val rows = categories.filter { it.parent == parent }.sortedBy { it.name }.map { c ->
                obj(
                    "CategoryId" to c.id, "Name" to c.name, "HasChildren" to categories.any { it.parent == c.id }.toString(),
                    "ItemCount" to itemsIn(c.id).size.toString(),
                )
            }
            ok(obj("Success" to true, "PayloadJson" to JsonArray(rows).toString()))
        }
        "createCount" -> createCount(r)
        else -> ok(obj("Success" to true))
    }

    private fun findCount(masterId: String) = state.counts.firstOrNull { it.masterId.equals(masterId, true) }

    private fun countNotFound() = ok(obj("Success" to false, "ErrorCode" to "COUNT_NOT_FOUND"))

    private fun openWallToWall() = state.counts.firstOrNull { it.type == "WallToWall" && (it.status == "Open" || it.status == "Counting") }

    private fun newCount(type: String, description: String, itemIds: List<String>): Count {
        val n = ++state.countSeq
        val count = Count("MAIN-DEMO-$n", type, "DCC-" + n.toString().padStart(6, '0'), description, "Open", itemIds = itemIds)
        state.counts += count
        return count
    }

    private fun createCount(r: JsonObject): Reply {
        val type = listOf("Hazard", "Blind", "WallToWall").firstOrNull { it.equals(r.s("CountType"), true) }
            ?: return ok(obj("Success" to false, "ErrorCode" to "INVALID_REQUEST", "Message" to "Unknown count type."))
        if (type == "WallToWall") {
            openWallToWall()?.let {
                return ok(obj("Success" to true, "MasterId" to it.masterId, "Reference" to it.reference, "Status" to it.status, "Existing" to true,
                    "Message" to "The store's open wall to wall count ${it.masterId} was joined."))
            }
        }
        val fromCategories = parseArray(r.s("CategoriesJson")).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.flatMap { itemsIn(it) }.map { it.id }
        val picked = parseArray(r.s("ItemsJson")).mapNotNull { (it as? JsonObject)?.s("ItemId") }.mapNotNull { item(it)?.id }
        val ids = (fromCategories + picked).distinct()
        if (type != "WallToWall" && ids.isEmpty()) {
            return ok(obj("Success" to false, "ErrorCode" to "NO_ITEMS", "Message" to "Pick at least one category or item."))
        }
        val label = if (type == "WallToWall") "Wall to wall count" else "$type count"
        val count = newCount(type, r.s("Description").ifBlank { "$label created on the device" }, ids)
        return ok(obj("Success" to true, "MasterId" to count.masterId, "Reference" to count.reference, "Status" to count.status,
            "Existing" to false, "Message" to "Count ${count.masterId} created."))
    }

    private fun countStage(op: String, r: JsonObject): Reply = when (op) {
        "getStageMode" -> ok(obj("Success" to true, "StageJournalCounts" to true))
        "openStage" -> {
            val count = r.s("MasterId").takeIf { it.isNotBlank() }?.let(::findCount)
                ?: openWallToWall()
                ?: newCount("WallToWall", "Wall to wall count", emptyList())
            if (count.status == "Open") count.status = "Counting"
            count.counterCount = maxOf(count.counterCount, 1)
            count.myStatus = "Counting"
            val stageId = state.stages.entries.firstOrNull { it.value == count.masterId }?.key
                ?: ("STG-" + state.next().toString().padStart(6, '0')).also { state.stages[it] = count.masterId }
            ok(obj("Success" to true, "MasterId" to count.masterId, "StageId" to stageId, "MasterType" to count.type, "StageJournalCounts" to true))
        }
        "submitLines" -> {
            val stageId = r.s("StageId")
            val count = state.stages[stageId]?.let(::findCount)
                ?: return ok(obj("Success" to false, "ErrorCode" to "STAGE_NOT_FOUND", "Message" to "This count is not open any more."))
            val lines = parseArray(r.s("LinesJson")).filterIsInstance<JsonObject>()
            val (accepted, rejected) = lines.partition { l ->
                count.itemIds.isEmpty() || count.itemIds.any { it.equals(l.s("ItemId"), true) }
            }
            accepted.mapNotNull { item(it.s("ItemId"))?.id }.forEach { count.counted += it }
            count.lineCount += accepted.size
            if (r.b("EndCount")) count.myStatus = "Submitted"
            ok(obj("Success" to true, "MasterId" to count.masterId, "StageId" to stageId, "Accepted" to accepted.size,
                "Rejected" to rejected.size, "Duplicates" to 0, "MasterType" to count.type, "StageJournalCounts" to true))
        }
        else -> ok(obj("Success" to true))
    }

    private fun countTask(op: String, r: JsonObject): Reply = when (op) {
        "getCountBins" -> ok(obj("Success" to true, "Bins" to state.bins.map { (bin, loc) -> obj("BinId" to bin, "Description" to "Bin $bin", "LocationId" to loc) }))
        "addCountBin" -> {
            val bin = r.s("BinId").trim().uppercase()
            val loc = r.s("LocationId").uppercase().ifBlank { "MAIN" }
            if (bin.isNotEmpty() && state.bins.none { it.first == bin }) state.bins += bin to loc
            val placed = state.bins.firstOrNull { it.first == bin }
            ok(obj("Success" to bin.isNotEmpty(), "Accepted" to if (bin.isNotEmpty()) 1 else 0,
                "Bins" to listOfNotNull(placed?.let { obj("BinId" to it.first, "Description" to "Bin ${it.first}", "LocationId" to it.second) })))
        }
        else -> ok(obj("Success" to true, "success" to true))
    }

    // =============================================================================================
    // On-hand and validation (HSMobileOpsInquirySvc)
    // =============================================================================================

    private fun inquiry(op: String, r: JsonObject): Reply = when (op) {
        "validateLocation" -> {
            val wh = r.s("WarehouseId").ifBlank { WAREHOUSE }
            val loc = r.s("LocationId").trim().uppercase()
            val exists = warehouses.any { it.first.equals(wh, true) } && (loc in locations && (wh.equals(WAREHOUSE, true) || loc == "MAIN"))
            ok(obj("Success" to true, "Exists" to exists, "CanReceiveInto" to exists, "WarehouseId" to wh.uppercase(), "LocationId" to loc,
                "ErrorCode" to if (exists) null else "NOT_FOUND"))
        }
        "validateBatch" -> ok(obj("Success" to true, "Registered" to r.s("BatchId").equals(BATCH, true)))
        "validateSerial" -> ok(obj("Success" to true, "Registered" to false))
        "getLocationOnHand" -> {
            val wh = r.s("WarehouseId")
            val loc = r.s("LocationId").trim().uppercase()
            val rows = if (!wh.equals(WAREHOUSE, true)) emptyList() else state.stock.mapNotNull { (itemId, byLoc) ->
                val qty = byLoc[loc] ?: 0.0
                if (qty == 0.0) null else obj("ItemId" to itemId, "WarehouseId" to WAREHOUSE, "OnHand" to qty, "Available" to qty, "Reserved" to 0.0)
            }
            ok(obj("Success" to true, "Rows" to rows))
        }
        "getOnHand" -> {
            val it = item(r.s("ItemId")) ?: return ok(obj("Success" to false, "ErrorCode" to "NOT_FOUND", "Rows" to emptyList<Any>()))
            val wh = r.s("WarehouseId")
            val rows = warehouses.filter { w -> wh.isBlank() || w.first.equals(wh, true) }.map { (id, _, site) ->
                val qty = onHand(it.id, id)
                val ordered = if (id == WAREHOUSE) orderedQty(it.id) else 0.0
                obj("ItemId" to it.id, "SiteId" to site, "WarehouseId" to id, "OnHand" to qty, "Available" to qty, "Reserved" to 0.0,
                    "Ordered" to ordered, "TotalAvailable" to qty + ordered)
            }
            ok(obj("Success" to true, "Rows" to rows))
        }
        else -> ok(obj("Success" to true))
    }

    /** Price check: the demo item's own price, no promotions. */
    private fun priceCheck(r: JsonObject): Reply {
        val it = item(r.s("ItemId")) ?: return ok(obj("Success" to false, "ErrorCode" to "NOT_FOUND", "Message" to "No item ${r.s("ItemId")}."))
        val qty = r.s("Quantity").toDoubleOrNull() ?: 1.0
        val gross = it.price * qty
        return ok(
            obj(
                "Success" to true, "ItemId" to it.id, "ItemName" to it.name, "Quantity" to qty, "UnitId" to UNIT,
                "UnitPrice" to it.price, "GrossAmount" to gross, "DiscountAmount" to 0.0, "DiscountPct" to 0.0, "NetAmount" to gross,
                "OfferName" to "", "Currency" to "AED", "PriceIncludesTax" to true, "StoreName" to WAREHOUSE,
            ),
        )
    }

    /** "Health Care > Pain Relief" - the item feed's category column. */
    private fun categoryPath(id: String): String {
        val leaf = categories.firstOrNull { it.id == id } ?: return ""
        val parent = categories.firstOrNull { it.id == leaf.parent }
        return if (parent == null) leaf.name else "${parent.name} > ${leaf.name}"
    }

    private fun orderedQty(itemId: String) =
        state.pos.filter { it.confirmed }.flatMap { it.lines }.filter { it.itemId == itemId }.sumOf { (it.ordered - it.received).coerceAtLeast(0.0) }

    // =============================================================================================
    // Items, barcodes, units, search and the offline SKU cache
    // =============================================================================================

    private fun itemSvc(op: String, r: JsonObject): Reply = when (op) {
        "getItem" -> item(r.s("ItemId"))?.let {
            ok(obj("Success" to true, "ItemId" to it.id, "ItemName" to it.name, "InventUnit" to UNIT, "PurchUnit" to UNIT,
                "BatchActive" to it.batch, "SerialActive" to false, "LocationActive" to true))
        } ?: ok(obj("Success" to false, "ErrorCode" to "NOT_FOUND"))
        "getBarcode" -> item(r.s("Barcode"))?.let {
            ok(obj("Success" to true, "ItemId" to it.id, "Quantity" to 1.0, "UnitId" to UNIT))
        } ?: ok(obj("Success" to false, "ErrorCode" to "NOT_FOUND"))
        "getWarehouses" -> ok(obj("Success" to true, "Warehouses" to warehouses.map { (id, name, site) -> obj("WarehouseId" to id, "WarehouseName" to name, "SiteId" to site) }))
        else -> ok(obj("Success" to true))
    }

    private fun itemUnits(r: JsonObject): Reply {
        val rows = parseArray(r.s("ItemsJson")).filterIsInstance<JsonObject>().mapNotNull { key ->
            val it = item(key.s("ItemId")) ?: return@mapNotNull null
            obj("ItemId" to it.id, "ConfigId" to key.s("ConfigId"), "SizeId" to key.s("SizeId"), "ColorId" to key.s("ColorId"),
                "StyleId" to key.s("StyleId"), "InventoryUnit" to UNIT, "Units" to units())
        }
        return ok(obj("Success" to true, "Items" to rows))
    }

    /** Home search: an item (by number, barcode or name), and the orders that match or carry it. */
    private fun search(r: JsonObject): Reply {
        val q = r.s("Query").trim()
        if (q.isEmpty()) return ok(obj("Success" to true, "Hits" to emptyList<Any>()))
        val found = item(q) ?: items.firstOrNull { it.name.contains(q, true) }
        val hits = mutableListOf<JsonObject>()
        state.pos.filter { it.confirmed && (it.id.contains(q, true) || (found != null && it.lines.any { l -> l.itemId == found.id })) }.forEach { po ->
            val line = found?.let { f -> po.lines.firstOrNull { it.itemId == f.id } }
            hits += obj(
                "Kind" to "PO", "Match" to if (po.id.contains(q, true)) "ORDER" else "ITEM", "Reference" to po.id,
                "Title" to vendorName(po.vendor), "Detail" to (line?.let { "Line ${it.lineNumber.toInt()}" } ?: "${po.lines.size} lines"),
                "Status" to if (po.open()) "Backorder" else "Received", "Date" to po.deliveryDate(),
                "Quantity" to (line?.ordered ?: 0.0), "UnitId" to UNIT,
            )
        }
        state.transfers.filter { it.id.contains(q, true) || (found != null && it.lines.any { l -> l.itemId == found.id }) }.forEach { to ->
            val line = found?.let { f -> to.lines.firstOrNull { it.itemId == f.id } }
            val out = to.from == WAREHOUSE
            hits += obj(
                "Kind" to "TO", "Match" to if (to.id.contains(q, true)) "ORDER" else "ITEM", "Reference" to to.id,
                "Title" to if (out) "To Second Store" else "From Second Store", "Detail" to (line?.let { "Line ${it.lineNumber.toInt()}" } ?: "${to.lines.size} lines"),
                "Status" to to.status, "Date" to today(), "Quantity" to (line?.qty ?: 0.0), "UnitId" to UNIT, "Direction" to if (out) "OUT" else "IN",
            )
        }
        return ok(obj("Success" to true, "ItemId" to found?.id, "ItemName" to found?.name, "UnitId" to found?.let { UNIT }, "Hits" to hits))
    }

    /** getSkuPage: positional rows per feed, all in one page (see SkuRows for the column order). */
    private fun skuPage(r: JsonObject): Reply {
        val rows: List<List<String>> = when (r.s("Feed").uppercase()) {
            "ITEMS" -> items.map { listOf(it.id, it.name, UNIT, UNIT, UNIT, if (it.batch) "1" else "0", "0", "1", "1", categoryPath(it.category)) }
            "BARCODES" -> items.map { listOf(it.barcode, it.id, "1", UNIT, "", "", "", "", "0") }
            // Demo categories never change: nothing for a delta.
            "CATEGORIES" -> emptyList()
            else -> emptyList()
        }
        // Everything fits in the first page; a later page (AfterKey set) is empty.
        val page = if (r.s("AfterKey").isBlank()) rows else emptyList()
        return ok(
            obj(
                "Success" to true, "RowsJson" to JsonArray(page.map { row -> JsonArray(row.map { JsonPrimitive(it) }) }).toString(),
                "RowCount" to page.size, "NextKey" to "", "HasMore" to false, "ServerUtc" to nowUtc().removeSuffix("Z"),
            ),
        )
    }

    // =============================================================================================
    // Order creation: PO, TO, PO confirmation, purchase returns
    // =============================================================================================

    private fun priced(l: JsonObject, index: Int): JsonObject {
        val it = item(l.s("ItemId"))
        val qty = l.d("Qty")
        val price = if (l.b("PriceOverride")) l.d("Price") else it?.price ?: 0.0
        return obj(
            "LineNumber" to (l.d("LineNumber").takeIf { n -> n > 0 } ?: (index + 1).toDouble()), "ItemId" to l.s("ItemId"),
            "ItemName" to (it?.name ?: l.s("ItemName")), "BatchId" to l.s("BatchId"), "LocationId" to l.s("LocationId"),
            "Qty" to qty, "UnitId" to UNIT, "Price" to price, "PriceUnit" to 1.0, "PriceOverride" to l.b("PriceOverride"),
            "DiscPct" to 0.0, "DiscAmount" to 0.0, "LineAmount" to round2(qty * price),
            "PriceSource" to if (l.b("PriceOverride")) "Manual" else "Trade agreement",
        )
    }

    private fun pricedLines(r: JsonObject) = parseArray(r.s("LinesJson")).filterIsInstance<JsonObject>().mapIndexed { i, l -> priced(l, i) }

    private fun orderSummary(po: Po): JsonObject = obj(
        "PurchId" to po.id, "VendorAccount" to po.vendor, "VendorName" to vendorName(po.vendor), "WarehouseId" to WAREHOUSE,
        "DeliveryDate" to po.deliveryDate(), "LineCount" to po.lines.size,
        "OrderTotal" to round2(po.lines.sumOf { (item(it.itemId)?.price ?: 0.0) * it.ordered }), "CurrencyCode" to CURRENCY,
        "ApprovalStatus" to if (po.confirmed) "Confirmed" else "Approved", "PurchStatus" to "Backorder",
        "CreatedBy" to po.createdBy, "CreatedDate" to today(), "ConfirmationNumber" to if (po.confirmed) "${po.id}-1" else "",
    )

    private fun orderCreate(op: String, r: JsonObject): Reply {
        val key = r.s("IdempotencyKey")
        if (key.isNotEmpty() && op.startsWith("create") || op == "confirmPurchaseOrder") {
            state.documents["order:$key"]?.takeIf { key.isNotEmpty() }?.let { done ->
                return ok(JsonObject(done + ("AlreadyCreated" to JsonPrimitive(true))))
            }
        }
        val reply: JsonObject = when (op) {
            "getModulePermissions" -> obj("Success" to true, "Permissions" to permissions())
            "listVendors" -> obj(
                "Success" to true, "HasMore" to false,
                "Vendors" to vendors.map { (id, name, terms) ->
                    obj("AccountNum" to id, "Name" to name, "CurrencyCode" to CURRENCY, "PaymentTerms" to terms, "DeliveryTerms" to "DAP", "DeliveryMode" to "Road")
                },
            )
            "getOrderItem" -> item(r.s("ItemId"))?.let {
                obj("Success" to true, "Item" to obj(
                    "ItemId" to it.id, "ItemName" to it.name, "PurchUnit" to UNIT, "InventUnit" to UNIT,
                    "BatchActive" to it.batch, "SerialActive" to false, "NegativePhysical" to false,
                    "MinQty" to 0.0, "MaxQty" to 0.0, "MultipleQty" to 0.0, "StandardQty" to 1.0, "Variants" to emptyList<Any>(),
                ))
            } ?: obj("Success" to false, "ErrorCode" to "NOT_FOUND", "Message" to "${r.s("ItemId")} is not an item in the demo company.")
            "priceLines" -> pricedLines(r).let { obj("Success" to true, "Lines" to it, "CurrencyCode" to CURRENCY, "OrderTotal" to round2(it.sumOf { l -> l.d("LineAmount") })) }
            "getStock" -> stockRows(r)
            "createPurchaseOrder" -> {
                val lines = pricedLines(r)
                val id = "DPO-" + (200 + state.next()).toString().padStart(6, '0')
                state.pos += Po(id, r.s("VendorAccount").ifBlank { VENDOR }, r.s("DeliveryDate").take(10).ifBlank { null },
                    lines.mapIndexed { i, l -> PoLine((i + 1).toDouble(), l.s("ItemId"), l.d("Qty")) }.toMutableList(), createdBy = USER)
                obj("Success" to true, "DocumentNumber" to id, "ConfirmationNumber" to "$id-1", "CurrencyCode" to CURRENCY,
                    "OrderTotal" to round2(lines.sumOf { it.d("LineAmount") }), "Lines" to lines, "Message" to "Purchase order $id created and confirmed.")
            }
            "createTransferOrder" -> {
                val lines = pricedLines(r)
                val id = "DTO-" + (400 + state.next()).toString().padStart(6, '0')
                state.transfers += To(id, r.s("FromWarehouseId").ifBlank { WAREHOUSE }.uppercase(), r.s("ToWarehouseId").ifBlank { STORE2 }.uppercase(),
                    lines.mapIndexed { i, l -> ToLine((i + 1).toDouble(), l.s("ItemId"), l.d("Qty")) })
                obj("Success" to true, "DocumentNumber" to id, "TransitWarehouseId" to TRANSIT, "Lines" to lines, "Message" to "Transfer order $id created.")
            }
            "listPurchaseOrdersToConfirm" -> obj("Success" to true, "HasMore" to false, "Orders" to state.pos.filter { !it.confirmed }.map(::orderSummary))
            "getPurchaseOrder" -> state.pos.firstOrNull { it.id.equals(r.s("PurchId"), true) }?.let { po ->
                obj("Success" to true, "CurrencyCode" to CURRENCY, "Orders" to listOf(orderSummary(po)),
                    "Lines" to po.lines.mapIndexed { i, l ->
                        priced(obj("LineNumber" to l.lineNumber, "ItemId" to l.itemId, "Qty" to l.ordered), i)
                    })
            } ?: obj("Success" to false, "ErrorCode" to "NOT_FOUND", "Message" to "${r.s("PurchId")} is not in the demo company.")
            "confirmPurchaseOrder" -> state.pos.firstOrNull { it.id.equals(r.s("PurchId"), true) }?.let { po ->
                po.confirmed = true
                po.date = null
                obj("Success" to true, "DocumentNumber" to po.id, "ConfirmationNumber" to "${po.id}-1", "Message" to "${po.id} confirmed.")
            } ?: obj("Success" to false, "ErrorCode" to "NOT_FOUND", "Message" to "${r.s("PurchId")} is not in the demo company.")
            "listReturnReasons" -> obj(
                "Success" to true,
                "Reasons" to listOf(obj("Code" to "DAMAGED", "Description" to "Damaged in transit"), obj("Code" to "EXPIRED", "Description" to "Expired or short-dated"),
                    obj("Code" to "WRONG", "Description" to "Wrong item delivered")),
                "Actions" to listOf(obj("Code" to "CREDIT", "Description" to "Credit note"), obj("Code" to "REPLACE", "Description" to "Replace goods")),
            )
            "createPurchaseReturn" -> {
                val lines = pricedLines(r)
                val n = state.next()
                lines.forEach { addStock(it.s("ItemId"), it.s("LocationId"), -it.d("Qty")) }
                val id = "DRT-" + (500 + n).toString().padStart(6, '0')
                obj("Success" to true, "DocumentNumber" to id, "PackingSlipId" to "DPS-" + (500 + n).toString().padStart(6, '0'),
                    "CurrencyCode" to CURRENCY, "OrderTotal" to round2(lines.sumOf { it.d("LineAmount") }), "Lines" to lines,
                    "Message" to "Return $id created and posted.")
            }
            else -> obj("Success" to true)
        }
        if (key.isNotEmpty() && reply.b("Success") && (op.startsWith("create") || op == "confirmPurchaseOrder")) state.documents["order:$key"] = reply
        return ok(reply)
    }

    /** Available stock of an item: one row per batch, or per batch and location with GroupByLocation. */
    private fun stockRows(r: JsonObject): JsonObject {
        val it = item(r.s("ItemId")) ?: return obj("Success" to false, "ErrorCode" to "NOT_FOUND")
        val wh = r.s("WarehouseId").ifBlank { r.s("FromWarehouseId") }.ifBlank { WAREHOUSE }
        val batch = if (it.batch) BATCH else ""
        val rows = if (wh.equals(WAREHOUSE, true)) {
            val byLoc = state.stock[it.id].orEmpty()
            if (r.b("GroupByLocation")) {
                byLoc.filter { e -> e.value > 0 }.map { (loc, q) -> obj("ItemId" to it.id, "BatchId" to batch, "LocationId" to loc, "Available" to q, "UnitId" to UNIT) }
            } else {
                listOf(obj("ItemId" to it.id, "BatchId" to batch, "Available" to byLoc.values.sum(), "UnitId" to UNIT))
            }
        } else {
            listOf(obj("ItemId" to it.id, "BatchId" to batch, "LocationId" to "MAIN", "Available" to onHand(it.id, wh), "UnitId" to UNIT))
        }
        return obj("Success" to true, "Lines" to rows)
    }

    // =============================================================================================
    // Feedback
    // =============================================================================================

    private fun feedback(op: String, r: JsonObject): Reply = when (op) {
        "submitFeedback", "updateFeedback" -> {
            val id = r.s("FeedbackId").ifBlank { "DFB-" + state.next() }
            state.feedback.removeAll { it.s("FeedbackId") == id }
            state.feedback += obj("FeedbackId" to id, "SubmittedUtc" to nowUtc(), "Screen" to r.s("Screen"), "FeedbackType" to r.s("FeedbackType"),
                "Message" to r.s("Message"), "Status" to "New", "Response" to "")
            ok(obj("Success" to true))
        }
        "listMyFeedback" -> ok(obj("Success" to true, "Items" to state.feedback.reversed()))
        else -> ok(obj("Success" to true))
    }

    // =============================================================================================
    // OData (/data/<Entity>) - {"value":[...]} with the entity's own member names
    // =============================================================================================

    private fun odata(rest: String, query: String?): Reply {
        val entity = rest.substringBefore('?').substringBefore('(').substringBefore('/')
        val params = parseQuery(query ?: rest.substringAfter('?', ""))
        val rows: List<JsonObject> = when (entity) {
            "ReleasedProductsV2" -> items.map {
                obj("dataAreaId" to COMPANY, "ItemNumber" to it.id, "SearchName" to it.name, "ProductName" to it.name,
                    "InventoryUnitSymbol" to UNIT, "PurchaseUnitSymbol" to UNIT, "SalesUnitSymbol" to UNIT, "ItemModelGroupId" to "FIFO",
                    "TrackingDimensionGroupName" to if (it.batch) "Batch" else "None", "StorageDimensionGroupName" to "SiteWHLoc",
                    "IsBatchNumberActive" to yesNo(it.batch), "IsSerialNumberActive" to "No", "IsCatchWeightProduct" to "No",
                    "ProductLifecycleStateId" to "", "ModifiedDateTime" to "2026-01-05T08:00:00Z")
            }
            "TrackingDimensionGroups" -> listOf(
                obj("dataAreaId" to COMPANY, "TrackingDimensionGroupName" to "Batch", "IsBatchNumberActive" to "Yes", "IsSerialNumberActive" to "No"),
                obj("dataAreaId" to COMPANY, "TrackingDimensionGroupName" to "None", "IsBatchNumberActive" to "No", "IsSerialNumberActive" to "No"),
            )
            "ProductBarcodesV3" -> items.map {
                obj("dataAreaId" to COMPANY, "Barcode" to it.barcode, "ItemNumber" to it.id, "ProductQuantity" to 1.0,
                    "ProductQuantityUnitSymbol" to UNIT, "IsDefaultScannedBarcode" to "Yes")
            }
            "Warehouses" -> warehouses.map { (id, name, site) ->
                obj("dataAreaId" to COMPANY, "WarehouseId" to id, "WarehouseName" to name, "OperationalSiteId" to site,
                    "AreAdvancedWarehouseManagementProcessesEnabled" to "No", "WarehouseType" to "Default")
            }
            "WarehouseLocations" -> warehouses.flatMap { (id, _, _) ->
                (if (id == WAREHOUSE) locations else listOf("MAIN")).map { loc ->
                    obj("dataAreaId" to COMPANY, "WarehouseId" to id, "WarehouseLocationId" to loc,
                        "InputWarehouseLocationBlockingCauseId" to "", "OutputWarehouseLocationBlockingCauseId" to "")
                }
            }
            "WarehousesOnHand" -> items.flatMap { it ->
                warehouses.map { (id, _, site) ->
                    val qty = onHand(it.id, id)
                    val ordered = if (id == WAREHOUSE) orderedQty(it.id) else 0.0
                    obj("dataAreaId" to COMPANY, "ItemNumber" to it.id, "InventorySiteId" to site, "InventoryWarehouseId" to id,
                        "OnHandQuantity" to qty, "AvailableOnHandQuantity" to qty, "ReservedOnHandQuantity" to 0.0,
                        "OrderedQuantity" to ordered, "ReservedOrderedQuantity" to 0.0, "AvailableOrderedQuantity" to ordered,
                        "TotalAvailableQuantity" to qty + ordered, "AreWarehouseManagementProcessesUsed" to "No")
                }
            }
            "PurchaseOrderHeadersV2" -> state.pos.filter { it.confirmed }.map {
                obj("dataAreaId" to COMPANY, "PurchaseOrderNumber" to it.id, "OrderVendorAccountNumber" to it.vendor,
                    "PurchaseOrderStatus" to if (it.open()) "Backorder" else "Received", "DocumentApprovalStatus" to "Confirmed",
                    "RequestedDeliveryDate" to it.deliveryDate() + "T12:00:00Z", "DefaultReceivingSiteId" to SITE,
                    "DefaultReceivingWarehouseId" to WAREHOUSE, "CurrencyCode" to CURRENCY)
            }
            "PurchaseOrderLinesV2" -> state.pos.filter { it.confirmed }.flatMap { po ->
                po.lines.map { l ->
                    obj("dataAreaId" to COMPANY, "PurchaseOrderNumber" to po.id, "LineNumber" to l.lineNumber, "ItemNumber" to l.itemId,
                        "LineDescription" to item(l.itemId)?.name.orEmpty(), "PurchaseUnitSymbol" to UNIT, "OrderedPurchaseQuantity" to l.ordered,
                        "ReceivingSiteId" to SITE, "ReceivingWarehouseId" to WAREHOUSE, "ReceivingWarehouseLocationId" to "MAIN",
                        "AllowedOverdeliveryPercentage" to 10.0, "AllowedUnderdeliveryPercentage" to 100.0,
                        "PurchaseOrderLineStatus" to if (l.received < l.ordered) "Backorder" else "Received", "IsLineStopped" to "No")
                }
            }
            "Companies" -> listOf(obj("DataArea" to COMPANY, "Name" to "Demo Company"))
            else -> emptyList()
        }
        val filtered = rows.filter { matches(it, params["\$filter"].orEmpty()) }
        val top = params["\$top"]?.toIntOrNull()
        val skip = params["\$skip"]?.toIntOrNull() ?: 0
        val page = filtered.drop(skip).let { if (top != null && top >= 0) it.take(top) else it }
        val envelope = mutableMapOf<String, JsonElement>("value" to JsonArray(page))
        if (params["\$count"].equals("true", true)) envelope["@odata.count"] = JsonPrimitive(filtered.size)
        return ok(JsonObject(envelope))
    }

    private val eqClause = Regex("""(\w+)\s+eq\s+'((?:[^']|'')*)'""")

    /** Applies every `Field eq 'value'` of the filter (or-ed values per field); anything else is ignored. */
    private fun matches(row: JsonObject, filter: String): Boolean {
        if (filter.isBlank()) return true
        val wanted = eqClause.findAll(filter).groupBy({ it.groupValues[1] }, { it.groupValues[2].replace("''", "'") })
        return wanted.all { (field, values) ->
            val have = (row[field] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@all true
            values.any { it.equals(have, true) }
        }
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.contains('=') }.associate {
        val k = it.substringBefore('=')
        val v = it.substringAfter('=')
        decode(k) to decode(v)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    // =============================================================================================
    // JSON helpers
    // =============================================================================================

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun ok(body: JsonObject) = Reply(200, body.toString())

    /** A JSON object from Kotlin values: String, Number, Boolean, null, JsonElement, lists of those. */
    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to element(v) })

    private fun element(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Iterable<*> -> JsonArray(v.map(::element))
        else -> JsonPrimitive(v.toString())
    }

    /** The `_request` object of a service call (or the body itself when it has no wrapper). */
    private fun requestOf(body: String?): JsonObject {
        val root = runCatching { json.parseToJsonElement(body.orEmpty()) as? JsonObject }.getOrNull() ?: return JsonObject(emptyMap())
        return root["_request"] as? JsonObject ?: root
    }

    private fun parseArray(text: String): List<JsonElement> =
        text.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: emptyList()

    private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.d(key: String): Double = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: 0.0

    private fun JsonObject.b(key: String): Boolean = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull.equals("true", true) } ?: false

    private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    private fun nowUtc(): String = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "Z"

    private fun ean13(base12: String): String {
        val digits = base12.take(12).padEnd(12, '0')
        val sum = digits.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return digits + ((10 - sum % 10) % 10)
    }
}
