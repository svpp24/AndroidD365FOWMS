package com.d365fo.mobileops.domain.usecase

import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.ValidationChain
import com.d365fo.mobileops.domain.model.ValidationLayer
import com.d365fo.mobileops.domain.model.ValidationOutcome
import com.d365fo.mobileops.domain.model.ValidationSeverity
import com.d365fo.mobileops.domain.model.invalid
import com.d365fo.mobileops.domain.repository.ScanRepository
import com.d365fo.mobileops.util.NetworkResult
import javax.inject.Inject

/** Batch, serial and location for one captured line. */
data class DimensionInput(
    val siteId: String?,
    val warehouseId: String?,
    val locationId: String?,
    val batchId: String?,
    val serialId: String?,
)

/**
 * Validates the inventory dimensions on a captured line across all four layers.
 *
 * The serial-uniqueness check (layer 4) is the only part that touches the network, and it
 * is treated as advisory when the device is offline: blocking capture because a check could
 * not run would make the app useless in exactly the dead zone it exists to survive. The
 * local duplicate check still applies, and the authoritative check happens again server-side
 * at posting time, where a genuine duplicate lands in the dead-letter queue with the reason
 * attached.
 */
class ValidateTrackingDimensionsUseCase @Inject constructor(
    private val scanRepository: ScanRepository,
) {
    suspend operator fun invoke(
        line: PurchaseOrderLine,
        input: DimensionInput,
        locationRequired: Boolean,
    ): ValidationOutcome = ValidationChain.Builder()
        // -- Layer 1: format --------------------------------------------------------------
        .checkIf(line.tracking.requiresBatch) {
            when {
                input.batchId.isNullOrBlank() -> invalid(
                    ValidationSeverity.HARD, ValidationLayer.FORMAT, FIELD_BATCH,
                    "A batch number is required for ${line.itemId}",
                )
                input.batchId.length > MAX_DIMENSION_LENGTH -> invalid(
                    ValidationSeverity.HARD, ValidationLayer.FORMAT, FIELD_BATCH,
                    "Batch number is too long", input.batchId,
                )
                else -> ValidationOutcome.Valid
            }
        }
        .checkIf(line.tracking.requiresSerial) {
            when {
                input.serialId.isNullOrBlank() -> invalid(
                    ValidationSeverity.HARD, ValidationLayer.FORMAT, FIELD_SERIAL,
                    "A serial number is required for ${line.itemId}",
                )
                input.serialId.length > MAX_DIMENSION_LENGTH -> invalid(
                    ValidationSeverity.HARD, ValidationLayer.FORMAT, FIELD_SERIAL,
                    "Serial number is too long", input.serialId,
                )
                else -> ValidationOutcome.Valid
            }
        }
        .checkIf(locationRequired) {
            if (input.locationId.isNullOrBlank()) {
                invalid(
                    ValidationSeverity.HARD, ValidationLayer.FORMAT, FIELD_LOCATION,
                    "A location is required for this item",
                )
            } else {
                ValidationOutcome.Valid
            }
        }
        // -- Layer 2: local lookup --------------------------------------------------------
        .checkIf(!input.locationId.isNullOrBlank()) {
            val site = input.siteId ?: line.siteId
            val warehouse = input.warehouseId ?: line.warehouseId
            if (site == null || warehouse == null) {
                ValidationOutcome.Valid
            } else if (!scanRepository.isLocationValid(site, warehouse, input.locationId!!)) {
                invalid(
                    ValidationSeverity.HARD, ValidationLayer.LOCAL_LOOKUP, FIELD_LOCATION,
                    "Location ${input.locationId} is not valid in warehouse $warehouse",
                    input.locationId,
                )
            } else {
                ValidationOutcome.Valid
            }
        }
        // -- Layer 2: local serial reuse --------------------------------------------------
        .checkIf(line.tracking.requiresSerial && !input.serialId.isNullOrBlank()) {
            if (scanRepository.isSerialUsedLocally(line.itemId, input.serialId!!)) {
                invalid(
                    ValidationSeverity.HARD, ValidationLayer.LOCAL_LOOKUP, FIELD_SERIAL,
                    "Serial ${input.serialId} is already captured on this device",
                    input.serialId,
                )
            } else {
                ValidationOutcome.Valid
            }
        }
        // -- Layer 4: server serial uniqueness --------------------------------------------
        .checkIf(line.tracking.requiresSerial && !input.serialId.isNullOrBlank()) {
            when (val result = scanRepository.isSerialAlreadyRegistered(line.itemId, input.serialId!!)) {
                is NetworkResult.Success ->
                    if (result.data) {
                        invalid(
                            ValidationSeverity.HARD, ValidationLayer.SERVER, FIELD_SERIAL,
                            "Serial ${input.serialId} is already registered in D365FO",
                            input.serialId,
                        )
                    } else {
                        ValidationOutcome.Valid
                    }

                // Offline or a transient server problem: let capture proceed. The posting
                // path re-checks server-side, and a genuine duplicate surfaces there with
                // its reason attached rather than blocking a worker in a dead zone.
                is NetworkResult.Error, NetworkResult.Loading -> ValidationOutcome.Valid
            }
        }
        .build()
        .evaluate()

    private companion object {
        const val FIELD_BATCH = "batch"
        const val FIELD_SERIAL = "serial"
        const val FIELD_LOCATION = "location"
        const val MAX_DIMENSION_LENGTH = 20
    }
}
