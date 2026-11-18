package com.naeblis11.mealplanner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.app.ThemeMode
import com.naeblis11.mealplanner.calendar.CalendarInfo
import com.naeblis11.mealplanner.calendar.CalendarMessages
import com.naeblis11.mealplanner.calendar.GoogleCalendar
import com.naeblis11.mealplanner.sync.SyncMessages
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.theme.MealColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backup: BackupState,
    calendar: CalendarSetupState,
    version: String,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismissBackup: () -> Unit,
    onSetUpCalendar: () -> Unit,
    onChooseCalendar: (CalendarInfo) -> Unit,
    onCancelChoosing: () -> Unit,
    onOpenAppSettings: () -> Unit,
    /** Desktop: Start with Windows; null hides the panel (Android, and while it is being read). */
    startup: StartupState? = null,
    onStartupChange: (Boolean) -> Unit = {},
    /** Desktop: the built-in server's panel (the Chrome extension and Alexa); null hides it (Android). */
    server: AlexaState? = null,
    onCreateToken: () -> Unit = {},
    onCopyToken: () -> Unit = {},
    onTokenDone: () -> Unit = {},
    /** Desktop: the Google Calendar sign-in (P5-R5), in place of the phone's calendar setup; null on Android. */
    google: GoogleState? = null,
    googleActions: GoogleActions = GoogleActions(),
    /** Desktop: another Meal Planner PC on the network (plan 6), atop Settings and in the Alexa section; null hides it. */
    peerNotice: PeerNotice? = null,
    /** Plan 8: the update check's section (both apps); null hides it. */
    updates: UpdateUiState? = null,
    updateActions: UpdateActions = UpdateActions(),
    /** Desktop: where the recipes and the app data are (P7-R10); null hides the panel (Android, and while it is read). */
    folders: FoldersState? = null,
    /** Desktop: what the window says about Documents (P7-R10), atop Settings with Check again; null hides it. */
    libraryNotice: String? = null,
    /** Desktop: Check again, the only time Settings writes to the library to look (P7-R10b). */
    onCheckLibraryAgain: () -> Unit = {},
    /** Desktop: Check again is writing; its button is disabled meanwhile (M5). */
    libraryChecking: Boolean = false,
    onOpenLibrary: () -> Unit = {},
    onOpenAppData: () -> Unit = {},
    /** Desktop: more for the Folders panel (P7-R11's Allow Meal Planner); nothing on Android. */
    folderExtras: @Composable () -> Unit = {},
    /** Desktop: "Phones and PCs", LAN sync's pairing and devices (stage 2b); null hides it (Android). */
    devices: DevicesState? = null,
    deviceActions: DeviceActions = DeviceActions(),
    /** Android: "Join a PC", LAN sync's pairing with the household's PC (stage 2c); null hides it (the desktop). */
    join: JoinState? = null,
    joinActions: JoinActions = JoinActions(),
    /** Light, dark, or the system's choice (owner, 2026-10-12); null hides the panel. */
    themeMode: ThemeMode? = null,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    /** Kroger (plan 2026-11-17), both apps: the panel's content; null hides it. */
    kroger: (@Composable () -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // P6-R8: the window's notice, again at the top of Settings.
            peerNotice?.let { AttentionBanner(it.text) }
            // P7-R10: the window's notice, again at the top of Settings.
            if (libraryNotice != null) {
                AttentionBanner(libraryNotice)
                OutlinedButton(onClick = onCheckLibraryAgain, enabled = !libraryChecking, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (libraryChecking) "Checking..." else "Check again")
                }
            }
            if (folders != null) {
                Panel("Folders") {
                    FolderRow("Your recipes: ${folders.libraryPath}", folders.libraryThere, onOpenLibrary)
                    FolderRow("App data: ${folders.appDataPath}", folders.appDataThere, onOpenAppData)
                    // P7-R11: the installed app's "Allow Meal Planner (asks for admin)", while Documents is blocked.
                    folderExtras()
                }
            }
            if (startup != null) {
                Panel("Start with Windows") { StartupSetting(startup, onStartupChange) }
            }
            // Stage 4a, R4: a client PC's main PC answers Alexa and pairs the phones.
            val mainPc = join?.takeIf { it.forPc }?.status?.pcName
            if (server != null) {
                Panel(SERVER_PANEL) { ServerSetting(server, mainPc?.let(SyncMessages::clientAlexa) ?: peerNotice?.alexa, onCreateToken, onCopyToken, onTokenDone) }
            }
            if (devices != null) {
                // While an older household's PC answers (P6-R8), or this PC is a client (stage 4a), pairing is off and says why.
                val pairingOff = mainPc?.let(SyncMessages::clientDevices) ?: peerNotice?.takeIf { it.alexa != null }?.text
                Panel(DEVICES_PANEL) { DevicesSetting(devices, deviceActions, yielding = pairingOff) }
            }
            if (join != null) {
                Panel(if (join.forPc) JOIN_PANEL_PC else JOIN_PANEL) { JoinSetting(join, joinActions) }
            }
            Panel("Google Calendar") {
                if (google != null) {
                    GoogleSetting(google, googleActions)
                } else {
                    CalendarSetup(calendar, onSetUpCalendar, onChooseCalendar, onCancelChoosing, onOpenAppSettings)
                }
            }
            if (kroger != null) Panel(com.naeblis11.mealplanner.kroger.KROGER_PANEL) { kroger() }
            if (themeMode != null) {
                Panel(APPEARANCE_PANEL) { AppearanceSetting(themeMode, onThemeModeChange) }
            }
            Panel("Backup") {
                // Moving recipes to the PC is the phone's sentence; the PC (which has the server panel) only says what it is.
                Text(
                    if (server == null) {
                        "A zip of every recipe and photo. To move them to the PC, close Meal Planner there and unzip it into Documents\\Meal Planner."
                    } else {
                        "A zip of every recipe and photo, to keep somewhere safe or to load onto the phone."
                    },
                )
                when (backup) {
                    BackupState.Working -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text("Saving backup...")
                    }
                    is BackupState.Done -> { Text(backup.message, color = MealColors.AccentHover); TextButton(onClick = onDismissBackup) { Text("OK") } }
                    is BackupState.Failed -> { AttentionBanner(backup.message); TextButton(onClick = onDismissBackup) { Text("OK") } }
                    BackupState.Idle -> {}
                }
                Button(onClick = onExport, enabled = backup != BackupState.Working) { Text("Export backup") }
            }
            Panel("Import") {
                Text("Add recipes from a recipe file (.yaml), a Meal Master file (.mmf) or a backup (.zip). You review them before anything is saved.")
                OutlinedButton(onClick = onImport) { Text("Import recipes") }
            }
            if (updates != null) {
                Panel(UPDATES_PANEL) { UpdateSetting(updates, updateActions) }
            }
            Panel(PRIVACY_PANEL) { PrivacySetting() }
            Panel("About") {
                Text("Meal Planner $version")
                if (google != null) {
                    // The desktop goes online (Google, a recipe's photo, Home Assistant, its updates); the phone only
                    // for its updates (plan 8, ABOUT_PHONE_DATA).
                    Text(ABOUT_DESKTOP_DATA)
                    Text(ABOUT_DESKTOP_CALENDAR)
                    Text("Meal Planner is free software under the MIT License.")
                    Text(ABOUT_DESKTOP_BUILT_WITH)
                    Text(ABOUT_DESKTOP_FONTS)
                } else {
                    Text(ABOUT_PHONE_DATA)
                    Text(ABOUT_PHONE_SCANNER)
                    Text("Meals you send to a calendar are written to that calendar on this phone; Android's own calendar sync takes them from there.")
                    Text("Meal Planner is free software under the MIT License.")
                    Text("It is built with Kotlin, Jetpack Compose, AndroidX, Room, kotlinx.serialization and SnakeYAML, all under the Apache License 2.0.")
                    Text("It uses the phone's own fonts; none are bundled.")
                }
            }
        }
    }
}

/** One of the desktop's folders: its path, and Open folder while it is there to open. */
@Composable
private fun FolderRow(text: String, there: Boolean, onOpen: () -> Unit) {
    Column {
        SelectionContainer { Text(text) }
        if (there) TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open folder") }
    }
}

@Composable
private fun CalendarSetup(
    state: CalendarSetupState,
    onSetUp: () -> Unit,
    onChoose: (CalendarInfo) -> Unit,
    onCancel: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Text("Send a week's meals to a calendar on this phone, such as your Google calendar. Android syncs that calendar; Meal Planner itself goes online only to check GitHub for its own updates and, when you press Sync, to reach your PC.")
    val chosen = state.chosen
    if (chosen != null) Text("Sending to ${chosen.name}", style = MaterialTheme.typography.titleMedium)
    if (state.permissionDenied) {
        AttentionBanner(CalendarMessages.PERMISSION_DENIED)
        OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open app settings") }
    }
    state.error?.let { AttentionBanner(it) }
    when (val picker = state.picker) {
        CalendarPicker.Closed ->
            if (chosen == null) {
                Button(onClick = onSetUp, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set up calendar sending") }
            } else {
                OutlinedButton(onClick = onSetUp, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose another calendar") }
            }
        CalendarPicker.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Text("Looking for calendars...")
        }
        is CalendarPicker.Choosing -> {
            Text(if (picker.calendars.isEmpty()) CalendarMessages.NO_CALENDARS else "Choose the calendar to send meals to:")
            for (calendar in picker.calendars) {
                OutlinedButton(onClick = { onChoose(calendar) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(calendar.label) }
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
    }
}

/**
 * Settings' Privacy panel: the summary always, the rest behind Read the policy.
 *
 * The whole policy lives in the app rather than behind a link because the app is usable with no connection at all,
 * and a policy you can only read online is no use in a grocery store. [PrivacyPolicy] is the only copy; the one in
 * `docs/PRIVACY.md` is kept identical by `tests/test_privacy_policy.py`.
 */
@Composable
internal fun PrivacySetting() {
    var open by rememberSaveable { mutableStateOf(false) }
    Text(PrivacyPolicy.SUMMARY)
    if (open) {
        Text(PrivacyPolicy.UPDATED, color = MealColors.Muted, style = MaterialTheme.typography.bodySmall)
        PrivacyPolicy.SECTIONS.forEach { (heading, body) ->
            Text(heading, style = MaterialTheme.typography.titleMedium)
            Text(body)
        }
    }
    OutlinedButton(onClick = { open = !open }) { Text(if (open) HIDE_PRIVACY else READ_PRIVACY) }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MealColors.Paper), border = CardDefaults.outlinedCardBorder(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** Settings' Appearance panel (owner, 2026-10-12). */
const val APPEARANCE_PANEL = "Appearance"

/** Settings' Privacy panel, which holds the whole of [PrivacyPolicy]. */
const val PRIVACY_PANEL = "Privacy"
const val READ_PRIVACY = "Read the privacy policy"
const val HIDE_PRIVACY = "Hide the privacy policy"

/** The three choices, in the order they show; [ThemeMode.SYSTEM] is the default. */
val THEME_CHOICES: List<Pair<ThemeMode, String>> = listOf(
    ThemeMode.SYSTEM to "Match system",
    ThemeMode.LIGHT to "Light",
    ThemeMode.DARK to "Dark",
)

/** Light, dark, or whatever the PC or phone is set to; the app recolours at once and keeps the choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSetting(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Text("Choose a light or dark look, or follow this device's own setting.", color = MealColors.Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((choice, label) in THEME_CHOICES) {
            FilterChip(
                selected = mode == choice,
                onClick = { onChange(choice) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** Settings' desktop switch: a whole-row toggle, greyed outside the installed app, with Windows' refusal shown. */
@Composable
private fun StartupSetting(state: StartupState, onChange: (Boolean) -> Unit) {
    val enabled = state.available && !state.busy
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = state.on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Text(STARTUP_LABEL, modifier = Modifier.weight(1f))
        Switch(checked = state.on, onCheckedChange = null, enabled = enabled)
    }
    Text(if (state.available) STARTUP_HELP else STARTUP_UNAVAILABLE, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
    state.error?.let { AttentionBanner(it) }
}

const val STARTUP_LABEL = "Start Meal Planner when you sign in to Windows"
const val STARTUP_HELP = "It starts in the tray, without opening its window."
const val STARTUP_UNAVAILABLE = "Only the installed app can start with Windows."

/**
 * Settings' desktop server panel (P4-R6): where it listens, the extension's address, and Alexa's token. While a token
 * is being made the server moves onto the home network, so it says Starting... until that is done. While an older
 * household's PC on the network answers Alexa ([alexaElsewhere], P6-R8) it says so instead, and no token is made; one
 * already set up is kept.
 */
@Composable
private fun ServerSetting(state: AlexaState, alexaElsewhere: String?, onCreateToken: () -> Unit, onCopy: () -> Unit, onDone: () -> Unit) {
    val status = state.status
    if (state.busy) {
        Text(SERVER_STARTING, color = MealColors.Muted)
    } else {
        when (status.state) {
            ServerState.LISTENING -> Text(serverListening(status.port, status.onLan))
            ServerState.PORT_IN_USE -> AttentionBanner(portInUseNotice(status.port))
            ServerState.FAILED -> AttentionBanner(SERVER_FAILED)
            ServerState.STARTING -> Text(SERVER_STARTING, color = MealColors.Muted)
            ServerState.STOPPED -> Text(SERVER_STOPPED, color = MealColors.Muted)
        }
    }
    Text(extensionHelp(status.port), color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
    // While another PC answers Alexa neither button makes a token; the one set up stays in the secrets file.
    val enabled = alexaElsewhere == null && !state.busy
    if (status.tokenConfigured) {
        Text(alexaElsewhere ?: ALEXA_ON)
        // A lost token is never a dead end: a new one replaces it, once Home Assistant has the new line.
        if (state.newToken == null) {
            if (alexaElsewhere == null) Text(NEW_TOKEN_WARNING, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onCreateToken, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(NEW_TOKEN) }
        }
    } else {
        Text(alexaElsewhere ?: ALEXA_OFF)
        Button(onClick = onCreateToken, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(CREATE_TOKEN) }
    }
    // Shown once: the token lives only in the view model until Done or leaving Settings; nothing here saves it or
    // describes it elsewhere.
    val token = state.newToken
    if (token != null) {
        Text(TOKEN_ONCE)
        SelectionContainer { Text(secretsLine(token), fontFamily = FontFamily.Monospace) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.copied) "Copied" else "Copy") }
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
        }
    }
    state.error?.let { AttentionBanner(it) }
}

const val SERVER_PANEL = "Chrome extension and Alexa"
const val SERVER_STARTING = "Starting..."
const val SERVER_STOPPED = "Stopped."
const val SERVER_FAILED =
    "Meal Planner couldn't start its server, so the Chrome extension and Alexa won't reach it. The log in %LOCALAPPDATA%\\Meal Planner\\.cache says why."
const val ALEXA_ON = "Alexa: a voice token is set up, so Home Assistant can add to your lists and plan meals."
const val ALEXA_OFF = "Alexa: not set up. Create a token, then put it in Home Assistant."
const val TOKEN_ONCE = "Put this line in Home Assistant's secrets.yaml. It is only shown now."
const val CREATE_TOKEN = "Create a token"
const val NEW_TOKEN = "Make a new token"
const val NEW_TOKEN_WARNING = "Home Assistant will stop working until you paste the new line into its secrets.yaml."

fun serverListening(port: Int, onLan: Boolean): String =
    if (onLan) "Listening on port $port on your home network, so Home Assistant can reach it for Alexa." else "Listening on port $port on this PC only."

fun extensionHelp(port: Int): String =
    "The Chrome extension sends recipes to http://127.0.0.1:$port on this PC; each one opens here for review."

/** What Settings' Google section can ask for (the desktop's GoogleViewModel); all nothing by default. */
class GoogleActions(
    val signIn: () -> Unit = {},
    val cancelSignIn: () -> Unit = {},
    val loadCalendars: () -> Unit = {},
    val choose: (GoogleCalendar) -> Unit = {},
    val cancelChoosing: () -> Unit = {},
    val signOut: () -> Unit = {},
    val chooseClientFile: () -> Unit = {},
    val revokeAccess: () -> Unit = {},
)

/**
 * Settings' Google section on the desktop (P5-R5): without a client, how to make one and where to pick its file;
 * with one, that client read-only (S2); signed out, Sign in; while the browser is open, the address and Cancel; signed
 * in, the account, the calendar meals go to, Choose calendar, Sign in again (for when Google stops honouring the
 * sign-in, P5-T6a), Sign out (this PC only), and Revoke access at Google, which asks first (S1). One thing runs at a
 * time: every button waits while something is under way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoogleSetting(state: GoogleState, actions: GoogleActions) {
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }
    Text(GOOGLE_INTRO)
    val status = state.status
    val idle = state.phase == GooglePhase.IDLE
    when {
        state.phase == GooglePhase.SIGNING_IN -> {
            Text(GOOGLE_WAITING)
            state.signInUrl?.let { url ->
                Text(GOOGLE_OPEN_BY_HAND, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
                SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = actions.cancelSignIn, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
        // S2: the picker only while no client is set up.
        !status.clientReady -> {
            Text(GOOGLE_NEEDS_CLIENT, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.chooseClientFile, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text(CHOOSE_CLIENT_FILE) }
        }
        !status.signedIn -> {
            Text(GOOGLE_SIGNED_OUT)
            Button(onClick = actions.signIn, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text(SIGN_IN_WITH_GOOGLE) }
            ClientInUse(status.clientId)
        }
        else -> {
            Text(signedInAs(status.account), style = MaterialTheme.typography.titleMedium)
            Text(status.calendar?.let { sendingTo(it.name) } ?: NO_GOOGLE_CALENDAR)
            val picker = state.calendars
            when {
                state.phase == GooglePhase.LOADING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(GOOGLE_LOADING)
                }
                picker != null -> {
                    state.note?.let { AttentionBanner(it) }
                    Text(if (picker.isEmpty()) NO_WRITABLE_CALENDARS else "Choose the calendar to send meals to:")
                    for (calendar in picker) {
                        OutlinedButton(onClick = { actions.choose(calendar) }, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(calendar.name)
                        }
                    }
                    TextButton(onClick = actions.cancelChoosing, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                }
                else -> OutlinedButton(onClick = actions.loadCalendars, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (status.calendar == null) CHOOSE_CALENDAR else CHOOSE_ANOTHER_CALENDAR)
                }
            }
            if (state.phase == GooglePhase.SIGNING_OUT) {
                Text(GOOGLE_SIGNING_OUT, color = MealColors.Muted)
            } else {
                // Wraps in a narrow window rather than squeezing the labels.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = actions.signIn, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text(GOOGLE_SIGN_IN_AGAIN) }
                    TextButton(onClick = actions.signOut, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text(SIGN_OUT) }
                    TextButton(onClick = { confirmRevoke = true }, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text(REVOKE_ACCESS) }
                }
            }
            ClientInUse(status.clientId)
        }
    }
    state.error?.let { AttentionBanner(it) }
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(REVOKE_ACCESS) },
            text = { Text(REVOKE_WARNING) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = false
                    actions.revokeAccess()
                }) { Text(REVOKE_CONFIRM, color = MealColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text(KEEP_ACCESS) } },
        )
    }
}

// S2: the client in use, read-only, and how to change it by hand; the app never replaces it.
@Composable
private fun ClientInUse(clientId: String?) {
    if (clientId == null) return
    Text(clientInUse(clientId), color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
    Text(CLIENT_BY_HAND, color = MealColors.Muted, style = MaterialTheme.typography.bodySmall)
}

const val ABOUT_DESKTOP_DATA =
    "Your recipes, meal plans and lists stay on this PC. Meal Planner goes online only to send a week to the Google " +
        "Calendar you sign in to, to fetch a recipe's photo for the Chrome extension, to answer Home Assistant on your " +
        "home network once Alexa is set up, to sync with the phones and PCs you pair (over an encrypted connection on " +
        "your home network), to look up Kroger prices and add what you pick to your Kroger cart, and to ask GitHub whether there is a newer Meal Planner (downloading it when you choose " +
        "Install). On your home network it also says that it is running, with this PC's name, so another PC running " +
        "Meal Planner can find it."
const val ABOUT_DESKTOP_CALENDAR = "Meals you send go to the Google calendar chosen above; Meal Planner only changes the events it sent."
const val ABOUT_DESKTOP_FONTS = "It uses Windows' own fonts; none are bundled."
const val ABOUT_DESKTOP_BUILT_WITH =
    "It is built with Kotlin, Compose Multiplatform, AndroidX (Room, SQLite, Lifecycle and Navigation), kotlinx.coroutines, " +
        "kotlinx.serialization, Ktor, SnakeYAML, metadata-extractor and JmDNS, all under the Apache License 2.0; TwelveMonkeys " +
        "ImageIO and Skia, under the BSD 3-Clause License; Project Nayuki's QR Code generator, under the MIT License; and " +
        "JNA, under the Apache License 2.0 or the LGPL 2.1. SQLite itself is in the public domain."

const val GOOGLE_INTRO =
    "Send a week's meals to one of your Google calendars. Meal Planner changes only the meals it sent; it never reads your other events."
const val GOOGLE_NEEDS_CLIENT =
    "Signing in needs a Google OAuth client of your own, made once in the Google Cloud console (console.cloud.google.com): " +
        "choose or create a project, enable the Google Calendar API, set up the OAuth consent screen (External) and add your " +
        "Google account to its test users, then under Credentials create an OAuth client ID of type Desktop app and download " +
        "its JSON file. While the consent screen is in Testing, Google ends the sign-in after 7 days; publish it to keep it."
const val CHOOSE_CLIENT_FILE = "Choose the client file"
const val CLIENT_BY_HAND =
    "To use another client, close Meal Planner, change MEAL_PLANNER_GCAL_CLIENT_ID and MEAL_PLANNER_GCAL_CLIENT_SECRET in the " +
        ".env file in the Meal Planner folder under %LOCALAPPDATA%, then sign in again."
const val GOOGLE_SIGNED_OUT = "Not signed in to Google."
const val SIGN_IN_WITH_GOOGLE = "Sign in with Google"

/** Signed in, a fresh sign-in: what the Calendar's "Sign in to Google again in Settings." asks for (P5-T6a). */
const val GOOGLE_SIGN_IN_AGAIN = "Sign in again"
const val GOOGLE_WAITING = "Waiting for you to allow Meal Planner in your browser..."
const val GOOGLE_OPEN_BY_HAND = "If no browser opened, open this address on this PC:"
const val NO_GOOGLE_CALENDAR = "No calendar chosen yet: choose the one to send meals to."
const val CHOOSE_CALENDAR = "Choose calendar"
const val CHOOSE_ANOTHER_CALENDAR = "Choose another calendar"
const val GOOGLE_LOADING = "Looking for your calendars..."
const val NO_WRITABLE_CALENDARS = "That Google account has no calendars Meal Planner can write to."
const val SIGN_OUT = "Sign out"
const val REVOKE_ACCESS = "Revoke access at Google"
const val REVOKE_CONFIRM = "Revoke access"
const val KEEP_ACCESS = "Keep access"
const val REVOKE_WARNING =
    "Google will stop accepting Meal Planner's sign-in for this OAuth client. Anything else that sends to Google " +
        "Calendar with the same client stops working too. Sign out on its own only forgets the sign-in on this PC."
const val GOOGLE_SIGNING_OUT = "Signing out..."

/** The OAuth client in use, shown read-only. */
fun clientInUse(clientId: String): String = "OAuth client: $clientId"

/** "Signed in as" the account's address, once Google's calendar list has said it. */
fun signedInAs(account: String?): String = if (account == null) "Signed in to Google" else "Signed in as $account"

fun sendingTo(name: String): String = "Sending to $name"
