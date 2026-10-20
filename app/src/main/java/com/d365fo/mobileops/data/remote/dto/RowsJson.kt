package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val rowsFormat = Json { encodeDefaults = true }

/**
 * [rows] as a JSON array string, for a request's `*Json` member. AOS's JSON binding can't read a
 * list of a custom class on the way in (every element arrives as a JObject and the call fails
 * with an InvalidCastException), so these lists travel as a string the X++ contract parses
 * itself - see HSMobileOpsPostRequest.parmLinesJson.
 */
fun <T> rowsJson(serializer: KSerializer<T>, rows: List<T>): String =
    rowsFormat.encodeToString(ListSerializer(serializer), rows)
