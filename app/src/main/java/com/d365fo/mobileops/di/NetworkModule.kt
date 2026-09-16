package com.d365fo.mobileops.di

import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.remote.api.AadTokenApi
import com.d365fo.mobileops.data.remote.interceptor.AosReachabilityInterceptor
import com.d365fo.mobileops.data.remote.interceptor.AuthInterceptor
import com.d365fo.mobileops.data.remote.interceptor.CrossCompanyInterceptor
import com.d365fo.mobileops.data.remote.interceptor.RateLimitInterceptor
import com.d365fo.mobileops.data.remote.interceptor.RetryAfterInterceptor
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.D365Environment
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.NetworkPreferences
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.util.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import okhttp3.CertificatePinner
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ODataClient

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ServiceClient

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AadClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true          // D365FO entities carry far more than we request
        explicitNulls = false             // omit nulls rather than sending them to X++
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
        classDiscriminator = "type"
        serializersModule = SerializersModule { }
    }

    /**
     * Legal entity for the X-Company header and for every service request body.
     *
     * Falls back to the build's configured company. The stored value is what a device
     * registration flow would set per device; until one exists, a null here means every call
     * goes out with an empty company and the service answers "No legal entity" — which reads
     * like a broken deployment rather than like missing configuration.
     */
    @Provides
    @Singleton
    fun provideCompanyProvider(secureStore: SecureStore): CompanyProvider =
        CompanyProvider {
            secureStore.getString("worker_data_area")?.takeIf { it.isNotBlank() }
                ?: BuildConfig.D365_COMPANY.takeIf { it.isNotBlank() }
        }

    /**
     * Pins the AOS certificate chain.
     *
     * Pinned by public key hash rather than by certificate: Microsoft rotates the leaf
     * certificate on managed D365FO environments without notice, and pinning the certificate
     * itself would take a warehouse offline mid-shift. Two pins are configured so a planned
     * key rotation can be staged ahead of the cutover.
     *
     * Disabled in debug builds so a sandbox can be inspected with a proxy.
     */
    @Provides
    @Singleton
    fun provideCertificatePinner(
        connectionSettings: ConnectionSettings,
    ): CertificatePinner {
        if (!BuildConfig.CERT_PINNING_ENABLED) return CertificatePinner.DEFAULT

        // Hosts come from both the compiled environments and whatever the operator typed on
        // the device, because either may be the one in use.
        //
        // CAVEAT: OkHttp's CertificatePinner is immutable and this client is a singleton, so
        // pinning is fixed for the process lifetime. Changing environment in Settings does
        // not re-pin until the app is restarted. Acceptable because switching environment
        // already clears local data and signs the worker out, but it is a real constraint
        // and not an accident.
        val hosts = buildSet {
            D365Environment.entries.map { it.host }.filter { it.isNotBlank() }.forEach(::add)
            connectionSettings.tenant.value.host.takeIf { it.isNotBlank() }?.let(::add)
        }

        val builder = CertificatePinner.Builder()
        hosts.forEach { host ->
            builder.add(host, BuildConfig.CERT_PIN_PRIMARY)
            builder.add(host, BuildConfig.CERT_PIN_BACKUP)
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        aosReachabilityInterceptor: AosReachabilityInterceptor,
        authInterceptor: AuthInterceptor,
        rateLimitInterceptor: RateLimitInterceptor,
        retryAfterInterceptor: RetryAfterInterceptor,
        crossCompanyInterceptor: CrossCompanyInterceptor,
        certificatePinner: CertificatePinner,
        networkPreferences: NetworkPreferences,
    ): OkHttpClient = OkHttpClient.Builder()
        .certificatePinner(certificatePinner)
        // Mirrors the semaphore in RateLimitInterceptor so OkHttp does not hold sockets
        // open for calls that are queued behind it anyway.
        .dispatcher(
            Dispatcher().apply {
                maxRequests = Constants.MAX_CONCURRENT_REQUESTS
                maxRequestsPerHost = Constants.MAX_CONCURRENT_REQUESTS
            },
        )
        // Added first so it wraps every other AOS interceptor - see its own class doc for
        // why that ordering is what makes it count one outcome per logical request.
        .addInterceptor(aosReachabilityInterceptor)
        .addInterceptor(rateLimitInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(retryAfterInterceptor)
        .addInterceptor(crossCompanyInterceptor)
        .apply {
            // HEADERS, not BODY.
            //
            // BODY writes the whole response to logcat before the interceptor chain returns,
            // one line at a time. A 15 KB on-hand response cost **29 seconds** on the handheld
            // for a call the server answered in 117 ms — measured, not guessed: the
            // `server-timing` header said 117 ms while OkHttp reported 29494 ms for the same
            // response.
            //
            // That is not a logging overhead, it is a different application. And it is
            // invisible in an emulator on a workstation, so it survives right up until someone
            // tries the app on the device it is for.
            //
            // `server-timing` carries what a body dump was being used for anyway: where the
            // time actually went. Raise this to BODY deliberately, for one debugging session,
            // and put it back.
            //
            // The bearer token is redacted even so. It is valid for an hour against the whole
            // tenant, and logcat is readable over adb by anyone with the handheld in their
            // hands — which, in a warehouse, is everyone.
            //
            // debugHttpLoggingInterceptor itself is a debug/release source-set pair, not a
            // runtime branch here: okhttp-logging-interceptor is debugImplementation-only (see
            // build.gradle.kts), so the class isn't even on release's classpath - a plain `if`
            // referencing HttpLoggingInterceptor directly here would fail to compile release at
            // all, not just skip it at runtime.
            if (BuildConfig.ALLOW_SENSITIVE_LOGGING) {
                debugHttpLoggingInterceptor("Authorization")?.let(::addInterceptor)
            }
        }
        // Warehouse Wi-Fi roams between access points; a short connect timeout produces
        // spurious failures during a handover, and a long read timeout lets a form-letter
        // posting finish rather than being abandoned halfway through. Connect stays fixed -
        // it's about the handover, not a slow server. Read/write come from the Settings
        // "Network" picker (see NetworkPreferences) and, like CertificatePinner above, are
        // read once here at provide-time: a changed setting takes effect on the next app
        // start, not immediately - this client is a Dagger singleton, built once.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(networkPreferences.timeout.value.seconds, TimeUnit.SECONDS)
        .writeTimeout(networkPreferences.timeout.value.seconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
        .also { Timber.i("OkHttp client built with request timeout = ${networkPreferences.timeout.value.seconds}s") }


    /**
     * A bare client for the Azure AD token endpoint.
     *
     * Deliberately not the shared OkHttpClient: that one carries [AuthInterceptor], which
     * asks the token provider for a token — so using it to *fetch* a token would recurse
     * until the stack gave out. It also skips certificate pinning, because the pins are for
     * the AOS host and login.microsoftonline.com is a different chain entirely.
     */
    @Provides
    @Singleton
    @AadClient
    fun provideAadOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            // HEADERS, never BODY: the request body carries the client secret, and a secret
            // in logcat is a secret on every crash report and bug bundle. The response headers
            // are safe, but the response *body* is the access token, so this must never be
            // raised to BODY either. See the identical note on the main client for why this
            // goes through debugHttpLoggingInterceptor rather than constructing
            // HttpLoggingInterceptor directly.
            if (BuildConfig.ALLOW_SENSITIVE_LOGGING) {
                debugHttpLoggingInterceptor("Authorization")?.let(::addInterceptor)
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideAadTokenApi(
        @AadClient client: OkHttpClient,
        json: Json,
    ): AadTokenApi = Retrofit.Builder()
        .baseUrl("https://login.microsoftonline.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AadTokenApi::class.java)

    // D365ODataApi and MobileOpsServiceApi are NOT provided here.
    //
    // They are bound in ApiModule and AppModule respectively, built from the OkHttpClient and
    // Json above. Kept apart from this module because they are the one thing that changes with
    // the tenant configured on the device — see D365RetrofitFactory in LiveApis.kt.
}
