package com.d365fo.mobileops.domain.model

/**
 * Which tier a check runs in. Ordered cheapest-first, and evaluation stops at the first
 * hard failure, so a malformed barcode never costs a database hit and a barcode that is not
 * in the local master never costs a network round trip.
 */
enum class ValidationLayer(val budgetMillis: Long?) {
    /** Format, regex, length. No I/O at all. */
    FORMAT(null),

    /** Indexed Room lookup — item exists, location is active. */
    LOCAL_LOOKUP(50),

    /** Cached business rules — UoM conversion, tolerances, dimension requirements. */
    CACHED_RULES(100),

    /** D365FO round trip. Only for checks that cannot be answered from the device. */
    SERVER(null),
}

/**
 * HARD blocks progression outright. SOFT can be overridden with a supervisor PIN — used
 * for tolerance breaches and count variances, where the physical goods on the dock are the
 * ground truth and the system is the thing that is wrong.
 */
enum class ValidationSeverity { HARD, SOFT }

sealed interface ValidationOutcome {

    data object Valid : ValidationOutcome

    data class Invalid(
        val severity: ValidationSeverity,
        val layer: ValidationLayer,
        /** Which field to attach the inline error to. */
        val field: String,
        /** Already-resolved, worker-facing text. */
        val message: String,
        /** What was actually scanned or typed — written to the audit log verbatim. */
        val rawValue: String? = null,
    ) : ValidationOutcome

    /** True when a supervisor PIN would let the worker continue. */
    val isOverridable: Boolean
        get() = this is Invalid && severity == ValidationSeverity.SOFT
}

/**
 * Runs checks in layer order and returns the first failure.
 *
 * Soft failures do not stop evaluation: a quantity can be both over tolerance (soft) and
 * missing a mandatory batch number (hard), and the worker needs to be told about the hard
 * one rather than being offered an override that would not help.
 */
class ValidationChain private constructor(
    private val checks: List<suspend () -> ValidationOutcome>,
) {
    suspend fun evaluate(): ValidationOutcome {
        var firstSoft: ValidationOutcome.Invalid? = null
        for (check in checks) {
            when (val outcome = check()) {
                is ValidationOutcome.Valid -> Unit
                is ValidationOutcome.Invalid ->
                    if (outcome.severity == ValidationSeverity.HARD) {
                        return outcome
                    } else if (firstSoft == null) {
                        firstSoft = outcome
                    }
            }
        }
        return firstSoft ?: ValidationOutcome.Valid
    }

    class Builder {
        private val checks = mutableListOf<suspend () -> ValidationOutcome>()

        fun check(block: suspend () -> ValidationOutcome) = apply { checks.add(block) }

        fun build() = ValidationChain(checks.toList())
    }
}

fun invalid(
    severity: ValidationSeverity,
    layer: ValidationLayer,
    field: String,
    message: String,
    rawValue: String? = null,
) = ValidationOutcome.Invalid(severity, layer, field, message, rawValue)
