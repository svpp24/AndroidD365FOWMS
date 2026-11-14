package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.local.entity.BarcodeEntity
import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.ItemVariantEntity
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The rows of HSMobileOpsSkuPageService::getSkuPage, positional arrays per feed, as cache
 * entities - see SkuSyncRepository. The column order is the contract with D365FO.
 */
internal object SkuRows {

    private fun JsonArray.text(i: Int): String = getOrNull(i)?.jsonPrimitive?.content.orEmpty()
    private fun JsonArray.flag(i: Int): Boolean = text(i) == "1"

    /** [itemId, searchName, inventUnit, purchUnit, salesUnit, batch, serial, location, active, category] */
    fun item(row: JsonArray, now: Long): ItemEntity? = with(row) {
        val itemId = text(0).trim().takeIf { it.isNotEmpty() } ?: return@with null
        val batch = flag(5)
        val serial = flag(6)
        ItemEntity(
            itemId = itemId,
            itemName = text(1),
            searchName = text(1).ifBlank { null },
            inventoryUnit = text(2),
            purchaseUnit = text(3).ifBlank { null },
            salesUnit = text(4).ifBlank { null },
            trackingRequirement = when {
                batch && serial -> TrackingRequirement.BATCH_AND_SERIAL
                batch -> TrackingRequirement.BATCH
                serial -> TrackingRequirement.SERIAL
                else -> TrackingRequirement.NONE
            },
            locationMandatory = flag(7),
            active = flag(8),
            modifiedAt = now,
            // Column 9 from a server with the 12 Nov item feed; absent before.
            category = if (size > 9) text(9).trim().ifBlank { null } else null,
        )
    }

    /** [itemId, inventUnit, purchUnit, salesUnit, [[unit, factor, decimals], ...]] */
    fun units(row: JsonArray, now: Long): com.d365fo.mobileops.data.local.dao.SkuUnitRow? = with(row) {
        val itemId = text(0).trim().takeIf { it.isNotEmpty() } ?: return@with null
        val list = (getOrNull(4) as? JsonArray).orEmpty().mapNotNull { u ->
            val unit = (u as? JsonArray) ?: return@mapNotNull null
            val unitId = unit.text(0).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val factor = unit.text(1).trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            com.d365fo.mobileops.data.local.entity.ItemUnitEntity(itemId, unitId, factor.toPlainString(), unit.text(2).toIntOrNull() ?: 0, now)
        }
        com.d365fo.mobileops.data.local.dao.SkuUnitRow(itemId, text(1).trim(), text(2).trim().ifBlank { null }, text(3).trim().ifBlank { null }, list)
    }

    /** [barcode, itemId, qty, unit, configId, sizeId, colorId, styleId, blocked] */
    fun barcode(row: JsonArray, now: Long): BarcodeEntity? = with(row) {
        val barcode = text(0).trim().takeIf { it.isNotEmpty() } ?: return@with null
        val quantity = text(2).toBigDecimalOrNull()
        BarcodeEntity(
            // Scanner output is normalised to upper case, so the stored key must match.
            barcode = barcode.uppercase(),
            itemId = text(1).trim(),
            quantityPerScan = (quantity?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE).toPlainString(),
            unitId = text(3).ifBlank { null },
            active = !flag(8),
            modifiedAt = now,
            configId = text(4).ifBlank { null },
            sizeId = text(5).ifBlank { null },
            colorId = text(6).ifBlank { null },
            styleId = text(7).ifBlank { null },
        )
    }

    /** CATEGORIES feed: [itemId, category path]; a blank path clears the item's category. */
    fun category(row: JsonArray): Pair<String, String?>? = with(row) {
        val itemId = text(0).trim().takeIf { it.isNotEmpty() } ?: return@with null
        itemId to text(1).trim().ifBlank { null }
    }

    /** [itemId, configId, sizeId, colorId, styleId] */
    fun variant(row: JsonArray, now: Long): ItemVariantEntity? = with(row) {
        val itemId = text(0).trim().takeIf { it.isNotEmpty() } ?: return@with null
        // Column 5, the variant's name, from a server with ps_bak102 or later; absent before.
        ItemVariantEntity(itemId, text(1), text(2), text(3), text(4), syncedAt = now, name = if (size > 5) text(5).trim() else "")
    }

    /** D365FO's clock (ServerUtc), [minutes] back - a change committed while the last sync ran is not missed. */
    fun withOverlap(serverUtc: String, minutes: Long): String = runCatching {
        LocalDateTime.parse(serverUtc.take(19)).minusMinutes(minutes).format(SERVER_FORMAT)
    }.getOrDefault("")

    private val SERVER_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
}
