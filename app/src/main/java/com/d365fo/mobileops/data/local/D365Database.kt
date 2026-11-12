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
import com.d365fo.mobileops.data.local.entity.CountExceptionEntity
import com.d365fo.mobileops.data.local.entity.CountLineAttemptEntity
import com.d365fo.mobileops.data.local.entity.CountLocationCheckEntity
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
import com.d365fo.mobileops.data.local.entity.ReadCacheEntity
import com.d365fo.mobileops.data.local.dao.ReadCacheDao
import com.d365fo.mobileops.data.local.entity.WarehouseEntity
import com.d365fo.mobileops.data.local.entity.WorkerDirectoryEntity
import com.d365fo.mobileops.data.local.entity.WorkerPermissionEntity

@Database(
    entities = [
        // master data
        ItemEntity::class,
        BarcodeEntity::class,
        com.d365fo.mobileops.data.local.entity.ItemVariantEntity::class,
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
        CountExceptionEntity::class,
        CountLocationCheckEntity::class,
        CountLineAttemptEntity::class,
        com.d365fo.mobileops.data.local.entity.CountBinEntity::class,
        com.d365fo.mobileops.data.local.entity.CountLineBinEntity::class,
        // transfer orders
        TransferOrderEntity::class,
        TransferLineEntity::class,
        // sales
        SalesOrderEntity::class,
        SalesLineEntity::class,
        // sync
        SyncQueueEntity::class,
        FailedSyncEntity::class,
        com.d365fo.mobileops.data.local.entity.SentPostingEntity::class,
        StagedLineEntity::class,
        // roles / permissions
        WorkerPermissionEntity::class,
        WorkerDirectoryEntity::class,
        // offline answers to read calls
        ReadCacheEntity::class,

        com.d365fo.mobileops.data.local.entity.VendorEntity::class,
        com.d365fo.mobileops.data.local.entity.CreatedOrderEntity::class,
        com.d365fo.mobileops.data.local.entity.OrderDraftEntity::class,
    ],
    version = 22,
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
    abstract fun readCacheDao(): ReadCacheDao
    abstract fun orderCreationDao(): com.d365fo.mobileops.data.local.dao.OrderCreationDao
    abstract fun unsentWorkDao(): com.d365fo.mobileops.data.sync.UnsentWorkDao
    abstract fun workBackupDao(): com.d365fo.mobileops.data.sync.WorkBackupDao
    abstract fun deviceCleanupDao(): com.d365fo.mobileops.data.sync.DeviceCleanupDao

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

        /** 8 -> 9: location-first counting, empty locations, unexpected items. Additive only. */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `locationFirst` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `zeroStockCheck` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `count_submission` ADD COLUMN `countsDone` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `count_submission` ADD COLUMN `exceptionsOnly` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `count_submission` ADD COLUMN `countsWarning` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `count_exception` (`clientId` TEXT NOT NULL, `journalId` TEXT NOT NULL, " +
                        "`itemId` TEXT NOT NULL, `locationId` TEXT, `quantity` TEXT NOT NULL, `batchId` TEXT, " +
                        "`serialId` TEXT, `note` TEXT, `capturedBy` TEXT, `capturedAt` INTEGER NOT NULL, " +
                        "`sentAt` INTEGER, `sendError` TEXT, PRIMARY KEY(`clientId`), FOREIGN KEY(`journalId`) " +
                        "REFERENCES `invent_journal`(`journalId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_count_exception_journalId` ON `count_exception` (`journalId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `count_location_check` (`journalId` TEXT NOT NULL, " +
                        "`locationId` TEXT NOT NULL, `verifiedEmpty` INTEGER NOT NULL, `verifiedBy` TEXT, " +
                        "`verifiedAt` INTEGER NOT NULL, `sentAt` INTEGER, PRIMARY KEY(`journalId`, `locationId`), " +
                        "FOREIGN KEY(`journalId`) REFERENCES `invent_journal`(`journalId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        /** 9 -> 10: recounts, double-blind passes, per-line attempt history. Additive only. */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `countMode` TEXT NOT NULL DEFAULT 'SINGLE'")
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `recountOf` TEXT")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `recount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `count_submission` ADD COLUMN `resultNote` TEXT")
                db.execSQL("ALTER TABLE `count_submission` ADD COLUMN `thirdCountNeeded` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `count_line_attempt` (`journalLineId` TEXT NOT NULL, " +
                        "`attemptNo` INTEGER NOT NULL, `journalId` TEXT NOT NULL, `countedBy` TEXT, " +
                        "`countedAt` INTEGER, `quantity` TEXT, `markedOnly` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`journalLineId`, `attemptNo`), FOREIGN KEY(`journalId`) REFERENCES " +
                        "`invent_journal`(`journalId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_count_line_attempt_journalId` ON `count_line_attempt` (`journalId`)")
            }
        }

        /** 10 -> 11: count origin priority, tolerance text and ABC class for offline display. Additive. */
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `toleranceInfo` TEXT")
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `abcClass` TEXT")
            }
        }

        /** 11 -> 12: location types, verify-only lines, stock freeze. Additive only. */
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `freezeState` TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE `invent_journal` ADD COLUMN `snapshotAt` INTEGER")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `locationType` TEXT NOT NULL DEFAULT 'PHYSICAL'")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `locationOwner` TEXT")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `verifyOnly` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `confirmedBy` TEXT")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `confirmedAt` INTEGER")
                db.execSQL("ALTER TABLE `invent_journal_line` ADD COLUMN `confirmationSentAt` INTEGER")
            }
        }

        /** Product dimensions (variants) on journal lines and barcodes. */
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                for (table in listOf("invent_journal_line", "barcode")) {
                    for (column in listOf("configId", "sizeId", "colorId", "styleId")) {
                        db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` TEXT")
                    }
                }
            }
        }

        /** The offline read cache. Additive: a new table, nothing existing touched. */
        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_read_cache` (`key` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
            }
        }

        /** PO and TO creation: the vendor list kept for offline search, orders made here, drafts. */
        private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `order_vendor` (`accountNum` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`currencyCode` TEXT NOT NULL, `paymentTerms` TEXT NOT NULL, `deliveryTerms` TEXT NOT NULL, " +
                        "`deliveryMode` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`accountNum`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_vendor_name` ON `order_vendor` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `created_order` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `documentNumber` TEXT, `title` TEXT NOT NULL, `lineCount` INTEGER NOT NULL, " +
                        "`total` REAL, `currencyCode` TEXT, `message` TEXT, `pricedLinesJson` TEXT, `userName` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_created_order_createdAt` ON `created_order` (`createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `order_draft` (`kind` TEXT NOT NULL, `userName` TEXT NOT NULL, " +
                        "`json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`kind`, `userName`))",
                )
            }
        }

        /** Released variants, for offline variant choice (SKU sync's VARIANTS feed). */
        private val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `item_variant` (`itemId` TEXT NOT NULL, `configId` TEXT NOT NULL, " +
                        "`sizeId` TEXT NOT NULL, `colorId` TEXT NOT NULL, `styleId` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`itemId`, `configId`, `sizeId`, `colorId`, `styleId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_variant_syncedAt` ON `item_variant` (`syncedAt`)")
            }
        }

        /** Variant names (feedback: the variant picker showed only "Config C1"). */
        private val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `item_variant` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** The variant of an item found that is not on a counting journal (14 Oct). */
        private val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf("configId", "sizeId", "colorId", "styleId").forEach {
                    db.execSQL("ALTER TABLE `count_exception` ADD COLUMN `$it` TEXT")
                }
            }
        }

        /** Sent postings awaiting D365FO's final answer - see SentPostingEntity. */
        private val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sent_posting` (`idempotencyKey` TEXT NOT NULL, " +
                        "`dataAreaId` TEXT NOT NULL, `operation` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, " +
                        "`payloadJson` TEXT NOT NULL, `workerId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                        "`originallyQueuedAt` INTEGER NOT NULL, `sentAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`idempotencyKey`))",
                )
            }
        }

        /** Virtual bins for counting. */
        private val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `count_bin` (`warehouseId` TEXT NOT NULL, `binId` TEXT NOT NULL, " +
                        "`description` TEXT, PRIMARY KEY(`warehouseId`, `binId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `count_line_bin` (`journalLineId` TEXT NOT NULL, `binId` TEXT NOT NULL, " +
                        "`journalId` TEXT NOT NULL, `quantity` TEXT NOT NULL, PRIMARY KEY(`journalLineId`, `binId`), " +
                        "FOREIGN KEY(`journalId`) REFERENCES `invent_journal`(`journalId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_count_line_bin_journalId` ON `count_line_bin` (`journalId`)")
            }
        }

        /** A bin's location: bins are per location (store count, 8 Nov). */
        private val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `count_bin` ADD COLUMN `locationId` TEXT")
            }
        }

        /** The item's category (user, 12 Nov: items by category); filled by the next full item download. */
        private val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `item` ADD COLUMN `category` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_category` ON `item` (`category`)")
            }
        }

        val MIGRATIONS: Array<androidx.room.migration.Migration> =
            arrayOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
            )
    }
}
