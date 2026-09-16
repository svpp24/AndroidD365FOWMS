package com.d365fo.mobileops.data.remote.error

import com.d365fo.mobileops.data.remote.dto.ODataErrorEnvelope
import com.d365fo.mobileops.data.remote.interceptor.NotAuthenticatedException
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Turns every possible D365FO failure into a classified [AppFailure].
 *
 * The classification matters more than the message: [AppFailure.retryable] is what decides
 * whether a queued product receipt is retried with backoff or parked in the dead-letter
 * queue for a supervisor. Getting it wrong either spams AAD with doomed retries or strands
 * a recoverable timeout in front of a human.
 */
@Singleton
class D365ErrorMapper @Inject constructor(
    private val json: Json,
) {

    /** Wraps a Retrofit call, mapping both HTTP failures and thrown transport exceptions. */
    suspend fun <T> safeCall(block: suspend () -> Response<T>): NetworkResult<T> = try {
        val response = block()
        if (response.isSuccessful) {
            val body = response.body()
            if (body == null) {
                NetworkResult.Error(AppFailure.Fatal("empty response body"))
            } else {
                NetworkResult.Success(body, response.headers()["ETag"])
            }
        } else {
            NetworkResult.Error(fromResponse(response))
        }
    } catch (e: Throwable) {
        NetworkResult.Error(fromThrowable(e))
    }

    fun fromThrowable(e: Throwable): AppFailure = when (e) {
        is UnknownHostException -> AppFailure.Offline()
        is SocketTimeoutException -> AppFailure.Transient("request timed out")
        // A pinning failure is never retried: it means the chain we got is not the one we
        // trust, and retrying only repeats the exposure.
        is SSLPeerUnverifiedException ->
            AppFailure.Fatal("certificate pinning failure — refusing to continue", e)
        // Checked before IOException: NotAuthenticatedException *is* an IOException, and
        // classifying it as Transient would retry a request that can never succeed until a
        // human signs in.
        is NotAuthenticatedException -> AppFailure.Authentication(e.message ?: "not signed in")
        is IOException -> AppFailure.Transient(e.message ?: "network error")
        else -> AppFailure.Fatal(e.message ?: "unexpected error", e)
    }

    fun <T> fromResponse(response: Response<T>): AppFailure {
        val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
        val parsed = rawBody?.let { parseODataError(it) }
        val serverMessage = parsed?.second
        val serverCode = parsed?.first

        return when (response.code()) {
            401, 403 -> AppFailure.Authentication(
                serverMessage ?: "not authorised for this D365FO environment",
            )

            404 -> AppFailure.BusinessRule(
                serverMessage ?: "the record no longer exists in D365FO",
                d365ErrorCode = serverCode,
                httpCode = 404,
            )

            409, 412 -> AppFailure.Conflict(
                serverMessage ?: "the record changed in D365FO",
                entity = response.raw().request.url.pathSegments.lastOrNull() ?: "unknown",
                serverETag = response.headers()["ETag"],
            )

            // 400 from D365FO is almost always an X++ validation failure, not a malformed
            // request. Retrying it is pointless — a human has to change something.
            400, 422 -> AppFailure.BusinessRule(
                serverMessage?.let(::humanise) ?: "D365FO rejected this posting",
                d365ErrorCode = serverCode,
                d365Detail = rawBody?.take(MAX_DETAIL_CHARS),
            )

            429 -> AppFailure.Transient(
                "D365FO is throttling requests",
                httpCode = 429,
                retryAfterSeconds = response.headers()["Retry-After"]?.toLongOrNull(),
            )

            in 500..599 -> AppFailure.Transient(
                serverMessage ?: "D365FO is unavailable",
                httpCode = response.code(),
            )

            else -> AppFailure.Fatal(
                serverMessage ?: "unexpected response ${response.code()}",
            )
        }
    }

    private fun parseODataError(body: String): Pair<String?, String?>? = runCatching {
        val envelope = json.decodeFromString<ODataErrorEnvelope>(body)
        val error = envelope.error ?: return@runCatching null
        error.code to (error.innererror?.deepestMessage() ?: error.message)
    }.getOrNull()

    /**
     * D365FO messages are written for a finance user at a desktop. A few common ones are
     * rewritten for someone holding a scanner in front of a pallet; the rest pass through,
     * because a vague generic message is worse than an unfamiliar precise one.
     */
    private fun humanise(serverMessage: String): String = when {
        serverMessage.containsAny("period", "closed", "not open") ->
            "The accounting period is closed in D365FO. Ask your supervisor to reopen it."

        serverMessage.containsAny("already been posted", "already received") ->
            "This order was already received in D365FO. Refresh to see the current quantities."

        serverMessage.containsAny("on hold", "stopped", "blocked") ->
            "This item or vendor is blocked in D365FO."

        serverMessage.contains("serial", ignoreCase = true) &&
            serverMessage.containsAny("exists", "duplicate") ->
            "That serial number is already registered in D365FO."

        serverMessage.containsAny("batch", "shelf life") && serverMessage.contains("expired", true) ->
            "That batch is expired in D365FO."

        // Specifically *inventory* dimensions. A bare "dimension" match also swallowed
        // "Ledger dimension 4021 is invalid", rewriting a finance error into warehouse
        // advice that sent the worker looking at a location field for no reason.
        serverMessage.containsAny("InventDim", "inventory dimension") ->
            "Inventory dimensions are incomplete for this line. Check site, warehouse and location."

        else -> serverMessage
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }

    private companion object {
        const val MAX_DETAIL_CHARS = 2_000
    }
}
