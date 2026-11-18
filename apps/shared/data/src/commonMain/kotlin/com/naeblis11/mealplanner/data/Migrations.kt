package com.naeblis11.mealplanner.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Every schema change since version 1, in order. Never a destructive fallback. These migrations need a
 * SQLiteDriver: without one, Room calls `migrate(SupportSQLiteDatabase)`, which throws, so every builder
 * that opens an existing file must call `setDriver`.
 *
 * Every migration after [MIGRATION_5_6] calls `SyncSchema.dropTriggers(connection)` first: Room migrates before
 * SyncSchema.Callback's onOpen recreates the triggers, so the previous build's would otherwise record the migration's
 * own writes as changes (and an ALTER TABLE RENAME re-parses them). No triggers existed before version 6.
 */
object Migrations {
    /** Plan 4: the meal plan, pantry, shopping list and remembered aisles. Recipes are untouched. */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            V2_TABLES.forEach { connection.execSQL(it) }
        }
    }

    /** Plan 5: calendar_event, the events "Send this week" put on a calendar. Nothing else changes. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            V3_TABLES.forEach { connection.execSQL(it) }
        }
    }

    /**
     * Desktop plan 2: which recipe file each row came from (file_name, file_hash; both stay null on
     * Android) and app_meta for one-time markers. Existing rows keep every value.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            V4_CHANGES.forEach { connection.execSQL(it) }
        }
    }

    /** Desktop plan 5: google_event, the events "Send this week" put on a Google calendar. Nothing else changes. */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            V5_TABLES.forEach { connection.execSQL(it) }
        }
    }

    /**
     * LAN sync stage 1: each shopping row's sync id (a fresh one for every row already there), a recipe's photo hash
     * (null until its next save), and sync_change, empty: what the library holds now is the baseline.
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            V6_CHANGES.forEach { connection.execSQL(it) }
        }
    }

    /** LAN sync stage 2a: sync_pending, records waiting to be applied. The old triggers are dropped first (stage 1's rule). */
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            SyncSchema.dropTriggers(connection)
            V7_TABLES.forEach { connection.execSQL(it) }
        }
    }

    /** LAN sync stage 2b: paired_device, the master PC's phones and PCs. The old triggers are dropped first (stage 1's rule). */
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            SyncSchema.dropTriggers(connection)
            V8_TABLES.forEach { connection.execSQL(it) }
        }
    }

    /** Kroger (plan 2026-11-17): kroger_match, the household's remembered picks. The old triggers are dropped first (stage 1's rule). */
    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            SyncSchema.dropTriggers(connection)
            V9_TABLES.forEach { connection.execSQL(it) }
        }
    }

    /** Kroger aisles (owner, 2026-11-17): where the store shelves each remembered product. Triggers dropped first. */
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) {
            SyncSchema.dropTriggers(connection)
            connection.execSQL("ALTER TABLE `kroger_match` ADD COLUMN `aisle` TEXT")
            connection.execSQL("ALTER TABLE `kroger_match` ADD COLUMN `aisle_store_id` TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        MIGRATION_9_10,
    )

    // Byte for byte the createSql of schemas/.../9.json.
    private val V9_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `kroger_match` (`name_key` TEXT NOT NULL, `product_id` TEXT NOT NULL, `upc` TEXT NOT NULL, `quantity` INTEGER NOT NULL, PRIMARY KEY(`name_key`))",
    )

    // Byte for byte the createSql of schemas/.../8.json.
    private val V8_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `paired_device` (`device_id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `key_hash` TEXT NOT NULL, `paired_at` INTEGER NOT NULL, `last_sync_at` INTEGER, PRIMARY KEY(`device_id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_paired_device_key_hash` ON `paired_device` (`key_hash`)",
    )

    // Byte for byte the createSql of schemas/.../7.json.
    private val V7_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `sync_pending` (`table_name` TEXT NOT NULL, `sync_key` TEXT NOT NULL, `reason` TEXT NOT NULL, `record` TEXT NOT NULL, PRIMARY KEY(`table_name`, `sync_key`))",
    )

    // Byte for byte the createSql of schemas/.../2.json, so a migrated database is the same as a new one.
    private val V2_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `meal_plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `slot` TEXT NOT NULL, `recipe_id` INTEGER NOT NULL, `servings` TEXT, FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_plan_date_slot` ON `meal_plan` (`date`, `slot`)",
        "CREATE INDEX IF NOT EXISTS `index_meal_plan_recipe_id` ON `meal_plan` (`recipe_id`)",
        "CREATE TABLE IF NOT EXISTS `pantry_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `exact_match` INTEGER NOT NULL, `aisle` TEXT, `active` INTEGER NOT NULL, `added_on` TEXT)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pantry_item_name` ON `pantry_item` (`name`)",
        "CREATE TABLE IF NOT EXISTS `shopping_list_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `amount` TEXT, `unit` TEXT, `aisle` TEXT, `in_pantry` INTEGER NOT NULL, `checked` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `ingredient_aisle` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `aisle` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_ingredient_aisle_name` ON `ingredient_aisle` (`name`)",
    )

    // Byte for byte the createSql of schemas/.../3.json, so a migrated database is the same as a new one.
    private val V3_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `calendar_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `calendar_id` INTEGER NOT NULL, `date` TEXT NOT NULL, `slot` TEXT NOT NULL, `event_id` INTEGER NOT NULL, `content_hash` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_event_calendar_id_date_slot` ON `calendar_event` (`calendar_id`, `date`, `slot`)",
    )

    // What Room's own auto-migration emits for these entity changes. The two column definitions are byte
    // for byte their text in schemas/.../4.json's recipe createSql (Room lists them last, where ALTER TABLE
    // puts them), and app_meta's statement is its createSql there.
    private val V4_CHANGES = listOf(
        "ALTER TABLE `recipe` ADD COLUMN `file_name` TEXT",
        "ALTER TABLE `recipe` ADD COLUMN `file_hash` TEXT",
        "CREATE TABLE IF NOT EXISTS `app_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
    )

    // Byte for byte the createSql of schemas/.../5.json, so a migrated database is the same as a new one.
    private val V5_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `google_event` (`calendar_id` TEXT NOT NULL, `date` TEXT NOT NULL, `slot` TEXT NOT NULL, `event_id` TEXT NOT NULL, `content_hash` TEXT NOT NULL, PRIMARY KEY(`calendar_id`, `date`, `slot`))",
    )

    // The two columns as Room's auto-migration would add them (their text in schemas/.../6.json, listed last as
    // ALTER TABLE puts them); every existing shopping row gets its own id before the unique index; then the index and
    // sync_change byte for byte their createSql in 6.json.
    private val V6_CHANGES = listOf(
        "ALTER TABLE `recipe` ADD COLUMN `image_hash` TEXT",
        "ALTER TABLE `shopping_list_item` ADD COLUMN `sync_id` TEXT NOT NULL DEFAULT ''",
        "UPDATE `shopping_list_item` SET `sync_id` = lower(hex(randomblob(16)))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_shopping_list_item_sync_id` ON `shopping_list_item` (`sync_id`)",
        "CREATE TABLE IF NOT EXISTS `sync_change` (`table_name` TEXT NOT NULL, `sync_key` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, `updated_by` TEXT NOT NULL, `change_seq` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`table_name`, `sync_key`))",
        "CREATE INDEX IF NOT EXISTS `index_sync_change_change_seq` ON `sync_change` (`change_seq`)",
    )
}
