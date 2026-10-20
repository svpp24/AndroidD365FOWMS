package com.d365fo.mobileops.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.local.dao.OrderCreationDao
import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.local.entity.OrderKind
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.logging.LogStore
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.FeedbackItemDto
import com.d365fo.mobileops.data.remote.dto.FeedbackRequestBody
import com.d365fo.mobileops.data.remote.dto.FeedbackServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.payload.SendFeedbackPayload
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What a worker is reporting. The names are the wire format (HSMobileOpsFeedbackType). */
enum class FeedbackType(val label: String) { Problem("Problem"), Idea("Idea"), Question("Question") }

/**
 * The screen feedback was started from, and its screenshot - handed from the top bar's
 * feedback button to the feedback screen (see FeedbackButton). Consume-once, like
 * PendingFlowResume: opening feedback from Settings later must not pick up an old screenshot.
 */
@Singleton
class FeedbackOrigin @Inject constructor(@ApplicationContext private val context: Context) {
    private var screen: String? = null
    private var screenshot: File? = null

    /** Saves [bitmap] (scaled down, JPEG) as the pending screenshot for feedback about [screenName]. */
    suspend fun capture(screenName: String, bitmap: Bitmap?) = withContext(Dispatchers.IO) {
        screen = screenName
        screenshot = bitmap?.let { full ->
            val scale = minOf(1f, 1080f / full.width)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true) else full
            File(context.cacheDir, "feedback").apply { mkdirs() }.resolve("pending.jpg").also { file ->
                file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            }
        }
    }

    fun consume(): Pair<String?, File?> = (screen to screenshot).also {
        screen = null
        screenshot = null
    }
}

/** One piece of feedback as the worker sees it again. */
data class FeedbackEntry(
    val id: String,
    val sentAt: String,
    val screen: String,
    val type: String,
    val message: String,
    /** New, In progress, Done, Won't do - or Waiting / Not sent for one still on the device. */
    val status: String,
    val reply: String,
    val waiting: Boolean,
)

/**
 * Feedback from the app: saved and queued like an order (so it survives being offline) and
 * sent at once when D365FO answers; HSMobileOpsFeedbackSvc records it and attaches the
 * screenshot. The worker's sent feedback, with the team's status and reply, comes back from
 * listMyFeedback.
 */
@Singleton
class FeedbackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val dao: OrderCreationDao,
    private val orders: OrderCreationRepository,
    private val logStore: LogStore,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
) {

    val appVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val deviceModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Saves and queues the feedback; returns its id. The screenshot is moved out of the cache. */
    suspend fun submit(
        userName: String,
        warehouseId: String,
        screen: String,
        type: FeedbackType,
        message: String,
        screenshot: File?,
    ): String {
        val id = UUID.randomUUID().toString()
        val kept = screenshot?.takeIf { it.isFile }?.let { file ->
            withContext(Dispatchers.IO) {
                File(context.filesDir, "feedback").apply { mkdirs() }.resolve("$id.jpg").also { file.copyTo(it, overwrite = true) }
            }
        }
        val payload = SendFeedbackPayload(
            idempotencyKey = id,
            dataAreaId = companyProvider().orEmpty(),
            userName = userName,
            deviceId = secureStore.deviceId(),
            warehouseId = warehouseId,
            appVersion = appVersion,
            deviceModel = deviceModel,
            screen = screen,
            feedbackType = type.name,
            message = message.trim(),
            logs = recentLogs(),
            screenshotPath = kept?.absolutePath,
        )
        val row = CreatedOrderEntity(
            id = id,
            kind = OrderKind.FEEDBACK,
            status = CreatedOrderStatus.WAITING,
            documentNumber = screen,
            title = "${type.label}: ${message.trim()}",
            lineCount = 0,
            userName = userName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        orders.queueAndSend(row, SyncOperation.SEND_FEEDBACK, "feedback", payload, userName, null)
        return id
    }

    /** Feedback still on this device (waiting, or refused) - D365FO's list covers the rest. */
    fun observeUnsent(userName: String): Flow<List<FeedbackEntry>> = dao.observeOrders(OrderKind.FEEDBACK).map { rows ->
        rows.filter { it.userName == userName && it.status != CreatedOrderStatus.CREATED }.map { row ->
            FeedbackEntry(
                id = row.id,
                sentAt = TIME.format(java.time.Instant.ofEpochMilli(row.createdAt)),
                screen = row.documentNumber.orEmpty(),
                type = row.title.substringBefore(':'),
                message = row.title.substringAfter(": "),
                status = if (row.status == CreatedOrderStatus.FAILED) "Not sent" else "Waiting to send",
                reply = row.message.orEmpty(),
                waiting = true,
            )
        }
    }

    /** The worker's feedback as D365FO has it, newest first. */
    suspend fun mine(userName: String): NetworkResult<List<FeedbackEntry>> {
        val request = FeedbackServiceRequest(FeedbackRequestBody(dataAreaId = companyProvider().orEmpty(), userName = userName, take = 50))
        return when (val result = errorMapper.safeCall { api.listMyFeedback(request) }) {
            is NetworkResult.Success ->
                if (result.data.success) NetworkResult.Success(result.data.items.map { it.toEntry() })
                else NetworkResult.Error(AppFailure.BusinessRule(result.data.message.ifBlank { "Could not read your feedback" }))
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /** Changes feedback already in D365FO - only while its status is New (online only). */
    suspend fun update(userName: String, id: String, type: FeedbackType, message: String): NetworkResult<Unit> {
        val request = FeedbackServiceRequest(
            FeedbackRequestBody(
                dataAreaId = companyProvider().orEmpty(),
                userName = userName,
                feedbackId = id,
                feedbackType = type.name,
                message = message.trim(),
            ),
        )
        return when (val result = errorMapper.safeCall { api.updateFeedback(request) }) {
            is NetworkResult.Success ->
                if (result.data.success) NetworkResult.Success(Unit)
                else NetworkResult.Error(AppFailure.BusinessRule(result.data.message.ifBlank { "Could not change the feedback" }))
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /** The last lines of the app's own log - what was happening when the worker wrote. */
    private fun recentLogs(): String =
        logStore.entries.value.takeLast(60).joinToString("\n") { e ->
            "${LOG_TIME.format(e.timestamp)} ${e.levelLabel} ${e.tag}: ${e.message}" + (e.throwable?.let { "\n$it" } ?: "")
        }.takeLast(12_000)

    private fun FeedbackItemDto.toEntry() = FeedbackEntry(
        id = feedbackId,
        sentAt = submittedUtc.let { raw ->
            runCatching { TIME.format(java.time.Instant.parse(raw.replace(' ', 'T').let { if (it.endsWith("Z")) it else it + "Z" })) }.getOrDefault(raw)
        },
        screen = screen,
        type = feedbackType,
        message = message,
        status = when (status) {
            "InProgress" -> "In progress"
            "WontDo" -> "Won't do"
            else -> status
        },
        reply = response,
        waiting = false,
    )

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())
        val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
