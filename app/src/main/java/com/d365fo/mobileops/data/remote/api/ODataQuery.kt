package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.util.Formatters

/**
 * Builds OData `$filter` strings without hand-concatenating quotes at call sites.
 *
 * String literals are escaped by doubling single quotes, which is the OData rule. This is
 * the only place that formats a filter value, so an item number containing an apostrophe
 * cannot break a query or smuggle in extra predicates.
 */
object ODataFilter {

    fun eq(field: String, value: String): String = "$field eq ${literal(value)}"
    fun eq(field: String, value: Boolean): String = "$field eq $value"
    fun eq(field: String, value: Number): String = "$field eq $value"

    fun gt(field: String, epochMs: Long): String =
        "$field gt ${Formatters.epochMillisToOData(epochMs)}"

    fun lt(field: String, epochMs: Long): String =
        "$field lt ${Formatters.epochMillisToOData(epochMs)}"

    fun ge(field: String, value: Number): String = "$field ge $value"
    fun geDate(field: String, epochMs: Long): String =
        "$field ge ${Formatters.epochMillisToOData(epochMs)}"
    fun ne(field: String, value: String): String = "$field ne ${literal(value)}"

    fun startsWith(field: String, value: String): String =
        "startswith($field, ${literal(value)})"

    fun contains(field: String, value: String): String =
        "contains($field, ${literal(value)})"

    fun and(vararg clauses: String?): String =
        clauses.filterNotNull().filter { it.isNotBlank() }.joinToString(" and ") { "($it)" }

    fun or(vararg clauses: String?): String =
        clauses.filterNotNull().filter { it.isNotBlank() }.joinToString(" or ") { "($it)" }

    fun literal(value: String): String = "'" + value.replace("'", "''") + "'"
}

/** Column projections. Sending an explicit `$select` is mandatory, never optional. */
object ODataSelect {

    val PURCH_HEADER = listOf(
        "PurchaseOrderNumber", "OrderVendorAccountNumber", "PurchaseOrderStatus",
        "DocumentApprovalStatus", "RequestedDeliveryDate", "DefaultReceivingSiteId",
        "DefaultReceivingWarehouseId", "CurrencyCode", "dataAreaId",
    ).joinToString(",")

    val PURCH_LINE = listOf(
        "PurchaseOrderNumber", "LineNumber", "ItemNumber", "LineDescription",
        "PurchaseUnitSymbol", "OrderedPurchaseQuantity", "ReceivingSiteId",
        "ReceivingWarehouseId", "ReceivingWarehouseLocationId",
        "AllowedOverdeliveryPercentage", "AllowedUnderdeliveryPercentage",
        "PurchaseOrderLineStatus", "IsLineStopped", "dataAreaId",
    ).joinToString(",")

    /** ProductReceiptLinesV2 - supplies the received quantity PurchaseOrderLinesV2 omits. */
    val RECEIPT_LINE = listOf(
        "PurchaseOrderNumber", "PurchaseOrderLineNumber", "ItemNumber",
        "ReceivedPurchaseQuantity", "RemainingPurchaseQuantity", "PurchaseUnitSymbol",
        "ProductReceiptNumber", "ProductReceiptDate", "dataAreaId",
    ).joinToString(",")

    /**
     * ReleasedProductsV2. There is no ProductName on this entity - sending one is a 400 that
     * takes forty seconds to come back. SearchName is the operator-facing name.
     */
    val RELEASED_PRODUCT = listOf(
        "ItemNumber", "SearchName", "InventoryUnitSymbol", "PurchaseUnitSymbol",
        "SalesUnitSymbol", "TrackingDimensionGroupName", "StorageDimensionGroupName",
        "IsBatchNumberActive", "IsSerialNumberActive", "ProductLifecycleStateId", "dataAreaId",
    ).joinToString(",")

    /**
     * Confirmed against the live instance: all four names are present on every one of the
     * 143 rows this tenant returns. Before this list the query fetched whole rows - 524 KB
     * for data the picker uses about 10 KB of, parsed on a handheld over warehouse wifi.
     */
    val WAREHOUSE = listOf(
        "WarehouseId", "WarehouseName", "OperationalSiteId",
        "AreAdvancedWarehouseManagementProcessesEnabled", "dataAreaId",
    ).joinToString(",")

    val BARCODE = listOf(
        "Barcode", "ItemNumber", "ProductQuantity", "ProductQuantityUnitSymbol",
        "IsDefaultScannedBarcode", "dataAreaId",
    ).joinToString(",")

    val LOCATION = listOf(
        "WarehouseId", "WarehouseLocationId", "InputWarehouseLocationBlockingCauseId",
        "OutputWarehouseLocationBlockingCauseId", "dataAreaId",
    ).joinToString(",")

    val ON_HAND = listOf(
        "ItemNumber", "InventorySiteId", "InventoryWarehouseId", "OnHandQuantity",
        "AvailableOnHandQuantity", "ReservedOnHandQuantity", "OrderedQuantity",
        "ReservedOrderedQuantity", "AvailableOrderedQuantity", "TotalAvailableQuantity",
        "dataAreaId",
    ).joinToString(",")
}

