package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a D365FO boolean, whatever shape it arrives in.
 *
 * X++ has no native boolean on a table field — it has the `NoYes` enum — and the OData layer
 * serialises that as the *string* `"No"` or `"Yes"`. `"IsLineStopped":"No"` is what comes back
 * on PurchaseOrderLinesV2, and a plain `Boolean` field cannot decode it: kotlinx.serialization
 * throws, the whole page fails, and the worklist ends up empty with no visible cause. That is
 * exactly how this first showed up.
 *
 * Not every field is a NoYes, though. Some entities publish a real JSON `true`/`false`, and
 * which one an entity uses is not something the app can know in advance — so this accepts both
 * rather than betting on either. Also accepts 1/0, which some computed fields emit.
 *
 * Anything unrecognised decodes to null rather than throwing. A single odd value in one column
 * should not cost the operator the entire page: every consumer of these flags already treats
 * null as "not known", and the conservative default lives at the call site where the meaning of
 * the flag is understood.
 */
object NoYesSerializer : KSerializer<Boolean?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.d365fo.NoYes", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Boolean? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: return decoder.decodeBoolean()
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return fromToken(primitive.content)
        // Unquoted: a real JSON boolean, or 1/0.
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> fromToken(primitive.content)
        }
    }

    private fun fromToken(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }

    /**
     * Writes back the string form. These entities are read-only in this app, so this exists to
     * satisfy the interface and to keep round-tripping honest in tests; a numeric or bare
     * boolean would not be what D365FO sent.
     */
    override fun serialize(encoder: Encoder, value: Boolean?) {
        encoder.encodeString(if (value == true) "Yes" else "No")
    }
}
