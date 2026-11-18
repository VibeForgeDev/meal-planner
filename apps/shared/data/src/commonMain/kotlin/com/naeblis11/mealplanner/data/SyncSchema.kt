package com.naeblis11.mealplanner.data

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * LAN sync stage 1: SQLite triggers record the last change to every synced record in sync_change (SyncChangeEntity),
 * so no write path (a repository, RecipeFolder, a raw DAO call) can forget to, and an entity @Update can't overwrite
 * a stamp. [Callback] installs them, with this device's id and change counter in app_meta, each time the database
 * opens; recreating them every time keeps them in step with this code. A change only counts when one of the table's
 * synced columns really changed, so the desktop's file bookkeeping (file_name, file_hash) is never a change. A change
 * of a record's key (a renamed pantry item, a meal moved to another slot) is a delete of the old key and a change of
 * the new one. REPLACE (a meal put into a filled slot) fires no delete trigger, as SQLite fires them only with
 * recursive_triggers on, so the slot stays live. Pantry and aisle keys are the name folded to lower case in ASCII only
 * ([nameKey], [asciiLower]), never through SQLite's lower(), whose folding a phone's platform SQLite may widen.
 */
object SyncSchema {
    const val DEVICE_ID_KEY = "device_id"
    const val CHANGE_SEQ_KEY = "change_seq"

    /** A synced table: its sync key as SQL over a row (`@` stands for OLD or NEW) and the columns that make a change. */
    internal class Synced(val table: String, private val key: String, val keyColumns: List<String>, val columns: List<String>) {
        fun key(row: String): String = key.replace("@", row)
    }

    internal val TABLES = listOf(
        Synced("recipe", "@.`recipe_uuid`", listOf("recipe_uuid"), listOf("recipe_uuid", "raw_yaml", "image_hash")),
        Synced("meal_plan", "@.`date` || '|' || @.`slot`", listOf("date", "slot"), listOf("date", "slot", "recipe_id", "servings")),
        Synced("pantry_item", asciiLower("@.`name`"), listOf("name"), listOf("name", "exact_match", "aisle", "active", "added_on")),
        Synced("ingredient_aisle", asciiLower("@.`name`"), listOf("name"), listOf("name", "aisle")),
        Synced(
            "shopping_list_item", "@.`sync_id`", listOf("sync_id"),
            listOf("sync_id", "name", "amount", "unit", "aisle", "in_pantry", "checked"),
        ),
        // The key is already folded (KrogerQuery.nameKey), as every device makes it.
        Synced("kroger_match", "@.`name_key`", listOf("name_key"), listOf("name_key", "product_id", "upc", "quantity", "aisle", "aisle_store_id")),
    )

    /**
     * [expr] with A-Z folded to a-z in SQL, as [nameKey]: 26 nested replace() calls, which every SQLite has. Recreated
     * with the triggers at every open, so a key already stored (ASCII names) is unchanged.
     */
    internal fun asciiLower(expr: String): String = ('A'..'Z').fold(expr) { acc, c -> "replace($acc, '$c', '${c.lowercaseChar()}')" }

    /** A planned meal's sync key, as the meal_plan triggers write it. */
    fun mealKey(date: String, slot: String): String = "$date|$slot"

    /**
     * A pantry item's or remembered aisle's sync key: the name with A-Z folded to a-z and nothing else, as NOCASE folds.
     * The triggers make the same key with [asciiLower], never SQLite's lower(): a phone's platform SQLite may fold
     * other letters too (through ICU), and every device must make the same key for the same name.
     */
    fun nameKey(name: String): String = buildString { name.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }

    suspend fun deviceId(db: AppDatabase): String =
        db.appMetaDao().get(DEVICE_ID_KEY) ?: error("The database was opened without SyncSchema.Callback.")

    suspend fun changeSeq(db: AppDatabase): Long =
        db.appMetaDao().get(CHANGE_SEQ_KEY)?.toLong() ?: error("The database was opened without SyncSchema.Callback.")

    // UTC epoch milliseconds; julianday works on every SQLite a phone has (unixepoch('subsec') needs 3.42).
    private const val NOW = "CAST(ROUND((julianday('now') - 2440587.5) * 86400000) AS INTEGER)"

    // A DELETE then a plain INSERT, with no conflict clause: minSdk 26's system SQLite (3.18) has no UPSERT, and an outer
    // statement's own conflict clause (Room's UPDATE OR ABORT) overrides a trigger's INSERT OR REPLACE.
    private fun record(table: String, key: String, deleted: Int) =
        "UPDATE app_meta SET `value` = CAST(`value` AS INTEGER) + 1 WHERE `key` = '$CHANGE_SEQ_KEY'; " +
            "DELETE FROM sync_change WHERE table_name = '$table' AND sync_key = $key; " +
            "INSERT INTO sync_change (table_name, sync_key, updated_at, updated_by, change_seq, deleted) " +
            "VALUES ('$table', $key, $NOW, (SELECT `value` FROM app_meta WHERE `key` = '$DEVICE_ID_KEY'), " +
            "(SELECT CAST(`value` AS INTEGER) FROM app_meta WHERE `key` = '$CHANGE_SEQ_KEY'), $deleted);"

    // Meals sync by their recipe's uuid, so a recipe given a new uuid changes every meal planned with it: one counter
    // step for them all, each meal's change replaced by a live one.
    private const val RECORD_RECIPES_MEALS =
        "UPDATE app_meta SET `value` = CAST(`value` AS INTEGER) + 1 WHERE `key` = '$CHANGE_SEQ_KEY'; " +
            "DELETE FROM sync_change WHERE table_name = 'meal_plan' AND sync_key IN " +
            "(SELECT `date` || '|' || `slot` FROM meal_plan WHERE recipe_id = NEW.`id`); " +
            "INSERT INTO sync_change (table_name, sync_key, updated_at, updated_by, change_seq, deleted) " +
            "SELECT 'meal_plan', `date` || '|' || `slot`, $NOW, (SELECT `value` FROM app_meta WHERE `key` = '$DEVICE_ID_KEY'), " +
            "(SELECT CAST(`value` AS INTEGER) FROM app_meta WHERE `key` = '$CHANGE_SEQ_KEY'), 0 FROM meal_plan WHERE recipe_id = NEW.`id`;"

    /** Each trigger's name and its CREATE statement. */
    internal fun triggers(): List<Pair<String, String>> = TABLES.flatMap { t ->
        val on = "ON `${t.table}`"
        // BINARY: on a NOCASE column (pantry and aisle names) a plain IS NOT ignores a change of case.
        val changed = t.columns.joinToString(" OR ") { "OLD.`$it` IS NOT NEW.`$it` COLLATE BINARY" }
        val rekeyExtra = if (t.table == "recipe") " $RECORD_RECIPES_MEALS" else ""
        val columns = t.columns.joinToString(", ") { "`$it`" }
        val keyColumns = t.keyColumns.joinToString(", ") { "`$it`" }
        listOf(
            "sync_${t.table}_insert" to "AFTER INSERT $on BEGIN ${record(t.table, t.key("NEW"), 0)} END",
            "sync_${t.table}_update" to "AFTER UPDATE OF $columns $on WHEN $changed BEGIN ${record(t.table, t.key("NEW"), 0)} END",
            "sync_${t.table}_rekey" to
                "AFTER UPDATE OF $keyColumns $on WHEN ${t.key("OLD")} IS NOT ${t.key("NEW")} BEGIN ${record(t.table, t.key("OLD"), 1)}$rekeyExtra END",
            "sync_${t.table}_delete" to "AFTER DELETE $on BEGIN ${record(t.table, t.key("OLD"), 1)} END",
        ).map { (name, body) -> name to "CREATE TRIGGER `$name` $body" }
    }

    /**
     * Drops every sync trigger: those [triggers] names and any other `sync_` trigger an older build left. Room migrates
     * before [Callback] runs, so each migration after 5 -> 6 calls this first: its own writes are not changes, and an
     * ALTER TABLE RENAME can't trip over a trigger naming the old table. The next open recreates them.
     */
    fun dropTriggers(connection: SQLiteConnection) {
        val names = triggers().map { it.first }.toMutableSet()
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync\\_%' ESCAPE '\\'").use { st ->
            while (st.step()) names += st.getText(0)
        }
        for (name in names) connection.execSQL("DROP TRIGGER IF EXISTS `$name`")
    }

    /** The device id (made once), the counter (from 0) and the triggers, recreated. */
    fun install(connection: SQLiteConnection) {
        connection.execSQL("INSERT OR IGNORE INTO app_meta (`key`, `value`) VALUES ('$DEVICE_ID_KEY', lower(hex(randomblob(16))))")
        connection.execSQL("INSERT OR IGNORE INTO app_meta (`key`, `value`) VALUES ('$CHANGE_SEQ_KEY', '0')")
        dropTriggers(connection)
        for ((_, create) in triggers()) connection.execSQL(create)
    }

    /** Add to every builder that opens the app's database (AppDatabase.open, AppDatabase.openAt). */
    object Callback : RoomDatabase.Callback() {
        override fun onOpen(connection: SQLiteConnection) {
            install(connection)
        }
    }
}
