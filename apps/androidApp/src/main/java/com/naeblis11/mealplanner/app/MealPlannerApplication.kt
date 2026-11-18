package com.naeblis11.mealplanner.app

import com.naeblis11.mealplanner.kroger.AndroidKroger
import com.naeblis11.mealplanner.kroger.KrogerControls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.app.Application
import android.content.Context
import com.naeblis11.mealplanner.calendar.AndroidCalendarGateway
import com.naeblis11.mealplanner.calendar.CalendarChoice
import com.naeblis11.mealplanner.calendar.CalendarGateway
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.open
import com.naeblis11.mealplanner.sync.AndroidSync
import com.naeblis11.mealplanner.sync.PhoneSyncControls
import com.naeblis11.mealplanner.update.AndroidUpdates
import com.naeblis11.mealplanner.update.DeferredUpdates
import com.naeblis11.mealplanner.update.Updates
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The phone's container. [gatewayOverride] lets full-app tests use a fake calendar. */
fun AppContainer(context: Context, gatewayOverride: CalendarGateway? = null): AppContainer {
    val appContext = context.applicationContext
    return AppContainer(
        databaseFactory = { AppDatabase.open(appContext) },
        imagesDir = File(appContext.filesDir, "images"),
        cacheDir = appContext.cacheDir,
        gatewayFactory = { gatewayOverride ?: AndroidCalendarGateway(appContext) },
        choiceFactory = { CalendarChoice(appContext.getSharedPreferences(CalendarChoice.FILE, Context.MODE_PRIVATE)) },
        settingsFactory = { SharedPreferencesStore(appContext.getSharedPreferences(SCREEN_PREFS, Context.MODE_PRIVATE)) },
    )
}

/** The phone's small screen preferences (the recipe list's collapsed categories). */
private const val SCREEN_PREFS = "screen"

class MealPlannerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    /**
     * Whether one of the app's activities is resumed: the update's installer starts only then (P8-PF11). Each resume
     * also refreshes Android's "install unknown apps" answer, so the banner goes once the user comes back having allowed
     * it (only once the update check exists: a resume never creates it).
     */
    val foreground = ForegroundActivities(onResumed = { if (updatesHolder.isInitialized()) updates.recheckPermission() })

    /** How the update check is made; full-app tests swap in one on a fake server before anything uses it. */
    internal var updatesFactory: () -> Updates = { AndroidUpdates.create(this, foreground::any) }

    private val updatesHolder = lazy { updatesFactory() }

    /**
     * Plan 8: the update check, one per process; only the release build checks (AndroidUpdates.isRelease). Making it
     * reads files and settings, so the app makes it off the main thread, through [updateControls].
     */
    val updates: Updates by updatesHolder

    /**
     * What the screens use (P8-F1): ready at once on the main thread, while [updates] is made on a background thread
     * (DeferredUpdates). The same one Updates for the whole process.
     */
    val updateControls: DeferredUpdates by lazy { DeferredUpdates(build = { updates }) }

    /** How the phone's LAN sync is made (stage 2c); full-app tests swap in one over an in-process hub before anything uses it. */
    internal var syncFactory: () -> PhoneSyncControls = { AndroidSync.create(this, container) }

    /**
     * LAN sync (stage 2c): the Sync button and Settings' Join a PC. There is one per process, so a sync, a pairing or a
     * Leave outlives the screen, and the activity, that started it. It is made on first use; it reads the pairing off the
     * main thread.
     */
    val syncControls: PhoneSyncControls by lazy { syncFactory() }

    /** How Kroger is made (plan 2026-11-17); full-app tests swap in a fake before anything uses it. */
    internal var krogerFactory: () -> KrogerControls? = { AndroidKroger.create(this, container.settings) }

    private val krogerStarted = AtomicBoolean(false)
    private val _kroger = MutableStateFlow<KrogerControls?>(null)

    /**
     * Kroger: Send to Kroger, Settings' panel and Remembered products. Null until made: [startKrogerOnce] makes it on a
     * background thread, as it reads its sealed file through the Keystore, so the shopping list never waits for it.
     */
    val kroger: StateFlow<KrogerControls?> = _kroger.asStateFlow()

    fun startKrogerOnce() {
        if (krogerStarted.compareAndSet(false, true)) {
            kotlin.concurrent.thread(isDaemon = true, name = "kroger") {
                _kroger.value = try {
                    krogerFactory()
                } catch (e: Exception) {
                    System.err.println("Meal Planner: Kroger couldn't be set up (${e.javaClass.simpleName})")
                    null
                }
            }
        }
    }

    private val launchCheckStarted = AtomicBoolean(false)

    /** How many launch checks this process started (at most one); for the tests. */
    internal val launchChecks = AtomicInteger()

    /**
     * The launch check, once per process, whichever activity is created first, a fresh start or one Android restores
     * after it ended the process (which brings a saved state but is still a launch). Updates decides whether a day has
     * passed, and prunes old downloads first. It starts once Updates is made, off the main thread.
     */
    fun startLaunchCheckOnce() {
        if (launchCheckStarted.compareAndSet(false, true)) {
            launchChecks.incrementAndGet()
            updateControls.onBuilt { it.startLaunchCheck() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(foreground)
    }
}

fun Context.appContainer(): AppContainer = (applicationContext as MealPlannerApplication).container

/** The screens' update check (DeferredUpdates): never makes Updates on the calling thread. */
fun Context.appUpdates(): DeferredUpdates = (applicationContext as MealPlannerApplication).updateControls

/** The phone's LAN sync (stage 2c), one per process. */
fun Context.appSync(): PhoneSyncControls = (applicationContext as MealPlannerApplication).syncControls
