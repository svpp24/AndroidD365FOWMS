package com.d365fo.mobileops.local

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.d365fo.mobileops.data.local.D365Database
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 7 -> 8 against real SQLite: on-hand moves out of the counting line into
 * counting_line_reference (blind counting), counts survive, and a counting journal that was
 * "POSTED" (counts imported) becomes SUBMITTED.
 *
 * The version-7 database is built from Room's own exported 7.json, then opened with the real
 * migrations - so Room validates the migrated tables against the version-8 entities exactly as
 * it would on a device, and a migration that drifts from the entities fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class CountingMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(DB)
    }

    @Test
    fun `7 to 8 moves on-hand off counting lines and keeps counts`() {
        createVersion7 { db ->
            db.execSQL(
                "INSERT INTO invent_journal (journalId, type, journalNameId, serverJournalNumber, description, " +
                    "journalDate, siteId, warehouseId, workerId, deviceId, status, blindCount, " +
                    "varianceThresholdPercent, postedAt, countStartedAt, sync_state, local_modified_at, retry_count) " +
                    "VALUES ('CNT-1', 'COUNTING', 'Count', 'CNT-1', NULL, 0, NULL, 'WH1', 'w', 'd', 'POSTED', 0, " +
                    "NULL, 5, 1, 'SYNCED', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO invent_journal_line (journalLineId, journalId, lineNumber, itemId, itemName, quantity, " +
                    "unitId, countedQuantity, expectedQuantity, siteId, warehouseId, locationId, batchId, serialId, " +
                    "inventDimId, varianceOverridden, markedCounted, capturedAt) " +
                    "VALUES ('CNT-1-1', 'CNT-1', 1, 'ITEM1', 'Item 1', '12', '', '9', '12', NULL, 'WH1', 'A-01', " +
                    "NULL, NULL, NULL, 0, 0, 0)",
            )
        }

        val room = Room.databaseBuilder(context, D365Database::class.java, DB)
            .addMigrations(*D365Database.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT quantity, expectedQuantity, countedQuantity FROM invent_journal_line").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEmpty()
                assertThat(c.isNull(1)).isTrue()
                assertThat(c.getString(2)).isEqualTo("9")
            }
            db.query("SELECT onHandQuantity FROM counting_line_reference WHERE journalLineId = 'CNT-1-1'").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("12")
            }
            db.query("SELECT status, transferMode, passNumber FROM invent_journal").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("SUBMITTED")
                assertThat(c.getString(1)).isEqualTo("DMF")
                assertThat(c.getInt(2)).isEqualTo(1)
            }
        } finally {
            room.close()
        }
    }

    /** Creates [DB] at version 7 from the exported schema, then lets [seed] insert rows. */
    private fun createVersion7(seed: (SupportSQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(File(SCHEMA_DIR, "7.json").readText()).jsonObject["database"]!!.jsonObject
        val statements = buildList {
            schema["entities"]!!.jsonArray.forEach { entity ->
                val e = entity.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                add(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { index ->
                    add(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { add(it.jsonPrimitive.content) }
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use(seed)
        helper.close()
    }

    private companion object {
        const val DB = "counting-migration-test"
        val SCHEMA_DIR = File("schemas/com.d365fo.mobileops.data.local.D365Database")
    }
}
