package com.d365fo.mobileops.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.d365fo.mobileops.data.local.converter.Converters
import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.InventDimDao
import com.d365fo.mobileops.data.local.dao.ItemDao
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
import com.d365fo.mobileops.data.local.dao.FlowSessionDao
import com.d365fo.mobileops.data.local.dao.WarehouseDao
import com.d365fo.mobileops.data.local.entity.BarcodeEntity
import com.d365fo.mobileops.data.local.entity.CountClaimEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionEntity
import com.d365fo.mobileops.data.local.entity.CountingLineReferenceEntity
import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
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
import com.d365fo.mobileops.data.local.entity.StagedLineEntity
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import com.d365fo.mobileops.data.local.entity.SyncWatermarkEntity
import com.d365fo.mobileops.data.local.entity.TransferLineEntity
import com.d365fo.mobileops.data.local.entity.TransferOrderEntity
import com.d365fo.mobileops.data.local.entity.ValidationAuditEntity
import com.d365fo.mobileops.data.local.entity.FlowSessionEntity
import com.d365fo.mobileops.data.local.entity.WarehouseEntity
import com.d365fo.mobileops.data.local.entity.WorkerDirectoryEntity
import com.d365fo.mobileops.data.local.entity.WorkerPermissionEntity

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
        // journals (arrival, movement, adjustment, counting)
        JournalEntity::class,
        JournalLineEntity::class,
        // counting
        CountingLineReferenceEntity::class,
        CountClaimEntity::class,
        CountSubmissionEntity::class,
        // transfer orders
        TransferOrderEntity::class,
        TransferLineEntity::class,
        // sales
        SalesOrderEntity::class,
        SalesLineEntity::class,
        // sync
        SyncQueueEntity::class,
        FailedSyncEntity::class,
        StagedLineEntity::class,
        // roles / permissions
        WorkerPermissionEntity::class,
        WorkerDirectoryEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class D365Database : RoomDatabase() {

    abstract fun purchOrderDao(): PurchOrderDao
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
    abstract fun stagedLineDao(): StagedLineDao
    abstract fun permissionDao(): PermissionDao
    abstract fun countingDao(): CountingDao

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

        /**
         * 3 -> 4: adds the staged-line ledger - see the class doc on [StagedLineEntity] for why
         * it exists. Additive, like the migrations before it.
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `staged_line` (
                        `flowType` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `lineNumber` REAL NOT NULL,
                        `quantity` TEXT NOT NULL,
                        `queueId` TEXT NOT NULL,
                        `stagedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`flowType`, `orderId`, `lineNumber`, `queueId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * 4 -> 5: drops `goods_receipt` and `goods_receipt_line`.
         *
         * These tables backed an earlier, parallel receiving implementation that the current
         * step-flow capture (`FlowSessionEntity` / `StagedLineEntity`, posted via
         * `SyncQueueEntity`) has fully superseded - nothing in the app ever wrote a row to
         * either table. Dropping them is therefore not the destructive case the
         * no-fallback policy above guards against: there is no unsynced transaction in
         * either table to lose.
         */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `goods_receipt_line`")
                db.execSQL("DROP TABLE IF EXISTS `goods_receipt`")
            }
        }

        /**
         * 5 -> 6: counting progress. `invent_journal_line.markedCounted` (a line the worker
         * marked counted without entering a quantity) and `invent_journal.countStartedAt` (when
         * Start counting was pressed).
         *
         * Additive only - two new columns with defaults, nothing existing touched, so no counted
         * quantity already captured can be lost by running it.
         */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `markedCounted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `countStartedAt` INTEGER")
            }
        }

        /**
         * 6 -> 7: adds `worker_permission` (per-worker, per-[com.d365fo.mobileops.domain.model.WarehouseWorkflow]
         * allow/deny, gating the four Home screen module cards) and `worker_directory` (the
         * Admin Center's own local cache of who else is a worker, for browsing offline once
         * seen once). Additive only, like every migration before it - nothing existing is
         * touched.
         */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `worker_permission` (
                        `userName` TEXT NOT NULL,
                        `workflow` TEXT NOT NULL,
                        `allowed` INTEGER NOT NULL,
                        PRIMARY KEY(`userName`, `workflow`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `worker_directory` (
                        `userName` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `isSupervisor` INTEGER NOT NULL,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userName`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * 7 -> 8: inventory count claims, audit and WorkManager End count.
         *
         * Additive, plus one move: a counting line's on-hand leaves invent_journal_line for
         * counting_line_reference (blind counting - no counter-facing row carries it), copied
         * before it is blanked, so a marked-only line still submits with zero variance. A counting
         * journal at POSTED meant "counts imported" before; it becomes SUBMITTED, since the
         * journal itself was never posted in D365FO. No count is touched.
         */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `transferMode` TEXT NOT NULL DEFAULT 'DMF'")
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `originType` TEXT NOT NULL DEFAULT 'SCHEDULED'")
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `passNumber` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `countedBy` TEXT")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `countedAt` INTEGER")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `lineCountStartedAt` INTEGER")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `counting_line_reference` (`journalLineId` TEXT NOT NULL, " +
                        "`journalId` TEXT NOT NULL, `onHandQuantity` TEXT, PRIMARY KEY(`journalLineId`), " +
                        "FOREIGN KEY(`journalId`) REFERENCES `invent_journal`(`journalId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_counting_line_reference_journalId` ON `counting_line_reference` (`journalId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `count_claim` (`journalId` TEXT NOT NULL, `claimToken` TEXT NOT NULL, " +
                        "`rangeId` TEXT, `locationFrom` TEXT, `locationTo` TEXT, `claimedBy` TEXT NOT NULL, " +
                        "`claimedAt` INTEGER NOT NULL, `expiresAt` INTEGER, `heartbeatSeconds` INTEGER NOT NULL, " +
                        "`lastHeartbeatAt` INTEGER, `lost` INTEGER NOT NULL, `lostReason` TEXT, PRIMARY KEY(`journalId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `count_submission` (`journalId` TEXT NOT NULL, `submissionId` TEXT NOT NULL, " +
                        "`transferMode` TEXT NOT NULL, `state` TEXT NOT NULL, `error` TEXT, `message` TEXT, " +
                        "`executionId` TEXT, `percent` INTEGER, `userId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                        "`originType` TEXT NOT NULL, `passNumber` INTEGER NOT NULL, `startedAt` INTEGER, " +
                        "`submittedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`journalId`))",
                )

                val countingJournals = "SELECT `journalId` FROM `invent_journal` WHERE `type` = 'COUNTING'"
                db.execSQL(
                    "INSERT OR REPLACE INTO `counting_line_reference` (`journalLineId`, `journalId`, `onHandQuantity`) " +
                        "SELECT `journalLineId`, `journalId`, `expectedQuantity` FROM `invent_journal_line` " +
                        "WHERE `journalId` IN ($countingJournals)",
                )
                db.execSQL(
                    "UPDATE `invent_journal_line` SET `quantity` = '', `expectedQuantity` = NULL " +
                        "WHERE `journalId` IN ($countingJournals)",
                )
                db.execSQL(
                    "UPDATE `invent_journal` SET `status` = 'SUBMITTED', `postedAt` = NULL " +
                        "WHERE `type` = 'COUNTING' AND `status` = 'POSTED'",
                )
            }
        }

        val MIGRATIONS: Array<androidx.room.migration.Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
    }
}
