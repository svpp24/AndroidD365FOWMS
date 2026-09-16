package com.d365fo.mobileops.data.remote.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds `cross-company=true` to every OData read.
 *
 * Without it D365FO serves only the *authenticating account's default legal entity* and
 * silently ignores the `X-Company` header. On this tenant that meant a device configured for
 * USMF was reading INMF purchase orders — a worklist that looked entirely plausible and would
 * have posted receipts into the wrong company.
 *
 * Applied as an interceptor rather than a parameter on each of a dozen API methods: one place
 * to be right, and no way for a newly added query to forget it. The companion half of the fix
 * lives in the repositories, which must still pin `dataAreaId` in their `$filter` — cross-
 * company on its own widens the result to *every* company rather than selecting one.
 *
 * Scoped to `/data/` paths so it never touches the custom AOS service endpoints, where the
 * parameter is meaningless.
 */
@Singleton
class CrossCompanyInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        val isODataRead = request.method == "GET" &&
            url.encodedPath.contains("/data/") &&
            url.queryParameter(PARAM) == null

        if (!isODataRead) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .url(url.newBuilder().addQueryParameter(PARAM, "true").build())
                .build(),
        )
    }

    private companion object {
        const val PARAM = "cross-company"
    }
}
