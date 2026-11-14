package com.d365fo.mobileops.di

import android.content.Context
import com.d365fo.mobileops.data.repository.CachedLookupRepository
import com.d365fo.mobileops.data.repository.OnDemandLookupRepositoryImpl
import com.d365fo.mobileops.data.repository.ServiceLookupRepository
import com.d365fo.mobileops.data.remote.api.LiveMobileOpsServiceApi
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.logging.LogFilePersistence
import com.d365fo.mobileops.data.repository.WorkerContextRepositoryImpl
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.security.TokenProvider
import com.d365fo.mobileops.data.sync.ConnectivityMonitor
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.domain.repository.WorkerContextRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Injected rather than referenced directly so tests can substitute a test dispatcher and
     * assert on repository behaviour without a real thread pool.
     */
    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Application-scoped coroutine scope for work that must outlive any screen — the sync
     * status pipeline in particular. SupervisorJob so one failing collector cannot cancel
     * the others.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(
        dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideSecureStore(@ApplicationContext context: Context): SecureStore =
        SecureStore(context)

    @Provides
    @Singleton
    fun provideLogFilePersistence(@ApplicationContext context: Context): LogFilePersistence =
        LogFilePersistence(context)

    @Provides
    @Singleton
    fun provideConnectivityMonitor(@ApplicationContext context: Context): ConnectivityMonitor =
        ConnectivityMonitor(context)

    @Provides
    @Singleton
    fun provideSyncScheduler(@ApplicationContext context: Context): SyncScheduler =
        SyncScheduler(context)

    @Provides
    @Singleton
    fun provideWorkerContextRepositoryImpl(
        secureStore: SecureStore,
        tokenProvider: TokenProvider,
        workerSession: WorkerSessionRepository,
    ): WorkerContextRepositoryImpl =
        WorkerContextRepositoryImpl(secureStore, tokenProvider, workerSession)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /** The device key and token service for a device setup code, else client credentials - see RoutingTokenProvider. */
    @Binds
    @Singleton
    abstract fun bindTokenProvider(impl: com.d365fo.mobileops.data.security.RoutingTokenProvider): TokenProvider

    /**
     * Three layers, outermost first: cache, then the AOS service, then OData.
     *
     * CachedLookupRepository answers what it can from Room and delegates the rest;
     * ServiceLookupRepository prefers the custom AOS service and falls back to OData per call
     * when the model is not deployed on the target environment. Both stay injectable as
     * concrete types because each holds the next one as a delegate.
     */
    @Binds
    @Singleton
    abstract fun bindOnDemandLookupRepository(
        impl: CachedLookupRepository,
    ): OnDemandLookupRepository

    @Binds
    @Singleton
    abstract fun bindMobileOpsServiceApi(impl: LiveMobileOpsServiceApi): MobileOpsServiceApi

    @Binds
    @Singleton
    abstract fun bindWorkerContextRepository(
        impl: WorkerContextRepositoryImpl,
    ): WorkerContextRepository
}
