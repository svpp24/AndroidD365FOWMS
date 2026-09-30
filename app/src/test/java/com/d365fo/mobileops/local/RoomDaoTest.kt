package com.d365fo.mobileops.local

import android.app.Application
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.local.entity.BarcodeEntity
import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.LocationEntity
import com.d365fo.mobileops.data.local.entity.PurchLineEntity
import com.d365fo.mobileops.data.local.entity.PurchOrderEntity
import com.d365fo.mobileops.data.local.entity.PurchaseOrderStatus
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Executes the hand-written DAO SQL against a real SQLite engine.
 *
 * The Jupiter suites cover the pure logic; nothing there ever runs a query. Room's KSP
 * processor validates that a statement *parses* and that its columns map, but it cannot
 * check that the predicate expresses the rule intended — a `LIKE` that never matches or a
 * join that silently drops rows compiles perfectly and fails only on a loading dock.
 *
 * SQLCipher is deliberately not wired in here: the cipher's native library cannot load under
 * Robolectric, and the encryption layer is orthogonal to whether the SQL is correct.
 */
/*
 * `application = Application::class` is load-bearing. Left to itself Robolectric boots the
 * real D365MobileApp from the merged manifest, which runs Hilt injection, which builds
 * SecureStore, which asks for AndroidKeyStore — a provider that does not exist off-device.
 * That is correct behaviour for the app and irrelevant to whether the SQL is right, so this
 * suite starts a bare Application and drives Room directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class RoomDaoTest {

    private lateinit var db: D365Database

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            D365Database::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    // -- fixtures ------------------------------------------------------------------------

    private fun item(
        id: String,
        tracking: TrackingRequirement = TrackingRequirement.NONE,
        active: Boolean = true,
    ) = ItemEntity(
        itemId = id,
        itemName = "Item $id",
        searchName = id,
        inventoryUnit = "ea",
        purchaseUnit = "ea",
        salesUnit = "ea",
        trackingRequirement = tracking,
        locationMandatory = true,
        active = active,
        modifiedAt = 1_000L,
    )

    private fun barcode(code: String, itemId: String, active: Boolean = true) = BarcodeEntity(
        barcode = code,
        itemId = itemId,
        quantityPerScan = "1",
        unitId = "ea",
        active = active,
        modifiedAt = 1_000L,
    )

    private fun order(
        id: String,
        warehouse: String = "WH1",
        status: PurchaseOrderStatus = PurchaseOrderStatus.BACKORDER,
        approved: Boolean = true,
        vendorName: String = "Nordwind Components AB",
    ) = PurchOrderEntity(
        purchId = id,
        vendorAccount = "V-100418",
        vendorName = vendorName,
        status = status,
        siteId = "S-01",
        warehouseId = warehouse,
        deliveryDate = 2_000L,
        currencyCode = "EUR",
        approved = approved,
        overDeliveryPercent = null,
        underDeliveryPercent = null,
        modifiedAt = 1_000L,
    )

    private fun line(
        po: String,
        no: Double,
        itemId: String,
        ordered: String,
        received: String = "0",
    ) = PurchLineEntity(
        lineId = "$po:$no",
        purchId = po,
        lineNumber = no,
        itemId = itemId,
        itemName = "Item $itemId",
        purchaseUnit = "ea",
        inventoryUnit = "ea",
        unitConversionFactor = "1",
        orderedQuantity = ordered,
        receivedQuantity = received,
        siteId = "S-01",
        warehouseId = "WH1",
        locationId = "RECV-01",
        overDeliveryPercent = null,
        underDeliveryPercent = null,
        lineStatus = PurchaseOrderStatus.BACKORDER,
        serverRecId = 1L,
        modifiedAt = 1_000L,
    )

    // -- scan resolution -----------------------------------------------------------------

    @Test
    fun `resolveBarcode joins the barcode to its item in one hop`() = runTest {
        db.itemDao().upsertItems(listOf(item("ITEM-1", TrackingRequirement.BATCH)))
        db.itemDao().upsertBarcodes(listOf(barcode("5901234123457", "ITEM-1")))

        val hit = db.itemDao().resolveBarcode("5901234123457")

        assertThat(hit).isNotNull()
        assertThat(hit!!.itemId).isEqualTo("ITEM-1")
        assertThat(hit.trackingRequirement).isEqualTo(TrackingRequirement.BATCH)
        assertThat(hit.itemActive).isTrue()
        assertThat(hit.barcodeActive).isTrue()
    }

    @Test
    fun `resolveBarcode reports item and barcode activity separately`() = runTest {
        // A blocked item and a retired barcode are different problems and get different
        // messages, so the query must not collapse them into one flag.
        db.itemDao().upsertItems(listOf(item("ITEM-2", active = false)))
        db.itemDao().upsertBarcodes(listOf(barcode("111", "ITEM-2", active = true)))

        val hit = db.itemDao().resolveBarcode("111")!!

        assertThat(hit.itemActive).isFalse()
        assertThat(hit.barcodeActive).isTrue()
    }

    @Test
    fun `resolveBarcode misses an unknown code rather than throwing`() = runTest {
        assertThat(db.itemDao().resolveBarcode("nope")).isNull()
    }

    // -- worklist ------------------------------------------------------------------------

    @Test
    fun `observeOpenOrders returns only receivable orders for the worker's warehouse`() = runTest {
        val dao = db.purchOrderDao()
        dao.upsertOrders(
            listOf(
                order("PO-1"),
                order("PO-2", warehouse = "WH9"),
                order("PO-3", status = PurchaseOrderStatus.RECEIVED),
                order("PO-4", approved = false),
                order("PO-5", status = PurchaseOrderStatus.NONE),
            ),
        )

        val visible = dao.observeOpenOrders("WH1").first().map { it.order.purchId }

        assertThat(visible).containsExactly("PO-1", "PO-5")
    }

    @Test
    fun `searchOpenOrders matches a PO prefix and ranks it above a vendor hit`() = runTest {
        val dao = db.purchOrderDao()
        dao.upsertOrders(
            listOf(
                order("PO-000487", vendorName = "Contoso Steel Ltd"),
                order("PO-000999", vendorName = "PO-000487 Holdings"),
            ),
        )

        val hits = dao.searchOpenOrders("WH1", "PO-000487").first().map { it.order.purchId }

        assertThat(hits).hasSize(2)
        assertThat(hits.first()).isEqualTo("PO-000487")
    }

    @Test
    fun `searchOpenOrders finds an order by vendor name substring`() = runTest {
        val dao = db.purchOrderDao()
        dao.upsertOrders(listOf(order("PO-1", vendorName = "Nordwind Components AB")))

        val hits = dao.searchOpenOrders("WH1", "COMPONENTS").first()

        // The screen upper-cases the query before it reaches the DAO, and SQLite LIKE is
        // case-insensitive for ASCII, so a mid-name match must still land.
        assertThat(hits.map { it.order.purchId }).containsExactly("PO-1")
    }

    @Test
    fun `order and lines come back as one aggregate`() = runTest {
        val dao = db.purchOrderDao()
        dao.replaceOrderWithLines(
            order = order("PO-1"),
            lines = listOf(line("PO-1", 1.0, "ITEM-1", "500"), line("PO-1", 2.0, "ITEM-2", "40")),
        )

        val stored = dao.getOrder("PO-1")

        assertThat(stored).isNotNull()
        assertThat(stored!!.lines).hasSize(2)
        assertThat(stored.lines.map { it.itemId }).containsExactly("ITEM-1", "ITEM-2")
    }

    @Test
    fun `findOpenLinesForItem skips lines that are already fully received`() = runTest {
        val dao = db.purchOrderDao()
        dao.replaceOrderWithLines(
            order = order("PO-1"),
            lines = listOf(
                line("PO-1", 1.0, "ITEM-1", ordered = "100", received = "100"),
                line("PO-1", 2.0, "ITEM-1", ordered = "50", received = "10"),
            ),
        )

        val open = dao.findOpenLinesForItem("PO-1", "ITEM-1")

        assertThat(open.map { it.lineNumber }).containsExactly(2.0)
    }

    @Test
    fun `replaceOrderWithLines does not leave stale lines behind`() = runTest {
        val dao = db.purchOrderDao()
        dao.replaceOrderWithLines(order("PO-1"), listOf(line("PO-1", 1.0, "ITEM-1", "500")))
        dao.replaceOrderWithLines(order("PO-1"), listOf(line("PO-1", 2.0, "ITEM-2", "40")))

        val stored = dao.getOrder("PO-1")!!

        assertThat(stored.lines.map { it.lineNumber }).containsExactly(2.0)
    }

    // -- sync queue ----------------------------------------------------------------------

    private fun queued(id: String, dedupe: String, createdAt: Long, nextAt: Long = 0L) =
        SyncQueueEntity(
            queueId = id,
            operation = SyncOperation.POST_PRODUCT_RECEIPT,
            aggregateId = "receipt-$id",
            payloadJson = "{}",
            workerId = "worker-1",
            deviceId = "device-1",
            createdAt = createdAt,
            nextAttemptAt = nextAt,
            dedupeKey = dedupe,
        )

    @Test
    fun `the unique dedupe key makes a double-tapped post idempotent`() = runTest {
        val q = db.syncQueueDao()
        q.enqueue(queued("q1", "receipt-1", 1_000L))
        q.enqueue(queued("q2", "receipt-1", 1_001L))

        // IGNORE on the unique index, not a crash and not a second product receipt.
        assertThat(q.observePendingCount().first()).isEqualTo(1)
        assertThat(q.findByDedupeKey("receipt-1")?.queueId).isEqualTo("q1")
    }

    @Test
    fun `leaseBatch claims due work in FIFO order and hides it from the next lease`() = runTest {
        val q = db.syncQueueDao()
        q.enqueue(queued("q-late", "d1", createdAt = 2_000L))
        q.enqueue(queued("q-early", "d2", createdAt = 1_000L))

        val first = q.leaseBatch(limit = 1, now = 5_000L)
        assertThat(first.map { it.queueId }).containsExactly("q-early")

        // The leased row must not be handed to a second run while the first still holds it.
        val second = q.leaseBatch(limit = 10, now = 5_000L)
        assertThat(second.map { it.queueId }).containsExactly("q-late")
    }

    @Test
    fun `an entry is not due until its backoff has elapsed`() = runTest {
        val q = db.syncQueueDao()
        q.enqueue(queued("q1", "d1", createdAt = 1_000L, nextAt = 9_000L))

        assertThat(q.leaseBatch(limit = 10, now = 5_000L)).isEmpty()
        assertThat(q.leaseBatch(limit = 10, now = 9_000L).map { it.queueId })
            .containsExactly("q1")
    }

    @Test
    fun `an expired lease becomes claimable again`() = runTest {
        // A worker killed mid-post must not make an entry invisible forever.
        val q = db.syncQueueDao()
        q.enqueue(queued("q1", "d1", createdAt = 1_000L))
        q.leaseBatch(limit = 1, now = 5_000L, leaseMs = 1_000L)

        assertThat(q.leaseBatch(limit = 1, now = 5_500L)).isEmpty()
        assertThat(q.leaseBatch(limit = 1, now = 7_000L).map { it.queueId }).containsExactly("q1")
    }

    @Test
    fun `deadLetter moves the entry across in one transaction`() = runTest {
        val q = db.syncQueueDao()
        q.enqueue(queued("q1", "d1", createdAt = 1_000L))

        q.deadLetter(
            queueId = "q1",
            failure = FailedSyncEntity(
                failureId = "f1",
                operation = SyncOperation.POST_PRODUCT_RECEIPT,
                aggregateId = "receipt-q1",
                payloadJson = "{}",
                workerId = "worker-1",
                deviceId = "device-1",
                originallyQueuedAt = 1_000L,
                failedAt = 2_000L,
                attempts = 3,
                errorCode = "PeriodClosed",
                errorMessage = "closed",
                serverDetail = null,
            ),
        )

        assertThat(q.observePendingCount().first()).isEqualTo(0)
        assertThat(q.observeUnacknowledgedFailureCount().first()).isEqualTo(1)
    }

    @Test
    fun `acknowledging a failure clears the badge without resolving it`() = runTest {
        val q = db.syncQueueDao()
        q.insertFailure(
            FailedSyncEntity(
                failureId = "f1",
                operation = SyncOperation.POST_PRODUCT_RECEIPT,
                aggregateId = "receipt-1",
                payloadJson = "{}",
                workerId = "worker-1",
                deviceId = "device-1",
                originallyQueuedAt = 1_000L,
                failedAt = 2_000L,
                attempts = 3,
                errorCode = null,
                errorMessage = "boom",
                serverDetail = null,
            ),
        )

        q.acknowledgeFailure("f1")

        assertThat(q.observeUnacknowledgedFailureCount().first()).isEqualTo(0)
        assertThat(q.observeFailures().first()).hasSize(1)
    }

    // -- locations -----------------------------------------------------------------------

    @Test
    fun `location lookup is scoped to warehouse, and site is not part of the key`() = runTest {
        val dao = db.locationDao()
        dao.upsert(
            listOf(
                // Blank site is what the OData pull actually stores: WarehouseLocations
                // publishes no site. The same location id in two warehouses must stay distinct
                // even so, which is the whole reason the lookup can drop the site safely.
                LocationEntity("", "WH1", "RECV-01", null, active = true, modifiedAt = 0L),
                LocationEntity("", "WH9", "RECV-01", null, active = true, modifiedAt = 0L),
            ),
        )

        assertThat(dao.find("WH1", "RECV-01")).isNotNull()
        assertThat(dao.find("WH9", "RECV-01")).isNotNull()
        assertThat(dao.find("WH2", "RECV-01")).isNull()
    }

    @Test
    fun `a blocked location is stored but reported inactive`() = runTest {
        val dao = db.locationDao()
        dao.upsert(
            listOf(
                LocationEntity("", "WH1", "QC-HOLD", null, active = false, modifiedAt = 0L),
            ),
        )

        assertThat(dao.find("WH1", "QC-HOLD")?.active).isFalse()
        assertThat(dao.search("WH1", "QC").map { it.locationId }).isEmpty()
    }
}
