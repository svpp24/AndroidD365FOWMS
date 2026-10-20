package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsOrderCreateSvc - one request and one response shape for every operation. */
@Serializable
data class OrderServiceRequest(@SerialName("_request") val request: OrderRequestBody)

@Serializable
data class OrderRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String = "",
    @SerialName("DeviceId") val deviceId: String = "",
    @SerialName("IdempotencyKey") val idempotencyKey: String = "",
    @SerialName("VendorAccount") val vendorAccount: String = "",
    @SerialName("SiteId") val siteId: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("FromWarehouseId") val fromWarehouseId: String = "",
    @SerialName("ToWarehouseId") val toWarehouseId: String = "",
    @SerialName("DeliveryDate") val deliveryDate: String = "",
    @SerialName("ShipDate") val shipDate: String = "",
    @SerialName("ReceiveDate") val receiveDate: String = "",
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ConfigId") val configId: String = "",
    @SerialName("SizeId") val sizeId: String = "",
    @SerialName("ColorId") val colorId: String = "",
    @SerialName("StyleId") val styleId: String = "",
    @SerialName("PurchId") val purchId: String = "",
    /** Purchase return: the return reason code and the vendor's RMA number (blank = D365FO's). */
    @SerialName("ReturnReasonCode") val returnReasonCode: String = "",
    @SerialName("RmaNumber") val rmaNumber: String = "",
    /** Purchase return: the return action every line gets (ReturnActionDefaults). */
    @SerialName("ReturnActionId") val returnActionId: String = "",
    /** getStock: a row per bin as well (purchase return picks the bin it takes stock from). */
    @SerialName("GroupByLocation") val groupByLocation: Boolean = false,
    @SerialName("Skip") val skip: Int = 0,
    @SerialName("Take") val take: Int = 0,
    /**
     * The lines as a JSON array string - AOS's JSON binding can't read a list of a custom class
     * on the way in (see HSMobileOpsOrderRequest.parmLines). Build it with [linesJson].
     */
    @SerialName("LinesJson") val linesJson: String = "",
)

private val linesFormat = kotlinx.serialization.json.Json { encodeDefaults = true }

/** [OrderRequestBody.linesJson] for these lines. */
fun linesJson(lines: List<OrderLineDto>): String = if (lines.isEmpty()) "" else linesFormat.encodeToString(kotlinx.serialization.builtins.ListSerializer(OrderLineDto.serializer()), lines)

@Serializable
data class OrderLineDto(
    @SerialName("LineNumber") val lineNumber: Double = 0.0,
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("ConfigId") val configId: String = "",
    @SerialName("SizeId") val sizeId: String = "",
    @SerialName("ColorId") val colorId: String = "",
    @SerialName("StyleId") val styleId: String = "",
    @SerialName("BatchId") val batchId: String = "",
    @SerialName("SerialId") val serialId: String = "",
    @SerialName("LocationId") val locationId: String = "",
    @SerialName("Qty") val qty: Double = 0.0,
    @SerialName("UnitId") val unitId: String = "",
    @SerialName("Price") val price: Double = 0.0,
    @SerialName("PriceUnit") val priceUnit: Double = 0.0,
    @SerialName("PriceOverride") val priceOverride: Boolean = false,
    @SerialName("DiscPct") val discPct: Double = 0.0,
    @SerialName("DiscAmount") val discAmount: Double = 0.0,
    @SerialName("LineAmount") val lineAmount: Double = 0.0,
    @SerialName("PriceSource") val priceSource: String = "",
    @SerialName("Available") val available: Double = 0.0,
    @SerialName("Message") val message: String = "",
)

@Serializable
data class OrderVendorDto(
    @SerialName("AccountNum") val accountNum: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("CurrencyCode") val currencyCode: String = "",
    @SerialName("PaymentTerms") val paymentTerms: String = "",
    @SerialName("DeliveryTerms") val deliveryTerms: String = "",
    @SerialName("DeliveryMode") val deliveryMode: String = "",
)

@Serializable
data class OrderItemDto(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("PurchUnit") val purchUnit: String = "",
    @SerialName("InventUnit") val inventUnit: String = "",
    @SerialName("ConfigActive") val configActive: Boolean = false,
    @SerialName("SizeActive") val sizeActive: Boolean = false,
    @SerialName("ColorActive") val colorActive: Boolean = false,
    @SerialName("StyleActive") val styleActive: Boolean = false,
    @SerialName("BatchActive") val batchActive: Boolean = false,
    @SerialName("SerialActive") val serialActive: Boolean = false,
    /**
     * The item may go below zero on hand (physical negative inventory, from its model group or
     * the request's From warehouse) - no stock refusal then.
     */
    @SerialName("NegativePhysical") val negativePhysical: Boolean = false,
    @SerialName("MinQty") val minQty: Double = 0.0,
    @SerialName("MaxQty") val maxQty: Double = 0.0,
    @SerialName("MultipleQty") val multipleQty: Double = 0.0,
    @SerialName("StandardQty") val standardQty: Double = 0.0,
    @SerialName("Variants") val variants: List<OrderLineDto> = emptyList(),
)

@Serializable
data class OrderServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    @SerialName("Message") val message: String = "",
    @SerialName("DocumentNumber") val documentNumber: String = "",
    @SerialName("AlreadyCreated") val alreadyCreated: Boolean = false,
    @SerialName("TransitWarehouseId") val transitWarehouseId: String = "",
    @SerialName("CurrencyCode") val currencyCode: String = "",
    @SerialName("OrderTotal") val orderTotal: Double = 0.0,
    @SerialName("HasMore") val hasMore: Boolean = false,
    @SerialName("Item") val item: OrderItemDto? = null,
    @SerialName("Vendors") val vendors: List<OrderVendorDto> = emptyList(),
    @SerialName("Lines") val lines: List<OrderLineDto> = emptyList(),
    @SerialName("Permissions") val permissions: List<ModulePermissionDto> = emptyList(),
    @SerialName("ConfirmationNumber") val confirmationNumber: String = "",
    @SerialName("Orders") val orders: List<OrderSummaryDto> = emptyList(),
    /** Purchase return: the packing slip posted for it - the stock has left. */
    @SerialName("PackingSlipId") val packingSlipId: String = "",
    @SerialName("Reasons") val reasons: List<ReturnReasonDto> = emptyList(),
    /** Return actions, in the same shape as the reasons. */
    @SerialName("Actions") val actions: List<ReturnReasonDto> = emptyList(),
)

/** A return reason code (ReturnReasonCode). */
@Serializable
data class ReturnReasonDto(
    @SerialName("Code") val code: String = "",
    @SerialName("Description") val description: String = "",
)

/** A purchase order as PO confirmation lists it (HSMobileOpsOrderSummary). */
@Serializable
data class OrderSummaryDto(
    @SerialName("PurchId") val purchId: String = "",
    @SerialName("VendorAccount") val vendorAccount: String = "",
    @SerialName("VendorName") val vendorName: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("DeliveryDate") val deliveryDate: String = "",
    @SerialName("LineCount") val lineCount: Int = 0,
    @SerialName("OrderTotal") val orderTotal: Double = 0.0,
    @SerialName("CurrencyCode") val currencyCode: String = "",
    @SerialName("ApprovalStatus") val approvalStatus: String = "",
    @SerialName("PurchStatus") val purchStatus: String = "",
    @SerialName("CreatedBy") val createdBy: String = "",
    @SerialName("CreatedDate") val createdDate: String = "",
    @SerialName("ConfirmationNumber") val confirmationNumber: String = "",
)
