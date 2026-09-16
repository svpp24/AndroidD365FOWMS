package com.d365fo.mobileops.repository

import com.d365fo.mobileops.data.remote.dto.PurchaseOrderHeaderDto
import com.d365fo.mobileops.data.repository.toEntity
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Which DocumentApprovalStatus values let an operator receive.
 *
 * Getting this wrong is silent in both directions. Too strict and a confirmed order is refused
 * at the bay with the pallet already on the floor; too loose and a draft order is received
 * against, which D365FO rejects only at posting time — long after the operator has moved on.
 *
 * The values below are the real ones this tenant publishes, not a guess at the enum.
 */
class ApprovalStatusTest {

    private fun header(status: String?) = PurchaseOrderHeaderDto(
        purchaseOrderNumber = "PO-1",
        vendorAccount = "V-1",
        purchaseOrderStatus = "Backorder",
        approvalStatus = status,
    ).toEntity()

    @Test
    fun `a confirmed order is receivable`() {
        // Confirmation is the step that makes a purchase order receivable at all, so this is
        // the commonest state a real order is in when it reaches the bay.
        assertThat(header("Confirmed").approved).isTrue()
    }

    @Test
    fun `an approved order is receivable`() {
        assertThat(header("Approved").approved).isTrue()
    }

    @Test
    fun `a finalized order is still receivable against outstanding lines`() {
        assertThat(header("Finalized").approved).isTrue()
    }

    @Test
    fun `a draft order is not receivable`() {
        assertThat(header("Draft").approved).isFalse()
    }

    @Test
    fun `an order in review or rejected is not receivable`() {
        assertThat(header("InReview").approved).isFalse()
        assertThat(header("Rejected").approved).isFalse()
        assertThat(header("InExternalReview").approved).isFalse()
    }

    @Test
    fun `a blank status means change management is off, not that approval was withheld`() {
        // On a tenant without change management the field is empty on every order. Reading
        // that as "not approved" would refuse the entire warehouse.
        assertThat(header(null).approved).isTrue()
        assertThat(header("").approved).isTrue()
    }

    @Test
    fun `the comparison ignores case`() {
        assertThat(header("confirmed").approved).isTrue()
        assertThat(header("DRAFT").approved).isFalse()
    }
}
