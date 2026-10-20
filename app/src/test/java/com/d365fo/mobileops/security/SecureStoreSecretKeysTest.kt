package com.d365fo.mobileops.security

import com.d365fo.mobileops.data.security.SecureStore
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Only connection settings and login accounts stay encrypted (decision, 11 Oct). */
class SecureStoreSecretKeysTest {

    @Test
    fun `connection settings and login accounts stay encrypted`() {
        listOf(
            "connection_profile_client_secret::DEV",
            "connection_profile_labels",
            "connection_client_secret",
            "offline_sign_in::001",
            "last_worker_user_name",
            "worker_id",
            "active_environment",
            "license_certificate",
            "db_passphrase",
            "debug_worker_session",
        ).forEach { assertThat(it to SecureStore.isSecret(it)).isEqualTo(it to true) }
    }

    @Test
    fun `everything else is plain`() {
        listOf(
            "device_id",
            "idle_lock_ms",
            "has_seen_onboarding",
            "display_scale",
            "post_immediately",
            "count_alert_seen_triggered",
            "sum_items",
        ).forEach { assertThat(it to SecureStore.isSecret(it)).isEqualTo(it to false) }
    }
}
