package com.naeblis11.mealplanner.ui

import com.naeblis11.mealplanner.kroger.KrogerSetting
import com.naeblis11.mealplanner.kroger.KrogerSettingsActions
import com.naeblis11.mealplanner.kroger.KrogerSettingsViewModel
import com.naeblis11.mealplanner.kroger.LocalKroger
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.settings.AlexaState
import com.naeblis11.mealplanner.settings.AllowAppControls
import com.naeblis11.mealplanner.settings.AlexaViewModel
import com.naeblis11.mealplanner.settings.BackupViewModel
import com.naeblis11.mealplanner.settings.CalendarSetupViewModel
import com.naeblis11.mealplanner.settings.DeviceActions
import com.naeblis11.mealplanner.settings.DevicesState
import com.naeblis11.mealplanner.settings.FoldersState
import com.naeblis11.mealplanner.settings.GoogleActions
import com.naeblis11.mealplanner.settings.GoogleControls
import com.naeblis11.mealplanner.settings.GoogleState
import com.naeblis11.mealplanner.settings.GoogleViewModel
import com.naeblis11.mealplanner.settings.JoinActions
import com.naeblis11.mealplanner.settings.JoinState
import com.naeblis11.mealplanner.settings.JoinViewModel
import com.naeblis11.mealplanner.settings.PairingControls
import com.naeblis11.mealplanner.settings.PairingViewModel
import com.naeblis11.mealplanner.settings.PeerControls
import com.naeblis11.mealplanner.settings.PeerNotice
import com.naeblis11.mealplanner.settings.ServerControls
import com.naeblis11.mealplanner.settings.SettingsScreen
import com.naeblis11.mealplanner.settings.StartupState
import com.naeblis11.mealplanner.settings.StartupSwitch
import com.naeblis11.mealplanner.settings.StartupViewModel
import com.naeblis11.mealplanner.settings.StorageControls
import com.naeblis11.mealplanner.settings.UpdateActions
import com.naeblis11.mealplanner.settings.UpdateUiState
import com.naeblis11.mealplanner.settings.UpdateViewModel
import com.naeblis11.mealplanner.sync.JoinControls
import com.naeblis11.mealplanner.update.UpdateControls
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: another Meal Planner PC's notice, Start with Windows, the server and Google Calendar's sign-in (desktop
 * only), where the desktop keeps things (P7-R10), Phones and PCs (LAN sync stage 2b, desktop only), Join a PC (LAN sync
 * stage 2c, Android only), the phone's calendar, backup, import, updates and About.
 */
fun NavGraphBuilder.settingsDestination(
    nav: NavController,
    container: AppContainer,
    backupVm: BackupViewModel,
    startImport: () -> Unit,
    startup: StartupSwitch? = null,
    server: ServerControls? = null,
    google: GoogleControls? = null,
    peers: PeerControls? = null,
    storage: StorageControls? = null,
    updates: UpdateControls? = null,
    devices: PairingControls? = null,
    join: JoinControls? = null,
) {
    composable(Routes.SETTINGS) {
        // P6-R6: opening Settings looks for other Meal Planner PCs again; what was found tops the screen (P6-R8).
        if (peers != null) LaunchedEffect(peers) { peers.browseNow() }
        // P7-R10: which of the two folders is there to open. Only read: nothing is written to look (P7-R10b); the
        // library is checked again only when the user presses Check again.
        // M6: read again after each Check again, which may have made the library folder.
        var foldersRead by remember { mutableIntStateOf(0) }
        val folders by produceState<FoldersState?>(null, storage, foldersRead) {
            if (storage != null) {
                value = withContext(Dispatchers.IO) {
                    FoldersState(storage.libraryPath, storage.appDataPath, storage.libraryExists(), storage.appDataExists())
                }
            }
        }
        val themeMode by container.theme.mode.collectAsStateWithLifecycle()
        val libraryNotice by (storage?.libraryNotice ?: NO_LIBRARY_NOTICE).collectAsStateWithLifecycle()
        val libraryChecking by (storage?.checking ?: NOT_CHECKING).collectAsStateWithLifecycle()
        val checkScope = rememberCoroutineScope()
        val peerNotice by (peers?.notice ?: NO_PEERS).collectAsStateWithLifecycle()
        val backup by backupVm.state.collectAsStateWithLifecycle()
        val exportGuard = rememberLaunchGuard()
        val saveBackup = rememberSaveFile(exportGuard, "application/zip") { target ->
            backupVm.export(open = target.open, deleteDestination = target.delete)
        }
        val calendarVm: CalendarSetupViewModel = viewModel { CalendarSetupViewModel(container.calendarGateway, container.calendarChoice) }
        val calendar by calendarVm.state.collectAsStateWithLifecycle()
        val requestCalendarPermission = rememberCalendarPermissionRequest(calendarVm::permissionResult)
        val openAppSettings = rememberAppSettingsOpener(calendarVm::returnedFromAppSettings)
        val version = rememberAppVersion()
        val startupVm: StartupViewModel? = startup?.let { switch -> viewModel { StartupViewModel(switch) } }
        val startupState by (startupVm?.state ?: NO_STARTUP).collectAsStateWithLifecycle()
        val alexaVm: AlexaViewModel? = server?.let { controls -> viewModel { AlexaViewModel(controls) } }
        val alexa by (alexaVm?.state ?: NO_SERVER).collectAsStateWithLifecycle()
        // P4-R6: a token on screen leaves memory with Settings; one made meanwhile waits for Settings' return.
        if (alexaVm != null) {
            DisposableEffect(alexaVm) {
                alexaVm.shown()
                onDispose { alexaVm.left() }
            }
        }
        // P5-R5: the desktop's Google sign-in, in place of the phone's calendar setup.
        val googleVm: GoogleViewModel? = google?.let { controls -> viewModel { GoogleViewModel(controls) } }
        val googleState by (googleVm?.state ?: NO_GOOGLE).collectAsStateWithLifecycle()
        // The rail and the tabs keep Settings' entry (saveState), so its view model outlives the screen: leaving it
        // cancels a sign-in still waiting on the browser, as AlexaViewModel's left() hides a token.
        if (googleVm != null) {
            DisposableEffect(googleVm) { onDispose { googleVm.left() } }
        }
        // Plan 8: the update check's section, on both apps.
        val updateVm: UpdateViewModel? = updates?.let { controls -> viewModel { UpdateViewModel(controls) } }
        val updateState by (updateVm?.state ?: NO_UPDATES).collectAsStateWithLifecycle()
        // Stage 2b: Phones and PCs. Leaving Settings closes a pairing window left open (D10).
        val devicesVm: PairingViewModel? = devices?.let { controls -> viewModel { PairingViewModel(controls) } }
        val devicesState by (devicesVm?.state ?: NO_PAIRING).collectAsStateWithLifecycle()
        if (devicesVm != null) {
            DisposableEffect(devicesVm) {
                devicesVm.shown()
                onDispose { devicesVm.left() }
            }
        }
        // Stage 2c: Join a PC (Android). The Wi-Fi is read again whenever Settings comes back to the front (D3).
        val joinVm: JoinViewModel? = join?.let { controls -> viewModel { JoinViewModel(controls) } }
        val joinState by (joinVm?.state ?: NO_JOIN).collectAsStateWithLifecycle()
        if (joinVm != null) LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { joinVm.recheckWifi() }
        val scanGuard = rememberLaunchGuard()
        val scan = rememberCodeScanner(scanGuard, onScanned = { text -> joinVm?.scanned(text) }, onUnavailable = { joinVm?.scanUnavailable() })
        val openWifiSettings = rememberWifiSettingsOpener { joinVm?.recheckWifi() }
        // Plan 2026-11-17: Kroger, on both apps. Leaving Settings cancels a sign-in still waiting on the browser.
        val krogerVm: KrogerSettingsViewModel? = LocalKroger.current?.let { controls -> viewModel { KrogerSettingsViewModel(controls) } }
        if (krogerVm != null) {
            DisposableEffect(krogerVm) { onDispose { krogerVm.left() } }
        }
        val krogerState = krogerVm?.state?.collectAsStateWithLifecycle()?.value
        val openMatches = dropUnlessResumed { nav.navigate(Routes.KROGER_MATCHES) }
        val clientGuard = rememberLaunchGuard()
        // Android's Settings registers no picker of its own for it.
        val chooseClientFile: () -> Unit =
            if (googleVm != null) rememberChooseFile(clientGuard, CLIENT_FILE_TITLE, "*.json") { picked -> googleVm.useClientFile(picked) } else NOTHING
        SettingsScreen(
            backup = backup,
            calendar = calendar,
            version = version,
            onBack = dropUnlessResumed { nav.popBackStack() },
            onExport = { saveBackup("meal-planner-backup-${LocalDate.now()}.zip") },
            onImport = startImport,
            onDismissBackup = backupVm::dismiss,
            // The system dialog only when it is needed; once allowed, straight to the list.
            onSetUpCalendar = { if (calendarVm.hasPermission()) calendarVm.loadCalendars() else requestCalendarPermission() },
            onChooseCalendar = calendarVm::choose,
            onCancelChoosing = calendarVm::cancelChoosing,
            onOpenAppSettings = openAppSettings,
            startup = startupState,
            onStartupChange = { on -> startupVm?.set(on) },
            server = alexa,
            onCreateToken = { alexaVm?.createToken() },
            onCopyToken = { alexaVm?.copyToken() },
            onTokenDone = { alexaVm?.tokenDone() },
            google = googleState,
            googleActions = googleVm?.let { vm ->
                GoogleActions(vm::signIn, vm::cancelSignIn, vm::loadCalendars, vm::choose, vm::cancelChoosing, vm::signOut, chooseClientFile, vm::revokeAccess)
            } ?: GoogleActions(),
            peerNotice = peerNotice,
            folders = folders,
            libraryNotice = libraryNotice,
            libraryChecking = libraryChecking,
            onCheckLibraryAgain = {
                storage?.let { s ->
                    checkScope.launch {
                        withContext(Dispatchers.IO) { s.checkAgain() }
                        foldersRead++
                    }
                }
            },
            onOpenLibrary = { storage?.openLibrary() },
            onOpenAppData = { storage?.openAppData() },
            folderExtras = { if (storage != null) AllowAppControls(storage) },
            devices = devicesState,
            deviceActions = devicesVm?.let { vm ->
                DeviceActions(
                    vm::pair, vm::cancelPairing, vm::askRemove, vm::confirmRemove, vm::cancelRemove,
                    askHandover = vm::askHandover,
                    confirmHandover = vm::confirmHandover,
                    cancelHandoverAsk = vm::cancelHandoverAsk,
                    cancelHandover = vm::cancelHandover,
                )
            } ?: DeviceActions(),
            join = joinState,
            joinActions = joinVm?.let { vm ->
                JoinActions(
                    scan = { scan?.invoke() ?: vm.scanUnavailable() },
                    openTyping = vm::openTyping,
                    cancelTyping = vm::cancelTyping,
                    submitCode = vm::submitCode,
                    choose = vm::choose,
                    askLeave = vm::askLeave,
                    confirmLeave = vm::confirmLeave,
                    cancelLeave = vm::cancelLeave,
                    openWifiSettings = openWifiSettings,
                )
            } ?: JoinActions(),
            updates = updateState,
            updateActions = updateVm?.let { vm ->
                UpdateActions(vm::checkNow, vm::setAutomatic, vm::install, vm::confirmInstall, vm::cancelInstall, vm::openInstallPermission)
            } ?: UpdateActions(),
            themeMode = themeMode,
            onThemeModeChange = container.theme::set,
            kroger = if (krogerVm == null || krogerState == null) null else {
                {
                    KrogerSetting(
                        krogerState,
                        KrogerSettingsActions(
                            signIn = krogerVm::signIn,
                            cancelSignIn = krogerVm::cancelSignIn,
                            signOut = krogerVm::signOut,
                            findStores = krogerVm::findStores,
                            chooseStore = krogerVm::chooseStore,
                            changeStore = krogerVm::changeStore,
                            setModality = krogerVm::setModality,
                            useClient = krogerVm::useClient,
                            forgetClient = krogerVm::forgetClient,
                            openMatches = openMatches,
                        ),
                    )
                }
            },
        )
    }
}

// Android has no Start with Windows.
private val NO_STARTUP: StateFlow<StartupState?> = MutableStateFlow(null)

// Android has no built-in server.
private val NO_SERVER: StateFlow<AlexaState?> = MutableStateFlow(null)

// Android has no Google sign-in of its own in phase 1.
private val NO_GOOGLE: StateFlow<GoogleState?> = MutableStateFlow(null)

// Android has no Documents folder to be kept from.
private val NO_LIBRARY_NOTICE: StateFlow<String?> = MutableStateFlow(null)

private val NOT_CHECKING: StateFlow<Boolean> = MutableStateFlow(false)

// Android looks for no other PCs in phase 1.
private val NO_PEERS: StateFlow<PeerNotice?> = MutableStateFlow(null)

// No update check given (tests that leave it out).
private val NO_UPDATES: StateFlow<UpdateUiState?> = MutableStateFlow(null)

// Android has no sync port of its own.
private val NO_PAIRING: StateFlow<DevicesState?> = MutableStateFlow(null)

// The desktop has no Join a PC.
private val NO_JOIN: StateFlow<JoinState?> = MutableStateFlow(null)

private val NOTHING: () -> Unit = {}

private const val CLIENT_FILE_TITLE = "Choose the Google client file"
