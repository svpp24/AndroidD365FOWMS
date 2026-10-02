package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.security.ConnectionSettings
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds Retrofit clients from the *runtime* tenant configuration.
 *
 * The base URL used to be fixed at graph-construction time from BuildConfig, which meant
 * changing environment required editing local.properties and rebuilding. Now the URL is
 * typed on the device, so the client has to be rebuilt whenever it changes — and it has to
 * be built lazily, because at first launch there is no URL at all and Retrofit rejects a
 * blank base URL by throwing.
 *
 * Rebuild is keyed on the URL string rather than done eagerly on every call: constructing a
 * Retrofit instance per request would discard OkHttp's connection pool and add a TLS
 * handshake to every scan.
 */
@Singleton
class D365RetrofitFactory @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    fun create(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}

/** Caches one API instance per base URL, rebuilding only when the URL actually changes. */
private class RebuildingApi<T : Any>(
    private val factory: D365RetrofitFactory,
    private val service: Class<T>,
    private val baseUrl: () -> String,
) {
    private var builtFor: String? = null
    private var cached: T? = null

    @Synchronized
    fun get(): T {
        val url = baseUrl()
        require(url.startsWith("https://")) {
            "No D365FO environment is configured. Set it in Settings before going live."
        }
        val existing = cached
        if (existing != null && builtFor == url) return existing
        Timber.i("building %s client for %s", service.simpleName, url)
        return factory.create(url).create(service).also {
            cached = it
            builtFor = url
        }
    }
}

/**
 * The HSMobileOps AOS service, rooted at `/api/services/`.
 *
 * Shares the OkHttpClient with the OData path, so the two use one connection pool and one TLS
 * session to the same host — the first service call after an OData call pays no handshake.
 */
@Singleton
class LiveMobileOpsServiceApi @Inject constructor(
    factory: D365RetrofitFactory,
    settings: ConnectionSettings,
) : MobileOpsServiceApi {

    private val delegate = RebuildingApi(factory, MobileOpsServiceApi::class.java) {
        settings.tenant.value.servicesRoot
    }

    override suspend fun getPurchaseOrder(
        body: com.d365fo.mobileops.data.remote.dto.PurchOrderServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getPurchaseOrder(body, tag)

    override suspend fun getTransferOrder(
        body: com.d365fo.mobileops.data.remote.dto.TransferServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getTransferOrder(body, tag)

    override suspend fun getTransferLinesForItem(
        body: com.d365fo.mobileops.data.remote.dto.TransferLineServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getTransferLinesForItem(body, tag)

    override suspend fun signIn(
        body: com.d365fo.mobileops.data.remote.dto.SignInServiceRequest,
    ) = delegate.get().signIn(body)

    override suspend fun changePassword(
        body: com.d365fo.mobileops.data.remote.dto.ChangePasswordServiceRequest,
    ) = delegate.get().changePassword(body)

    override suspend fun setModulePermission(
        body: com.d365fo.mobileops.data.remote.dto.SetModulePermissionServiceRequest,
    ) = delegate.get().setModulePermission(body)

    override suspend fun getWorkerDirectory(
        body: com.d365fo.mobileops.data.remote.dto.WorkerDirectoryServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getWorkerDirectory(body, tag)

    override suspend fun getLinesForItem(
        body: com.d365fo.mobileops.data.remote.dto.LineServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getLinesForItem(body, tag)

    override suspend fun validateLocation(
        body: com.d365fo.mobileops.data.remote.dto.LocationServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().validateLocation(body, tag)

    override suspend fun validateSerial(
        body: com.d365fo.mobileops.data.remote.dto.SerialServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().validateSerial(body, tag)

    override suspend fun validateBatch(
        body: com.d365fo.mobileops.data.remote.dto.BatchServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().validateBatch(body, tag)

    override suspend fun getOnHand(
        body: com.d365fo.mobileops.data.remote.dto.OnHandServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getOnHand(body, tag)

    override suspend fun listOpenJournals(
        body: com.d365fo.mobileops.data.remote.dto.JournalListServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().listOpenJournals(body, tag)

    override suspend fun exportOpenJournals(
        body: com.d365fo.mobileops.data.remote.dto.ScopeExportServiceRequest,
    ) = delegate.get().exportOpenJournals(body)

    override suspend fun importCounts(
        body: com.d365fo.mobileops.data.remote.dto.ImportCountsServiceRequest,
    ) = delegate.get().importCounts(body)

    override suspend fun postProductReceipt(
        body: com.d365fo.mobileops.data.remote.dto.PostServiceRequest,
    ) = delegate.get().postProductReceipt(body)

    override suspend fun postTransferShipment(
        body: com.d365fo.mobileops.data.remote.dto.PostServiceRequest,
    ) = delegate.get().postTransferShipment(body)

    override suspend fun postTransferReceipt(
        body: com.d365fo.mobileops.data.remote.dto.PostServiceRequest,
    ) = delegate.get().postTransferReceipt(body)

    override suspend fun processPending(
        body: com.d365fo.mobileops.data.remote.dto.PostQueueServiceRequest,
    ) = delegate.get().processPending(body)

    override suspend fun getQueueStatus(
        body: com.d365fo.mobileops.data.remote.dto.PostQueueServiceRequest,
    ) = delegate.get().getQueueStatus(body)

    override suspend fun exportSkuMaster(
        body: com.d365fo.mobileops.data.remote.dto.SkuExportServiceRequest,
    ) = delegate.get().exportSkuMaster(body)

    override suspend fun getLicense(
        body: com.d365fo.mobileops.data.remote.dto.GetLicenseServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getLicense(body, tag)

    override suspend fun registerDevice(
        body: com.d365fo.mobileops.data.remote.dto.RegisterDeviceServiceRequest,
    ) = delegate.get().registerDevice(body)

    override suspend fun dmfExportToPackage(body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest) = delegate.get().dmfExportToPackage(body)

    override suspend fun dmfGetExecutionSummaryStatus(body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest, tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead) =
        delegate.get().dmfGetExecutionSummaryStatus(body, tag)

    override suspend fun dmfGetExportedPackageUrl(body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest, tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead) =
        delegate.get().dmfGetExportedPackageUrl(body, tag)

    override suspend fun dmfGetExecutionErrors(body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest, tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead) =
        delegate.get().dmfGetExecutionErrors(body, tag)

    override suspend fun dmfGetAzureWriteUrl(body: com.d365fo.mobileops.data.remote.dto.DmfServiceRequest) = delegate.get().dmfGetAzureWriteUrl(body)
}

@Singleton
class LiveD365ODataApi @Inject constructor(
    factory: D365RetrofitFactory,
    settings: ConnectionSettings,
) : D365ODataApi {

    private val delegate = RebuildingApi(factory, D365ODataApi::class.java) {
        settings.tenant.value.odataRoot
    }

    override suspend fun getPurchaseOrderHeaders(
        filter: String, select: String, orderBy: String, top: Int, skip: Int, count: Boolean,
    ) = delegate.get().getPurchaseOrderHeaders(filter, select, orderBy, top, skip, count)

    override suspend fun getPurchaseOrderLines(
        filter: String, select: String?, orderBy: String, top: Int, skip: Int,
    ) = delegate.get().getPurchaseOrderLines(filter, select, orderBy, top, skip)

    override suspend fun getProductReceiptLines(
        filter: String, select: String, orderBy: String, top: Int, skip: Int,
    ) = delegate.get().getProductReceiptLines(filter, select, orderBy, top, skip)

    override suspend fun getReleasedProducts(
        filter: String, select: String?, orderBy: String, top: Int, skip: Int,
    ) = delegate.get().getReleasedProducts(filter, select, orderBy, top, skip)

    override suspend fun getTrackingDimensionGroups(top: Int) =
        delegate.get().getTrackingDimensionGroups(top)

    override suspend fun getBarcodes(
        filter: String, select: String, orderBy: String, top: Int, skip: Int,
    ) = delegate.get().getBarcodes(filter, select, orderBy, top, skip)

    override suspend fun getLocations(
        filter: String, select: String, orderBy: String, top: Int, skip: Int,
    ) = delegate.get().getLocations(filter, select, orderBy, top, skip)

    override suspend fun getOnHand(filter: String, select: String, top: Int) =
        delegate.get().getOnHand(filter, select, top)

    override suspend fun findSerialNumber(filter: String, select: String, top: Int) =
        delegate.get().findSerialNumber(filter, select, top)

    override suspend fun getWarehouses(filter: String, select: String, top: Int) =
        delegate.get().getWarehouses(filter, select, top)

    override suspend fun getConnectivityProbe(top: Int, select: String) =
        delegate.get().getConnectivityProbe(top, select)

    override suspend fun followProductsLink(nextLink: String) =
        delegate.get().followProductsLink(nextLink)

    override suspend fun followBarcodesLink(nextLink: String) =
        delegate.get().followBarcodesLink(nextLink)

    override suspend fun followLocationsLink(nextLink: String) =
        delegate.get().followLocationsLink(nextLink)

    override suspend fun followPurchaseHeadersLink(nextLink: String) =
        delegate.get().followPurchaseHeadersLink(nextLink)

    override suspend fun followPurchaseLinesLink(nextLink: String) =
        delegate.get().followPurchaseLinesLink(nextLink)

    override suspend fun getAzureWriteUrl(
        body: com.d365fo.mobileops.data.remote.dto.GetAzureWriteUrlRequest,
    ) = delegate.get().getAzureWriteUrl(body)

    override suspend fun exportToPackage(
        body: com.d365fo.mobileops.data.remote.dto.ExportToPackageRequest,
    ) = delegate.get().exportToPackage(body)

    override suspend fun importFromPackage(
        body: com.d365fo.mobileops.data.remote.dto.ImportFromPackageRequest,
    ) = delegate.get().importFromPackage(body)

    override suspend fun getExecutionSummaryStatus(
        body: com.d365fo.mobileops.data.remote.dto.ExecutionIdRequest,
    ) = delegate.get().getExecutionSummaryStatus(body)

    override suspend fun getExportedPackageUrl(
        body: com.d365fo.mobileops.data.remote.dto.ExecutionIdRequest,
    ) = delegate.get().getExportedPackageUrl(body)

    override suspend fun getExecutionErrors(
        body: com.d365fo.mobileops.data.remote.dto.ExecutionIdRequest,
    ) = delegate.get().getExecutionErrors(body)
}


@Singleton
class LiveCountTaskServiceApi @Inject constructor(
    factory: D365RetrofitFactory,
    settings: ConnectionSettings,
) : CountTaskServiceApi {

    private val delegate = RebuildingApi(factory, CountTaskServiceApi::class.java) {
        settings.tenant.value.servicesRoot
    }

    override suspend fun claimJournal(body: com.d365fo.mobileops.data.remote.dto.CountClaimServiceRequest) =
        delegate.get().claimJournal(body)

    override suspend fun heartbeatClaim(body: com.d365fo.mobileops.data.remote.dto.CountClaimTokenServiceRequest) =
        delegate.get().heartbeatClaim(body)

    override suspend fun releaseJournal(body: com.d365fo.mobileops.data.remote.dto.CountClaimTokenServiceRequest) =
        delegate.get().releaseJournal(body)

    override suspend fun getJournalState(
        body: com.d365fo.mobileops.data.remote.dto.JournalStateServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getJournalState(body, tag)

    override suspend fun getJournalLines(
        body: com.d365fo.mobileops.data.remote.dto.DirectLinesServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getJournalLines(body, tag)

    override suspend fun submitCounts(body: com.d365fo.mobileops.data.remote.dto.DirectSubmitServiceRequest) =
        delegate.get().submitCounts(body)

    override suspend fun submitExceptions(body: com.d365fo.mobileops.data.remote.dto.CountExceptionsServiceRequest) =
        delegate.get().submitExceptions(body)

    override suspend fun createSpotCount(body: com.d365fo.mobileops.data.remote.dto.SpotCountServiceRequest) =
        delegate.get().createSpotCount(body)

    override suspend fun getOpportunityCount(
        body: com.d365fo.mobileops.data.remote.dto.OpportunityServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getOpportunityCount(body, tag)

    override suspend fun reportDiscrepancy(body: com.d365fo.mobileops.data.remote.dto.DiscrepancyServiceRequest) =
        delegate.get().reportDiscrepancy(body)

    override suspend fun getCounterProfile(
        body: com.d365fo.mobileops.data.remote.dto.CounterProfileServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getCounterProfile(body, tag)

    override suspend fun getLineAttributes(
        body: com.d365fo.mobileops.data.remote.dto.LineAttributesServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getLineAttributes(body, tag)
}

@Singleton
class LiveCountSupervisorServiceApi @Inject constructor(
    factory: D365RetrofitFactory,
    settings: ConnectionSettings,
) : CountSupervisorServiceApi {

    private val delegate = RebuildingApi(factory, CountSupervisorServiceApi::class.java) {
        settings.tenant.value.servicesRoot
    }

    override suspend fun getVariance(
        body: com.d365fo.mobileops.data.remote.dto.VarianceServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getVariance(body, tag)

    override suspend fun reviewLines(body: com.d365fo.mobileops.data.remote.dto.ReviewServiceRequest) =
        delegate.get().reviewLines(body)

    override suspend fun postJournal(body: com.d365fo.mobileops.data.remote.dto.PostJournalServiceRequest) =
        delegate.get().postJournal(body)

    override suspend fun getAccuracyReport(
        body: com.d365fo.mobileops.data.remote.dto.AccuracyServiceRequest,
        tag: com.d365fo.mobileops.data.remote.interceptor.IdempotentRead,
    ) = delegate.get().getAccuracyReport(body, tag)
}
