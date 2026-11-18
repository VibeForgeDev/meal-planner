package com.naeblis11.mealplanner

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naeblis11.mealplanner.app.MealPlannerApplication
import com.naeblis11.mealplanner.app.appContainer
import com.naeblis11.mealplanner.app.appSync
import com.naeblis11.mealplanner.app.appUpdates
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.theme.MealPalette
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = appContainer()
        // Plan 8: ready at once; the update check itself is made on a background thread (P8-F1, DeferredUpdates).
        val updates = appUpdates()
        // LAN sync (stage 2c): the Sync button and Join a PC, one per process; nothing syncs until the user asks.
        val sync = appSync()
        // The launch check, once per process (a rotation isn't a launch; a process Android ended and restored with this
        // activity's saved state is), on Updates' own IO scope, never this thread. At most once a day.
        (application as MealPlannerApplication).startLaunchCheckOnce()
        // Plan 2026-11-17: Kroger, made off this thread; its button and panel appear once it is ready.
        val app = application as MealPlannerApplication
        app.startKrogerOnce()
        setContent {
            // Settings > Appearance (owner, 2026-10-12): light, dark, or the phone's own setting.
            val mode by container.theme.mode.collectAsStateWithLifecycle()
            val kroger by app.kroger.collectAsStateWithLifecycle()
            val dark = mode.isDark(isSystemInDarkTheme())
            val ground = (if (dark) MealPalette.Dark else MealPalette.Light).paper
            SideEffect {
                // The window behind the app, and the status and navigation bars' icons, follow the app's choice, not
                // the system's: dark icons on the light ground, light ones on the dark.
                window.setBackgroundDrawable(ColorDrawable(ground.toArgb()))
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MealPlannerTheme(dark = dark) {
                MealPlannerApp(container, updates = updates, join = sync, sync = sync, kroger = kroger)
            }
        }
    }
}
