package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.LocationRequestBody
import com.d365fo.mobileops.data.remote.dto.LocationServiceRequest
import com.d365fo.mobileops.data.remote.dto.OrdersForDateRequestBody
import com.d365fo.mobileops.data.remote.dto.OrdersForDateServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.domain.model.OrderStatus
import com.d365fo.mobileops.domain.repository.PurchaseOrderSummary
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import com.d365fo.mobileops.util.plainError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One item on a location: what D365FO holds there, summed over batches, serials and variants. */
data class LocationStock(
    val itemId: String,
    val onHand: Double,
    val available: Double,
    val reserved: Double,
)

/** A price check's answer, in the store's currency. */
data class PriceQuote(
    val itemId: String,
    val itemName: String,
    val quantity: Double,
    val unitId: String,
    val unitPrice: Double,
    val grossAmount: Double,
    val discountAmount: Double,
    val discountPct: Double,
    val netAmount: Double,
    val offers: String,
    val currency: String,
    val priceIncludesTax: Boolean,
    val store: String,
)

/**
 * The Enquiry reads that are not a single-item lookup (user, 12 Nov): every open purchase order of
 * the store (HSMobileOpsPurchListSvc/getOpenOrders) and what is on one location
 * (HSMobileOpsInquirySvc/getLocationOnHand). Both are plain reads, asked fresh each time.
 */
@Singleton
class EnquiryRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private fun company(): String = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    suspend fun openOrders(warehouseId: String): NetworkResult<List<PurchaseOrderSummary>> = withContext(ioDispatcher) {
        val zone = java.time.ZoneId.systemDefault()
        when (val r = errorMapper.safeCall {
            service.getOpenOrders(OrdersForDateServiceRequest(OrdersForDateRequestBody(company(), warehouseId.trim(), "")))
        }) {
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (!r.data.success && r.data.errorCode != TRUNCATED) {
                NetworkResult.Error(AppFailure.BusinessRule(plainError(r.data.errorCode, "Couldn't list purchase orders"), r.data.errorCode))
            } else {
                NetworkResult.Success(
                    r.data.orders.filter { it.purchId.isNotBlank() }.map { row ->
                        PurchaseOrderSummary(
                            purchId = row.purchId,
                            vendorAccount = row.vendorAccount.orEmpty(),
                            status = statusOf(row.status),
                            hasOpenLines = row.hasOpenLines,
                            siteId = row.siteId?.takeIf { it.isNotBlank() },
                            warehouseId = row.warehouseId?.takeIf { it.isNotBlank() },
                            deliveryDate = row.deliveryDate
                                ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                                ?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
                        )
                    },
                )
            }
        }
    }

    suspend fun locationItems(warehouseId: String, locationId: String): NetworkResult<List<LocationStock>> = withContext(ioDispatcher) {
        when (val r = errorMapper.safeCall {
            service.getLocationOnHand(LocationServiceRequest(LocationRequestBody(company(), warehouseId.trim(), locationId.trim())))
        }) {
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (!r.data.success) {
                NetworkResult.Error(AppFailure.BusinessRule(plainError(r.data.errorCode, "Couldn't list the location's items"), r.data.errorCode))
            } else {
                NetworkResult.Success(
                    r.data.rows.filter { it.itemId.isNotBlank() }
                        .map { LocationStock(it.itemId, it.onHand, it.available, it.reserved) },
                )
            }
        }
    }

    /** Price check (user, 12 Nov): priced exactly as Commerce's price simulator prices it for the store. */
    suspend fun checkPrice(
        warehouseId: String,
        itemId: String,
        variant: com.d365fo.mobileops.domain.model.ProductVariant,
        quantity: Double,
        unitId: String,
    ): NetworkResult<PriceQuote> = withContext(ioDispatcher) {
        when (val r = errorMapper.safeCall {
            service.checkPrice(
                com.d365fo.mobileops.data.remote.dto.PriceCheckServiceRequest(
                    com.d365fo.mobileops.data.remote.dto.PriceCheckRequestBody(
                        dataAreaId = company(),
                        warehouseId = warehouseId.trim(),
                        itemId = itemId,
                        configId = variant.configId.orEmpty(),
                        sizeId = variant.sizeId.orEmpty(),
                        colorId = variant.colorId.orEmpty(),
                        styleId = variant.styleId.orEmpty(),
                        quantity = quantity,
                        unitId = unitId,
                    ),
                ),
            )
        }) {
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> with(r.data) {
                if (!success) {
                    NetworkResult.Error(
                        AppFailure.BusinessRule(message?.takeIf { it.isNotBlank() } ?: plainError(errorCode, "Couldn't get the price"), errorCode),
                    )
                } else {
                    NetworkResult.Success(
                        PriceQuote(
                            itemId, itemName, quantity, unitId, unitPrice, grossAmount, discountAmount, discountPct, netAmount,
                            offerName.orEmpty(), currency.orEmpty(), priceIncludesTax, storeName.orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    private fun statusOf(symbol: String?): OrderStatus = when (symbol?.lowercase()) {
        "backorder" -> OrderStatus.BACKORDER
        "received" -> OrderStatus.RECEIVED
        "invoiced" -> OrderStatus.INVOICED
        "canceled" -> OrderStatus.CANCELED
        else -> OrderStatus.NONE
    }

    private companion object {
        const val TRUNCATED = "TRUNCATED"
    }
}
