package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PurchaseOrderHeadersV2.
 *
 * Field names verified against a live instance rather than assumed. Two that are commonly
 * expected are simply absent and must not be reintroduced:
 *
 *  - **VendorName** — the entity carries only `OrderVendorAccountNumber`. The worklist shows
 *    the account number; a name would need a second call to the vendor master.
 *  - **ModifiedDateTime** — absent, which is why there is no delta-sync watermark on this
 *    entity. Every pull is a full pull. See MasterDataRepositoryImpl for the consequences.
 */
@Serializable
data class PurchaseOrderHeaderDto(
    @SerialName("dataAreaId") val dataAreaId: String? = null,
    @SerialName("PurchaseOrderNumber") val purchaseOrderNumber: String,
    @SerialName("OrderVendorAccountNumber") val vendorAccount: String = "",
    @SerialName("PurchaseOrderStatus") val purchaseOrderStatus: String? = null,
    @SerialName("DocumentApprovalStatus") val approvalStatus: String? = null,
    @SerialName("RequestedDeliveryDate") val requestedDeliveryDate: String? = null,
    @SerialName("DefaultReceivingSiteId") val siteId: String? = null,
    @SerialName("DefaultReceivingWarehouseId") val warehouseId: String? = null,
    @SerialName("CurrencyCode") val currencyCode: String? = null,
)

/**
 * PurchaseOrderLinesV2.
 *
 * Critically, this entity exposes **only the ordered quantity**. There is no
 * `ReceivedPurchaseQuantity` and no remaining quantity — those live on
 * [ProductReceiptLineDto], keyed by purchase order number plus line number, and have to be
 * aggregated client-side. That is the single biggest shape difference from what the app
 * originally assumed, and it is why the worklist needs a third call.
 */
@Serializable
data class PurchaseOrderLineDto(
    @SerialName("dataAreaId") val dataAreaId: String? = null,
    @SerialName("PurchaseOrderNumber") val purchaseOrderNumber: String,
    @SerialName("LineNumber") val lineNumber: Double,
    @SerialName("ItemNumber") val itemNumber: String = "",
    @SerialName("LineDescription") val lineDescription: String? = null,
    @SerialName("PurchaseUnitSymbol") val purchaseUnit: String? = null,
    @SerialName("OrderedPurchaseQuantity") val orderedQuantity: Double = 0.0,
    @SerialName("ReceivingSiteId") val siteId: String? = null,
    @SerialName("ReceivingWarehouseId") val warehouseId: String? = null,
    @SerialName("ReceivingWarehouseLocationId") val locationId: String? = null,
    @SerialName("AllowedOverdeliveryPercentage") val overDeliveryPercentage: Double? = null,
    @SerialName("AllowedUnderdeliveryPercentage") val underDeliveryPercentage: Double? = null,
    @SerialName("PurchaseOrderLineStatus") val lineStatus: String? = null,
    @SerialName("IsLineStopped")
    @Serializable(with = NoYesSerializer::class)
    val isStopped: Boolean? = null,
    @SerialName("ItemBatchNumber") val batchNumber: String? = null,
    @SerialName("ItemSerialNumber") val serialNumber: String? = null,
)

/**
 * ProductReceiptLinesV2 — what has actually been received against a purchase order line.
 *
 * One row per posted product receipt line, so a partially received line has several. The
 * repository sums [receivedPurchaseQuantity] per (order, line) to get the received total.
 * [remainingPurchaseQuantity] is also published, but it reflects the state at the time that
 * receipt posted rather than now, so it is read only as a cross-check and never trusted as
 * the current remainder.
 */
@Serializable
data class ProductReceiptLineDto(
    @SerialName("dataAreaId") val dataAreaId: String? = null,
    @SerialName("ProductReceiptNumber") val productReceiptNumber: String = "",
    @SerialName("ProductReceiptDate") val productReceiptDate: String? = null,
    @SerialName("PurchaseOrderNumber") val purchaseOrderNumber: String = "",
    @SerialName("PurchaseOrderLineNumber") val purchaseOrderLineNumber: Double = 0.0,
    @SerialName("LineNumber") val lineNumber: Double = 0.0,
    @SerialName("ItemNumber") val itemNumber: String = "",
    @SerialName("ReceivedPurchaseQuantity") val receivedPurchaseQuantity: Double = 0.0,
    @SerialName("ReceivedInventoryQuantity") val receivedInventoryQuantity: Double = 0.0,
    @SerialName("RemainingPurchaseQuantity") val remainingPurchaseQuantity: Double = 0.0,
    @SerialName("PurchaseUnitSymbol") val purchaseUnit: String? = null,
    @SerialName("ReceivingWarehouseId") val warehouseId: String? = null,
    @SerialName("ReceivingSiteId") val siteId: String? = null,
    @SerialName("ItemBatchNumber") val batchNumber: String? = null,
    @SerialName("ItemSerialNumber") val serialNumber: String? = null,
)

// Product receipt posting lives in PostServiceDto.kt (PostServiceRequest / PostServiceResponse),
// matching the deployed HSMobileOpsPostingSvc contract. The types that used to live here
// described a service (`D365MobileOpsServices/MobileReceivingService`) that was never built —
// see PostingDispatcher for the real one.

/** ItemSerialNumbers — layer-4 serial uniqueness probe. */
@Serializable
data class SerialNumberDto(
    @SerialName("ItemNumber") val itemNumber: String = "",
    @SerialName("SerialNumber") val serialNumber: String = "",
    @SerialName("dataAreaId") val dataAreaId: String? = null,
)
