package com.naeblis11.mealplanner.data

import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SyncSchema's triggers themselves: dropping them for a migration, and the SQL a phone's SQLite can run. */
class SyncSchemaTest {
    private val dir: File = Files.createTempDirectory("mp-sync-schema").toFile()
    private val file = File(dir, "test.db")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A new database file, opened once (Room opens lazily) so the callback has run. */
    private fun created() {
        val db = AppDatabase.openAt(file)
        try {
            runBlocking { db.appMetaDao().get(SyncSchema.DEVICE_ID_KEY) }
        } finally {
            db.close()
        }
    }

    private fun syncTriggers(connection: SQLiteConnection): List<String> =
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync\\_%' ESCAPE '\\' ORDER BY name")
            .use { st -> buildList { while (st.step()) add(st.getText(0)) } }

    @Test
    fun dropTriggersLeavesNoneAndTheNextOpenRecreatesAll() = runBlocking {
        created()
        BundledSQLiteDriver().open(file.absolutePath).use { c ->
            assertEquals(SyncSchema.triggers().map { it.first }.sorted(), syncTriggers(c))
            // A retired trigger from an older build goes too.
            c.execSQL("CREATE TRIGGER `sync_retired` AFTER INSERT ON app_meta BEGIN SELECT 1; END")
            SyncSchema.dropTriggers(c)
            assertEquals(emptyList<String>(), syncTriggers(c))
        }
        val db = AppDatabase.openAt(file)
        try {
            val names = db.useReaderConnection { connection ->
                connection.usePrepared("SELECT count(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync%'") {
                    it.step(); it.getLong(0)
                }
            }
            assertEquals(24L, names)
        } finally {
            db.close()
        }
    }

    /** Every statement install prepares, as the connection sees it. */
    private class Recording(private val real: SQLiteConnection) : SQLiteConnection by real {
        val seen = mutableListOf<String>()
        override fun prepare(sql: String): SQLiteStatement {
            seen += sql
            return real.prepare(sql)
        }
    }

    // minSdk 26 phones ship SQLite 3.18; Robolectric's and the desktop's SQLite are newer, so a statement using later
    // syntax passes every test here and fails only on an old phone. This list is what 3.18 lacks.
    @Test
    fun theSqlRunsOnSqlite318() {
        created()
        val recorded = BundledSQLiteDriver().open(file.absolutePath).use { c ->
            Recording(c).also { SyncSchema.install(it) }.seen
        }
        val all = SyncSchema.triggers().map { it.second } + recorded
        assertTrue(recorded.isNotEmpty())
        val newer = listOf("ON CONFLICT", "DO UPDATE", "RETURNING", "unixepoch", "iif(", "->>", " OVER (", "FILTER (")
        for (sql in all) {
            for (word in newer) assertTrue("$word in: $sql", !sql.contains(word, ignoreCase = true))
        }
    }

    // Android's own SQLite may fold more than ASCII in lower() (ICU); NOCASE and nameKey fold ASCII only. The triggers
    // fold with replace(), so every device makes the same key.
    @Test
    fun nameKeysFoldAsciiOnlyOnEveryDevice() = runBlocking {
        for ((_, sql) in SyncSchema.triggers()) assertTrue(sql, !sql.contains("lower(", ignoreCase = true))
        val db = AppDatabase.openAt(file)
        try {
            db.pantryDao().insertOrIgnore(PantryItemEntity(name = "\u00C9pice Mix"))
            db.shoppingDao().rememberAisle(IngredientAisleEntity(name = "\u00C9PICE Mix", aisle = "Spices"))
            assertEquals("\u00C9pice mix", SyncSchema.nameKey("\u00C9pice Mix"))
            assertEquals(false, db.syncDao().change("pantry_item", SyncSchema.nameKey("\u00C9pice Mix"))!!.deleted)
            assertEquals(false, db.syncDao().change("ingredient_aisle", SyncSchema.nameKey("\u00C9PICE Mix"))!!.deleted)
        } finally {
            db.close()
        }
    }
}
