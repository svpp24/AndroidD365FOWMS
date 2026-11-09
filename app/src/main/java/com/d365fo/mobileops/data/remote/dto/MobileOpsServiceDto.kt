package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contracts for the HSMobileOps AOS service.
 *
 * A custom service takes its parameters wrapped in an object named after the X++ method parameter
 * — `_request` here. That wrapper is not decoration: post the contract unwrapped and the AOS
 * deserialises every field to its default and answers as though nothing was asked. If the service
 * is ever regenerated with a differently named parameter, this wrapper name changes with it.
 *
 * Booleans are real JSON booleans on this path, unlike the OData entities where they arrive as the
 * NoYes strings "Yes"/"No" — a custom service serialises the X++ `boolean` type directly.
 */
@Serializable
data class PurchOrderServiceRequest(
    @SerialName("_request") val request: PurchOrderRequestBody,
)

@Serializable
data class PurchOrderRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("PurchId") val purchId: String,
    @SerialName("WarehouseId") val warehouseId: String = "",
)

@Serializable
data class PurchOrderServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** Stable token — NOT_FOUND, NOT_APPROVED, NOTHING_OUTSTANDING, INVALID_REQUEST. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("PurchId") val purchId: String = "",
    @SerialName("VendorAccount") val vendorAccount: String = "",
    @SerialName("VendorName") val vendorName: String = "",
    @SerialName("PurchStatus") val purchStatus: String? = null,
    @SerialName("ApprovalStatus") val approvalStatus: String? = null,
    /**
     * Whether the AOS considers this order receivable. Decided on the server precisely because
     * the client got it wrong twice while inferring it from an approval-status string.
     */
    @SerialName("Receivable") val receivable: Boolean = false,
    @SerialName("SiteId") val siteId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String? = null,
    @SerialName("DeliveryDate") val deliveryDate: String? = null,
    @SerialName("CurrencyCode") val currencyCode: String? = null,
    /** Always the true total, whether or not [lines] was populated. */
    @SerialName("OutstandingLineCount") val outstandingLineCount: Int = 0,
    /**
     * False when the order was too large to send lines inline; ask per item instead.
     * Not an error - see LineServiceResponse.
     */
    @SerialName("LinesIncluded") val linesIncluded: Boolean = false,
    @SerialName("Lines") val lines: List<PurchLineServiceDto> = emptyList(),
    /**
     * Whether receiving still asks for a put-away location when a line has none of its own.
     * Backoffice-configurable - see HSMobileOpsProcessSetup.RequireLocationCapture. Defaults
     * true (keep asking) so an old server that has not deployed this field yet, deserializing
     * it as absent, changes nothing about existing behaviour.
     */
    @SerialName("RequireLocationCapture") val requireLocationCapture: Boolean = true,
)

@Serializable
data class PurchLineServiceDto(
    @SerialName("LineNumber") val lineNumber: Double = 0.0,
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    /** The item's warehouse mobile device description lines (WHSInventTable.RFDescription1/2). */
    @SerialName("MobileDescription1") val mobileDescription1: String = "",
    @SerialName("MobileDescription2") val mobileDescription2: String = "",
    @SerialName("PurchUnit") val purchUnit: String = "",
    @SerialName("InventUnit") val inventUnit: String = "",
    @SerialName("OrderedQty") val orderedQty: Double = 0.0,
    /** Server-derived from PurchQty − RemainPurchPhysical. No client-side receipt join. */
    @SerialName("ReceivedQty") val receivedQty: Double = 0.0,
    @SerialName("RemainingQty") val remainingQty: Double = 0.0,
    /** Staged on some device, not yet processed. Defaults to 0 so a server without this field
     *  deployed yet changes nothing about existing behaviour. */
    @SerialName("UncommittedQty") val uncommittedQty: Double = 0.0,
    @SerialName("SiteId") val siteId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String? = null,
    @SerialName("LocationId") val locationId: String? = null,
    @SerialName("ConfigId") val configId: String? = null,
    @SerialName("SizeId") val sizeId: String? = null,
    @SerialName("ColorId") val colorId: String? = null,
    @SerialName("StyleId") val styleId: String? = null,
    /** Straight from InventDimGroupSetup — the step sequence is computed from these three. */
    @SerialName("BatchRequired") val batchRequired: Boolean = false,
    @SerialName("SerialRequired") val serialRequired: Boolean = false,
    @SerialName("LocationRequired") val locationRequired: Boolean = false,
    @SerialName("BatchMandatory") val batchMandatory: Boolean = true,
    @SerialName("SerialMandatory") val serialMandatory: Boolean = true,
    @SerialName("LocationMandatory") val locationMandatory: Boolean = true,
    @SerialName("SerialPerUnit") val serialPerUnit: Boolean = true,
    @SerialName("OverDeliveryPct") val overDeliveryPct: Double = 0.0,
    @SerialName("UnderDeliveryPct") val underDeliveryPct: Double = 0.0,
    @SerialName("LineStatus") val lineStatus: String? = null,
    @SerialName("Stopped") val stopped: Boolean = false,
    /** Units the quantity can be entered in, to PurchUnit - empty from a server without UoM support. */
    @SerialName("Units") val units: List<ItemUnitDto> = emptyList(),
)

/** One HSMobileOpsItemUnitRow: 1 UnitId = Factor of the line's (or inventory) unit. */
@Serializable
data class ItemUnitDto(
    @SerialName("UnitId") val unitId: String = "",
    @SerialName("Factor") val factor: Double = 0.0,
    @SerialName("Decimals") val decimals: Int = 0,
)

internal fun List<ItemUnitDto>.toItemUnits(): List<com.d365fo.mobileops.domain.model.ItemUnit> =
    filter { it.unitId.isNotBlank() && it.factor > 0.0 }
        .map { com.d365fo.mobileops.domain.model.ItemUnit(it.unitId, java.math.BigDecimal.valueOf(it.factor), it.decimals) }

// -- Location -------------------------------------------------------------------------------

@Serializable
data class LocationServiceRequest(
    @SerialName("_request") val request: LocationRequestBody,
)

@Serializable
data class LocationRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("LocationId") val locationId: String,
)

@Serializable
data class LocationServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    /** Distinct from [canReceiveInto]: "no such bin" and "blocked" are different problems. */
    @SerialName("Exists") val exists: Boolean = false,
    @SerialName("CanReceiveInto") val canReceiveInto: Boolean = false,
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("LocationId") val locationId: String = "",
    @SerialName("BlockingCause") val blockingCause: String? = null,
)

// -- Serial ---------------------------------------------------------------------------------

@Serializable
data class SerialServiceRequest(
    @SerialName("_request") val request: SerialRequestBody,
)

@Serializable
data class SerialRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("ItemId") val itemId: String,
    @SerialName("SerialId") val serialId: String,
)

@Serializable
data class SerialServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Registered") val registered: Boolean = false,
)

// -- Batch ------------------------------------------------------------------------------------

@Serializable
data class BatchServiceRequest(
    @SerialName("_request") val request: BatchRequestBody,
)

@Serializable
data class BatchRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("ItemId") val itemId: String,
    @SerialName("BatchId") val batchId: String,
)

@Serializable
data class BatchServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Registered") val registered: Boolean = false,
)

// -- On hand --------------------------------------------------------------------------------

@Serializable
data class OnHandServiceRequest(
    @SerialName("_request") val request: OnHandRequestBody,
)

@Serializable
data class OnHandRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("ItemId") val itemId: String,
    /** Blank means every warehouse. */
    @SerialName("WarehouseId") val warehouseId: String = "",
)

@Serializable
data class OnHandServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Rows") val rows: List<OnHandServiceRow> = emptyList(),
)

@Serializable
data class OnHandServiceRow(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("SiteId") val siteId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String? = null,
    @SerialName("OnHand") val onHand: Double = 0.0,
    @SerialName("Available") val available: Double = 0.0,
    @SerialName("Reserved") val reserved: Double = 0.0,
    @SerialName("Ordered") val ordered: Double = 0.0,
    @SerialName("TotalAvailable") val totalAvailable: Double = 0.0,
)

// -- Lines for one item ---------------------------------------------------------------------

@Serializable
data class LineServiceRequest(
    @SerialName("_request") val request: LineRequestBody,
)

@Serializable
data class LineRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("PurchId") val purchId: String,
    @SerialName("ItemId") val itemId: String,
)

/**
 * The outstanding lines for one item on one order.
 *
 * The large-order path. `getPurchaseOrder` omits lines past a threshold and reports the count
 * instead, so the item step asks this — one keyed query returning the handful of lines that
 * actually carry the scanned item, rather than thousands the device would filter and discard.
 */
@Serializable
data class LineServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** NOT_ON_ORDER when the item is not outstanding here — a real answer, not a failure. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Lines") val lines: List<PurchLineServiceDto> = emptyList(),
)

// -- Counting journals -----------------------------------------------------------------------

@Serializable
data class JournalListServiceRequest(
    @SerialName("_request") val request: JournalListRequestBody,
)

@Serializable
data class JournalListRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    /** Who is asking - an external counter only sees their team's journals (server-side). */
    @SerialName("UserId") val userId: String? = null,
    /** Also list journals finished for this user - the list's Done tab. Each comes with a DoneNote. */
    @SerialName("IncludeDone") val includeDone: Boolean = false,
)

@Serializable
data class JournalListServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** TRUNCATED when the warehouse has more open journals than HSMobileOpsCountingService will
     *  return in one call — a real, actionable answer (close some journals), not a failure. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Rows") val rows: List<JournalSummaryServiceRow> = emptyList(),
)

@Serializable
data class JournalSummaryServiceRow(
    @SerialName("JournalId") val journalId: String = "",
    @SerialName("JournalNameId") val journalNameId: String = "",
    @SerialName("Description") val description: String? = null,
    /** e.g. "2026-09-11T12:00:00" — no zone suffix, unlike OData's own datetime format.
     *  Confirmed live 2026-09-23. Parsed with Formatters.serviceDateTimeToEpochMillis, not
     *  odataToEpochMillis (which requires the Z suffix and would silently drop this to null). */
    @SerialName("JournalDate") val journalDate: String = "",
    @SerialName("LineCount") val lineCount: Int = 0,
    /** DMF or DIRECT - decided by the server per journal. Absent on an older AOS build: DMF. */
    @SerialName("TransferMode") val transferMode: String? = null,
    @SerialName("Claims") val claims: List<CountClaimRowDto> = emptyList(),
    @SerialName("Ranges") val ranges: List<CountRangeRowDto> = emptyList(),
    @SerialName("LocationFirst") val locationFirst: Boolean = false,
    @SerialName("ZeroStockCheck") val zeroStockCheck: Boolean = false,
    /** SINGLE or DOUBLE_BLIND. */
    @SerialName("CountMode") val countMode: String? = null,
    @SerialName("PassNumber") val passNumber: Int = 1,
    /** Dual count: how many of this person's lines are recounts. */
    @SerialName("RecountLines") val recountLines: Int = 0,
    @SerialName("RecountOf") val recountOf: String? = null,
    @SerialName("OriginType") val originType: String? = null,
    @SerialName("Priority") val priority: Int = 0,
    @SerialName("ToleranceInfo") val toleranceInfo: String? = null,
    @SerialName("AbcClass") val abcClass: String? = null,
    /** NONE, LOCATION_FROZEN or SNAPSHOT_ONLY. */
    @SerialName("FreezeState") val freezeState: String? = null,
    /** No zone suffix, UTC - see Formatters.serviceDateTimeToEpochMillis. */
    @SerialName("SnapshotAtUtc") val snapshotAtUtc: String? = null,
    @SerialName("ControlGroupId") val controlGroupId: String? = null,
    @SerialName("RepeatInfo") val repeatInfo: String? = null,
    /** Set only on a finished journal (IncludeDone): "Posted 5 Nov 2026", or why it is waiting. */
    @SerialName("DoneNote") val doneNote: String? = null,
)

// -- Counting journal export -- one AOS call creates a request-private DMF project scoped to one
// journal and returns its name; the app then triggers the run itself via the public
// ExportToPackage OData action (see DmfDto.kt). See HSMobileOpsCountingService::
// exportOpenJournals' own doc comment and aos/README.md.

@Serializable
data class ScopeExportServiceRequest(
    @SerialName("_request") val request: ScopeExportRequestBody,
)

@Serializable
data class ScopeExportRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    /** A claimed location range of a wall-to-wall journal; both null exports the whole journal. */
    @SerialName("LocationFrom") val locationFrom: String? = null,
    @SerialName("LocationTo") val locationTo: String? = null,
    @SerialName("ClaimToken") val claimToken: String? = null,
)

@Serializable
data class ScopeExportServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    /** Set only when [success] - pass as `definitionGroupId` to ExportToPackage. */
    @SerialName("DefinitionGroupName") val definitionGroupName: String? = null,
)
// -- Counting journal import -- one shared DMF project, triggered as an enqueued batch job
// (DMFPackageImporter, not the public ImportFromPackage OData action) so concurrency safety
// comes from D365's own batch framework rather than project isolation. See
// HSMobileOpsCountingService::importCounts' own doc comment and aos/README.md. The app still
// polls the enqueued execution via the public GetExecutionSummaryStatus/GetExecutionErrors
// OData actions — see DmfDto.kt — only the trigger moved off the public REST action.

@Serializable
data class ImportCountsServiceRequest(
    @SerialName("_request") val request: ImportCountsRequestBody,
)

@Serializable
data class ImportCountsRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("PackageUrl") val packageUrl: String,
    @SerialName("ClaimToken") val claimToken: String? = null,
    /** Client-generated UUID, the server's idempotency key for this submission. */
    @SerialName("SubmissionId") val submissionId: String? = null,
    @SerialName("UserId") val userId: String? = null,
    @SerialName("DeviceId") val deviceId: String? = null,
    /** ISO 8601 UTC. */
    @SerialName("StartedAtUtc") val startedAtUtc: String? = null,
    @SerialName("SubmittedAtUtc") val submittedAtUtc: String? = null,
    @SerialName("PassNumber") val passNumber: Int? = null,
    @SerialName("OriginType") val originType: String? = null,
)

@Serializable
data class ImportCountsServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    /** Set only when [success] - poll via the public GetExecutionSummaryStatus/GetExecutionErrors
     *  OData actions, same as any other DMF execution id. */
    @SerialName("ExecutionId") val executionId: String? = null,
)
