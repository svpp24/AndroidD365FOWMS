package com.d365fo.mobileops.repository

import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import retrofit2.Response as RetrofitResponse
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException

class D365ErrorMapperTest {

    private val mapper = D365ErrorMapper(Json { ignoreUnknownKeys = true })

    private fun errorResponse(code: Int, body: String = "{}"): RetrofitResponse<Unit> =
        RetrofitResponse.error(
            body.toResponseBody("application/json".toMediaType()),
            Response.Builder()
                .code(code)
                .message("error")
                .protocol(Protocol.HTTP_1_1)
                .request(Request.Builder().url("https://aos.example.com/data/Test").build())
                .build(),
        )

    @Test
    fun `an unknown host is offline, not an error the worker should see`() {
        val failure = mapper.fromThrowable(UnknownHostException("no dns"))

        assertThat(failure).isInstanceOf(AppFailure.Offline::class.java)
        assertThat(failure.retryable).isTrue()
    }

    @Test
    fun `a timeout is transient and retryable`() {
        val failure = mapper.fromThrowable(SocketTimeoutException("timeout"))

        assertThat(failure).isInstanceOf(AppFailure.Transient::class.java)
        assertThat(failure.retryable).isTrue()
    }

    @Test
    fun `a pinning failure is fatal and never retried`() {
        // Retrying a chain we do not trust only repeats the exposure.
        val failure = mapper.fromThrowable(SSLPeerUnverifiedException("pin mismatch"))

        assertThat(failure).isInstanceOf(AppFailure.Fatal::class.java)
        assertThat(failure.retryable).isFalse()
    }

    @ParameterizedTest(name = "HTTP {0} is retryable = {1}")
    @CsvSource(
        "401, false",
        "403, false",
        "400, false",
        "404, false",
        "409, false",
        "412, false",
        "429, true",
        "500, true",
        "503, true",
    )
    fun `classifies http status codes by whether a retry could ever succeed`(
        code: Int,
        retryable: Boolean,
    ) {
        val failure = mapper.fromResponse(errorResponse(code))

        assertThat(failure.retryable).isEqualTo(retryable)
    }

    @Test
    fun `extracts the deepest X++ message from a nested OData error`() {
        // D365FO buries the message a validation actually wrote several levels down; the
        // outer ones are generic wrappers that tell a worker nothing.
        val body = """
            {
              "error": {
                "code": "BadRequest",
                "message": "An error has occurred.",
                "innererror": {
                  "message": "Write failed.",
                  "type": "Microsoft.Dynamics.Ax.Xpp.ErrorException",
                  "internalexception": {
                    "message": "Serial number SN-123 already exists for item ITEM-001."
                  }
                }
              }
            }
        """.trimIndent()

        val failure = mapper.fromResponse(errorResponse(400, body)) as AppFailure.BusinessRule

        assertThat(failure.message).contains("Serial already registered")
        assertThat(failure.d365ErrorCode).isEqualTo("BadRequest")
    }

    @Test
    fun `rewrites a closed period into something a warehouse worker can act on`() {
        val body = """
            {"error":{"code":"BadRequest","message":"The fiscal period is closed for this date."}}
        """.trimIndent()

        val failure = mapper.fromResponse(errorResponse(400, body)) as AppFailure.BusinessRule

        assertThat(failure.message).contains("ask a supervisor")
        assertThat(failure.retryable).isFalse()
    }

    @Test
    fun `passes an unrecognised server message through rather than replacing it`() {
        // A vague generic message is worse than an unfamiliar precise one — the precise one
        // can at least be searched for or read out over the radio.
        val body = """{"error":{"code":"X","message":"Ledger dimension 4021 is invalid."}}"""

        val failure = mapper.fromResponse(errorResponse(400, body)) as AppFailure.BusinessRule

        assertThat(failure.message).isEqualTo("Ledger dimension 4021 is invalid.")
    }

    @Test
    fun `carries Retry-After through on a 429`() {
        val response = RetrofitResponse.error<Unit>(
            "{}".toResponseBody("application/json".toMediaType()),
            Response.Builder()
                .code(429)
                .message("throttled")
                .protocol(Protocol.HTTP_1_1)
                .header("Retry-After", "120")
                .request(Request.Builder().url("https://aos.example.com/data/Test").build())
                .build(),
        )

        val failure = mapper.fromResponse(response) as AppFailure.Transient

        assertThat(failure.retryAfterSeconds).isEqualTo(120)
    }

    @Test
    fun `safeCall wraps a thrown transport exception instead of letting it escape`() = runTest {
        val result = mapper.safeCall<Unit> { throw UnknownHostException("no dns") }

        assertThat(result).isInstanceOf(NetworkResult.Error::class.java)
        assertThat((result as NetworkResult.Error).failure)
            .isInstanceOf(AppFailure.Offline::class.java)
    }
}
