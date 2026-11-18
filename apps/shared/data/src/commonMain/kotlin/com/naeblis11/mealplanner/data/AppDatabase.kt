package com.naeblis11.mealplanner.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * The app's database. Schema changes are Room migrations with the schema exported to
 * apps/shared/data/schemas; a destructive fallback is never used, so an app update can't
 * wipe a library. Each platform opens it with its own driver (AppDatabaseAndroid.kt, and
 * the desktop's in desktopMain).
 */
@Database(
    entities = [
        RecipeEntity::class, RecipeIngredientEntity::class, RecipeStepEntity::class,
        MealPlanEntity::class, PantryItemEntity::class, ShoppingItemEntity::class, IngredientAisleEntity::class,
        CalendarEventEntity::class, AppMetaEntity::class, GoogleEventEntity::class,
        SyncChangeEntity::class, SyncPendingEntity::class, PairedDeviceEntity::class, KrogerMatchEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao

    abstract fun mealPlanDao(): MealPlanDao

    abstract fun pantryDao(): PantryDao

    abstract fun shoppingDao(): ShoppingDao

    abstract fun calendarEventDao(): CalendarEventDao

    abstract fun appMetaDao(): AppMetaDao

    abstract fun googleEventDao(): GoogleEventDao

    abstract fun syncDao(): SyncDao

    abstract fun pairedDeviceDao(): PairedDeviceDao

    abstract fun krogerMatchDao(): KrogerMatchDao

    companion object {
        const val FILE_NAME = "mealplanner.db"
    }
}

/** Room's KSP generates the actual for each platform. */
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
