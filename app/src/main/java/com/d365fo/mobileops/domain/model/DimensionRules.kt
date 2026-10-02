package com.d365fo.mobileops.domain.model

/**
 * Whether each dimension the item tracks must be given for this line's process, as D365FO's
 * dimension group decides it (HSMobileOpsDimensionRules: mandatory, or Blank receipt / Blank issue
 * not allowed). A dimension that is asked for but not mandatory can be skipped. Defaults are
 * "required" so a server that does not send these behaves as before.
 */
@kotlinx.serialization.Serializable
data class DimensionRules(
    val batchMandatory: Boolean = true,
    val serialMandatory: Boolean = true,
    val locationMandatory: Boolean = true,
    /** Serial number control: one serial per unit. Off means one serial covers the line. */
    val serialPerUnit: Boolean = true,
)
