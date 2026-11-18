package com.naeblis11.mealplanner.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.domain.CategoryOptions
import com.naeblis11.mealplanner.ui.theme.MealColors
import com.naeblis11.mealplanner.importing.CHROME_RECIPE_WAITING
import com.naeblis11.mealplanner.kroger.KrogerControls
import com.naeblis11.mealplanner.kroger.LocalKroger
import com.naeblis11.mealplanner.kroger.krogerDestinations
import com.naeblis11.mealplanner.importing.ImportInbox
import com.naeblis11.mealplanner.importing.ImportScreen
import com.naeblis11.mealplanner.importing.ImportState
import com.naeblis11.mealplanner.importing.ImportViewModel
import com.naeblis11.mealplanner.recipes.NeedsAttentionScreen
import com.naeblis11.mealplanner.recipes.NeedsAttentionViewModel
import com.naeblis11.mealplanner.recipes.SyncAttentionViewModel
import com.naeblis11.mealplanner.recipes.SyncRemoveAsk
import com.naeblis11.mealplanner.settings.APP_REMOVED_MESSAGE
import com.naeblis11.mealplanner.settings.APP_REPLACED_MESSAGE
import com.naeblis11.mealplanner.settings.AllowAppControls
import com.naeblis11.mealplanner.settings.BackupViewModel
import com.naeblis11.mealplanner.settings.GoogleControls
import com.naeblis11.mealplanner.settings.JoinOfferBanner
import com.naeblis11.mealplanner.settings.JoinOfferControls
import com.naeblis11.mealplanner.settings.PairingControls
import com.naeblis11.mealplanner.settings.PeerControls
import com.naeblis11.mealplanner.settings.RESTART_BY_HAND
import com.naeblis11.mealplanner.settings.RESTART_NOW_LABEL
import com.naeblis11.mealplanner.settings.RestartControls
import com.naeblis11.mealplanner.settings.ServerControls
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.StartupSwitch
import com.naeblis11.mealplanner.settings.StorageControls
import com.naeblis11.mealplanner.settings.SyncAttention
import com.naeblis11.mealplanner.settings.portInUseNotice
import com.naeblis11.mealplanner.sync.HeldRemoval
import com.naeblis11.mealplanner.sync.JoinControls
import com.naeblis11.mealplanner.sync.SyncControls
import com.naeblis11.mealplanner.update.UpdateControls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The whole app. At least WIDE_MIN_WIDTH wide and WIDE_MIN_HEIGHT high (a PC window, an Android tablet) it gets the
 * navigation rail and the wide screens; otherwise (a phone, upright or on its side) the phone layout, unchanged. [startup] is the desktop's Start with Windows switch for
 * Settings; null on Android. [leaveGuard] holds a switch of section while a form has unsaved changes; the desktop
 * passes its own, so its Quit asks the same way. [imports] holds the desktop's recipes from the Chrome extension,
 * opened one at a time on the import review, and [server] is its built-in server for Settings and the port-in-use
 * notice; both null on Android. [google] is its Google Calendar, for Settings and "Send this week"; null on Android. [quitting] is the desktop shell's Quit under way, which stops the next recipe opening.
 * [peers] is the desktop's look-out for other Meal Planner PCs on the network (plan 6), for the banner and Settings;
 * null on Android. [storage] is the desktop's two folders (P7-R10), for Settings and the notice while Windows keeps
 * it from saving to Documents; null on Android. [restart] is the desktop's notice that a new version was installed over
 * the running one (P7-R12), with Restart now; null on Android.
 * [updates] is the update check (plan 8), on both apps: Settings' Updates section and the launch notice; null leaves both out.
 * [devices] is the desktop's Phones and PCs (LAN sync stage 2b); null on Android.
 * [syncAttention] is the desktop's held sync removals (stage 2b), for Needs attention and the Recipes banner; null on Android.
 * [join] is the phone's Join a PC (LAN sync stage 2c), for Settings; null on the desktop.
 * [sync] is the phone's Sync button (LAN sync stage 2c): the four main screens' top bars, the first sync's Merge or
 * Replace, the outcome and the Recipes hint; null on the desktop.
 * [kroger] is the device's Kroger (plan 2026-11-17), on both apps: Send to Kroger on the shopping list, Settings' panel
 * and Remembered products; null leaves them out.
 */
@Composable
fun MealPlannerApp(
    container: AppContainer,
    startup: StartupSwitch? = null,
    leaveGuard: LeaveGuard = remember { LeaveGuard() },
    imports: ImportInbox? = null,
    server: ServerControls? = null,
    google: GoogleControls? = null,
    quitting: () -> Boolean = { false },
    peers: PeerControls? = null,
    storage: StorageControls? = null,
    restart: RestartControls? = null,
    updates: UpdateControls? = null,
    devices: PairingControls? = null,
    syncAttention: SyncAttention? = null,
    join: JoinControls? = null,
    sync: SyncControls? = null,
    joinOffer: JoinOfferControls? = null,
    kroger: KrogerControls? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = isWide(maxWidth, maxHeight)
        CompositionLocalProvider(LocalWideLayout provides wide, LocalKroger provides kroger) {
            AppShell(container, startup, wide, leaveGuard, imports, server, google, quitting, peers, storage, restart, updates, devices, syncAttention, join, sync, joinOffer)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppShell(
    container: AppContainer,
    startup: StartupSwitch?,
    wide: Boolean,
    leaveGuard: LeaveGuard,
    imports: ImportInbox?,
    server: ServerControls?,
    google: GoogleControls?,
    quitting: () -> Boolean,
    peers: PeerControls?,
    storage: StorageControls?,
    restart: RestartControls?,
    updates: UpdateControls?,
    devices: PairingControls?,
    syncAttention: SyncAttention?,
    join: JoinControls?,
    sync: SyncControls?,
    joinOffer: JoinOfferControls?,
) {
    val nav = rememberNavController()
    val importVm: ImportViewModel = viewModel { ImportViewModel(container.recipes, container.imagesDir, container::newImportStagingDir) }
    val importGuard = rememberLaunchGuard()
    val startImport: () -> Unit = rememberOpenFile(importGuard) { picked ->
        importVm.start(name = picked.name, open = picked.open)
        nav.navigate(Routes.IMPORT)
    }
    val backupVm: BackupViewModel = viewModel { BackupViewModel(container.recipes, container.imagesDir) }
    // Stage 2c: the phone's Sync button, one view model for the four top bars (it survives a rotation); the sync itself
    // runs in the controls' app-wide scope.
    val syncVm: SyncViewModel? = sync?.let { controls -> viewModel { SyncViewModel(controls) } }
    val syncSnackbar = remember { SnackbarHostState() }
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    val tab = MainTab.forRoute(route)
    // P4-R4: a recipe from the Chrome extension opens on the review once no other import is being read, reviewed or
    // summed up; one sent meanwhile waits in the inbox rather than replacing it. Nor does it open over a form with
    // unsaved changes: it waits until the Edit or New form lets go of the LeaveGuard (Save, or Cancel then Discard),
    // so an edit is never dropped, or questioned, on the import's behalf. Nor before the NavHost (composed later, in
    // the Scaffold's content) has given the controller its graph and first screen. Nor while the app is quitting
    // ([quitting]): leaving a review to quit would otherwise start the next recipe mid-shutdown.
    var chromeWaiting = false
    if (imports != null) {
        val importState by importVm.state.collectAsStateWithLifecycle()
        val waiting by imports.waiting.collectAsStateWithLifecycle()
        val idle = importState is ImportState.Idle
        val guarded = leaveGuard.holding
        val ready = current != null
        // Held back by a review or an edit, the next recipe is noticed above every screen.
        chromeWaiting = ready && waiting > 0 && (!idle || guarded)
        LaunchedEffect(idle, waiting, guarded, ready) {
            if (!ready || !idle || guarded || quitting()) return@LaunchedEffect
            // The keys are a frame old: a file import started, or a form that took the guard, since then wins.
            if (importVm.state.value !is ImportState.Idle || leaveGuard.holding) return@LaunchedEffect
            val next = imports.take() ?: return@LaunchedEffect
            importVm.startPrepared(next.bundle, next.dir)
            if (nav.currentDestination?.route != Routes.IMPORT) nav.navigate(Routes.IMPORT)
        }
    }
    // A form with unsaved changes holds a switch of section until the user discards them.
    val openSection: (String) -> Unit = { target ->
        if (nav.currentDestination?.route != target) leaveGuard.request {
            // An import the rail leaves while it is reading, finished or failed (a review holds the guard, so it asks
            // first) is over: popped, not saved for the tab, and reset to Idle, or the next recipe from the extension
            // would wait behind it until the next file import (review, 2026-10-10).
            if (nav.currentDestination?.route == Routes.IMPORT) { importVm.cancel(); nav.popBackStack() }
            nav.openTab(target)
        }
    }
    CompositionLocalProvider(LocalLeaveGuard provides leaveGuard, LocalSyncAttention provides syncAttention, LocalSyncControls provides syncVm) {
        if (syncVm != null) SyncDialogs(syncVm, syncSnackbar)
        val tabBar = !wide && tab != null
        Scaffold(
            bottomBar = { if (tabBar) MainTabs(selected = tab, onSelect = { openSection(it.route) }) },
            // The tab bar takes the navigation bar's inset and the Scaffold lifts the snackbar above it; with no tab bar
            // (a wide window, a recipe, Settings) the snackbar keeps clear of the navigation bar itself (edge to edge).
            snackbarHost = { SnackbarHost(syncSnackbar, if (tabBar) Modifier else Modifier.navigationBarsPadding()) },
            // Each screen's own Scaffold handles the system bars; this one only adds the tab bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                // P7-R12: a new version was installed over this running copy; first, as it explains the rest.
                if (restart != null) ReplacedBanner(restart)
                // P4-R1: the desktop says above every screen while port 5000 is taken. Android has no server.
                if (server != null) ServerNotice(server)
                // P6-R8: another Meal Planner PC on the network, in one line above every screen. Android has none.
                if (peers != null) PeerBanner(peers)
                // Stage 4a, R8: a PC's first run found the household's main PC; Join opens Settings.
                if (joinOffer != null) {
                    JoinOfferBanner(joinOffer, onJoin = { openSection(Routes.SETTINGS) }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                // Plan 8: an update the launch check found, above every screen until opened or put away.
                if (updates != null) UpdateNotice(updates, onOpen = { openSection(Routes.SETTINGS) })
                // P7-R10: Windows refused a write to Documents; how to allow it, above every screen.
                if (storage != null) LibraryNoticeBanner(storage)
                if (chromeWaiting) AttentionBanner(CHROME_RECIPE_WAITING, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                // One Row in both layouts, so the NavHost keeps its place (and its back stack) when the window is resized.
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    if (wide) MainRail(selected = RailItem.forRoute(route), onSelect = { openSection(it.route) })
                    NavHost(
                        navController = nav,
                        startDestination = Routes.RECIPES,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        composable(Routes.RECIPES) { RecipesHome(nav, container, startImport, wide = LocalWideLayout.current) }
                        recipeDestinations(nav, container)
                        composable(Routes.IMPORT) { entry ->
                            val state by importVm.state.collectAsStateWithLifecycle()
                            val categoryOptions by importVm.categoryOptions.collectAsStateWithLifecycle(CategoryOptions.DEFAULT)
                            ImportScreen(
                                state = state,
                                onUpdate = { id, change -> importVm.update(id, change) },
                                onConfirm = { importVm.confirm() },
                                onCancel = { importVm.cancel(); leaveImport(nav) },
                                // Only while this entry is the one shown: a cancelled review's screen, still composed as it
                                // leaves, sees Idle and asks to be done just as the extension's next recipe opens on a new
                                // review, which this would otherwise reset and pop.
                                onDone = { if (nav.currentBackStackEntry?.id == entry.id) { importVm.done(); leaveImport(nav) } },
                                // Likewise only the review shown holds the LeaveGuard (the rail, the tabs, Quit ask first).
                                holdsLeave = current?.id == entry.id,
                                onBackToRecipes = {
                                    importVm.done()
                                    // Wherever the import was started (Settings too), its result leads to the recipe list.
                                    if (nav.currentDestination?.route == Routes.IMPORT) nav.popBackStack(Routes.RECIPES, inclusive = false)
                                },
                                categoryOptions = categoryOptions,
                            )
                        }
                        container.folder?.let { folder ->
                            composable(Routes.NEEDS_ATTENTION) {
                                val vm: NeedsAttentionViewModel = viewModel { NeedsAttentionViewModel(folder) }
                                val problems by vm.problems.collectAsStateWithLifecycle()
                                val message by vm.message.collectAsStateWithLifecycle()
                                val busy by vm.busy.collectAsStateWithLifecycle()
                                val removing by vm.removing.collectAsStateWithLifecycle()
                                val removeAsk by vm.removeAsk.collectAsStateWithLifecycle()
                                val usingNewFolder by vm.usingNewFolder.collectAsStateWithLifecycle()
                                // Stage 2b: recipes a sync from another device would remove, above the folder's problems.
                                val syncVm: SyncAttentionViewModel? = syncAttention?.let { attention -> viewModel { SyncAttentionViewModel(attention) } }
                                val syncHeld by (syncVm?.held ?: NO_SYNC_HELD).collectAsStateWithLifecycle()
                                val syncBusy by (syncVm?.busy ?: NOT_SYNC_BUSY).collectAsStateWithLifecycle()
                                val syncAsk by (syncVm?.ask ?: NO_SYNC_ASK).collectAsStateWithLifecycle()
                                val syncMessage by (syncVm?.message ?: NO_SYNC_MESSAGE).collectAsStateWithLifecycle()
                                NeedsAttentionScreen(
                                    problems = problems,
                                    message = message ?: syncMessage,
                                    onMessageShown = { if (message != null) vm.messageShown() else syncVm?.messageShown() },
                                    onBack = dropUnlessResumed { nav.popBackStack() },
                                    onAssignNewId = vm::assignNewId,
                                    onOpenFolder = folder::openFolder,
                                    busy = busy,
                                    onRemoveMissing = vm::askRemoveMissing,
                                    removing = removing,
                                    removeAsk = removeAsk,
                                    onConfirmRemoveMissing = vm::removeMissing,
                                    onKeepMissing = vm::keepMissing,
                                    onUseNewFolder = vm::useNewFolder,
                                    usingNewFolder = usingNewFolder,
                                    onPointBack = vm::pointBack,
                                    syncHeld = syncHeld.map { it.name },
                                    syncBusy = syncBusy,
                                    syncAsk = syncAsk,
                                    onAskSyncRemove = { syncVm?.askRemove() },
                                    onConfirmSyncRemove = { syncVm?.confirmRemove() },
                                    onCancelSyncRemove = { syncVm?.cancelRemove() },
                                    onKeepSyncHeld = { syncVm?.keep() },
                                )
                            }
                        }
                        settingsDestination(nav, container, backupVm, startImport, startup, server, google, peers, storage, updates, devices, join)
                        plannerDestinations(nav, container, google)
                        krogerDestinations(nav, container)
                    }
                }
            }
        }
    }
}

/** P4-R1: the one-line notice while the server's port is taken, above every screen. */
@Composable
private fun ServerNotice(server: ServerControls) {
    val status by server.status.collectAsStateWithLifecycle()
    if (status.state == ServerState.PORT_IN_USE) {
        AttentionBanner(portInUseNotice(status.port), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** P6-R8: the one-line notice about another Meal Planner PC on the network, above every screen, as the port's. */
@Composable
private fun PeerBanner(peers: PeerControls) {
    val notice by peers.notice.collectAsStateWithLifecycle()
    notice?.let { AttentionBanner(it.text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

/**
 * P7-R10: once Windows' Controlled folder access refused a write to the library, how to allow the app (or, for any
 * other failed write the library needed, why), above every screen for the rest of the session.
 */
@Composable
private fun LibraryNoticeBanner(storage: StorageControls) {
    val notice by storage.libraryNotice.collectAsStateWithLifecycle()
    notice?.let {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            AttentionBanner(it)
            // P7-R11: the installed app can ask Windows to allow it, right here.
            AllowAppControls(storage)
        }
    }
}

/**
 * P7-R12: the running copy was replaced on disk, above every screen for the rest of the session. Restart now only in the
 * installed app with a valid launcher; otherwise what to do by hand. When the launcher is gone too, a neutral notice
 * that already says what to do.
 */
@Composable
private fun ReplacedBanner(restart: RestartControls) {
    val replaced by restart.replaced.collectAsStateWithLifecycle()
    replaced?.let {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            AttentionBanner(if (it.removed) APP_REMOVED_MESSAGE else APP_REPLACED_MESSAGE)
            when {
                // The notice itself says what to do.
                it.removed -> Unit
                it.canRestart -> OutlinedButton(onClick = restart::restartNow, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                    Text(RESTART_NOW_LABEL)
                }
                else -> Text(RESTART_BY_HAND, color = MealColors.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Pops the import screen once, even if Back and the screen's own exit both ask. */
private fun leaveImport(nav: NavController) {
    if (nav.currentDestination?.route == Routes.IMPORT) nav.popBackStack()
}

/**
 * Switches top-level screen: Recipes stays at the bottom of the back stack (so Back
 * from any tab returns there), and each tab's state is saved and restored. Navigating
 * to the tab already shown does nothing, so a double tap is harmless. The rail's
 * Settings is a top-level screen in the same way.
 */
private fun NavController.openTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// No held sync removals (Android, and a desktop run without sync).
private val NO_SYNC_HELD: StateFlow<List<HeldRemoval>> = MutableStateFlow(emptyList())
private val NOT_SYNC_BUSY: StateFlow<Boolean> = MutableStateFlow(false)
private val NO_SYNC_ASK: StateFlow<SyncRemoveAsk?> = MutableStateFlow(null)
private val NO_SYNC_MESSAGE: StateFlow<String?> = MutableStateFlow(null)
