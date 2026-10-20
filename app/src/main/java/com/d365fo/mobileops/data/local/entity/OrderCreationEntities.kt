package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A vendor a purchase order can be raised against (not on hold), kept on the device so vendor
 * search works offline. Refreshed whole from `HSMobileOpsOrderCreateSvc/listVendors` while
 * online - see OrderCreationRepository.refreshVendors.
 */
@Entity(tableName = "order_vendor", indices = [Index("name")])
data class VendorEntity(
    @PrimaryKey val accountNum: String,
    val name: String,
    val currencyCode: String,
    val paymentTerms: String,
    val deliveryTerms: String,
    val deliveryMode: String,
    val savedAt: Long,
)

/** Where an order made on this device stands. */
enum class CreatedOrderStatus { WAITING, CREATED, FAILED }

/** Which kind of order. */
enum class OrderKind {
    PURCHASE,
    TRANSFER,

    /** A purchase order confirmed on this device; documentNumber is the PO number. */
    CONFIRMATION,

    /** Feedback sent from this device - kept here only until D365FO has it (see FeedbackRepository). */
    FEEDBACK,

    /** A return to a vendor; documentNumber is the return order, message names its packing slip. */
    RETURN,
}

/**
 * An order created on this device: saved the moment the worker taps Create, then updated with
 * D365FO's number and prices when it syncs. The id is the order's idempotency key, so the row
 * and its queue entry always match.
 */
@Entity(tableName = "created_order", indices = [Index("createdAt")])
data class CreatedOrderEntity(
    @PrimaryKey val id: String,
    val kind: OrderKind,
    val status: CreatedOrderStatus,
    /** PO or TO number once D365FO created it. */
    val documentNumber: String? = null,
    /** "19721 Contoso" for a purchase order, "AAC -> ABB" for a transfer. */
    val title: String,
    val lineCount: Int,
    /** D365FO's order total, once priced; null for transfers and before sync. */
    val total: Double? = null,
    val currencyCode: String? = null,
    /** D365FO's reason when it refused the order. */
    val message: String? = null,
    /** The lines as D365FO priced them (JSON of OrderLinePayload), once created. */
    val pricedLinesJson: String? = null,
    val userName: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * The order being built, one per kind and worker, so a half-built order survives the app being
 * closed. Its body is the screen's own draft state as JSON.
 */
@Entity(tableName = "order_draft", primaryKeys = ["kind", "userName"])
data class OrderDraftEntity(
    val kind: OrderKind,
    val userName: String,
    val json: String,
    val updatedAt: Long,
)
