package com.d365fo.mobileops.data.remote.interceptor

import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.TokenProvider
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the bearer token and the D365FO company context to every request.
 *
 * [runBlocking] is deliberate: OkHttp interceptors are synchronous by contract, and this
 * runs on OkHttp's own dispatcher thread, never on the main thread. The token provider
 * serialises refreshes internally, so a burst of parallel calls blocks on one refresh
 * rather than triggering many.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val companyProvider: CompanyProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenProvider.acquireSilent() }

        // Fail fast rather than sending an unauthenticated request.
        //
        // Previously a failed token acquisition just omitted the header and let the call go.
        // D365FO answers that with a 404 and "No route data was found" after ~38 seconds —
        // a message that points at the URL when the actual problem is that nobody is signed
        // in. Refusing here turns a 38-second red herring into an immediate, accurate error.
        if (token !is NetworkResult.Success) {
            val failure = (token as? NetworkResult.Error)?.failure
            // No connection to Azure AD: an outage, not a sign-in problem, so callers can fall
            // back to what works offline.
            if (failure is AppFailure.Offline || failure is AppFailure.Transient) {
                throw TokenUnreachableException(failure.message)
            }
            throw NotAuthenticatedException("Not signed in to D365FO (${failure?.message ?: "not signed in"})")
        }

        val request = chain.request().newBuilder().apply {
            header("Authorization", "Bearer ${token.data.value}")
            header("Accept", "application/json")
            header("OData-MaxVersion", "4.0")
            header("OData-Version", "4.0")
            // Legal entity. Without it D365FO resolves the user's default company, which is
            // not necessarily the one the handheld is operating in.
            companyProvider()?.let { header("X-Company", it) }
        }.build()

        val response = chain.proceed(request)

        // A 401 here means the token was rejected rather than merely stale. Retrying with
        // the same silent token would loop, so surface it and let the session layer force
        // re-authentication.
        return response
    }
}

/**
 * Thrown by [AuthInterceptor] when no token is available.
 *
 * Extends IOException because OkHttp contractually only allows an interceptor to throw
 * IOException — anything else escapes as an undeclared exception and crashes the call
 * instead of being handled. D365ErrorMapper maps it to AppFailure.Authentication.
 */
class NotAuthenticatedException(message: String) : java.io.IOException(message)

/** Thrown by [AuthInterceptor] when Azure AD can't be reached for a token. */
class TokenUnreachableException(message: String) : java.io.IOException(message)
