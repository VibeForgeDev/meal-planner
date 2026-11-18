package com.naeblis11.mealplanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phones already hold version-1 to -4 libraries: opening any of them with this build
 * must keep everything and add the new tables and columns exactly as a fresh install has them
 * (Room checks every table against the entities as it opens, and throws on a mismatch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(AppDatabase.FILE_NAME)
    }

    /** A database exactly as Room created [version], from its exported schema, then [seed]ed. */
    private fun createAt(version: Int, seed: SQLiteDatabase.() -> Unit) {
        val dir = File(System.getProperty("schemaDir") ?: error("schemaDir is not set; run through Gradle"))
        val schema = Json.parseToJsonElement(File(dir, "com.naeblis11.mealplanner.data.AppDatabase/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(AppDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.seed()
            db.version = version
        }
    }

    private suspend fun userVersion(db: AppDatabase): Int =
        db.useReaderConnection { connection ->
            connection.usePrepared("PRAGMA user_version") { statement -> statement.step(); statement.getLong(0).toInt() }
        }

    private fun SQLiteDatabase.oneRecipe() {
        execSQL("INSERT INTO recipe (id, recipe_uuid, name, raw_yaml) VALUES (7, 'u-7', 'Soup', 'recipe_name: Soup')")
        execSQL("INSERT INTO recipe_ingredient (recipe_id, order_num, name, amount, unit, amounts_json) VALUES (7, 0, 'Stock', '2', 'cup', '[]')")
        execSQL("INSERT INTO recipe_step (recipe_id, order_num, step_text) VALUES (7, 0, 'Simmer.')")
    }

    @Test
    fun aVersion1LibraryOpensAsVersion10WithEveryRecipeKept() = runTest {
        createAt(1) { oneRecipe() }
        val db = AppDatabase.open(context)
        try {
            assertEquals(listOf("Soup"), db.recipeDao().names())
            assertEquals(listOf("Stock"), db.recipeDao().ingredients(7).map { it.name })
            assertEquals(listOf("Simmer."), db.recipeDao().steps(7).map { it.stepText })
            db.mealPlanDao().put(MealPlanEntity(date = "2026-10-05", slot = "Dinner", recipeId = 7, servings = null))
            assertEquals("Soup", db.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
            db.calendarEventDao().record(CalendarEventEntity(calendarId = 1, date = "2026-10-05", slot = "Dinner", eventId = 9, contentHash = "h"))
            assertEquals(listOf(9L), db.calendarEventDao().all().map { it.eventId })
            assertEquals(emptyList<String>(), db.recipeDao().fileNames())
            assertEquals(10, userVersion(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion2LibraryOpensAsVersion10WithItsPlanPantryAndListKept() = runTest {
        createAt(2) {
            oneRecipe()
            execSQL("INSERT INTO meal_plan (date, slot, recipe_id, servings) VALUES ('2026-10-05', 'Dinner', 7, '4')")
            execSQL("INSERT INTO pantry_item (name, exact_match, aisle, active, added_on) VALUES ('Salt', 0, NULL, 1, '2026-09-01')")
            execSQL("INSERT INTO shopping_list_item (name, amount, unit, aisle, in_pantry, checked) VALUES ('Milk', '1', 'gal', 'Dairy & Eggs', 0, 1)")
            execSQL("INSERT INTO ingredient_aisle (name, aisle) VALUES ('Milk', 'Dairy & Eggs')")
        }
        val db = AppDatabase.open(context)
        try {
            assertEquals("4", db.mealPlanDao().assignment("2026-10-05", "Dinner")!!.servings)
            assertEquals("2026-09-01", db.pantryDao().byName("salt")!!.addedOn)
            val milk = db.shoppingDao().allInIdOrder().single()
            assertTrue(milk.checked)
            assertEquals("Dairy & Eggs", db.shoppingDao().rememberedAisle("milk"))
            assertEquals(emptyList<CalendarEventEntity>(), db.calendarEventDao().all())
            assertEquals(10, userVersion(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion3LibraryOpensAsVersion10WithEmptyFileColumns() = runTest {
        createAt(3) {
            oneRecipe()
            execSQL("INSERT INTO meal_plan (date, slot, recipe_id, servings) VALUES ('2026-10-05', 'Dinner', 7, NULL)")
            execSQL("INSERT INTO calendar_event (calendar_id, date, slot, event_id, content_hash) VALUES (1, '2026-10-05', 'Dinner', 9, 'h')")
        }
        val db = AppDatabase.open(context)
        try {
            val soup = db.recipeDao().recipe(7)!!
            assertNull(soup.fileName)
            assertNull(soup.fileHash)
            assertEquals("Soup", db.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
            assertEquals(listOf(9L), db.calendarEventDao().all().map { it.eventId })
            assertNull(db.appMetaDao().get("legacy_import"))
            db.appMetaDao().put(AppMetaEntity("legacy_import", "done"))
            assertEquals("done", db.appMetaDao().get("legacy_import"))
            assertEquals(10, userVersion(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion4LibraryOpensAsVersion10WithAnEmptyGoogleEventTable() = runTest {
        createAt(4) {
            oneRecipe()
            execSQL("INSERT INTO app_meta (`key`, `value`) VALUES ('legacy_import', 'done')")
        }
        val db = AppDatabase.open(context)
        try {
            assertEquals(listOf("Soup"), db.recipeDao().names())
            assertEquals("done", db.appMetaDao().get("legacy_import"))
            assertEquals(emptyList<GoogleEventEntity>(), db.googleEventDao().all())
            assertEquals(10, userVersion(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion6LibraryOpensAsVersion10WithItsChangesKeptAndTriggersBack() = runTest {
        createAt(6) {
            oneRecipe()
            execSQL("INSERT INTO sync_change (table_name, sync_key, updated_at, updated_by, change_seq, deleted) VALUES ('recipe', 'u-7', 5, 'dev', 3, 0)")
        }
        val db = AppDatabase.open(context)
        try {
            assertEquals(listOf(SyncChangeEntity("recipe", "u-7", 5L, "dev", 3L, false)), db.syncDao().allChanges())
            assertEquals(emptyList<SyncPendingEntity>(), db.syncDao().pending())
            db.pantryDao().insertOrIgnore(PantryItemEntity(name = "Milk"))
            assertEquals(false, db.syncDao().change("pantry_item", "milk")!!.deleted)
            assertEquals(10, userVersion(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion5LibraryOpensAsVersion10WithASyncIdOnEveryShoppingRow() = runTest {
        createAt(5) {
            oneRecipe()
            execSQL("INSERT INTO shopping_list_item (name, in_pantry, checked) VALUES ('Eggs', 0, 0)")
            execSQL("INSERT INTO shopping_list_item (name, in_pantry, checked) VALUES ('Eggs', 0, 1)")
        }
        val db = AppDatabase.open(context)
        try {
            val rows = db.shoppingDao().allInIdOrder()
            assertEquals(listOf(false, true), rows.map { it.checked })
            assertNotEquals(rows[0].syncId, rows[1].syncId)
            rows.forEach { assertTrue(it.syncId, Regex("[0-9a-f]{32}").matches(it.syncId)) }
            assertNull(db.recipeDao().recipe(7)!!.imageHash)
            assertEquals(emptyList<SyncChangeEntity>(), db.syncDao().changedSince(-1))
            assertEquals(10, userVersion(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun withoutTheMigrationOpeningRefusesRatherThanWiping() = runTest {
        createAt(2) { oneRecipe() }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.FILE_NAME).build()
        try {
            assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase }
        } finally {
            db.close()
        }
    }

    @Test
    fun thePhonesDatabaseRecordsChanges() = runTest {
        createAt(5) {}
        val db = AppDatabase.open(context)
        try {
            db.pantryDao().insertOrIgnore(PantryItemEntity(name = "Milk"))
            val c = db.syncDao().change("pantry_item", "milk")!!
            assertEquals(SyncSchema.deviceId(db), c.updatedBy)
            assertEquals(1L, c.changeSeq)
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion7LibraryOpensAsVersion10WithNoPairedDevicesAndItsPendingRecordsKept() = runTest {
        createAt(7) {
            oneRecipe()
            execSQL("INSERT INTO sync_pending (table_name, sync_key, reason, record) VALUES ('meal_plan', '2026-10-12|Dinner', 'missing_recipe', '{}')")
        }
        val db = AppDatabase.open(context)
        try {
            assertEquals(listOf("2026-10-12|Dinner"), db.syncDao().pending().map { it.syncKey })
            assertEquals(0, db.pairedDeviceDao().count())
            db.pantryDao().insertOrIgnore(PantryItemEntity(name = "Milk"))
            assertEquals(false, db.syncDao().change("pantry_item", "milk")!!.deleted)
            assertEquals(10, userVersion(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion8LibraryOpensAsVersion10WithNoKrogerMatchesAndItsDevicesKept() = runTest {
        createAt(8) {
            oneRecipe()
            execSQL("INSERT INTO paired_device (device_id, name, kind, key_hash, paired_at) VALUES ('d1', 'Phone', 'phone', 'h', 1)")
        }
        val db = AppDatabase.open(context)
        try {
            assertEquals(1, db.pairedDeviceDao().count())
            assertEquals(emptyList<KrogerMatchEntity>(), db.krogerMatchDao().all())
            // The new table is synced: its triggers are installed with the others.
            db.krogerMatchDao().put(KrogerMatchEntity("ground chuck", "0001", "0002", 2))
            assertEquals(false, db.syncDao().change("kroger_match", "ground chuck")!!.deleted)
            assertEquals(10, userVersion(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion9LibraryOpensAsVersion10WithItsMatchesKeptWithoutAnAisle() = runTest {
        createAt(9) {
            oneRecipe()
            execSQL("INSERT INTO kroger_match (name_key, product_id, upc, quantity) VALUES ('ground chuck', '0001', '0002', 2)")
        }
        val db = AppDatabase.open(context)
        try {
            assertEquals(listOf(KrogerMatchEntity("ground chuck", "0001", "0002", 2)), db.krogerMatchDao().all())
            // The aisle is synced: changing it alone records a change.
            db.krogerMatchDao().put(KrogerMatchEntity("ground chuck", "0001", "0002", 2, "Aisle 12", "01400943"))
            assertEquals(false, db.syncDao().change("kroger_match", "ground chuck")!!.deleted)
            assertEquals("Aisle 12", db.krogerMatchDao().byKey("ground chuck")!!.aisle)
            assertEquals(10, userVersion(db))
        } finally {
            db.close()
        }
    }
}
