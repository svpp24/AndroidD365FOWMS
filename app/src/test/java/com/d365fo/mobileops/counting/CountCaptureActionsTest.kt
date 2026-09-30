package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.CountCaptureActions
import com.d365fo.mobileops.data.counting.UnexpectedItemResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CountCaptureActionsTest {

    private val store = FakeCountCaptureStore(
        listOf(
            countLine(1, "ITEM-A", "A-01"),
            countLine(2, "ITEM-B", "a-01", counted = "4"),
            countLine(3, "ITEM-C", "B-02"),
        ),
    )
    private val actions = CountCaptureActions(store).also { it.clock = { 5_000L } }

    @Test
    fun `location is empty counts every line there as zero and records who verified it`() = runTest {
        val changed = actions.markLocationEmpty("CNT-1", "A-01", verifiedBy = "counter1")

        assertThat(changed).isEqualTo(2)
        val atA = store.lines.filter { it.locationId.equals("A-01", ignoreCase = true) }
        assertThat(atA.map { it.countedQuantity }).containsExactly("0", "0")
        assertThat(atA.map { it.countedBy }).containsExactly("counter1", "counter1")
        assertThat(store.lines.single { it.locationId == "B-02" }.countedQuantity).isNull()

        val check = store.checks.single()
        assertThat(check.verifiedEmpty).isTrue()
        assertThat(check.verifiedBy).isEqualTo("counter1")
        assertThat(check.verifiedAt).isEqualTo(5_000L)
    }

    @Test
    fun `location is empty on a location not on the journal changes nothing`() = runTest {
        assertThat(actions.markLocationEmpty("CNT-1", "Z-99", verifiedBy = "counter1")).isEqualTo(0)
        assertThat(store.checks).isEmpty()
    }

    @Test
    fun `unexpected item is recorded as an exception, never as a journal line`() = runTest {
        val result = actions.addUnexpectedItem(
            journalId = "CNT-1",
            itemId = " ITEM-X ",
            locationId = "A-01",
            quantityText = "007",
            batchId = "B1",
            serialId = "",
            note = " found behind pallet ",
            capturedBy = "counter1",
        )

        assertThat(result).isInstanceOf(UnexpectedItemResult.Saved::class.java)
        val exception = store.exceptions.single()
        assertThat(exception.itemId).isEqualTo("ITEM-X")
        assertThat(exception.quantity).isEqualTo("7")
        assertThat(exception.serialId).isNull()
        assertThat(exception.note).isEqualTo("found behind pallet")
        assertThat(store.lines).hasSize(3)
    }

    @Test
    fun `unexpected item needs an item and a whole quantity`() = runTest {
        assertThat(actions.addUnexpectedItem("CNT-1", "", null, "1", null, null, null, null))
            .isInstanceOf(UnexpectedItemResult.Invalid::class.java)
        assertThat(actions.addUnexpectedItem("CNT-1", "ITEM-X", null, "1.5", null, null, null, null))
            .isInstanceOf(UnexpectedItemResult.Invalid::class.java)
        assertThat(actions.addUnexpectedItem("CNT-1", "ITEM-X", null, "2", null, "SN1", null, null))
            .isInstanceOf(UnexpectedItemResult.Invalid::class.java)
        assertThat(store.exceptions).isEmpty()
    }
}
