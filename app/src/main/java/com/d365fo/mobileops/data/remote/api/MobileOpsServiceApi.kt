package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.BatchServiceRequest
import com.d365fo.mobileops.data.remote.dto.BatchServiceResponse
import com.d365fo.mobileops.data.remote.dto.GetLicenseServiceRequest
import com.d365fo.mobileops.data.remote.dto.GetLicenseServiceResponse
import com.d365fo.mobileops.data.remote.dto.RegisterDeviceServiceRequest
import com.d365fo.mobileops.data.remote.dto.RegisterDeviceServiceResponse
import com.d365fo.mobileops.data.remote.dto.SetModulePermissionServiceRequest
import com.d365fo.mobileops.data.remote.dto.SetModulePermissionServiceResponse
import com.d365fo.mobileops.data.remote.dto.WorkerDirectoryServiceRequest
import com.d365fo.mobileops.data.remote.dto.WorkerDirectoryServiceResponse
import com.d365fo.mobileops.data.remote.dto.ChangePasswordServiceResponse
import com.d365fo.mobileops.data.remote.dto.ChangePasswordServiceRequest
import com.d365fo.mobileops.data.remote.dto.ImportCountsServiceRequest
import com.d365fo.mobileops.data.remote.dto.ImportCountsServiceResponse
import com.d365fo.mobileops.data.remote.dto.JournalListServiceRequest
import com.d365fo.mobileops.data.remote.dto.JournalListServiceResponse
import com.d365fo.mobileops.data.remote.dto.LineServiceRequest
import com.d365fo.mobileops.data.remote.dto.LineServiceResponse
import com.d365fo.mobileops.data.remote.dto.LocationServiceRequest
import com.d365fo.mobileops.data.remote.dto.LocationServiceResponse
import com.d365fo.mobileops.data.remote.dto.OnHandServiceRequest
import com.d365fo.mobileops.data.remote.dto.OnHandServiceResponse
import com.d365fo.mobileops.data.remote.dto.PostQueueServiceRequest
import com.d365fo.mobileops.data.remote.dto.PostQueueServiceResponse
import com.d365fo.mobileops.data.remote.dto.PostServiceRequest
import com.d365fo.mobileops.data.remote.dto.PostServiceResponse
import com.d365fo.mobileops.data.remote.dto.PurchOrderServiceRequest
import com.d365fo.mobileops.data.remote.dto.PurchOrderServiceResponse
import com.d365fo.mobileops.data.remote.dto.ScopeExportServiceRequest
import com.d365fo.mobileops.data.remote.dto.ScopeExportServiceResponse
import com.d365fo.mobileops.data.remote.dto.SerialServiceRequest
import com.d365fo.mobileops.data.remote.dto.SignInServiceRequest
import com.d365fo.mobileops.data.remote.dto.SignInServiceResponse
import com.d365fo.mobileops.data.remote.dto.TransferLineServiceRequest
import com.d365fo.mobileops.data.remote.dto.TransferServiceRequest
import com.d365fo.mobileops.data.remote.dto.TransferServiceResponse
import com.d365fo.mobileops.data.remote.dto.SerialServiceResponse
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/**
 * The HSMobileOps AOS service — the read path that replaces OData for scan-driven work.
 *
 * Every method is a POST because that is how a D365FO custom service takes parameters, and every
 * one of them is nonetheless a **read**. That distinction is carried by the [IdempotentRead] tag:
 * without it `RetryAfterInterceptor` would treat these as unsafe to repeat and the step flow would
 * have no recovery from a `429` at all — which is exactly when recovery matters, because a 429
 * only happens when the environment is already under load.
 *
 * See `aos/README.md` for the service, its load characteristics, and the throttling priority that
 * has to be configured before go-live.
 */
interface MobileOpsServiceApi {

    /**
     * One purchase order with its receivable lines, their remaining quantities, and each item's
     * dimension requirements.
     *
     * Answers the PO step and the item step together. Over OData this was four queries.
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsReceivingSvc/getPurchaseOrder")
    suspend fun getPurchaseOrder(
        @Body body: PurchOrderServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<PurchOrderServiceResponse>

    /**
     * The outstanding lines for one item on one order - the large-order path.
     *
     * Called only when getPurchaseOrder reported LinesIncluded = false. Cost tracks the number of
     * lines carrying this item, not the size of the order.
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsReceivingSvc/getLinesForItem")
    suspend fun getLinesForItem(
        @Body body: LineServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<LineServiceResponse>

    // -- Transfer orders ---------------------------------------------------------------------
    //
    // Both take a Mode of "Ship" or "Receive". One order is worked from two ends and the answers
    // differ in every way that matters, so the direction travels with the request rather than
    // being inferred from which endpoint was called.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsTransferSvc/getTransferOrder")
    suspend fun getTransferOrder(
        @Body body: TransferServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<TransferServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsTransferSvc/getLinesForItem")
    suspend fun getTransferLinesForItem(
        @Body body: TransferLineServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<TransferServiceResponse>

    // -- Worker identity ---------------------------------------------------------------------
    //
    // These are the one group that is NOT tagged IdempotentRead. A sign-in counts a failed
    // attempt and can lock an account, and an administrative call changes a password - replaying
    // either on a 429 would lock a worker out of their own shift for a network hiccup.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsUserSvc/signIn")
    suspend fun signIn(@Body body: SignInServiceRequest): Response<SignInServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsUserSvc/changePassword")
    suspend fun changePassword(
        @Body body: ChangePasswordServiceRequest,
    ): Response<ChangePasswordServiceResponse>

    /**
     * An admin toggling one worker's access to one of the four handheld modules. NOT tagged
     * [IdempotentRead] - it is a write, and the sync queue (not `RetryAfterInterceptor`) owns
     * retrying it, same as every posting call. See `PostingDispatcher` and
     * `HSMobileOpsUserService::setModulePermission` for the server-side re-validation this
     * relies on rather than trusting the device's own cached role.
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsUserSvc/setModulePermission")
    suspend fun setModulePermission(
        @Body body: SetModulePermissionServiceRequest,
    ): Response<SetModulePermissionServiceResponse>

    /** The Admin Center's worker list - a plain read, tagged [IdempotentRead] like the others. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsUserSvc/getWorkerDirectory")
    suspend fun getWorkerDirectory(
        @Body body: WorkerDirectoryServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<WorkerDirectoryServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsInquirySvc/validateLocation")
    suspend fun validateLocation(
        @Body body: LocationServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<LocationServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsInquirySvc/validateSerial")
    suspend fun validateSerial(
        @Body body: SerialServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<SerialServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsInquirySvc/validateBatch")
    suspend fun validateBatch(
        @Body body: BatchServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<BatchServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsInquirySvc/getOnHand")
    suspend fun getOnHand(
        @Body body: OnHandServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<OnHandServiceResponse>

    // -- Counting journals ----------------------------------------------------------------------
    //
    // Headers only — cheap, same load shape as the inquiry reads above. A journal's lines are a
    // separate, bulk, DMF-backed fetch once the worker actually opens one; see
    // HSMobileOpsCountingService's own class doc and CountingJournalRepository.

    /**
     * Open counting journals with at least one line in [JournalListRequestBody.warehouseId].
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountingSvc/listOpenJournals")
    suspend fun listOpenJournals(
        @Body body: JournalListServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<JournalListServiceResponse>

    /**
     * Creates/reuses [ScopeExportRequestBody.journalId]'s own DMF export definition group,
     * scopes it to just that journal, then runs the export synchronously and hands back a
     * download URL directly, via a confirmed internal DMF API (see
     * HSMobileOpsCountingService::exportOpenJournals' own doc comment). One project per journal
     * — not shared — so two workers exporting different journals never race to overwrite each
     * other's filter; see HSMobileOpsCountingExportSetup::ensureExportForJournal. No polling:
     * this one call is the whole export. NOT tagged [IdempotentRead] — it rewrites server-side
     * state (the export project's packed query) and kicks off a real export each time, so a 429
     * must not be silently retried the way a read would be.
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountingSvc/exportOpenJournals")
    suspend fun exportOpenJournals(
        @Body body: ScopeExportServiceRequest,
    ): Response<ScopeExportServiceResponse>

    /**
     * Enqueues an import of [ImportCountsRequestBody.packageUrl] (already uploaded via the
     * public `GetAzureWriteUrl` OData action) as a D365 batch job, against the shared
     * `HSMobileOpsCountingImport` DMF project — `DMFPackageImporter`, not the public
     * `ImportFromPackage` OData action, so concurrency safety comes from D365's own batch
     * framework. Returns immediately with an execution id; poll it the same way a plain
     * `ImportFromPackage` execution id would be polled, via the public
     * `GetExecutionSummaryStatus`/`GetExecutionErrors` OData actions. See
     * HSMobileOpsCountingService::importCounts' own doc comment. NOT tagged [IdempotentRead] —
     * it enqueues a real import each time.
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountingSvc/importCounts")
    suspend fun importCounts(
        @Body body: ImportCountsServiceRequest,
    ): Response<ImportCountsServiceResponse>

    // -- Posting ------------------------------------------------------------------------------
    //
    // Deliberately NOT tagged IdempotentRead. These are writes, and RetryAfterInterceptor must
    // never retry one on its own: the sync queue is what owns the idempotency key and the retry
    // schedule for a posting, in PostingDispatcher. A 429 here is handled by the queue leaving
    // the entry for the next worker run, not by replaying the HTTP call in place.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/postProductReceipt")
    suspend fun postProductReceipt(@Body body: PostServiceRequest): Response<PostServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/postTransferShipment")
    suspend fun postTransferShipment(@Body body: PostServiceRequest): Response<PostServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/postTransferReceipt")
    suspend fun postTransferReceipt(@Body body: PostServiceRequest): Response<PostServiceResponse>

    /** Picks (positive quantity) or unpicks a scanned shipment line now - see TransferPicker. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/pickTransferLine")
    suspend fun pickTransferLine(@Body body: PostServiceRequest): Response<PostServiceResponse>

    /**
     * Works every Pending row in HSMobileOpsPostQueue for this company and reports what each
     * became. Operator-triggered from the Activity screen - see the class doc on
     * HSMobileOpsPostQueue for why there is no automatic timer calling this instead.
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/processPending")
    suspend fun processPending(
        @Body body: PostQueueServiceRequest,
    ): Response<PostQueueServiceResponse>

    /** The most recent 50 HSMobileOpsPostQueue rows for this company, whatever their status. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/getQueueStatus")
    suspend fun getQueueStatus(
        @Body body: PostQueueServiceRequest,
    ): Response<PostQueueServiceResponse>

    // -- Bulk SKU sync: see getSkuPage and SkuSyncRepository (paged, delta) ----------------

    // -- Offline licensing (certificate-chain design) --------------------------------------
    //
    // Called before any worker has signed in - see LicenseRepository. getLicense is tagged
    // IdempotentRead (a pure read, safe to retry on a 429); registerDevice is not, since it
    // writes HSMobileOpsDevice's own fleet row and must not be silently replayed.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsLicenseSvc/getLicense")
    suspend fun getLicense(
        @Body body: GetLicenseServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<GetLicenseServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsLicenseSvc/registerDevice")
    suspend fun registerDevice(@Body body: RegisterDeviceServiceRequest): Response<RegisterDeviceServiceResponse>

    // -- DMF (HSMobileOpsDmfSvc) ---------------------------------------------------------------
    //
    // X++ pass-throughs over DataManagementDefinitionGroupEntity's static actions, used instead
    // of the /data/ OData actions. See DmfGateway. Status/URL/error reads are IdempotentRead;
    // exportToPackage and getAzureWriteUrl start work or mint a blob, so they are not.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsDmfSvc/exportToPackage")
    suspend fun dmfExportToPackage(@Body body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest): Response<com.d365fo.mobileops.data.remote.dto.DmfServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsDmfSvc/getExecutionSummaryStatus")
    suspend fun dmfGetExecutionSummaryStatus(
        @Body body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.DmfServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsDmfSvc/getExportedPackageUrl")
    suspend fun dmfGetExportedPackageUrl(
        @Body body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.DmfServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsDmfSvc/getExecutionErrors")
    suspend fun dmfGetExecutionErrors(
        @Body body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.DmfServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsDmfSvc/getAzureWriteUrl")
    suspend fun dmfGetAzureWriteUrl(@Body body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest): Response<com.d365fo.mobileops.data.remote.dto.DmfServiceResponse>

    // -- Purchase order lists (HSMobileOpsPurchListSvc) --------------------------------------
    //
    // PO of today, read in X++ instead of PurchaseOrderHeadersV2. A pure read.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsPurchListSvc/getOrdersForDate")
    suspend fun getOrdersForDate(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrdersForDateServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrdersForDateServiceResponse>

    // -- Transfer order lists and serial history (HSMobileOpsTransferListSvc) - pure reads ------

    @POST("HSMobileOpsServiceGroup/HSMobileOpsTransferListSvc/listTransferOrders")
    suspend fun listTransferOrders(
        @Body body: com.d365fo.mobileops.data.remote.dto.TransferListServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.TransferListServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsTransferListSvc/listPickedSerials")
    suspend fun listPickedSerials(
        @Body body: com.d365fo.mobileops.data.remote.dto.TransferListServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.TransferListServiceResponse>

    // -- Items, barcodes, warehouses (HSMobileOpsItemSvc) --------------------------------------
    //
    // Replace the ReleasedProductsV2 / item barcode / Warehouses OData reads. All pure reads.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsItemSvc/getItem")
    suspend fun getItem(
        @Body body: com.d365fo.mobileops.data.remote.dto.ItemServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.ItemServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsItemSvc/getBarcode")
    suspend fun getBarcode(
        @Body body: com.d365fo.mobileops.data.remote.dto.BarcodeServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.BarcodeServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsItemSvc/getWarehouses")
    suspend fun getWarehousesService(
        @Body body: com.d365fo.mobileops.data.remote.dto.WarehousesServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.WarehousesServiceResponse>

    // -- Item units (HSMobileOpsUnitSvc) ------------------------------------------------------
    //
    // Units to the inventory unit for many item variants in one call - counting only; receiving
    // and transfer lines carry their units already. A pure read.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsUnitSvc/getItemUnits")
    suspend fun getItemUnits(
        @Body body: com.d365fo.mobileops.data.remote.dto.ItemUnitsServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.ItemUnitsServiceResponse>

    // -- Assigned warehouses (HSMobileOpsUserWarehouseSvc) -----------------------------------
    //
    // The worker's warehouses with names, for the warehouse switcher. A pure read.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsUserWarehouseSvc/getUserWarehouses")
    suspend fun getUserWarehouses(
        @Body body: com.d365fo.mobileops.data.remote.dto.UserWarehousesServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.UserWarehousesServiceResponse>

    // -- PO and TO creation (HSMobileOpsOrderCreateSvc) -------------------------------------
    //
    // Reads are tagged so the offline cache can replay them; pricing is deliberately not - a
    // price from yesterday's cache is worse than "priced when sent". The two creates are
    // posted from the sync queue with an idempotency key, never retried on their own.

    /** The worker's Menu access rows - refreshed without a new sign-in. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/getModulePermissions")
    suspend fun getModulePermissions(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** Vendors not on hold, a page at a time, for the offline vendor list. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/listVendors")
    suspend fun listVendors(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** Units, active dimensions, valid variants and purchase order settings of an item. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/getOrderItem")
    suspend fun getOrderItem(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** D365FO's price for each line, with its source - online only. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/priceLines")
    suspend fun priceLines(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** Creates the purchase order (atomic, idempotent on the key). */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/createPurchaseOrder")
    suspend fun createPurchaseOrder(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** Available stock of a variant at a warehouse, per batch and serial. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/getStock")
    suspend fun getStock(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** Creates the transfer order (atomic, idempotent on the key). */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/createTransferOrder")
    suspend fun createTransferOrder(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** Approved, unconfirmed purchase orders of a warehouse - PO confirmation's list. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/listPurchaseOrdersToConfirm")
    suspend fun listPurchaseOrdersToConfirm(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** One purchase order with its priced lines, for confirming it. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/getPurchaseOrder")
    suspend fun getPurchaseOrderForConfirm(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** One page of the offline item / barcode / variant cache - see SkuSyncRepository. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsSkuPageSvc/getSkuPage")
    suspend fun getSkuPage(
        @Body body: com.d365fo.mobileops.data.remote.dto.SkuPageServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.SkuPageServiceResponse>

    /** Worker sign-in history: one session, when it starts and when it ends - see SignInLogRecorder. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsSignInLogSvc/logSession")
    suspend fun logSignInSession(
        @Body body: com.d365fo.mobileops.data.remote.dto.SignInLogServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.SignInLogServiceResponse>

    /** Records feedback from the app (idempotent on FeedbackId). */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsFeedbackSvc/submitFeedback")
    suspend fun submitFeedback(
        @Body body: com.d365fo.mobileops.data.remote.dto.FeedbackServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.FeedbackServiceResponse>

    /** Changes feedback the worker sent, while the team has not picked it up yet (status New). */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsFeedbackSvc/updateFeedback")
    suspend fun updateFeedback(
        @Body body: com.d365fo.mobileops.data.remote.dto.FeedbackServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.FeedbackServiceResponse>

    /** The worker's own feedback with the team's status and reply. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsFeedbackSvc/listMyFeedback")
    suspend fun listMyFeedback(
        @Body body: com.d365fo.mobileops.data.remote.dto.FeedbackServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.FeedbackServiceResponse>

    /** The company's return reason codes (cached for offline replay). */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/listReturnReasons")
    suspend fun listReturnReasons(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** Creates a return order, confirms it and posts its packing slip (idempotent on the key). */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/createPurchaseReturn")
    suspend fun createPurchaseReturn(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>

    /** Confirms the purchase order (PurchFormLetter; idempotent on the key). */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsOrderCreateSvc/confirmPurchaseOrder")
    suspend fun confirmPurchaseOrder(
        @Body body: com.d365fo.mobileops.data.remote.dto.OrderServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.OrderServiceResponse>
}
