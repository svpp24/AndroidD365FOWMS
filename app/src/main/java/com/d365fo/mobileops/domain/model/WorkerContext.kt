package com.d365fo.mobileops.domain.model

/** Identity and defaults for the signed-in worker. Every posting is attributed to one. */
data class WorkerContext(
    val workerId: String,
    val personnelNumber: String?,
    val displayName: String,
    val deviceId: String,
    val dataAreaId: String,
    val siteId: String,
    val warehouseId: String,
) {
    companion object {
        /** Used only before sign-in completes; no posting may be built from it. */
        val UNAUTHENTICATED = WorkerContext(
            workerId = "",
            personnelNumber = null,
            displayName = "",
            deviceId = "",
            dataAreaId = "",
            siteId = "",
            warehouseId = "",
        )
    }
}
