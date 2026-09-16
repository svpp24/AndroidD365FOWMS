package com.d365fo.mobileops.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.d365fo.mobileops.data.local.converter.Converters
import com.d365fo.mobileops.data.local.dao.GoodsReceiptDao
import com.d365fo.mobileops.data.local.dao.InventDimDao
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.dao.LocationDao
import com.d365fo.mobileops.data.local.dao.OnHandDao
import com.d365fo.mobileops.data.local.dao.PurchOrderDao
import com.d365fo.mobileops.data.local.dao.SalesOrderDao
import com.d365fo.mobileops.data.local.dao.SyncQueueDao
import com.d365fo.mobileops.data.local.dao.SyncWatermarkDao
import com.d365fo.mobileops.data.local.dao.TransferOrderDao
import com.d365fo.mobileops.data.local.dao.ValidationAuditDao
import com.d365fo.mobileops.data.local.dao.FlowSessionDao
import com.d365fo.mobileops.data.local.dao.WarehouseDao
import com.d365fo.mobileops.data.local.entity.BarcodeEntity
import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.local.entity.GoodsReceiptEntity
import com.d365fo.mobileops.data.local.entity.GoodsReceiptLineEntity
import com.d365fo.mobileops.data.local.entity.InventDimEntity
import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.JournalEntity
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
import com.d365fo.mobileops.data.local.entity.LocationEntity
import com.d365fo.mobileops.data.local.entity.OnHandCacheEntity
import com.d365fo.mobileops.data.local.entity.PurchLineEntity
import com.d365fo.mobileops.data.local.entity.PurchOrderEntity
import com.d365fo.mobileops.data.local.entity.SalesLineEntity
import com.d365fo.mobileops.data.local.entity.SalesOrderEntity
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import com.d365fo.mobileops.data.local.entity.SyncWatermarkEntity
import com.d365fo.mobileops.data.local.entity.TransferLineEntity
import com.d365fo.mobileops.data.local.entity.TransferOrderEntity
import com.d365fo.mobileops.data.local.entity.ValidationAuditEntity
import com.d365fo.mobileops.data.local.entity.FlowSessionEntity
import com.d365fo.mobileops.data.local.entity.WarehouseEntity

@Database(
    entities = [
        // master data
        ItemEntity::class,
        BarcodeEntity::class,
        LocationEntity::class,
        InventDimEntity::class,
        OnHandCacheEntity::class,
        SyncWatermarkEntity::class,
        WarehouseEntity::class,
        FlowSessionEntity::class,
        ValidationAuditEntity::class,
        // purchase receiving
        PurchOrderEntity::class,
        PurchLineEntity::class,
        GoodsReceiptEntity::class,
        GoodsReceiptLineEntity::class,
        // journals (arrival, movement, adjustment, counting)
        JournalEntity::class,
        JournalLineEntity::class,
        // transfer orders
        TransferOrderEntity::class,
        TransferLineEntity::class,
        // sales
        SalesOrderEntity::class,
        SalesLineEntity::class,
        // sync
        SyncQueueEntity::class,
        FailedSyncEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class D365Database : RoomDatabase() {

    abstract fun purchOrderDao(): PurchOrderDao
    abstract fun goodsReceiptDao(): GoodsReceiptDao
    abstract fun itemDao(): ItemDao
    abstract fun locationDao(): LocationDao
    abstract fun inventDimDao(): InventDimDao
    abstract fun onHandDao(): OnHandDao
    abstract fun journalDao(): JournalDao
    abstract fun transferOrderDao(): TransferOrderDao
    abstract fun salesOrderDao(): SalesOrderDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun syncWatermarkDao(): SyncWatermarkDao
    abstract fun warehouseDao(): WarehouseDao
    abstract fun flowSessionDao(): FlowSessionDao
    abstract fun validationAuditDao(): ValidationAuditDao

    companion object {
        /**
         * Migrations are mandatory, never destructive. A fallback-to-destructive policy
         * would silently discard a warehouse worker's unsynced transactions on upgrade.
         * Master-data tables can be safely re-pulled; transaction tables cannot.
         */
        /**
         * 1 -> 2: adds the warehouse cache.
         *
         * Additive only. Nothing existing is touched, so no captured transaction can be lost
         * by running it - which is the property the no-destructive-fallback policy above
         * exists to protect.
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `warehouse` (
                        `warehouseId` TEXT NOT NULL,
                        `warehouseName` TEXT NOT NULL,
                        `siteId` TEXT NOT NULL,
                        `wmsEnabled` INTEGER NOT NULL,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`warehouseId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_warehouse_siteId` ON `warehouse` (`siteId`)",
                )
            }
        }

        /**
         * 2 -> 3: adds saved work-in-progress for the step flows.
         *
         * Additive, like 1 -> 2. Nothing captured before the upgrade existed on disk to lose;
         * anything captured after it survives one.
         */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flow_session` (
                        `flowType` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `warehouseId` TEXT,
                        `userName` TEXT,
                        `capturedJson` TEXT NOT NULL,
                        `lineCount` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`flowType`, `orderId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_flow_session_updatedAt` ON `flow_session` (`updatedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_flow_session_userName` ON `flow_session` (`userName`)",
                )
            }
        }

        val MIGRATIONS: Array<androidx.room.migration.Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
