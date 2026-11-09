package com.d365fo.mobileops.scan

import com.d365fo.mobileops.presentation.common.scan.ScanIdentifier
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.TransferDirection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** One test per barcode type the redesign brief lists (§6 Phase 3), in its identification order. */
class ScanIdentifierTest {

    private val lookups = FakeScanLookups()
    private val identifier = ScanIdentifier(lookups)

    private suspend fun identify(value: String) = identifier.identify(scan(value))

    @Test
    fun `GS1 label in brackets gives the item with batch, expiry and serial`() = runTest {
        val id = identify("(01)09506000134352(10)24A17(17)261231(21)SN0001") as ScanIdentity.Item
        assertThat(id.item.itemId).isEqualTo("BSR-11397-0")
        assertThat(id.batch).isEqualTo("24A17")
        assertThat(id.expiry).isEqualTo(LocalDate.of(2026, 12, 31))
        assertThat(id.serial).isEqualTo("SN0001")
    }

    @Test
    fun `GS1 DataMatrix with FNC1 separators and a count on the label`() = runTest {
        val id = identify("]d2010950600013435210lot-7b\u001D3012\u001D17270600") as ScanIdentity.Item
        assertThat(id.item.itemId).isEqualTo("BSR-11397-0")
        // Batch keeps its case - a lowercase lot is a different lot.
        assertThat(id.batch).isEqualTo("lot-7b")
        assertThat(id.labelQuantity).isEqualTo(BigDecimal("12"))
        // DD = 00 is the last day of the month.
        assertThat(id.expiry).isEqualTo(LocalDate.of(2027, 6, 30))
    }

    @Test
    fun `GS1 label whose GTIN is not on the device is unknown, but says it was GS1`() = runTest {
        val id = identify("(01)00012345678905(10)X1") as ScanIdentity.Unknown
        assertThat(id.gs1?.gtin).isEqualTo("00012345678905")
    }

    @Test
    fun `purchase order number`() = runTest {
        val id = identify("BSP-PO-000161763") as ScanIdentity.Document
        assertThat(id.kind).isEqualTo(ScanKind.PURCHASE_ORDER)
        assertThat(id.documentId).isEqualTo("BSP-PO-000161763")
    }

    @Test
    fun `transfer order number, with its direction from the device's copy`() = runTest {
        val ship = identify("BSP-TO-000000043") as ScanIdentity.Document
        assertThat(ship.kind).isEqualTo(ScanKind.TRANSFER_ORDER)
        assertThat(ship.direction).isEqualTo(TransferDirection.SHIP)
        assertThat((identify("BSP-TO-000000044") as ScanIdentity.Document).direction).isEqualTo(TransferDirection.RECEIVE)
        // Not on the device: still a transfer, direction unknown.
        assertThat((identify("BSP-TO-000000999") as ScanIdentity.Document).direction).isNull()
    }

    @Test
    fun `counting journal number`() = runTest {
        val id = identify("BSP-JN-000552008") as ScanIdentity.Document
        assertThat(id.kind).isEqualTo(ScanKind.COUNT_JOURNAL)
    }

    @Test
    fun `document numbers of another company and in lower case are still documents`() = runTest {
        val id = identify("  abc-po-000000017\r\n") as ScanIdentity.Document
        assertThat(id.kind).isEqualTo(ScanKind.PURCHASE_ORDER)
        assertThat(id.documentId).isEqualTo("ABC-PO-000000017")
    }

    @Test
    fun `document numbers are recognised without any lookup`() = runTest {
        identify("BSP-PO-000161763")
        assertThat(lookups.calls).isEmpty()
    }

    @Test
    fun `location in the current warehouse, either case`() = runTest {
        assertThat((identify("A-01-02") as ScanIdentity.Location).locationId).isEqualTo("A-01-02")
        assertThat((identify("a-01-02") as ScanIdentity.Location).locationId).isEqualTo("A-01-02")
    }

    @Test
    fun `store count bin label, with the location it belongs to`() = runTest {
        val id = identify("BIN-07") as ScanIdentity.Bin
        assertThat(id.bin.binId).isEqualTo("BIN-07")
        assertThat(id.bin.locationId).isEqualTo("A-01-02")
    }

    @Test
    fun `plain EAN from the barcode table`() = runTest {
        val id = identify("5012345678900") as ScanIdentity.Item
        assertThat(id.item.itemId).isEqualTo("BSR-20001-0")
        assertThat(id.barcode?.barcode).isEqualTo("5012345678900")
        assertThat(id.gs1).isNull()
    }

    @Test
    fun `item number label`() = runTest {
        val id = identify("BSR-10113-0") as ScanIdentity.Item
        assertThat(id.item.itemId).isEqualTo("BSR-10113-0")
        assertThat(id.barcode).isNull()
    }

    @Test
    fun `blocked item is identified, and marked inactive`() = runTest {
        val id = identify("4006381333931") as ScanIdentity.Item
        assertThat(id.item.active).isFalse()
    }

    @Test
    fun `a location wins over an item with the same code`() = runTest {
        lookups.items["RECEIVING"] = com.d365fo.mobileops.presentation.common.scan.ItemRef("RECEIVING", "Odd item")
        assertThat(identify("RECEIVING")).isInstanceOf(ScanIdentity.Location::class.java)
    }

    @Test
    fun `unknown barcode`() = runTest {
        val id = identify("ZZZ-NOT-A-THING")
        assertThat(id).isInstanceOf(ScanIdentity.Unknown::class.java)
        assertThat((id as ScanIdentity.Unknown).gs1).isNull()
    }

    @Test
    fun `blank scan is unknown`() = runTest {
        assertThat(identify("  \r\n")).isInstanceOf(ScanIdentity.Unknown::class.java)
    }
}
