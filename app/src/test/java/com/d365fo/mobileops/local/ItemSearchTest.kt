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
import com.d365fo.mobileops.data.local.dao.ItemSearch
import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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
 * Item search over the word index (performance plan, phase 3): names by word start, item numbers
 * anywhere. Runs on a version-24 database migrated by the real 24 -> 25 migration, so the index
 * of items already on a device is checked as well as the one kept by the triggers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class ItemSearchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(DB)
    }

    @Test
    fun `match expression is one prefix per word, operators lower-cased`() {
        assertThat(ItemSearch.matchExpression("Ibu 200")).isEqualTo("ibu* 200*")
        assertThat(ItemSearch.matchExpression("DM-1001")).isEqualTo("dm* 1001*")
        assertThat(ItemSearch.matchExpression("salt OR pepper")).isEqualTo("salt* or* pepper*")
        assertThat(ItemSearch.matchExpression("\"*-")).isNull()
    }

    @Test
    fun `migrated items are found by word start and by number anywhere`() = withMigrated { db ->
        val dao = db.itemDao()
        fun ids(q: String) = runBlocking { dao.searchItems(q).map { it.itemId } }

        assertThat(ids("ibu")).containsExactly("DM-1002")
        assertThat(ids("200")).containsExactly("DM-1002")
        assertThat(ids("tablets ibu")).containsExactly("DM-1002")
        // Middle of a word: no longer a name match (user, 20 Nov 2026).
        assertThat(ids("profen")).isEmpty()
        // Item numbers match anywhere, and numbers starting with the query come first.
        assertThat(ids("M-10")).containsExactly("DM-1001", "DM-1002").inOrder()
        assertThat(ids("1002")).containsExactly("DM-1002")
        assertThat(ids("dm-1")).containsExactly("DM-1001", "DM-1002").inOrder()
        // Search name too.
        assertThat(ids("pain")).containsExactly("DM-1002")
    }

    @Test
    fun `a replaced item is found by its new name only`() = withMigrated { db ->
        val dao = db.itemDao()
        runBlocking {
            dao.upsertItems(listOf(item("DM-1002", "Paracetamol 500mg", null)))
            dao.upsertItems(listOf(item("NEW-1", "Ibuprofen gel", null)))
            assertThat(dao.searchItems("ibu").map { it.itemId }).containsExactly("NEW-1")
            assertThat(dao.searchItems("para").map { it.itemId }).containsExactly("DM-1002")
            dao.deleteItemsNotSeenSince(Long.MAX_VALUE)
            assertThat(dao.searchItems("para")).isEmpty()
        }
    }

    @Test
    fun `category search uses the same matching`() = withMigrated { db ->
        val dao = db.itemDao()
        runBlocking {
            val found = dao.searchInCategory(all = true, none = false, path = "", below = "%", query = "ibu", limit = 50)
            assertThat(found.map { it.itemId }).containsExactly("DM-1002")
            val byNumber = dao.searchInCategory(all = true, none = false, path = "", below = "%", query = "-", limit = 50)
            assertThat(byNumber.map { it.itemId }).containsExactly("DM-1001", "DM-1002")
        }
    }

    private fun item(id: String, name: String, searchName: String?) = ItemEntity(
        itemId = id, itemName = name, searchName = searchName, inventoryUnit = "ea", purchaseUnit = null,
        salesUnit = null, trackingRequirement = TrackingRequirement.NONE, locationMandatory = false,
        active = true, modifiedAt = 1,
    )

    private fun withMigrated(block: (D365Database) -> Unit) {
        createVersion24 { db ->
            db.execSQL(
                "INSERT INTO item (itemId, itemName, searchName, inventoryUnit, purchaseUnit, salesUnit, " +
                    "trackingRequirement, locationMandatory, active, modifiedAt, category) VALUES " +
                    "('DM-1001', 'Vitamin C 1000mg', NULL, 'ea', NULL, NULL, 'NONE', 0, 1, 1, NULL), " +
                    "('DM-1002', 'Ibuprofen 200mg Tablets', 'PAINRELIEF', 'ea', NULL, NULL, 'NONE', 0, 1, 1, NULL)",
            )
        }
        val room = Room.databaseBuilder(context, D365Database::class.java, DB)
            .addMigrations(*D365Database.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            block(room)
        } finally {
            room.close()
        }
    }

    /** Creates [DB] at version 24 from the exported schema, then lets [seed] insert rows. */
    private fun createVersion24(seed: (SupportSQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(File(SCHEMA_DIR, "24.json").readText()).jsonObject["database"]!!.jsonObject
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
                .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                    override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use(seed)
        helper.close()
    }

    private companion object {
        const val DB = "item-search-test"
        val SCHEMA_DIR = File("schemas/com.d365fo.mobileops.data.local.D365Database")
    }
}
