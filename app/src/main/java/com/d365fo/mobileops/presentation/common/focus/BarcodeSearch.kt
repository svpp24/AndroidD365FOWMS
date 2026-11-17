package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.d365fo.mobileops.data.local.dao.ItemDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** The search boxes read the device's barcode list (user, 17 Nov 2026: "Search item or barcode"). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BarcodeSearchEntryPoint {
    fun itemDao(): ItemDao
}

/** What the search box says on every item list. */
const val ITEM_OR_BARCODE = "Search item or barcode"

/**
 * The item a typed (or scanned) search text is a barcode of, from the barcodes on this device -
 * null while it is not one. A list's filter keeps its own item / name match and adds this item.
 */
@Composable
fun rememberBarcodeItemId(query: String): String? {
    val context = LocalContext.current.applicationContext
    val dao = remember(context) {
        runCatching { EntryPointAccessors.fromApplication(context, BarcodeSearchEntryPoint::class.java).itemDao() }.getOrNull()
    }
    var itemId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, dao) {
        val text = query.trim()
        itemId = if (dao == null || text.length < 3) null else runCatching { dao.resolveBarcode(text)?.itemId }.getOrNull()
    }
    return itemId
}
