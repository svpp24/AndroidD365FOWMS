package com.d365fo.mobileops.di

import android.content.Context
import androidx.room.Room
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.InventDimDao
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.local.dao.FlowSessionDao
import com.d365fo.mobileops.data.local.dao.WarehouseDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.dao.LocationDao
import com.d365fo.mobileops.data.local.dao.OnHandDao
import com.d365fo.mobileops.data.local.dao.PermissionDao
import com.d365fo.mobileops.data.local.dao.PurchOrderDao
import com.d365fo.mobileops.data.local.dao.SalesOrderDao
import com.d365fo.mobileops.data.local.dao.StagedLineDao
import com.d365fo.mobileops.data.local.dao.SyncQueueDao
import com.d365fo.mobileops.data.local.dao.SyncWatermarkDao
import com.d365fo.mobileops.data.local.dao.TransferOrderDao
import com.d365fo.mobileops.data.local.dao.ValidationAuditDao
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.util.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        secureStore: SecureStore,
    ): D365Database = Room
        .databaseBuilder(context, D365Database::class.java, Constants.DATABASE_NAME)
        // SQLCipher with a Keystore-held passphrase. A handheld left in a yard is a
        // realistic threat, and the database holds vendor prices, order books and worker
        // identity — none of which should be readable by pulling the file off the device.
        .openHelperFactory(SupportFactory(secureStore.databasePassphrase()))
        .addMigrations(*D365Database.MIGRATIONS)
        // No fallbackToDestructiveMigration. Wiping the database on a version bump would
        // silently discard captured, unposted transactions — the one thing this app must
        // never lose. A missing migration should fail loudly in CI, not quietly on a dock.
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

    @Provides fun providePurchOrderDao(db: D365Database): PurchOrderDao = db.purchOrderDao()
    @Provides fun provideItemDao(db: D365Database): ItemDao = db.itemDao()
    @Provides fun provideWarehouseDao(db: D365Database): WarehouseDao = db.warehouseDao()
    @Provides fun provideFlowSessionDao(db: D365Database): FlowSessionDao = db.flowSessionDao()
    @Provides fun provideLocationDao(db: D365Database): LocationDao = db.locationDao()
    @Provides fun provideInventDimDao(db: D365Database): InventDimDao = db.inventDimDao()
    @Provides fun provideOnHandDao(db: D365Database): OnHandDao = db.onHandDao()
    @Provides fun provideJournalDao(db: D365Database): JournalDao = db.journalDao()
    @Provides fun provideTransferOrderDao(db: D365Database): TransferOrderDao = db.transferOrderDao()
    @Provides fun provideSalesOrderDao(db: D365Database): SalesOrderDao = db.salesOrderDao()
    @Provides fun provideSyncQueueDao(db: D365Database): SyncQueueDao = db.syncQueueDao()
    @Provides fun provideWatermarkDao(db: D365Database): SyncWatermarkDao = db.syncWatermarkDao()
    @Provides fun provideAuditDao(db: D365Database): ValidationAuditDao = db.validationAuditDao()
    @Provides fun provideStagedLineDao(db: D365Database): StagedLineDao = db.stagedLineDao()
    @Provides fun providePermissionDao(db: D365Database): PermissionDao = db.permissionDao()
    @Provides fun provideCountingDao(db: D365Database): CountingDao = db.countingDao()
}
