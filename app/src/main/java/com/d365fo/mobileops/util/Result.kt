package com.d365fo.mobileops.util

/**
 * Every network and repository boundary returns this. Nothing throws through to a ViewModel.
 */
sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T, val eTag: String? = null) : NetworkResult<T>
    data class Error(val failure: AppFailure) : NetworkResult<Nothing>
    data object Loading : NetworkResult<Nothing>

    val successOrNull: T? get() = (this as? Success)?.data
}

inline fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> = when (this) {
    is NetworkResult.Success -> NetworkResult.Success(transform(data), eTag)
    is NetworkResult.Error -> this
    NetworkResult.Loading -> NetworkResult.Loading
}

inline fun <T> NetworkResult<T>.onSuccess(block: (T) -> Unit): NetworkResult<T> =
    also { if (this is NetworkResult.Success) block(data) }

inline fun <T> NetworkResult<T>.onError(block: (AppFailure) -> Unit): NetworkResult<T> =
    also { if (this is NetworkResult.Error) block(failure) }

/**
 * Classified failure. [retryable] drives the sync queue: retryable failures go back on the
 * queue with backoff, non-retryable ones land in the dead-letter queue for a supervisor.
 */
sealed class AppFailure(
    open val message: String,
    val retryable: Boolean,
) {
    /** No usable connection. Never surfaced as an error during data capture — only during pull. */
    data class Offline(override val message: String = "offline") : AppFailure(message, retryable = true)

    /** Socket/read timeout, 5xx, 429. */
    data class Transient(
        override val message: String,
        val httpCode: Int? = null,
        val retryAfterSeconds: Long? = null,
    ) : AppFailure(message, retryable = true)

    /** Token expired or refused. Forces re-authentication. */
    data class Authentication(override val message: String) : AppFailure(message, retryable = false)

    /** 412 / ETag mismatch — the server record moved under us. */
    data class Conflict(
        override val message: String,
        val entity: String,
        val serverETag: String? = null,
    ) : AppFailure(message, retryable = false)

    /** D365FO refused the posting: validation error, closed period, blocked item. */
    data class BusinessRule(
        override val message: String,
        val d365ErrorCode: String? = null,
        val d365Detail: String? = null,
        /**
         * The HTTP status this was mapped from, when there was one.
         *
         * Carried because 404 is ambiguous on this API and the ambiguity matters: against an
         * entity it means the record is gone, but against a custom-service path it means the
         * service is not deployed on this environment at all. Only the caller can tell those
         * apart, and it cannot without the code. Null when the failure never touched HTTP.
         */
        val httpCode: Int? = null,
    ) : AppFailure(message, retryable = false)

    /** Client-side validation caught it before the wire. */
    data class Validation(override val message: String, val field: String? = null) :
        AppFailure(message, retryable = false)

    /** DB corruption, serialization failure, anything unclassified. */
    data class Fatal(override val message: String, val cause: Throwable? = null) :
        AppFailure(message, retryable = false)
}
