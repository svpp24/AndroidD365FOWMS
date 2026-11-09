package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.local.entity.OrderKind
import com.d365fo.mobileops.data.local.entity.VendorEntity
import com.d365fo.mobileops.data.repository.DraftLine
import com.d365fo.mobileops.data.repository.ReturnReason
import com.d365fo.mobileops.data.repository.StockRow
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ItemRef
import com.d365fo.mobileops.domain.model.RawScan
import com.d365fo.mobileops.domain.model.ScanSource
import com.d365fo.mobileops.presentation.modules.ordercreate.LineAdd
import com.d365fo.mobileops.presentation.modules.ordercreate.addOrMerge
import com.d365fo.mobileops.presentation.modules.ordercreate.addedLine
import com.d365fo.mobileops.presentation.modules.ordercreate.defaultCode
import com.d365fo.mobileops.presentation.modules.ordercreate.defaultOrderQty
import com.d365fo.mobileops.presentation.modules.ordercreate.itemCodeFor
import com.d365fo.mobileops.presentation.modules.ordercreate.lineInHand
import com.d365fo.mobileops.presentation.modules.ordercreate.onlyStockRow
import com.d365fo.mobileops.presentation.modules.ordercreate.sentOrderBand
import com.d365fo.mobileops.presentation.modules.ordercreate.undoAdd
import com.d365fo.mobileops.presentation.modules.ordercreate.vendorFor
import com.d365fo.mobileops.presentation.modules.ordercreate.vendorsLastUsedFirst
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The redesigned order-creation screens' rules (phase 7): Create PO's quantity per scan, adding
 * and undoing scans, the only location with stock, last-used first, and the band for an order sent.
 */
class OrderCreateRedesignTest {

    private fun line(item: String, qty: Double, location: String = "", id: String = item) =
        DraftLine(id = id, itemId = item, itemName = "Name of $item", qty = qty, unitId = "pcs", locationId = location)

    private fun vendor(account: String) = VendorEntity(account, "Vendor $account", "SAR", "", "", "", 0L)

    private fun order(status: CreatedOrderStatus, number: String? = null, message: String? = null) = CreatedOrderEntity(
        id = "o1", kind = OrderKind.PURCHASE, status = status, documentNumber = number,
        title = "19721 BinSina", lineCount = 2, message = message, userName = "002", createdAt = 0, updatedAt = 0,
    )

    // -- Create PO: minimum × multiple -------------------------------------------------------------

    @Test fun `default quantity is the minimum rounded up to the multiple`() {
        assertThat(defaultOrderQty(min = 10.0, max = 0.0, multiple = 12.0)).isEqualTo(12.0)
        assertThat(defaultOrderQty(min = 24.0, max = 0.0, multiple = 12.0)).isEqualTo(24.0)
        assertThat(defaultOrderQty(min = 25.0, max = 0.0, multiple = 12.0)).isEqualTo(36.0)
    }

    @Test fun `no minimum orders one multiple, nothing at all orders the standard quantity or one`() {
        assertThat(defaultOrderQty(min = 0.0, max = 0.0, multiple = 6.0)).isEqualTo(6.0)
        assertThat(defaultOrderQty(min = 0.0, max = 0.0, multiple = 0.0, standard = 5.0)).isEqualTo(5.0)
        assertThat(defaultOrderQty(min = 0.0, max = 0.0, multiple = 0.0)).isEqualTo(1.0)
    }

    @Test fun `default quantity never goes above the maximum`() {
        assertThat(defaultOrderQty(min = 10.0, max = 8.0, multiple = 0.0)).isEqualTo(8.0)
    }

    // -- Adding and Undo ---------------------------------------------------------------------------

    @Test fun `a new item is a new line, the same item again adds to its line`() {
        val (one, a1) = addOrMerge(emptyList(), line("A", 12.0, id = "l1"))
        assertThat(one).hasSize(1)
        assertThat(a1).isEqualTo(LineAdd("l1", 12.0))

        val (two, a2) = addOrMerge(one, line("A", 12.0, id = "l2"))
        assertThat(two).hasSize(1)
        assertThat(two.single().qty).isEqualTo(24.0)
        assertThat(a2).isEqualTo(LineAdd("l1", 12.0))

        val (three, _) = addOrMerge(two, line("B", 1.0, id = "l3"))
        assertThat(three.map { it.itemId }).containsExactly("A", "B").inOrder()
    }

    @Test fun `stock from another location is its own line`() {
        val (lines, _) = addOrMerge(listOf(line("A", 1.0, "L1", id = "l1")), line("A", 1.0, "L2", id = "l2"))
        assertThat(lines).hasSize(2)
    }

    @Test fun `a merged PO line is priced again`() {
        val priced = line("A", 12.0, id = "l1").copy(price = 2.0, lineAmount = 24.0)
        val (lines, _) = addOrMerge(listOf(priced), line("A", 12.0, id = "l2"), repriced = true)
        assertThat(lines.single().lineAmount).isNull()
        assertThat(lines.single().price).isNull()
    }

    @Test fun `a line with a price set by hand is never merged into`() {
        val manual = line("A", 12.0, id = "l1").copy(priceOverride = true, price = 3.0)
        val (lines, _) = addOrMerge(listOf(manual), line("A", 12.0, id = "l2"), repriced = true)
        assertThat(lines).hasSize(2)
    }

    @Test fun `undo takes back what the last scan added, and the line when nothing is left`() {
        val lines = listOf(line("A", 24.0, id = "l1"), line("B", 1.0, id = "l2"))
        val afterA = undoAdd(lines, LineAdd("l1", 12.0))
        assertThat(afterA.first { it.id == "l1" }.qty).isEqualTo(12.0)
        assertThat(undoAdd(lines, LineAdd("l2", 1.0)).map { it.id }).containsExactly("l1")
    }

    @Test fun `undo without a record drops the last line`() {
        val lines = listOf(line("A", 1.0, id = "l1"), line("B", 1.0, id = "l2"))
        assertThat(undoAdd(lines, null).map { it.id }).containsExactly("l1")
        assertThat(undoAdd(lines, LineAdd("gone", 1.0)).map { it.id }).containsExactly("l1")
    }

    @Test fun `the line in hand is the one the last scan touched`() {
        val lines = listOf(line("A", 1.0, id = "l1"), line("B", 1.0, id = "l2"))
        assertThat(lineInHand(lines, LineAdd("l1", 1.0))?.id).isEqualTo("l1")
        assertThat(lineInHand(lines, null)?.id).isEqualTo("l2")
        assertThat(lineInHand(emptyList(), null)).isNull()
    }

    @Test fun `added line wording shows the line total when the scan added to it`() {
        assertThat(addedLine(line("A", 12.0), 12.0)).isEqualTo("12 pcs")
        assertThat(addedLine(line("A", 24.0), 12.0)).isEqualTo("+12 · 24 pcs on the line")
    }

    // -- Return: only location with stock, last-used codes -----------------------------------------

    @Test fun `the only location with stock is taken, several are a choice`() {
        val one = StockRow("", "", 5.0, "L1")
        assertThat(onlyStockRow(listOf(one, StockRow("", "", 0.0, "L2")))).isEqualTo(one)
        assertThat(onlyStockRow(listOf(one, StockRow("", "", 2.0, "L2")))).isNull()
        assertThat(onlyStockRow(emptyList())).isNull()
    }

    @Test fun `reason defaults to the draft's, then the last used, then the only one`() {
        val codes = listOf(ReturnReason("1", "Damaged"), ReturnReason("2", "Wrong item"))
        assertThat(defaultCode("2", "1", codes)).isEqualTo("2")
        assertThat(defaultCode("", "1", codes)).isEqualTo("1")
        assertThat(defaultCode("", "9", codes)).isEmpty()
        assertThat(defaultCode("", null, codes.take(1))).isEqualTo("1")
    }

    // -- Create PO: vendors --------------------------------------------------------------------------

    @Test fun `last-used vendor comes first, not while searching`() {
        val list = listOf(vendor("100"), vendor("19721"), vendor("200"))
        assertThat(vendorsLastUsedFirst(list, vendor("19721"), "").map { it.accountNum })
            .containsExactly("19721", "100", "200").inOrder()
        assertThat(vendorsLastUsedFirst(list, vendor("19721"), "10").map { it.accountNum })
            .containsExactly("100", "19721", "200").inOrder()
        // Last used but not in this page of the list: still first.
        assertThat(vendorsLastUsedFirst(list.take(1), vendor("19721"), "").map { it.accountNum })
            .containsExactly("19721", "100").inOrder()
    }

    @Test fun `a scanned vendor account matches exactly`() {
        val list = listOf(vendor("19721"), vendor("197210"))
        assertThat(vendorFor(" 19721 ", list)?.accountNum).isEqualTo("19721")
        assertThat(vendorFor("1972", list)).isNull()
    }

    // -- The band for an order sent ------------------------------------------------------------------

    @Test fun `refused order shows D365FO's reason in a bad band`() {
        val band = sentOrderBand(order(CreatedOrderStatus.FAILED, message = "Vendor 27455 is not approved for BSR-1009-0"), "PO", online = true)
        assertThat(band.kind).isEqualTo(StatusKind.Bad)
        assertThat(band.title).isEqualTo("PO refused")
        assertThat(band.line).isEqualTo("Vendor 27455 is not approved for BSR-1009-0")
    }

    @Test fun `created order shows its number, waiting says sending or saved`() {
        val ok = sentOrderBand(order(CreatedOrderStatus.CREATED, number = "BSP-PO-000000001", message = "Confirmed"), "PO", online = true)
        assertThat(ok.kind).isEqualTo(StatusKind.Ok)
        assertThat(ok.title).isEqualTo("PO BSP-PO-000000001 created")

        assertThat(sentOrderBand(order(CreatedOrderStatus.WAITING), "PO", online = true).title).isEqualTo("Sending PO…")
        assertThat(sentOrderBand(order(CreatedOrderStatus.WAITING), "PO", online = false).title).isEqualTo("PO saved on this device")
    }

    @Test fun `created but not confirmed is a warning`() {
        val band = sentOrderBand(order(CreatedOrderStatus.CREATED, "BSP-PO-1", "Created; not confirmed: needs approval"), "PO", online = true)
        assertThat(band.kind).isEqualTo(StatusKind.Warn)
    }

    // -- Scans ---------------------------------------------------------------------------------------

    @Test fun `an item is looked up by its barcode, a GS1 label by its GTIN, else as scanned`() {
        val raw = RawScan("6291103652447", ScanSource.entries.first())
        assertThat(itemCodeFor(ScanIdentity.Item(raw, ItemRef("BSR-1009-0", "x")))).isEqualTo("BSR-1009-0")
        assertThat(itemCodeFor(ScanIdentity.Unknown(RawScan(" 19721 ", ScanSource.entries.first())))).isEqualTo("19721")
    }
}
