package com.naeblis11.mealplanner.app

import com.naeblis11.mealplanner.calendar.CalendarChoice
import com.naeblis11.mealplanner.calendar.CalendarGateway
import com.naeblis11.mealplanner.calendar.CalendarSync
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.KrogerMatchRepository
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.PantryRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.folder.RecipeFileStore
import com.naeblis11.mealplanner.folder.RecipeFolderStatus
import java.io.File
import java.nio.file.Files

/**
 * The app's long-lived objects, created once per process. Each platform supplies the
 * database, folders, calendar gateway and settings (MealPlannerApplication.kt on Android,
 * the desktop's DesktopApp.kt).
 */
class AppContainer(
    databaseFactory: () -> AppDatabase,
    /** Recipe photos (`<uuid>.jpg` and `<uuid>_thumb.jpg`), private to the app. */
    val imagesDir: File,
    private val cacheDir: File,
    gatewayFactory: () -> CalendarGateway,
    choiceFactory: () -> CalendarChoice,
    /** The desktop's recipe folder, written before every index row; null on Android, where Room is the source of truth. */
    private val files: RecipeFileStore? = null,
    /** The desktop's recipe folder status, built on first use; null on Android. */
    folderStatus: (() -> RecipeFolderStatus)? = null,
    /** The desktop's notice that Windows refuses the library (P7-R10b); nothing on Android. */
    private val onLibraryBlocked: () -> Unit = {},
    /** Small screen preferences (the recipe list's collapsed categories); in memory when a platform gives none. */
    settingsFactory: () -> SettingsStore = { MemorySettings() },
) {
    val database: AppDatabase by lazy(databaseFactory)

    val settings: SettingsStore by lazy(settingsFactory)

    /** Settings > Appearance: light, dark, or the system's choice (owner, 2026-10-12). */
    val theme: ThemePreference by lazy { ThemePreference(settings) }

    val recipes: RecipeRepository by lazy { RecipeRepository(database, imagesDir, files = files, onLibraryBlocked = onLibraryBlocked) }

    val plans: MealPlanRepository by lazy { MealPlanRepository(database) }

    val shopping: ShoppingRepository by lazy { ShoppingRepository(database) }

    val pantry: PantryRepository by lazy { PantryRepository(database) }

    /** Kroger's remembered picks (plan 2026-11-17), on both apps. */
    val krogerMatches: KrogerMatchRepository by lazy { KrogerMatchRepository(database) }

    /** The desktop's recipe folder (Needs attention, Open recipe folder); null on Android. */
    val folder: RecipeFolderStatus? by lazy { folderStatus?.invoke() }

    val calendarGateway: CalendarGateway by lazy(gatewayFactory)

    val calendarChoice: CalendarChoice by lazy(choiceFactory)

    val calendarSync: CalendarSync by lazy { CalendarSync(database, calendarGateway, calendarChoice) }

    /** Where the camera app writes a photo before it is processed. */
    val cameraDir: File
        get() = File(cacheDir, "camera").apply { mkdirs() }

    /**
     * A new, empty folder for one import's staged photos; the import deletes it when it ends. Created atomically with
     * a name of its own, so two imports staged at the same moment (the desktop's extension) never share one.
     */
    fun newImportStagingDir(): File {
        val parent = File(cacheDir, "import").apply { mkdirs() }
        return Files.createTempDirectory(parent.toPath(), "import-").toFile()
    }

    /** Deletes what a killed process left behind; started once, off the main thread. Tests join it. */
    val startupCleanup: Thread = discardLeftovers()

    // An import killed with the process leaves its staging folder behind; none can be
    // live yet, so all of cacheDir/import goes. It is moved aside at once (cheap, and a
    // new import starts clean) and deleted off the main thread. cacheDir/camera is kept.
    // An import or photo save killed mid-write leaves "<name>.tmp" or "<name>.<n>.bak"
    // next to the photos; the photos themselves are always intact. Only files older
    // than this start are touched, so a save that has just begun is never disturbed.
    private fun discardLeftovers(): Thread {
        val started = System.currentTimeMillis()
        val cache = cacheDir
        val current = File(cache, "import")
        if (current.exists() && !current.renameTo(File(cache, "$TRASH_PREFIX${System.nanoTime()}"))) current.deleteRecursively()
        val photos = imagesDir
        return Thread({
            cache.listFiles { file -> file.name.startsWith(TRASH_PREFIX) }.orEmpty().forEach { it.deleteRecursively() }
            photos.listFiles { file ->
                file.isFile && (file.name.endsWith(".tmp") || file.name.endsWith(".bak")) && file.lastModified() < started
            }.orEmpty().forEach { it.delete() }
        }, "startup-cleanup").apply { start() }
    }

    private companion object {
        const val TRASH_PREFIX = "import-discarded-"
    }
}
