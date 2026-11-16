package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The license-expiry band on Home (user, 16 Nov 2026): once a day per device for the last 30 days
 * before the license expires - amber "License expires in N days / Renew by <date>", red on the last
 * day. The x hides it until the next day; signing out and in again does not bring it back.
 *
 * The expiry date comes from the license check ([LicenseRepository]) and, for a build without it,
 * from D365FO's sign-in warning - see [record] and [recordFromWarning].
 */
@Singleton
class LicenseExpiryNotice @Inject constructor(private val secureStore: SecureStore) {

    /** The license's expiry, as ISO (2027-01-01T00:00:00Z) or a plain date. */
    fun record(expiry: String?) {
        val date = dateOf(expiry) ?: return
        secureStore.putString(KEY_EXPIRY, date.toString())
    }

    /** D365FO's sign-in warning ("... expires in 12 day(s) (on 2027-01-01...)") - the date in it. */
    fun recordFromWarning(warning: String?) {
        warning ?: return
        Regex("""(\d{4}-\d{2}-\d{2})""").find(warning)?.groupValues?.get(1)?.let(::record)
    }

    /** The band for [today], or null: outside the 30 days, already expired, or hidden today. */
    fun band(today: LocalDate = LocalDate.now()): BandState? = expiryBand(
        expiry = secureStore.getString(KEY_EXPIRY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        hiddenOn = secureStore.getString(KEY_HIDDEN_ON),
        today = today,
    )

    /** The x: hidden until tomorrow. */
    fun hideToday(today: LocalDate = LocalDate.now()) = secureStore.putString(KEY_HIDDEN_ON, today.toString())

    private fun dateOf(text: String?): LocalDate? = expiryDateOf(text)

    private companion object {
        const val KEY_EXPIRY = "license_expiry_date"
        const val KEY_HIDDEN_ON = "license_expiry_hidden_on"
    }
}

/** The band for [today]: none outside the last 30 days, once expired, or when hidden today. */
internal fun expiryBand(expiry: LocalDate?, hiddenOn: String?, today: LocalDate): BandState? {
    expiry ?: return null
    val days = ChronoUnit.DAYS.between(today, expiry)
    if (days < 1 || days > 30) return null
    if (hiddenOn == today.toString()) return null
    val date = expiry.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    return if (days == 1L) {
        BandState(BandKind.Bad, "License expires tomorrow", "Renew today. Sign-in stops on $date.")
    } else {
        BandState(BandKind.Warn, "License expires in $days days", "Renew by $date.")
    }
}

/** The date in "2027-01-01T00:00:00Z", "2027-01-01" or a sign-in warning naming it. */
internal fun expiryDateOf(text: String?): LocalDate? =
    text?.let { Regex("""\d{4}-\d{2}-\d{2}""").find(it)?.value }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
