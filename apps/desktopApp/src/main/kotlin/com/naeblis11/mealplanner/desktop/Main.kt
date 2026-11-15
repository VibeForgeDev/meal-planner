package com.naeblis11.mealplanner.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.naeblis11.mealplanner.app.PreferencesStore
import com.naeblis11.mealplanner.desktop.google.DpapiProtector
import com.naeblis11.mealplanner.desktop.google.GoogleAccount
import com.naeblis11.mealplanner.desktop.google.GoogleClients
import com.naeblis11.mealplanner.desktop.google.GoogleTokenStore
import com.naeblis11.mealplanner.desktop.mcp.McpServer
import com.naeblis11.mealplanner.desktop.mcp.McpTools
import com.naeblis11.mealplanner.desktop.peers.JmdnsDiscovery
import com.naeblis11.mealplanner.desktop.peers.PeerInterfaces
import com.naeblis11.mealplanner.desktop.peers.PeerMessages
import com.naeblis11.mealplanner.desktop.peers.PeerTxt
import com.naeblis11.mealplanner.desktop.peers.PeerWatch
import com.naeblis11.mealplanner.desktop.server.ApiToken
import com.naeblis11.mealplanner.desktop.server.DesktopServerControls
import com.naeblis11.mealplanner.desktop.server.ExtensionImport
import com.naeblis11.mealplanner.desktop.server.ImageFetcher
import com.naeblis11.mealplanner.desktop.server.KtorEngine
import com.naeblis11.mealplanner.desktop.server.MealPlannerServer
import com.naeblis11.mealplanner.desktop.server.PortNotice
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.desktop.server.ServerRoutes
import com.naeblis11.mealplanner.desktop.server.VoiceActions
import com.naeblis11.mealplanner.desktop.server.extensionRoutes
import com.naeblis11.mealplanner.desktop.server.mcpRoutes
import com.naeblis11.mealplanner.desktop.server.voiceRoutes
import com.naeblis11.mealplanner.desktop.sync.DesktopClient
import com.naeblis11.mealplanner.desktop.sync.DesktopSync
import com.naeblis11.mealplanner.desktop.sync.DpapiPairingStore
import com.naeblis11.mealplanner.desktop.sync.HandoverHere
import com.naeblis11.mealplanner.desktop.sync.HandoverPending
import com.naeblis11.mealplanner.desktop.sync.HandoverTaker
import com.naeblis11.mealplanner.desktop.sync.JdkHttpsEngine
import com.naeblis11.mealplanner.desktop.sync.JmdnsPcFinder
import com.naeblis11.mealplanner.desktop.sync.SyncCertificate
import com.naeblis11.mealplanner.desktop.sync.SyncServer
import com.naeblis11.mealplanner.desktop.update.DesktopUpdates
import com.naeblis11.mealplanner.desktop.update.UpdateQuit
import com.naeblis11.mealplanner.desktop.update.trayTooltip
import com.naeblis11.mealplanner.importing.ImportInbox
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.portInUseNotice
import com.naeblis11.mealplanner.sync.DeviceNames
import com.naeblis11.mealplanner.sync.HandoverBundle
import com.naeblis11.mealplanner.sync.PairingQr
import com.naeblis11.mealplanner.sync.SyncMessages
import com.naeblis11.mealplanner.ui.AwtFileChooser
import com.naeblis11.mealplanner.ui.LeaveGuard
import com.naeblis11.mealplanner.ui.LocalFileChooser
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.theme.MealPalette
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import io.ktor.server.routing.Route
import java.awt.Frame
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The window's close: [shutdown], then [exit] however it went. The window is already hidden by then,
 * so skipping [exit] would leave a running process with no window. A failure is logged; a
 * cancellation still exits, then carries on as a cancellation.
 */
internal suspend fun shutdownThenExit(shutdown: suspend () -> Unit, exit: () -> Unit) {
    try {
        shutdown()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        System.err.println("Meal Planner: closing failed: $t")
    } finally {
        exit()
    }
}

/** Logged when Quit's close ran out of time; DesktopApp.close has already said what it left open. */
internal const val QUIT_TIMED_OUT = "Meal Planner: closing timed out; quitting anyway."

/** Logged when Restart now's close ran out of time, so the new copy wasn't started (P7-R12). */
internal const val RESTART_SKIPPED = "Meal Planner: not restarting, as closing timed out; open it again yourself."

/**
 * Quit's close: [shutdown], then [release] (the single-instance lock) once everything is closed, then [afterRelease]
 * (Restart now's relaunch, P7-R12; nothing for Quit). A close that timed out (or failed) may leave background work
 * running until the process exits, which comes next anyway: the lock is left for the operating system to release then,
 * so a relaunch can't start on the same folder while that work runs, and [afterRelease] isn't run (a new copy would
 * only find the lock held and ask this one, already quitting, to show). [log] says it timed out.
 */
internal suspend fun closeForQuit(
    shutdown: suspend () -> Boolean,
    release: () -> Unit,
    log: (String) -> Unit = { System.err.println(it) },
    afterRelease: (() -> Unit)? = null,
) {
    if (shutdown()) {
        release()
        afterRelease?.invoke()
    } else {
        log(QUIT_TIMED_OUT)
        if (afterRelease != null) log(RESTART_SKIPPED)
    }
}

/**
 * Quit as the user asks for it (the tray's Quit, or closing the window when there is no tray): through [guard], so a
 * form with unsaved changes asks "Discard changes?" first, and [quitNow] runs only on Discard (Keep editing cancels
 * it). The window is shown first only then, so the question can be seen; a Quit with nothing to lose never shows it,
 * and so never composes the screens of a start that stayed in the tray.
 */
internal fun guardedQuit(shell: WindowShell, guard: LeaveGuard, quitNow: () -> Unit): () -> Unit = {
    if (guard.holding) shell.show()
    guard.request(quitNow)
}

/**
 * A JVM shutdown hook for when Windows ends the session (sign-out, shutdown) with the app in the tray: the same
 * bounded close as Quit. DesktopApp.close runs once, so after Quit's close the hook does nothing.
 */
internal fun closeOnExit(app: DesktopApp): Thread = Thread({ app.close() }, "meal-planner-exit")

/**
 * Quit's last step: [hook] (closeOnExit) is taken off before [exit], so a Quit whose close timed out ends the JVM
 * without the hook waiting out a second bounded close. Once the JVM is already shutting down the hook can't be taken
 * off; it runs then, and does nothing if Quit's close finished.
 */
internal fun exitWithoutHook(hook: Thread, exit: () -> Unit) {
    try {
        Runtime.getRuntime().removeShutdownHook(hook)
    } catch (e: IllegalStateException) {
        // Shutdown already under way.
    } finally {
        exit()
    }
}

/**
 * What main does once the app has started: [block], then [exit] however it went, 0 when it ended and 1 when it threw.
 * JmDNS's listener threads are non-daemon (P6-R5), so a crash that just left main would leave a process with no window
 * holding the single-instance lock, and every later launch would only ask it to come forward. A crash is [log]ged by
 * its class and stack frames, never its message (not ours to vouch for: it might carry the token or a peer's data), and
 * then [explain]ed (P7-R10: main shows the log's path in a small dialog), so it never ends without a word.
 */
internal fun runThenExit(
    exit: (Int) -> Unit = ::exitProcess,
    log: (String) -> Unit = { System.err.println(it) },
    explain: () -> Unit = {},
    explainWaitMillis: Long = EXPLAIN_WAIT_MILLIS,
    block: () -> Unit,
) {
    var code = 1
    try {
        block()
        code = 0
    } catch (t: Throwable) {
        log("Meal Planner: stopped by an error: ${t.javaClass.name}" + t.stackTrace.take(STACK_FRAMES_LOGGED).joinToString("") { "\n\tat $it" })
        // M2: waited for at most explainWaitMillis, and a failure in it is only logged, so the exit below always comes.
        waitAtMost(explainWaitMillis, log, explain)
    } finally {
        exit(code)
    }
}

/** The system property that turns discovery on in a preview (Gradle's run passes it for -Pmealplanner.peers=on). */
internal const val PEERS_PROPERTY = "mealplanner.peers"

/**
 * Whether this run looks for other Meal Planner PCs (plan 6, P6-PF7; plan 7, P7-R6). Set, [peers] (PEERS_PROPERTY's
 * value) decides alone: "on" looks, anything else doesn't, even in the installed app. The smoke run passes
 * -Dmealplanner.peers=off through JAVA_TOOL_OPTIONS, which can't override the launcher's -Dmealplanner.installed=true,
 * so the off has to win here, or its throwaway household would announce itself on the LAN. Unset, only the installed
 * app ([installed] is StartWithWindows.INSTALLED_PROPERTY's value) looks: a preview's household is older than any later
 * install's, so a preview that always announced would make the owner's installed app leave Google Calendar and Alexa
 * to it.
 */
internal fun lookForPeers(installed: String?, peers: String?): Boolean = if (peers != null) peers == "on" else installed == "true"

/**
 * The desktop app: one per data folder, living in the tray (P3-R1, P3-R2, P3-R6). Closing the window hides it; the
 * tray's Quit runs the bounded shutdown, after asking about an unsaved edit (guardedQuit). Started with MINIMIZED_ARG
 * (as Windows starts it at sign-in), it begins hidden in the tray. While it runs it answers the Chrome extension and
 * Alexa on port 5000 (plan 4), and sends the week to Google Calendar (plan 5).
 */
fun main(args: Array<String>) {
    // The JDK HttpServer's backstop limits (300 s per whole request and per whole reply; the sync port's own StallGuard
    // limits each stage and each read to 30 s and a reply to 120 s). This runs before anything can load the JDK's
    // HttpServer (SelfCheck's, GoogleSignIn's), because it reads those limits only once.
    JdkHttpsEngine.applyJdkLimits()
    // P7-R10: the app's own data (database, log, lock) is in the secrets folder, which Windows' Controlled folder access
    // doesn't guard; only the library (recipes, photos) is in Documents.
    val appDataDir = DesktopPaths.appDataDir()
    val cacheDir = File(appDataDir, ".cache")
    // Everything said on stderr (the watcher, the server, a crash) also lands in .cache\meal-planner.log. First of all,
    // so any failure from here on, and a second launch that can't reach the running one, leaves its line there.
    DesktopLog.install(cacheDir)
    val logFile = File(cacheDir, DesktopLog.FILE_NAME)
    // A start that fails says so in the log and in a small dialog naming it, never "Failed to launch JVM" alone.
    startOrExplain(logFile) { runApp(args, DesktopPaths.libraryDir(), appDataDir, cacheDir, logFile) }
}

private fun runApp(args: Array<String>, libraryDir: File, appDataDir: File, cacheDir: File, logFile: File) {
    val prefsNode = DesktopPaths.prefsNode()
    val settings = PreferencesStore(Preferences.userRoot().node(prefsNode))
    // P7-R12: the installed files as they are now (the jar this code came from, the jar set beside it, the launcher's
    // cfg), so a new MSI installed over the running app is noticed whenever the window is shown: every show looks, off
    // the Swing thread (the look reads the disk). Restart now is set once the window's quit exists.
    val relauncher = Relauncher()
    val restartAction = AtomicReference<() -> Unit> {}
    val replaced = ReplacedNotice(AppJar.running(), relauncher, restart = { restartAction.get()() })
    val shell = WindowShell(
        startMinimized = MINIMIZED_ARG in args,
        traySupported = isTraySupported,
        notice = TrayNotice(settings),
        look = { replaced.look() },
    )
    // A second launch on this app data folder only asks the first to come forward, then ends.
    val instance = SingleInstance.acquire(cacheDir) { SwingUtilities.invokeLater { shell.show() } } ?: exitProcess(0)
    // Ktor would add its own shutdown hook (up to 5 s) at the server's first start, racing closeOnExit's bounded close at
    // sign-out; that close already stops the server first.
    System.setProperty(KtorEngine.SHUTDOWN_HOOK_PROPERTY, "false")
    // The Alexa token, read once from the secrets file (P4-R6), which is in the app data folder; the
    // preview keeps its own in its data folder. Never logged.
    val secrets = SecretsFile(SecretsFile.forApp(appDataDir))
    val token = ApiToken(secrets)
    // The built-in server on port 5000 (P4-R1): started by app.start() after the startup sync, stopped first by close().
    // The preview (Gradle's run) listens on 5055 instead, through mealplanner.port, read once here.
    val port = MealPlannerServer.portFrom()
    // LAN sync (stage 2b): port 5001, or the preview's (5065, or 5066 beside a second preview) through
    // mealplanner.syncPort, read once here.
    val syncPort = SyncServer.portFrom()
    // The version other Meal Planner PCs see (plan 6); the packaged app sets it, as for Settings' About.
    val version = System.getProperty(DesktopUpdates.VERSION_PROPERTY) ?: "dev"
    val discoveryOn = lookForPeers(System.getProperty(StartWithWindows.INSTALLED_PROPERTY), System.getProperty(PEERS_PROPERTY))
    // Recipes from the Chrome extension wait here for the review screen (P4-R4); each one brings the window forward.
    val imports = ImportInbox()
    val showWindow: () -> Unit = { SwingUtilities.invokeLater { shell.show() } }
    // One photo downloader for the server's whole life: each owns an HttpClient, too costly to make per request.
    val photos = ImageFetcher()
    // Plan 8: the update check, only in the installed app (DesktopUpdates.unavailableReason), with its downloads in the
    // app data folder's updates (P7-R10). Install hands the verified MSI to explorer.exe (P8-PF2), then quits through
    // UpdateQuit, set once the window's composition has the quit and the LeaveGuard. Install is confirmed in Settings
    // first (spec rule 17), but the download can take a minute: the installer starts only while the quit is there
    // (P8-R6a) and no form or import review holds the guard (P8-F1); otherwise the verified file waits and the notice
    // asks to save or leave the edit, then Install again.
    val updateQuit = UpdateQuit()
    val updates = DesktopUpdates.create(
        appDataDir,
        settings,
        quit = { SwingUtilities.invokeLater { updateQuit.quit() } },
        canQuit = updateQuit::canQuit,
    )
    val app = DesktopApp(
        libraryDir,
        prefsNode,
        appDataDir = appDataDir,
        settingsFactory = { settings },
        serverFactory = { desktop ->
            val importer = ExtensionImport(desktop.container::newImportStagingDir, photos::fetch, imports, onReceived = showWindow)
            val voice = VoiceActions(desktop.container)
            MealPlannerServer(
                token,
                ServerRoutes(
                    listOf<Route.() -> Unit>(
                        { extensionRoutes(importer, onShowReview = showWindow) },
                        { voiceRoutes(voice, token = { token.value }) },
                        // Plan 2026-11-14: an MCP server for AI assistants on this PC (docs/MCP.md).
                        { mcpRoutes(McpServer(McpTools(desktop.container), version)) },
                    ),
                ),
                port = port,
                // P6-R8: while an older household's PC answers Alexa, this one stays on this PC (the token is kept); so
                // does a client PC's (stage 4a, R4: its main PC answers Alexa; the Chrome extension still works here).
                lanAllowed = desktop::serverOnLan,
            )
        },
        // Other Meal Planner PCs on the network (plan 6): announced after the startup sync, looked for at start and when
        // Settings opens. Only the installed app, or a preview run with -Pmealplanner.peers=on (on its own port, so two
        // previews can find each other), looks at all (lookForPeers).
        peersFactory = if (!discoveryOn) {
            null
        } else {
            { desktop ->
                PeerWatch(
                    JmdnsDiscovery(),
                    // Stage 4a: a client says so and offers no sync port; Join and Leave announce it afresh (roleChanged).
                    own = {
                        if (desktop.client?.isClient() == true) {
                            desktop.identity.record(PeerTxt.pcName(), version, role = PeerTxt.ROLE_CLIENT)
                        } else {
                            desktop.identity.record(PeerTxt.pcName(), version).copy(syncPort = syncPort)
                        }
                    },
                    port = port,
                    // P6-R8: the server listens again as the token and the yield allow; so does the sync port (stage 2b),
                    // which also closes while another main PC of this household is seen (stage 4a).
                    onYieldChanged = { rebindAll(desktop) },
                )
            }
        },
        // LAN sync's master side (stage 2b): closed until a device is paired or Pair a device is open, and while an older
        // household's PC answers. Its certificate and sealed password live beside the secrets file, in the app data folder.
        syncFactory = { desktop ->
            DesktopSync(
                database = { desktop.container.database },
                recipes = { desktop.container.recipes },
                imagesDir = desktop.imagesDir,
                certificate = SyncCertificate(appDataDir, DpapiProtector()),
                household = { desktop.identity.household.id() },
                pcName = { PeerTxt.pcName() },
                // Stage 4a: never on a client (R4), nor while another main PC of this household is seen (R12).
                lanAllowed = desktop::syncOnLan,
                addresses = { PeerInterfaces.pick(PeerInterfaces.system()).map { it.hostAddress } },
                port = syncPort,
                // Stage 4b: the token goes with the main PC's role; once the taker is done this PC becomes its client
                // and forgets the token (DesktopSync forgets the devices and the certificate).
                alexaToken = { token.value },
                onHandedOver = { handed ->
                    desktop.client?.joinByHandover(handed)
                    token.clear()
                },
            )
        },
        // LAN sync's client side (stage 4a): Join a household in Settings makes this PC a client of the main PC. Its
        // pairing is sealed with DPAPI in the app data folder; a Join or a Leave rebinds both servers and re-announces.
        clientFactory = { desktop ->
            DesktopClient(
                database = { desktop.container.database },
                recipes = { desktop.container.recipes },
                imagesDir = desktop.imagesDir,
                store = DpapiPairingStore(File(appDataDir, DpapiPairingStore.FILE_NAME), DpapiProtector()),
                finder = JmdnsPcFinder(ownId = { desktop.identity.instanceId() }),
                deviceName = DeviceNames.clean(PeerTxt.pcName(), DesktopClient.FALLBACK_NAME),
                onRoleChanged = {
                    rebindAll(desktop)
                    desktop.peers?.roleChanged()
                    desktop.joinOffer?.roleChanged()
                },
                // Stage 4b: offered the main PC's role, this PC takes it: the certificate and devices into its sync side,
                // the token into its secrets file. A hand-over cut short is kept sealed until it is finished.
                taker = object : HandoverTaker {
                    override suspend fun here(): HandoverHere {
                        val sync = checkNotNull(desktop.sync) { "No sync side." }
                        val addresses = PeerInterfaces.pick(PeerInterfaces.system()).map { it.hostAddress }.take(PairingQr.MAX_ADDRESSES)
                        return HandoverHere(DeviceNames.clean(PeerTxt.pcName(), DesktopClient.FALLBACK_NAME), addresses, syncPort, sync.hub.hubId())
                    }

                    override suspend fun adopt(bundle: HandoverBundle, masterKeyHash: String) {
                        checkNotNull(desktop.sync) { "No sync side." }.adopt(bundle, masterKeyHash)
                        bundle.alexaToken?.let(token::adopt)
                    }
                },
                pending = HandoverPending(File(appDataDir, HandoverPending.FILE_NAME), DpapiProtector()),
            )
        },
    )
    val started = app.start()
    // At launch, in the background: at most once a day, and only while Automatically is on (Updates decides).
    updates.startLaunchCheck()
    // P7-R6: smoke-packaged.ps1's self-check, only with -Dmealplanner.selfCheck=on (the installed launcher never passes
    // it). It checks what packaging can lose, keeps the app up while the script asks /healthz, then hides the window and
    // closes the app as Quit does (P7-PF7: the hidden window, then the bounded close, then the lock; the exit hook then
    // finds it closed) and ends the process with the result.
    val selfCheckOn = SelfCheck.enabled()
    if (selfCheckOn) {
        val serverListening: Pair<String, () -> String> = "server" to {
            val server = checkNotNull(app.server) { "there is no server" }
            val listening = runBlocking {
                withTimeoutOrNull(SelfCheck.READY_MILLIS) {
                    started.join()
                    server.status.first { it.state == ServerState.LISTENING }
                    true
                }
            }
            check(listening == true) { "not listening on port $port within ${SelfCheck.READY_MILLIS / 1000} s" }
            "port $port"
        }
        thread(isDaemon = true, name = "self-check") {
            SelfCheck.runThenQuit(
                checks = listOf(serverListening) + SelfCheck.standardChecks(),
                close = {
                    SwingUtilities.invokeAndWait { shell.quit() }
                    app.close().also { closed -> if (closed) instance.close() }
                },
                // After a close that timed out (exit 4) the exit hook (closeOnExit) tries the same bounded close once
                // more as the JVM ends; that can't change the exit code, which is already 4, and it is bounded too.
                exit = { code -> exitProcess(code) },
            )
        }
    }
    // P6-R5: JmDNS may leave threads of its own running (its listener executor's are non-daemon), and none may keep the
    // process alive after Quit or a crash. Compose's application() already ends the process when it ends
    // (exitProcessOnExit, on by default); runThenExit ends it however what follows the start goes.
    runThenExit(explain = { explainFailure(logFile, { System.err.println(it) }, ::showFailureDialog) }) {
        // Signing out or shutting Windows down ends the JVM without Quit: the same bounded close runs then (N7). First,
        // so a crash below that ends the process closes the app too.
        val exitHook = closeOnExit(app)
        Runtime.getRuntime().addShutdownHook(exitHook)
        // Settings' view of the server: its status, and Create a token (P4-R6).
        // Stage 4a, R4: a client PC makes no token; its main PC answers Alexa.
        val controls = app.server?.let { s ->
            DesktopServerControls(s, token, refusal = { app.client?.master?.value?.let(SyncMessages::clientAlexa) })
        }
        // Settings' Folders panel and the Controlled folder access notice (P7-R10).
        // P7-R11: the installed app can ask Windows (UAC) to allow it through Controlled folder access.
        // The prompt belongs to the app's window (set once it is composed), so it opens over it.
        val mainWindow = AtomicReference<java.awt.Window?>(null)
        val storage = DesktopStorage(
            app,
            allow = AllowApp(JnaElevatedRunner(owner = { mainWindow.get() }), recheck = { app.checkLibraryAgain() }),
        )
        // Google Calendar (P5-R4): the client from the secrets file, else the one built into the app (Task 13), and the
        // refresh token sealed with DPAPI beside the secrets file; the preview, like the token, keeps its own in its data
        // folder.
        val google = GoogleAccount(
            secrets,
            GoogleTokenStore(File(secrets.file.absoluteFile.parentFile, GoogleTokenStore.FILE_NAME), DpapiProtector()),
            settings,
            database = { app.container.database },
            builtInClient = GoogleClients.builtIn(),
            // P6-R8: only the older household's PC on the network sends to Google.
            sendRefusal = { app.peers?.yieldTo()?.let(PeerMessages::yieldGoogle) },
            // P6-FR1: and it looks for one just before each send, so a PC that came up since the last look is known.
            lookFirst = { app.peers?.lookNow() },
            // P6-R4: the app's one Household, so a send never dates the household differently from startup.
            household = { app.identity.household },
        )
        val startup = StartWithWindows(RegExe(), settings)
        // reg.exe takes a moment, so off the main thread. Only the installed app ever writes the Run key.
        thread(isDaemon = true, name = "start-with-windows") { startup.applyAtStartup() }
        application {
            val scope = rememberCoroutineScope()
            val trayState = rememberTrayState()
            val icon = remember { appIcon() }
            // P4-R1: a start that stays in the tray still hears, once, that port 5000 is taken.
            val portNotice = remember { PortNotice() }
            val server = app.server
            if (shell.traySupported && server != null) {
                LaunchedEffect(server) {
                    server.status.collect { status ->
                        if (portNotice.take(status)) {
                            trayState.sendNotification(Notification(TrayNotice.TITLE, portInUseNotice(status.port), Notification.Type.Warning))
                        }
                    }
                }
            }
            // The screens' guard, held here so Quit can ask it too; signing out of Windows can't be asked and isn't.
            val leaveGuard = remember { LeaveGuard() }
            // [afterRelease] is Restart now's relaunch (P7-R12), run only once the lock is released; null for Quit.
            val closeAndExit: ((() -> Unit)?) -> Unit = { afterRelease ->
                if (shell.quit()) {
                    // The UI thread stays free while the background work stops (a save may need it to finish);
                    // shutdown() runs off it, and the application ends back on it.
                    scope.launch {
                        shutdownThenExit(
                            shutdown = {
                                closeForQuit(
                                    shutdown = { app.shutdown() },
                                    // The log needs no closing: DesktopLog writes each record straight through.
                                    release = instance::close,
                                    afterRelease = afterRelease,
                                )
                            },
                            exit = { exitWithoutHook(exitHook, ::exitApplication) },
                        )
                    }
                }
            }
            val quitNow: () -> Unit = { closeAndExit(null) }
            // P8-F1: the update's quit goes through the guard too, so an edit begun in the moment between the check and
            // the quit is still asked about rather than lost.
            SideEffect { updateQuit.attach(quit = guardedQuit(shell, leaveGuard, quitNow), holding = { leaveGuard.holding }) }
            // The tray's Quit, and closing the window when there is no tray. Off during the self-check, which quits by
            // itself: a second quit racing its close could end the process mid-close or with the wrong exit code.
            val quit = SelfCheck.userQuit(selfCheckOn, guardedQuit(shell, leaveGuard, quitNow))
            // P7-R12: Restart now is the same guarded quit, then the installed launcher once the lock is released.
            val restart = SelfCheck.userQuit(selfCheckOn, guardedQuit(shell, leaveGuard) { closeAndExit { relauncher.launch() } })
            DisposableEffect(restart) {
                restartAction.set(restart)
                onDispose { restartAction.set {} }
            }
            val replacedState by replaced.replaced.collectAsState()
            // Plan 8: an update found while the app sits in the tray shows in the tray icon's tooltip too; a new version
            // already installed over this copy (P7-R12) says so first.
            val updateStatus by updates.state.collectAsState()
            if (shell.traySupported && !shell.quitting) {
                Tray(
                    icon = icon,
                    state = trayState,
                    tooltip = if (replacedState != null) trayTooltip(replacedState) else trayTooltip(updateStatus),
                    onAction = shell::show,
                    menu = {
                        Item("Open Meal Planner", onClick = shell::show)
                        Item("Quit", onClick = quit)
                    },
                )
            }
            val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
            // Hidden, not removed: with no window left the application would end before the database is closed.
            Window(
                visible = shell.isVisible,
                onCloseRequest = {
                    when (shell.closeRequested()) {
                        CloseOutcome.HIDDEN -> Unit
                        CloseOutcome.HIDDEN_FIRST_TIME ->
                            trayState.sendNotification(Notification(TrayNotice.TITLE, TrayNotice.MESSAGE, Notification.Type.Info))
                        CloseOutcome.QUIT -> quit()
                    }
                },
                title = "Meal Planner",
                icon = icon,
                state = windowState,
            ) {
                DisposableEffect(window) {
                    mainWindow.set(window)
                    onDispose { mainWindow.compareAndSet(window, null) }
                }
                LaunchedEffect(shell.raiseRequests) {
                    if (shell.raiseRequests > 0) {
                        windowState.isMinimized = false
                        bringToFront(window)
                    }
                }
                if (shell.hasBeenShown) {
                    // File dialogs belong to this window (P3-R5): they open over it and keep it modal.
                    val chooser = remember(window) { AwtFileChooser(window) }
                    // Settings > Appearance (owner, 2026-10-12): light, dark, or Windows' own app mode.
                    val themeMode by app.container.theme.mode.collectAsState()
                    val dark = themeMode.isDark(isSystemInDarkTheme())
                    // The window's own ground, so a resize never shows a white edge behind the dark app.
                    SideEffect { window.background = java.awt.Color((if (dark) MealPalette.Dark else MealPalette.Light).paper.toArgb()) }
                    // Windows draws the title bar, so it is asked to match (best effort; DarkTitleBar).
                    LaunchedEffect(window, dark) { DarkTitleBar.apply(window, dark) }
                    CompositionLocalProvider(LocalFileChooser provides chooser) {
                        MealPlannerTheme(dark = dark) {
                            MealPlannerApp(
                                app.container,
                                startup = startup,
                                leaveGuard = leaveGuard,
                                imports = imports,
                                server = controls,
                                google = google,
                                // A Quit under way: a recipe from the extension waiting behind the review it left stays put.
                                quitting = { shell.quitting },
                                // Plan 6: another Meal Planner PC on the network, in the banner and Settings.
                                peers = app.peers,
                                // P7-R10: where the recipes and the app data are, and the notice while Documents is refused.
                                storage = storage,
                                // P7-R12: a new version installed over this running copy, with Restart now.
                                restart = replaced,
                                // Plan 8: Settings' Updates section and the launch notice.
                                updates = updates,
                                // LAN sync (stage 2b): Settings' Phones and PCs.
                                devices = app.sync,
                                // LAN sync (stage 2b): held sync removals in Needs attention and on the Recipes banner.
                                syncAttention = app.sync,
                                // LAN sync stage 4a: Settings' Join a household, the Sync button once joined, and a
                                // first run's offer to join the main PC found on the network.
                                join = app.client?.controls,
                                sync = app.client?.controls,
                                joinOffer = app.joinOffer,
                            )
                        }
                    }
                }
            }
        }
    }
}

// The server and the sync port listen again as the token, the yield and this PC's role allow. Each in its own try, so
// one that fails never keeps the other from listening as it should.
private fun rebindAll(desktop: DesktopApp) {
    try {
        desktop.server?.rebind()
    } catch (e: Exception) {
        System.err.println("Meal Planner: rebinding the server failed: ${e.javaClass.simpleName}")
    }
    try {
        desktop.sync?.rebind()
    } catch (e: Exception) {
        System.err.println("Meal Planner: rebinding the sync port failed: ${e.javaClass.simpleName}")
    }
}

// Windows lets a background process raise its window only now and then; a moment of always-on-top gets past that.
private fun bringToFront(window: Frame) {
    window.isAlwaysOnTop = true
    window.toFront()
    window.requestFocus()
    window.isAlwaysOnTop = false
}
