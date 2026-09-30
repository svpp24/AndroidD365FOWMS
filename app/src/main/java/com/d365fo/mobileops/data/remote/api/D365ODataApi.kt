package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.ExecutionIdRequest
import com.d365fo.mobileops.data.remote.dto.ExportToPackageRequest
import com.d365fo.mobileops.data.remote.dto.GetAzureWriteUrlRequest
import com.d365fo.mobileops.data.remote.dto.ImportFromPackageRequest
import com.d365fo.mobileops.data.remote.dto.InventoryLocationDto
import com.d365fo.mobileops.data.remote.dto.ODataActionResult
import com.d365fo.mobileops.data.remote.dto.ODataCollection
import com.d365fo.mobileops.data.remote.dto.OnHandDto
import com.d365fo.mobileops.data.remote.dto.ProductBarcodeDto
import com.d365fo.mobileops.data.remote.dto.ProductReceiptLineDto
import com.d365fo.mobileops.data.remote.dto.PurchaseOrderHeaderDto
import com.d365fo.mobileops.data.remote.dto.PurchaseOrderLineDto
import com.d365fo.mobileops.data.remote.dto.ReleasedProductDto
import com.d365fo.mobileops.data.remote.dto.SerialNumberDto
import com.d365fo.mobileops.data.remote.dto.TrackingDimensionGroupDto
import com.d365fo.mobileops.data.remote.dto.WarehouseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Reads against the D365FO `/data` OData endpoint.
 *
 * Every method takes an explicit `$select`; none of them offer a no-filter overload. Pulling
 * an unbounded entity set onto a handheld is the single most common way to make one of these
 * apps unusable, so the API surface does not make it expressible.
 */
interface D365ODataApi {

    // -- Purchase orders -----------------------------------------------------------------

    @GET("PurchaseOrderHeadersV2")
    suspend fun getPurchaseOrderHeaders(
        @Query("\$filter") filter: String,
        @Query("\$select") select: String = ODataSelect.PURCH_HEADER,
        @Query("\$orderby") orderBy: String = "PurchaseOrderNumber asc",
        @Query("\$top") top: Int,
        @Query("\$skip") skip: Int = 0,
        @Query("\$count") count: Boolean = false,
    ): Response<ODataCollection<PurchaseOrderHeaderDto>>

    @GET("PurchaseOrderLinesV2")
    suspend fun getPurchaseOrderLines(
        @Query("\$filter") filter: String,
        @Query("\$select") select: String? = ODataSelect.PURCH_LINE,
        @Query("\$orderby") orderBy: String = "PurchaseOrderNumber asc,LineNumber asc",
        @Query("\$top") top: Int,
        @Query("\$skip") skip: Int = 0,
    ): Response<ODataCollection<PurchaseOrderLineDto>>

    /**
     * ProductReceiptLinesV2 - the received quantity per purchase order line.
     *
     * A separate call because PurchaseOrderLinesV2 publishes only the ordered quantity on
     * this deployment. One row per posted receipt line, so the caller sums them per
     * (order, line) to get the received total.
     */
    @GET("ProductReceiptLinesV2")
    suspend fun getProductReceiptLines(
        @Query("\$filter") filter: String,
        @Query("\$select") select: String = ODataSelect.RECEIPT_LINE,
        @Query("\$orderby") orderBy: String = "PurchaseOrderNumber asc,PurchaseOrderLineNumber asc",
        @Query("\$top") top: Int,
        @Query("\$skip") skip: Int = 0,
    ): Response<ODataCollection<ProductReceiptLineDto>>

    // -- Master data ---------------------------------------------------------------------

    @GET("ReleasedProductsV2")
    suspend fun getReleasedProducts(
        @Query("\$filter") filter: String,
        @Query("\$select") select: String? = ODataSelect.RELEASED_PRODUCT,
        @Query("\$orderby") orderBy: String = "ItemNumber asc",
        @Query("\$top") top: Int,
        @Query("\$skip") skip: Int = 0,
    ): Response<ODataCollection<ReleasedProductDto>>

    /**
     * Tracking dimension groups — which items require a batch or serial number.
     *
     * No `$filter`: this entity rejects a `dataAreaId` clause with a 400 on this deployment,
     * so it is fetched whole and joined client-side. It is a handful of rows.
     *
     * There is deliberately no companion `StorageDimensionGroups` call. That entity does not
     * exist here — `locationMandatory` is derived from the item's storage dimension group
     * name instead, in MasterDataRepositoryImpl.
     */
    @GET("TrackingDimensionGroups")
    suspend fun getTrackingDimensionGroups(
        @Query("\$top") top: Int = 500,
    ): Response<ODataCollection<TrackingDimensionGroupDto>>

    @GET("ProductBarcodesV3")
    suspend fun getBarcodes(
        @Query("\$filter") filter: String,
        @Query("\$select") select: String = ODataSelect.BARCODE,
        @Query("\$orderby") orderBy: String = "ItemNumber asc",
        @Query("\$top") top: Int,
        @Query("\$skip") skip: Int = 0,
    ): Response<ODataCollection<ProductBarcodeDto>>

    @GET("WarehouseLocations")
    suspend fun getLocations(
        @Query("\$filter") filter: String,
        @Query("\$select") select: String = ODataSelect.LOCATION,
        @Query("\$orderby") orderBy: String = "WarehouseLocationId asc",
        @Query("\$top") top: Int,
        @Query("\$skip") skip: Int = 0,
    ): Response<ODataCollection<InventoryLocationDto>>

    /**
     * Warehouses for the current legal entity.
     *
     * Used by Settings to let an operator pick a real warehouse instead of typing one from
     * memory. Two entity names have already turned out not to exist on this instance, so
     * discovering the value beats guessing it - and a wrong warehouse produces an empty
     * worklist, which looks like a broken app rather than a typo.
     */
    @GET("Warehouses")
    suspend fun getWarehouses(
        @Query("\$filter") filter: String,
        // The field names were read off the wire first, exactly as the note here used to say
        // to do; all four are present on all 143 rows this tenant returns. Without the list
        // the response is 524 KB of row the app ignores, parsed on a handheld.
        @Query("\$select") select: String = ODataSelect.WAREHOUSE,
        @Query("\$top") top: Int = 200,
    ): Response<ODataCollection<WarehouseDto>>

    // -- On hand -------------------------------------------------------------------------

    @GET("WarehousesOnHand")
    suspend fun getOnHand(
        @Query("\$filter") filter: String,
        @Query("\$select") select: String = ODataSelect.ON_HAND,
        @Query("\$top") top: Int,
    ): Response<ODataCollection<OnHandDto>>

    // -- Validation probes ---------------------------------------------------------------

    /** Layer-4 serial uniqueness: a hit means the serial is already registered. */
    @GET("ItemSerialNumbers")
    suspend fun findSerialNumber(
        @Query("\$filter") filter: String,
        @Query("\$select") select: String = "ItemNumber,SerialNumber",
        @Query("\$top") top: Int = 1,
    ): Response<ODataCollection<SerialNumberDto>>

    /**
     * Connectivity probe: one row from a small, always-present entity.
     *
     * **Not the service document.** `/data/` enumerates every entity the deployment publishes -
     * over four thousand of them on a stock install - and it was measured at 11.9 seconds on a
     * warm sandbox. On a cold AOS it exceeded a sixty-second timeout, so a perfectly healthy
     * environment reported "request timed out" and looked unreachable.
     *
     * A `$top=1` read is the cheapest thing that still proves the whole chain: DNS, TLS, the
     * token, and that OData is actually serving. What comes back barely matters - see
     * SettingsViewModel, which treats *any* HTTP reply as a reachable server.
     */
    @GET("Companies")
    suspend fun getConnectivityProbe(
        @Query("\$top") top: Int = 1,
        @Query("\$select") select: String = "DataArea",
    ): Response<okhttp3.ResponseBody>

    // -- Server-driven paging ------------------------------------------------------------

    /**
     * Follows an `@odata.nextLink` verbatim — the link already carries every query option,
     * so nothing may be appended to it.
     *
     * Declared once per payload type rather than as a generic method: Retrofit resolves the
     * response type from the declared return type via reflection, and a method-level type
     * parameter erases to Object, which the converter cannot deserialise.
     */
    @GET
    suspend fun followProductsLink(
        @Url nextLink: String,
    ): Response<ODataCollection<ReleasedProductDto>>

    @GET
    suspend fun followBarcodesLink(
        @Url nextLink: String,
    ): Response<ODataCollection<ProductBarcodeDto>>

    @GET
    suspend fun followLocationsLink(
        @Url nextLink: String,
    ): Response<ODataCollection<InventoryLocationDto>>

    @GET
    suspend fun followPurchaseHeadersLink(
        @Url nextLink: String,
    ): Response<ODataCollection<PurchaseOrderHeaderDto>>

    @GET
    suspend fun followPurchaseLinesLink(
        @Url nextLink: String,
    ): Response<ODataCollection<PurchaseOrderLineDto>>

    // -- Data management package REST API -------------------------------------------------
    //
    // Unbound OData actions on DataManagementDefinitionGroups - Microsoft's own documented
    // trigger/poll/blob-URL surface for DMF exports and imports, called through this same
    // `/data/` root. Replaces the custom X++ trigger/poll pair the counting-journal export
    // used to guess at. See DmfDto.kt's own doc comment and aos/README.md.

    @POST("DataManagementDefinitionGroups/Microsoft.Dynamics.DataEntities.GetAzureWriteUrl")
    suspend fun getAzureWriteUrl(@Body body: GetAzureWriteUrlRequest): Response<ODataActionResult>

    @POST("DataManagementDefinitionGroups/Microsoft.Dynamics.DataEntities.ExportToPackage")
    suspend fun exportToPackage(@Body body: ExportToPackageRequest): Response<ODataActionResult>

    @POST("DataManagementDefinitionGroups/Microsoft.Dynamics.DataEntities.ImportFromPackage")
    suspend fun importFromPackage(@Body body: ImportFromPackageRequest): Response<ODataActionResult>

    @POST("DataManagementDefinitionGroups/Microsoft.Dynamics.DataEntities.GetExecutionSummaryStatus")
    suspend fun getExecutionSummaryStatus(@Body body: ExecutionIdRequest): Response<ODataActionResult>

    @POST("DataManagementDefinitionGroups/Microsoft.Dynamics.DataEntities.GetExportedPackageUrl")
    suspend fun getExportedPackageUrl(@Body body: ExecutionIdRequest): Response<ODataActionResult>

    @POST("DataManagementDefinitionGroups/Microsoft.Dynamics.DataEntities.GetExecutionErrors")
    suspend fun getExecutionErrors(@Body body: ExecutionIdRequest): Response<ODataActionResult>
}
