package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.repository.WorkerContextRepositoryImpl
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Setting a device up with a device setup code ([DeviceSetupQr]), in order:
 * 1. the device key ([DeviceKey]) - a new one, so a code scanned again replaces the old key;
 * 2. register: the token service checks the code with D365FO, the license and the device limit;
 * 3. the connection is saved (local data wiped when it points somewhere new) and the company set;
 * 4. a first token proves the whole chain: "Device ready - N of M devices".
 */
@Singleton
class DeviceSetup @Inject constructor(
    private val client: TokenServiceClient,
    private val deviceKey: DeviceKey,
    private val secureStore: SecureStore,
    private val connectionSettings: ConnectionSettings,
    private val database: D365Database,
    private val workerContextRepository: WorkerContextRepositoryImpl,
    private val tokenProvider: TokenProvider,
    private val deactivation: DeviceDeactivation,
) {
    sealed interface Result {
        data class Ready(val message: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun setUp(read: DeviceSetupQr.Result.Ready): Result {
        val payload = read.payload
        val config = read.config
        val deviceId = secureStore.deviceId()

        val registered = withContext(Dispatchers.Default) {
            deviceKey.delete()
            val publicKey = deviceKey.publicKeyBase64()
            val request = TokenServiceClient.RegisterRequest(
                code = payload.code.trim().uppercase(),
                deviceId = deviceId,
                publicKey = publicKey,
                signature = deviceKey.sign(TokenServiceClient.registerText(payload.code, deviceId, publicKey)),
                deviceBrand = android.os.Build.MANUFACTURER.orEmpty(),
                deviceModel = android.os.Build.MODEL.orEmpty(),
                platform = "Android",
                platformVersion = android.os.Build.VERSION.RELEASE.orEmpty(),
                appVersionName = BuildConfig.VERSION_NAME,
                d365Url = config.resourceUrl,
                company = config.company,
            )
            client.register(config.tokenServiceUrl, request)
        }
        val answer = when (registered) {
            is TokenServiceClient.Answer.Ok -> registered.body
            is TokenServiceClient.Answer.NoConnection -> {
                Timber.w(registered.error, "device setup: token service not reachable")
                return Result.Failed("The token service could not be reached (${config.tokenServiceUrl}). Check the network and scan again.")
            }
            is TokenServiceClient.Answer.Refused -> {
                Timber.w("device setup: refused %s %s", registered.httpStatus, registered.code)
                deviceKey.delete()
                return Result.Failed(registered.message.ifBlank { "The setup code was refused (${registered.code})." })
            }
        }

        val hadTarget = connectionSettings.tenant.value.resourceUrl.isNotBlank()
        try {
            withContext(Dispatchers.IO) {
                val changed = connectionSettings.saveTenant(config)
                if (changed && hadTarget) database.clearAllTables()
                tokenProvider.invalidateCache()
                val company = answer.company.ifBlank { config.company }
                if (company.isNotBlank()) workerContextRepository.setRegistration(personnelNumber = null, dataAreaId = company)
                deactivation.clear()
            }
        } catch (e: Exception) {
            Timber.e(e, "device setup: save failed")
            return Result.Failed("The device was registered, but the connection could not be saved: ${e.message}")
        }

        val count = if (answer.maxDevices > 0) " - ${answer.activeDevices} of ${answer.maxDevices} devices" else ""
        val token = withTimeoutOrNull(TOKEN_CHECK_MS) { tokenProvider.acquireSilent() }
        return when (token) {
            is NetworkResult.Error -> Result.Failed("Device registered$count, but getting access failed: ${token.failure.message}")
            else -> Result.Ready("Device ready$count.")
        }
    }

    private companion object {
        const val TOKEN_CHECK_MS = 45_000L
    }
}
