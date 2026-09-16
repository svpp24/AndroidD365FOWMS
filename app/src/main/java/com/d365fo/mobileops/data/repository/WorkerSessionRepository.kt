package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.ChangePasswordRequestBody
import com.d365fo.mobileops.data.remote.dto.ChangePasswordServiceRequest
import com.d365fo.mobileops.data.remote.dto.SignInRequestBody
import com.d365fo.mobileops.data.remote.dto.SignInServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in warehouse worker.
 *
 * **No password is ever stored here, or anywhere on the device.** It is passed to the service on
 * the call that needs it and then dropped. There is deliberately no "remember me": a handheld is
 * shared across shifts and lives on a warehouse floor, so a cached credential is one lost device
 * away from being someone else's identity on a posting.
 *
 * The session is in memory only, for the same reason. Restarting the app signs the worker out,
 * which is the correct behaviour when the next person to pick the device up is a different person.
 */
@Singleton
class WorkerSessionRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
    private val secureStore: SecureStore,
    private val json: Json,
) {

    private val _session = MutableStateFlow<WorkerSession?>(
        // Debug builds only - see the doc on SecureStore.KEY_DEBUG_WORKER_SESSION. Every
        // reinstall during active testing otherwise re-shows the login screen even though
        // nothing about who is signed in has actually changed, which forces a human to type
        // real credentials back in after every single build. A release build never reads this
        // key, so production behaviour - in-memory only, gone on restart - is unchanged.
        if (BuildConfig.DEBUG) restoreDebugSession() else null,
    )
    val session: StateFlow<WorkerSession?> = _session.asStateFlow()

    private fun restoreDebugSession(): WorkerSession? =
        secureStore.getString(SecureStore.KEY_DEBUG_WORKER_SESSION)?.let { raw ->
            runCatching { json.decodeFromString(WorkerSession.serializer(), raw) }
                .onFailure { Timber.w(it, "could not restore debug worker session") }
                .getOrNull()
        }

    private fun company(): String =
        companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    suspend fun signIn(
        userName: String,
        password: String,
        deviceId: String,
        deviceBrand: String = "",
        deviceModel: String = "",
        platform: String = "",
        platformVersion: String = "",
        appVersionName: String = "",
    ): NetworkResult<WorkerSession> = withContext(ioDispatcher) {
        val name = userName.trim()
        if (name.isEmpty() || password.isEmpty()) {
            return@withContext NetworkResult.Error(
                AppFailure.Validation("Enter your user name and password"),
            )
        }

        val result = errorMapper.safeCall {
            service.signIn(
                SignInServiceRequest(
                    SignInRequestBody(
                        dataAreaId = company(),
                        userName = name,
                        password = password,
                        deviceId = deviceId,
                        deviceBrand = deviceBrand,
                        deviceModel = deviceModel,
                        platform = platform,
                        platformVersion = platformVersion,
                        appVersionName = appVersionName,
                    ),
                ),
            )
        }

        when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> {
                val body = result.data
                if (!body.success) {
                    // The wording follows the server's code, never guesses beyond it. In
                    // particular a wrong user name and a wrong password are one message, because
                    // telling them apart tells an attacker which accounts exist.
                    return@withContext NetworkResult.Error(
                        AppFailure.BusinessRule(
                            when (body.errorCode) {
                                "INVALID_CREDENTIALS" ->
                                    "That user name and password do not match"
                                "INACTIVE" -> "This account has been deactivated"
                                "LOCKED_OUT" -> body.message
                                    ?: "Too many failed attempts. Try again shortly."
                                "NO_WAREHOUSE" ->
                                    "No warehouse is assigned to you. Ask a supervisor."
                                else -> body.message ?: "Could not sign in"
                            },
                        ),
                    )
                }

                val warehouses = body.warehouses.map {
                    AssignedWarehouse(
                        siteId = it.siteId,
                        warehouseId = it.warehouseId,
                        warehouseName = it.warehouseName,
                        isDefault = it.isDefault,
                    )
                }

                val session = WorkerSession(
                    userName = body.userName,
                    displayName = body.displayName.ifBlank { body.userName },
                    personnelNumber = body.personnelNumber,
                    isSupervisor = body.isSupervisor,
                    isCycleCountSupervisor = body.isCycleCountSupervisor,
                    // Zero from the server means "no personal override", not "zero
                    // allowance" - kept null on this side so effectiveMaximum can tell the
                    // two apart without repeating the zero check at every call site. A
                    // plain string, not BigDecimal, for the same reason StoredLine's
                    // quantity is a string: WorkerSession is @Serializable for the
                    // debug-session cache, and kotlinx.serialization has no BigDecimal
                    // support out of the box. Routed through toString(), not the Double
                    // overload of toBigDecimal() - that constructs from the binary value
                    // directly and can turn a clean "10.1" into "10.099999999999999...".
                    maxOverPercent = body.maxOverPercent.toString().toBigDecimal()
                        .takeIf { it.signum() > 0 }?.toPlainString(),
                    maxOverQty = body.maxOverQty.toString().toBigDecimal()
                        .takeIf { it.signum() > 0 }?.toPlainString(),
                    mustChangePassword = body.mustChangePassword,
                    warehouses = warehouses,
                    // The default if one is flagged; otherwise the only one, or nothing and the
                    // app asks. Never a guess at which of several the worker meant.
                    selected = warehouses.firstOrNull { it.isDefault }
                        ?: warehouses.singleOrNull(),
                )
                _session.value = session
                if (BuildConfig.DEBUG) {
                    secureStore.putString(
                        SecureStore.KEY_DEBUG_WORKER_SESSION,
                        json.encodeToString(WorkerSession.serializer(), session),
                    )
                }
                NetworkResult.Success(session)
            }
        }
    }

    /** Switches to another assigned warehouse. Anything not on the list is refused. */
    fun selectWarehouse(warehouseId: String) {
        _session.update { current ->
            val match = current?.warehouses?.firstOrNull { it.warehouseId == warehouseId }
            if (match == null) current else current.copy(selected = match)
        }
    }

    fun signOut() {
        _session.value = null
        if (BuildConfig.DEBUG) secureStore.remove(SecureStore.KEY_DEBUG_WORKER_SESSION)
    }

    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): NetworkResult<Unit> = withContext(ioDispatcher) {
        val userName = _session.value?.userName
            ?: return@withContext NetworkResult.Error(
                AppFailure.Validation("Sign in first"),
            )

        val result = errorMapper.safeCall {
            service.changePassword(
                ChangePasswordServiceRequest(
                    ChangePasswordRequestBody(
                        dataAreaId = company(),
                        userName = userName,
                        currentPassword = currentPassword,
                        newPassword = newPassword,
                    ),
                ),
            )
        }
        result.toUnit {
            // The forced-change flag is cleared server-side, so the local copy follows.
            _session.update { it?.copy(mustChangePassword = false) }
        }
    }

    private inline fun NetworkResult<com.d365fo.mobileops.data.remote.dto.ChangePasswordServiceResponse>
        .toUnit(onSuccess: () -> Unit): NetworkResult<Unit> = when (this) {
        is NetworkResult.Error -> this
        NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        is NetworkResult.Success ->
            if (data.success) {
                onSuccess()
                NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(
                    AppFailure.BusinessRule(data.message ?: "The change was refused"),
                )
            }
    }
}

/**
 * Who is signed in, and where they may work.
 *
 * Held in memory only in a release build - see the class doc on WorkerSessionRepository.
 * [Serializable] exists solely for the debug-only cache described there; it is never sent over
 * the wire or written outside that one debug code path.
 */
@Serializable
data class WorkerSession(
    val userName: String,
    val displayName: String,
    val personnelNumber: String,
    val isSupervisor: Boolean,
    /** Distinct from [isSupervisor] - see the field help on HSMobileOpsUser.IsCycleCountSupervisor. */
    val isCycleCountSupervisor: Boolean = false,
    /**
     * This worker's personal over-delivery allowance - a plain string, see the conversion
     * note in [WorkerSessionRepository.signIn]. Null means no personal override is
     * configured; consumers must never treat that as a zero allowance. Only ever widens
     * what a purchase order line's own D365FO-configured tolerance already permits - see
     * ReceivingFlowViewModel.effectiveMaximum, the one place these are read.
     */
    val maxOverPercent: String? = null,
    val maxOverQty: String? = null,
    val mustChangePassword: Boolean,
    val warehouses: List<AssignedWarehouse>,
    /** Null when several warehouses are assigned and none is flagged default. */
    val selected: AssignedWarehouse?,
)

@Serializable
data class AssignedWarehouse(
    val siteId: String,
    val warehouseId: String,
    val warehouseName: String,
    val isDefault: Boolean,
) {
    /** "Main store (100011)", falling back to the code alone when there is no name. */
    val label: String
        get() = if (warehouseName.isBlank()) warehouseId else "$warehouseName ($warehouseId)"
}
