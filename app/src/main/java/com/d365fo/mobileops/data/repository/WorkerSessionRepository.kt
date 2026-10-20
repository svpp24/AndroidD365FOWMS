package com.d365fo.mobileops.data.repository

import android.content.Context
import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.local.dao.PermissionDao
import com.d365fo.mobileops.data.local.entity.WorkerPermissionEntity
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.ChangePasswordRequestBody
import com.d365fo.mobileops.data.remote.dto.ChangePasswordServiceRequest
import com.d365fo.mobileops.data.remote.dto.ModulePermissionDto
import com.d365fo.mobileops.data.remote.dto.SignInRequestBody
import com.d365fo.mobileops.data.remote.dto.SignInServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.OfflineSignIn
import com.d365fo.mobileops.data.security.OfflineSignInResult
import com.d365fo.mobileops.data.security.PlayIntegrityTokenProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 *
 * Offline sign-in: when D365FO can't be reached, a worker who signed in online here in the last
 * week can still sign in. That check uses a salted one-way verifier kept by [OfflineSignIn], not
 * the password. See that class for when the verifier is replaced or deleted.
 */
@Singleton
class WorkerSessionRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
    private val secureStore: SecureStore,
    private val json: Json,
    private val permissionDao: PermissionDao,
    private val integrityTokenProvider: PlayIntegrityTokenProvider,
    private val offlineSignIn: OfflineSignIn,
    private val reachability: com.d365fo.mobileops.data.sync.AosReachabilityMonitor,
    private val signInLog: com.d365fo.mobileops.data.sync.SignInLogRecorder,
    @ApplicationContext private val context: Context,
) {

    /** Writes the offline verifier after a sign-in, so its PBKDF2 rounds don't slow the sign-in. */
    private val background = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _session = MutableStateFlow<WorkerSession?>(
        // Debug builds only - see the doc on SecureStore.KEY_DEBUG_WORKER_SESSION. Every
        // reinstall during active testing otherwise re-shows the login screen even though
        // nothing about who is signed in has actually changed, which forces a human to type
        // real credentials back in after every single build. A release build never reads this
        // key, so production behaviour - in-memory only, gone on restart - is unchanged.
        if (BuildConfig.DEBUG) restoreDebugSession() else null,
    )
    val session: StateFlow<WorkerSession?> = _session.asStateFlow()

    /**
     * Each successful sign-in (online or offline), by user name - not a session restored at
     * start-up. [com.d365fo.mobileops.data.sync.ActiveSessionGuard] makes this device the
     * worker's active one in D365FO on it.
     */
    private val _signIns = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val signIns: kotlinx.coroutines.flow.SharedFlow<String> = _signIns

    /** Why the worker was signed out without asking - shown on the sign-in screen until the next sign-in. */
    private val _signOutNotice = MutableStateFlow<String?>(null)
    val signOutNotice: StateFlow<String?> = _signOutNotice.asStateFlow()

    /** The worker signed in on another device: sign out here and say so on the sign-in screen. */
    fun signOutElsewhere(message: String) {
        signOut(reason = "Signed in on another device")
        _signOutNotice.value = message
    }

    // Not part of WorkerSession itself - that's the session's identity/warehouses, cached
    // verbatim across a debug rebuild (see restoreDebugSession); a license-expiry notice is a
    // one-off "just signed in" moment, not something that should reappear from a stale cache on
    // every app restart. Cleared once shown - see dismissLicenseWarning.
    private val _licenseWarning = MutableStateFlow<String?>(null)
    val licenseWarning: StateFlow<String?> = _licenseWarning.asStateFlow()

    fun dismissLicenseWarning() {
        _licenseWarning.value = null
    }

    /**
     * The installed APK's own file modification time - changes on every install/reinstall,
     * same signal as PackageInfo.lastUpdateTime, without paying for it.
     *
     * context.applicationInfo is already resident in this process (handed to it by
     * ActivityThread at launch), so reading sourceDir off it is free. Calling
     * packageManager.getPackageInfo() instead - the first version of this - is a Binder round
     * trip to PackageManagerService, and doing that synchronously on the app's cold-start path,
     * before Hilt has even finished building this singleton, added a real, measurable delay to
     * every launch. Confirmed live: reported as "coming to signin page is slow" the moment this
     * was added, gone once replaced with this.
     */
    private fun currentInstallTime(): Long = runCatching {
        java.io.File(context.applicationInfo.sourceDir).lastModified()
    }.getOrDefault(0L)

    /**
     * Restores the cached session only across a rebuild, never across a bare close-and-reopen
     * of the same install.
     *
     * Those two look identical from a cold start alone - both are "no in-memory session, check
     * the cache" - so [SecureStore.KEY_DEBUG_SESSION_INSTALL_TIME] records the APK's own
     * `lastUpdateTime` at the moment the session was cached. Unchanged since means nothing was
     * reinstalled - an ordinary close-and-reopen, which must require login the same as
     * production does. Changed means a new build landed since - the actual case this cache
     * exists for - so this restores and immediately re-stamps the marker to the *current*
     * install time, so the next close-and-reopen of *this* build correctly requires login too
     * rather than surviving indefinitely off one rebuild.
     */
    private fun restoreDebugSession(): WorkerSession? {
        // Development only (DEV_SKIP_SIGN_IN, debug builds): no sign-in screen at all - the last
        // real session if one is cached, else one built from DEV_* in local.properties.
        if (BuildConfig.DEV_SKIP_SIGN_IN) return cachedDebugSession() ?: devSession()

        val cachedInstallTime = secureStore.getString(SecureStore.KEY_DEBUG_SESSION_INSTALL_TIME)
            ?.toLongOrNull()
        if (cachedInstallTime == null || cachedInstallTime == currentInstallTime()) return null

        val restored = secureStore.getString(SecureStore.KEY_DEBUG_WORKER_SESSION)?.let { raw ->
            runCatching { json.decodeFromString(WorkerSession.serializer(), raw) }
                .onFailure { Timber.w(it, "could not restore debug worker session") }
                .getOrNull()
        }
        if (restored != null) {
            secureStore.putString(
                SecureStore.KEY_DEBUG_SESSION_INSTALL_TIME,
                currentInstallTime().toString(),
            )
        }
        return restored
    }

    private fun cachedDebugSession(): WorkerSession? =
        secureStore.getString(SecureStore.KEY_DEBUG_WORKER_SESSION)?.let { raw ->
            runCatching { json.decodeFromString(WorkerSession.serializer(), raw) }.getOrNull()
        }

    /** A session from local.properties DEV_* values - null unless DEV_USER_NAME is set. */
    private fun devSession(): WorkerSession? {
        val user = BuildConfig.DEV_USER_NAME.takeIf { it.isNotBlank() } ?: return null
        val warehouse = BuildConfig.DEV_WAREHOUSE_ID.takeIf { it.isNotBlank() }?.let {
            AssignedWarehouse(siteId = BuildConfig.DEV_SITE_ID, warehouseId = it, warehouseName = it, isDefault = true)
        }
        Timber.w("DEV_SKIP_SIGN_IN: using a development session for '%s'", user)
        return WorkerSession(
            userName = user,
            displayName = BuildConfig.DEV_DISPLAY_NAME.ifBlank { user },
            personnelNumber = "",
            isSupervisor = BuildConfig.DEV_SUPERVISOR,
            isCycleCountSupervisor = BuildConfig.DEV_SUPERVISOR,
            mustChangePassword = false,
            warehouses = listOfNotNull(warehouse),
            selected = warehouse,
        )
    }

    private fun company(): String =
        companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    /** The user name the sign-in screen fills in next time (WorkerLoginViewModel). Never the PIN. */
    fun lastUserName(): String = runCatching { secureStore.getString(KEY_LAST_USER) }.getOrNull().orEmpty()

    // Before the session is set: setting it leaves the sign-in screen at once, which cancelled
    // the screen's own save - the name was never kept (seen 10 Oct, 1.0.80).
    private fun rememberLastUser(name: String) {
        runCatching { secureStore.putString(KEY_LAST_USER, name.trim()) }
    }

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
                AppFailure.Validation("Enter your user name and PIN"),
            )
        }

        // D365FO already known to be down (the cloud at the top is not green): offline at once,
        // rather than first sitting out the 15 s connect timeout of a sign-in that cannot work.
        // Recovery is the background probe's to notice - see AosReachabilityInterceptor.
        if (!reachability.reachable.value && offlineSignIn.hasAny()) {
            return@withContext signInOffline(name, password)
        }

        // Best-effort - see PlayIntegrityTokenProvider's own "never throws" doc comment. A null
        // token still signs in; AOS just sees an UNVERIFIED integrity verdict instead of a reason
        // to refuse the call.
        val integrityToken = integrityTokenProvider.requestToken()

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
                        integrityToken = integrityToken,
                    ),
                ),
            )
        }

        when (result) {
            is NetworkResult.Error -> when (result.failure) {
                // D365FO can't be reached: offline sign-in, if this worker has one here.
                is AppFailure.Offline, is AppFailure.Transient -> signInOffline(name, password)
                else -> result
            }
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> {
                val body = result.data
                if (!body.success) {
                    // Logged by errorCode, never the password - see the comment two lines down
                    // on why INVALID_CREDENTIALS itself is already deliberately ambiguous.
                    Timber.w("sign-in refused for '%s': %s", name, body.errorCode)
                    // D365FO refused, so no offline sign-in may outlive that answer. A license
                    // refusal is about the device, not this worker, so it clears every worker's.
                    if (body.errorCode in LICENSE_REFUSALS) offlineSignIn.forgetAll() else offlineSignIn.forget(name)
                    // The wording follows the server's code, never guesses beyond it. In
                    // particular a wrong user name and a wrong password are one message, because
                    // telling them apart tells an attacker which accounts exist.
                    return@withContext NetworkResult.Error(
                        AppFailure.BusinessRule(
                            when (body.errorCode) {
                                "INVALID_CREDENTIALS" ->
                                    "That user name and PIN do not match"
                                "INACTIVE" -> "This account has been deactivated"
                                "LOCKED_OUT" -> body.message
                                    ?: "Too many failed attempts. Try again shortly."
                                "NO_WAREHOUSE" ->
                                    "No warehouse is assigned to you. Ask a supervisor."
                                // Licensing problems, checked server-side before credentials are
                                // even looked at - see HSMobileOpsLicense and the signIn hook in
                                // HSMobileOpsUserService. The server's own message already names
                                // the specifics (an expiry date, the configured device count), so
                                // it's shown as-is rather than replaced with a generic string.
                                "NO_LICENSE", "LICENSE_INVALID", "LICENSE_EXPIRED",
                                "DEVICE_LIMIT_REACHED" ->
                                    body.message ?: "This app is not licensed. Contact your administrator."
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
                Timber.i("signed in: '%s', %d warehouse(s)%s", body.userName, warehouses.size,
                    body.licenseWarning?.takeIf { it.isNotBlank() }?.let { " - license warning: $it" }.orEmpty())
                rememberLastUser(userName)
                _session.value = session
                _signOutNotice.value = null
                _signIns.tryEmit(session.userName)
                signInLog.started(company(), session.userName, session.selected?.warehouseId, signedInOffline = false)
                _licenseWarning.value = body.licenseWarning?.takeIf { it.isNotBlank() }
                background.launch {
                    runCatching { offlineSignIn.remember(name, password, session) }
                        .onFailure { Timber.w(it, "could not keep offline sign-in for '%s'", name) }
                }
                // Down-sync: replaces this worker's whole permission set with whatever the
                // server just sent, every sign-in - see PermissionDao.replaceFor and
                // ModulePermissionDto's own doc on why an empty/missing list means "nothing
                // restricted" rather than "everything denied." Best-effort: a write failure here
                // must never fail the sign-in itself over a module-gating cache.
                runCatching {
                    permissionDao.replaceFor(body.userName, body.modulePermissions.toEntities(body.userName))
                }.onFailure { Timber.w(it, "could not cache module permissions for '%s'", body.userName) }
                if (BuildConfig.DEBUG) {
                    secureStore.putString(
                        SecureStore.KEY_DEBUG_WORKER_SESSION,
                        json.encodeToString(WorkerSession.serializer(), session),
                    )
                    // Stamped to this install so a bare close-and-reopen right after signing in
                    // still requires login next time - see restoreDebugSession.
                    secureStore.putString(
                        SecureStore.KEY_DEBUG_SESSION_INSTALL_TIME,
                        currentInstallTime().toString(),
                    )
                }
                NetworkResult.Success(session)
            }
        }
    }

    /**
     * Replaces the assigned warehouses with a fresh list from D365FO (names included). The
     * current warehouse stays selected while it is still assigned; otherwise the default, or
     * none when several are assigned and none is default.
     */
    fun replaceWarehouses(warehouses: List<AssignedWarehouse>) {
        if (warehouses.isEmpty()) return
        _session.update { current ->
            current?.copy(
                warehouses = warehouses,
                selected = current.selected?.let { sel -> warehouses.firstOrNull { it.warehouseId == sel.warehouseId } }
                    ?: warehouses.firstOrNull { it.isDefault }
                    ?: warehouses.singleOrNull(),
            )
        }
    }

    /** Switches to another assigned warehouse. Anything not on the list is refused. */
    fun selectWarehouse(warehouseId: String) {
        _session.update { current ->
            val match = current?.warehouses?.firstOrNull { it.warehouseId == warehouseId }
            if (match == null) current else current.copy(selected = match)
        }
        signInLog.warehouseChosen(warehouseId)
        // Debug builds keep the session across a rebuild (see restoreDebugSession); keep the
        // chosen warehouse with it, so a reinstall does not ask for the warehouse again.
        if (BuildConfig.DEBUG && secureStore.getString(SecureStore.KEY_DEBUG_WORKER_SESSION) != null) {
            _session.value?.let { secureStore.putString(SecureStore.KEY_DEBUG_WORKER_SESSION, json.encodeToString(WorkerSession.serializer(), it)) }
        }
    }

    /** [reason] goes into the sign-in history: "Signed out", or "Timed out" for the idle sign-out. */
    fun signOut(reason: String = "Signed out") {
        Timber.i("signed out: '%s'", _session.value?.userName ?: "unknown")
        if (_session.value != null) signInLog.ended(reason)
        _session.value = null
        _licenseWarning.value = null
        if (BuildConfig.DEBUG) {
            secureStore.remove(SecureStore.KEY_DEBUG_WORKER_SESSION)
            secureStore.remove(SecureStore.KEY_DEBUG_SESSION_INSTALL_TIME)
        }
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
            // The old password must stop working offline too.
            _session.value?.let { session ->
                background.launch { runCatching { offlineSignIn.remember(userName, newPassword, session) } }
            }
        }
    }

    /** Sign-in while D365FO can't be reached, checked against this worker's last online sign-in here. */
    /** A touch in the signed-in app - see SignInLogRecorder.touched. */
    fun noteActivity() = signInLog.touched()

    private fun signInOffline(name: String, password: String): NetworkResult<WorkerSession> {
        val noConnection = "Can't reach D365FO"
        val offline = offlineSignIn.verify(name, password)
        Timber.i("offline sign-in for '%s': %s", name, offline::class.simpleName)
        return when (offline) {
            is OfflineSignInResult.SignedIn -> {
                Timber.i("signed in offline: '%s'", offline.session.userName)
                rememberLastUser(name)
                _session.value = offline.session
                _signOutNotice.value = null
                _signIns.tryEmit(offline.session.userName)
                signInLog.started(company(), offline.session.userName, offline.session.selected?.warehouseId, signedInOffline = true)
                NetworkResult.Success(offline.session)
            }
            OfflineSignInResult.NotAvailable -> NetworkResult.Error(
                AppFailure.Offline(
                    "$noConnection. Offline sign-in works only after you have signed in on this device while connected.",
                ),
            )
            is OfflineSignInResult.Expired -> NetworkResult.Error(
                AppFailure.Offline(
                    "$noConnection. Your last online sign-in here was more than ${offline.days} days ago. " +
                        "Sign in once while connected.",
                ),
            )
            is OfflineSignInResult.WrongPassword -> NetworkResult.Error(
                AppFailure.Validation(
                    "That user name and PIN do not match. ${offline.attemptsRemaining} " +
                        (if (offline.attemptsRemaining == 1) "try" else "tries") +
                        " left before offline sign-in is turned off for you.",
                ),
            )
            OfflineSignInResult.Cleared -> NetworkResult.Error(
                AppFailure.Offline("Too many wrong PINs. Sign in while connected to D365FO."),
            )
            OfflineSignInResult.ClockBehind -> NetworkResult.Error(
                AppFailure.Offline("$noConnection, and this device's clock is behind. Check the date and time."),
            )
        }
    }

    private companion object {
        private const val KEY_LAST_USER = "last_worker_user_name"
        val LICENSE_REFUSALS = setOf("NO_LICENSE", "LICENSE_INVALID", "LICENSE_EXPIRED", "DEVICE_LIMIT_REACHED")
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

    /**
     * Only the rows the server actually named survive this mapping - an unrecognised `module`
     * string (an older client talking to a newer AOS that adds a fifth workflow some day, or a
     * typo) is dropped rather than crashing the sign-in over a display concern.
     */
    private fun List<ModulePermissionDto>.toEntities(userName: String): List<WorkerPermissionEntity> =
        mapNotNull { dto ->
            runCatching { WarehouseWorkflow.valueOf(dto.module) }.getOrNull()?.let { workflow ->
                WorkerPermissionEntity(userName = userName, workflow = workflow, allowed = dto.isAllowed)
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
    /** Signed in with [com.d365fo.mobileops.data.security.OfflineSignIn] while D365FO couldn't be reached. */
    val signedInOffline: Boolean = false,
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
