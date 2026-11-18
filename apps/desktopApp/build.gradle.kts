import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Match Kotlin's target: the Android Studio JDK is newer, and Gradle rejects mismatched Java/Kotlin targets.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared:ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    // The built-in server for the Chrome extension and Alexa (plan 4): Ktor on its CIO engine, JSON replies.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)
    // Windows DPAPI (Crypt32Util) seals the Google refresh token (plan 5).
    implementation(libs.jna.platform)
    // Finds other Meal Planner PCs on the home network over mDNS (plan 6). Apache License 2.0.
    implementation(libs.jmdns)
    // The sync port's self-signed certificate (LAN sync, stage 2b): Ktor's certificate builder, Apache License 2.0.
    implementation(libs.ktor.network.tls.certificates)
    // Draws the pairing QR code in Settings (LAN sync, stage 2b): Project Nayuki's QR Code generator, MIT License.
    implementation(libs.qrcodegen)

    testImplementation(libs.junit)
    // Builds an old server database beside the app's, for the test that shows it is never read.
    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.jb.compose.ui.test.junit4)
    // Route tests run against Ktor's test host, without sockets.
    testImplementation(libs.ktor.server.test.host)
}

// P7-R2: the installed app's version, in one place (apps/gradle.properties). An MSI's version is MAJOR.MINOR.BUILD,
// MAJOR and MINOR at most 255 and BUILD at most 65535.
val desktopVersion: String = providers.gradleProperty("mealplanner.desktopVersion").get()
val desktopVersionParts = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,5})""").matchEntire(desktopVersion)?.groupValues?.drop(1)?.map(String::toInt)
if (desktopVersionParts == null || desktopVersionParts[0] !in 1..255 || desktopVersionParts[1] > 255 || desktopVersionParts[2] > 65535) {
    throw GradleException("mealplanner.desktopVersion must be MAJOR.MINOR.BUILD, with MAJOR 1 to 255, MINOR 0 to 255 and BUILD 0 to 65535.")
}

// P7-R2: the MSI's identity. The upgrade code was made once (plan 7) and never changes: it is how Windows knows a newer
// MSI replaces this app. The install folder is a path under jpackage's per-user root, %LOCALAPPDATA%. Never let it
// default: jpackage would use %LOCALAPPDATA%\Meal Planner, the secrets folder (.env, google-token.dat), and its
// uninstall removes the install folder with everything in it.
val msiUpgradeUuid = "d96c85b6-daaf-4386-a1f2-cd677885477b"
// P7-R2b: one level, %LOCALAPPDATA%\Meal-Planner. jpackage adds RemoveFile rows only for INSTALLDIR, so a two-level
// per-user folder (Programs\Meal Planner) fails WiX's ICE64 and no MSI is made; Compose passes no --resource-dir on
// Windows to change that. The name isn't the secrets folder (Meal Planner, with a space), its 8.3 name is MEAL-P~1, never
// MEALPL~, and it never contains AppData\Local\Meal Planner, so inspect-msi.ps1's protections hold as they are. Any
// String passed to jpackage has no backslash (its args file drops one inside quotes; checkPackagingConfig).
val msiInstallDir = "Meal-Planner"

// P7-R3: what only the packaged launcher passes the JVM: the installed app's switch (Start with Windows, discovery) and
// its version (Settings' About, the record other PCs see). Never a preview's folder or port: the installed app uses
// Documents\Meal Planner and port 5000. Nor the smoke run's own switches (smoke-packaged.ps1 adds those itself).
val installedJvmArgs = listOf("-Dmealplanner.installed=true", "-Dmealplanner.version=$desktopVersion")
val notForTheInstalledApp = listOf(
    "mealplanner.dataDir",
    "mealplanner.port",
    "mealplanner.syncPort",
    "mealplanner.peers",
    "mealplanner.startWithWindows",
    "mealplanner.selfCheck",
    // Plan 8: the installed app checks for updates; only the smoke run turns that off, through JAVA_TOOL_OPTIONS.
    "mealplanner.updates",
)
// The one option Compose itself gives every launcher (its defaultJvmArgs). The launcher passes nothing else.
val composeLauncherJvmArgs = listOf("-Dcompose.application.configure.swing.globals=true")

// P7-R1: jpackage, which makes the app image and the MSI, isn't in Android Studio's JBR. The packaging tasks use the full
// JDK named by MEAL_PLANNER_PACKAGING_JDK (docs/WINDOWS.md, "Build the installer"); while it is set, `run` uses it too.
// Unset (P7-PF8), configuration and the tests work as before, and only the packaging tasks stop (checkPackagingJdk),
// with what to install.
val packagingJdk: String? = providers.environmentVariable("MEAL_PLANNER_PACKAGING_JDK").orNull?.takeIf { it.isNotBlank() }

// Development never touches the real library: `run` uses a throwaway preview folder (and its own settings node), and
// listens on 5055, never on 5000, where the installed app or the old Python server may be running. Only the installed
// app runs without these and finds Documents\Meal Planner; afterEvaluate (below) keeps them off its launcher.
// A second preview beside the first (plan 6's live check: another Meal Planner PC on the network) takes
// "-Pmealplanner.preview=<name>", which runs on build\preview-<name>, and "-Pmealplanner.port=<port>".
val previewName = providers.gradleProperty("mealplanner.preview").orNull
if (previewName != null && !Regex("[A-Za-z0-9-]{1,32}").matches(previewName)) {
    throw GradleException("mealplanner.preview must be 1 to 32 letters, digits or dashes.")
}
val previewPort = providers.gradleProperty("mealplanner.port").orNull ?: "5055"
if (previewPort.toIntOrNull()?.takeIf { it in 1024..65535 && it != 5000 } == null) {
    throw GradleException("mealplanner.port must be a port from 1024 to 65535 other than 5000, which the installed app may hold.")
}
// LAN sync (stage 2b): the preview's sync port, never the installed app's 5001 (nor 5000), and never its own server's.
// 5065 by default; 5066 beside a second preview run with -Pmealplanner.port=5056 (CLAUDE.md's peer check), so neither
// overlaps the two previews' server ports (5055, 5056).
val previewSyncPort = providers.gradleProperty("mealplanner.syncPort").orNull ?: if (previewPort == "5056") "5066" else "5065"
if (previewSyncPort.toIntOrNull()?.takeIf { it in 1024..65535 && it != 5000 && it != 5001 && it != previewPort.toInt() } == null) {
    throw GradleException("mealplanner.syncPort must be a port from 1024 to 65535 other than 5000 and 5001, which the installed app may hold, and other than mealplanner.port.")
}
val previewDir = if (previewName == null) "preview-data" else "preview-$previewName"
// P6-PF7: a preview looks for other Meal Planner PCs only when asked, with "-Pmealplanner.peers=on". Its household is
// older than any later install's, so one that always announced would make the installed app step back from Google
// Calendar and Alexa while it runs.
val previewPeers = providers.gradleProperty("mealplanner.peers").orNull
if (previewPeers != null && previewPeers != "on" && previewPeers != "off") {
    throw GradleException("mealplanner.peers must be on or off.")
}
val previewJvmArgs = buildList {
    add("-Dmealplanner.dataDir=${layout.buildDirectory.dir(previewDir).get().asFile.absolutePath}")
    add("-Dmealplanner.port=$previewPort")
    add("-Dmealplanner.syncPort=$previewSyncPort")
    if (previewPeers == "on") add("-Dmealplanner.peers=on")
}

compose.desktop {
    application {
        mainClass = "com.naeblis11.mealplanner.desktop.MainKt"
        packagingJdk?.let { javaHome = it }
        jvmArgs.addAll(previewJvmArgs)
        nativeDistributions {
            // P7-R1, P7-R2: a per-user MSI (packageMsi), no admin rights, no folder chooser. Compose downloads WiX
            // 3.11.2 for jpackage by itself (its downloadWix and unzipWix tasks), so the build needs no WiX install.
            targetFormats(TargetFormat.Msi)
            packageName = "Meal Planner"
            packageVersion = desktopVersion
            description = "Meal Planner"
            vendor = "Meal Planner"
            // P7-R4: the modules jdeps finds in the runtime classpath, worked out by hand (plan 7: suggestRuntimeModules
            // can't run on the JBR, which has no jpackage), plus jdk.accessibility, which only Compose's screen-reader
            // bridge loads, at run time. Compose always adds java.base, java.desktop,
            // java.logging and jdk.crypto.ec (empty since JDK 22: the EC provider is in java.base). smoke-packaged.ps1
            // checks the runtime has them all.
            modules(
                "java.instrument",
                "java.management",
                "java.net.http",
                "java.prefs",
                "java.sql",
                "jdk.accessibility",
                "jdk.httpserver",
                "jdk.unsupported",
            )
            windows {
                perUserInstall = true
                installationPath = msiInstallDir
                dirChooser = false
                menu = true
                // jpackage's default group is "Unknown".
                menuGroup = "Meal Planner"
                // Compose has no shortcut prompt: the desktop shortcut is always made, and the user may delete it.
                shortcut = true
                upgradeUuid = msiUpgradeUuid
                // Made from the repo's icon.svg by tools/make_icons.py (Pillow, requirements-dev.txt) and committed
                iconFile.set(project.file("packaging/meal-planner.ico"))
            }
        }
    }
}

// P7-R3: the packaged launcher's Java options and the MSI's settings, checked without building anything or needing
// jpackage (P7-PF6: `check` runs it, so it runs with the suite). smoke-packaged.ps1 runs it before it builds the app
// image. Like the refusals below, it looks tasks up while it runs, which the configuration cache (off) wouldn't allow.
val checkPackagingConfig = tasks.register("checkPackagingConfig") {
    group = "verification"
    description = "Checks the packaged launcher's Java options and the MSI's settings (plan 7); builds nothing."
    val packaging = project.tasks.withType<AbstractJPackageTask>()
    val runTask = project.tasks.named("run", JavaExec::class.java)
    val inspectMsiTask = project.tasks.named("inspectMsi", Exec::class.java)
    doLast {
        val problems = mutableListOf<String>()
        val msi = packaging.firstOrNull { it.name == "packageMsi" }
        if (msi == null) problems += "there is no packageMsi task"
        // The rule itself, on values that must fail and one that must pass.
        for (bad in listOf("Programs\\Meal Planner", "Meal \"Planner\"", "Meal\nPlanner", "Meal\rPlanner")) {
            if (jpackageStringProblem(bad) == null) problems += "jpackageStringProblem lets '${bad.replace("\r", "\\r").replace("\n", "\\n")}' through"
        }
        if (jpackageStringProblem(msiInstallDir) != null) problems += "jpackageStringProblem refuses $msiInstallDir"
        for (task in packaging) {
            // Compose writes each String option into jpackage's args file as "value", and the JDK's args-file reader
            // (jdk.internal.opt.CommandLine) takes a backslash inside quotes as an escape: "Programs\Meal Planner"
            // reaches jpackage as ProgramsMeal Planner, and \n or \t become control characters. A double quote would
            // end the value early. Folders are written with / (jpackage's Path.of turns it into \ on Windows).
            val strings = mapOf(
                "name" to listOfNotNull(task.packageName.orNull),
                "description" to listOfNotNull(task.packageDescription.orNull),
                "copyright" to listOfNotNull(task.packageCopyright.orNull),
                "vendor" to listOfNotNull(task.packageVendor.orNull),
                "version" to listOfNotNull(task.packageVersion.orNull),
                "build version" to listOfNotNull(task.packageBuildVersion.orNull),
                "install folder" to listOfNotNull(task.installationPath.orNull),
                "Start-menu group" to listOfNotNull(task.winMenuGroup.orNull),
                "upgrade code" to listOfNotNull(task.winUpgradeUuid.orNull),
                "main class" to listOfNotNull(task.launcherMainClass.orNull),
                "launcher argument" to task.launcherArgs.get(),
                "launcher Java option" to task.launcherJvmArgs.get(),
            )
            for ((what, values) in strings) for (value in values) {
                jpackageStringProblem(value)?.let { problems += "${task.name}'s $what '${value.replace("\r", "\\r").replace("\n", "\\n")}' has $it, which jpackage's args file would mangle (write folders with /)" }
            }
            val args = task.launcherJvmArgs.get()
            for (wanted in installedJvmArgs) if (args.count { it == wanted } != 1) problems += "${task.name}'s launcher lacks $wanted"
            for (arg in args) {
                if (notForTheInstalledApp.any { arg.startsWith("-D$it=") }) problems += "${task.name}'s launcher passes $arg"
                else if (arg !in installedJvmArgs && arg !in composeLauncherJvmArgs) problems += "${task.name}'s launcher passes $arg, which only the installed app's options and Compose's own may be"
            }
        }
        if (msi != null) {
            fun same(what: String, actual: Any?, wanted: Any) {
                if (actual != wanted) problems += "packageMsi's $what is $actual, not $wanted"
            }
            // The MSI's file name follows the package name (Meal Planner-<version>.msi).
            same("package name", msi.packageName.orNull, "Meal Planner")
            same("per-user install", msi.winPerUserInstall.orNull, true)
            // P7-R2b: one level, %LOCALAPPDATA%\Meal-Planner (WiX's ICE64 rejects a two-level per-user folder).
            same("install folder", msi.installationPath.orNull, "Meal-Planner")
            same("upgrade code", msi.winUpgradeUuid.orNull, msiUpgradeUuid)
            same("version", msi.packageVersion.orNull, desktopVersion)
            same("vendor", msi.packageVendor.orNull, "Meal Planner")
            same("folder chooser", msi.winDirChooser.orNull, false)
            same("Start-menu entry", msi.winMenu.orNull, true)
            same("Start-menu group", msi.winMenuGroup.orNull, "Meal Planner")
            same("desktop shortcut", msi.winShortcut.orNull, true)
            same("icon", msi.iconFile.orNull?.asFile?.name, "meal-planner.ico")
            // P7-PF4: every MSI is read back before it can be handed out.
            val finalizers = msi.finalizedBy.getDependencies(msi).map { it.name }
            if ("inspectMsi" !in finalizers) problems += "packageMsi isn't finalized by inspectMsi"
            same("output folder", msi.destinationDir.orNull?.asFile, layout.buildDirectory.dir("compose/binaries/main/msi").get().asFile)
        }
        // P7-T2a: the inspection judges the MSI against this build's version and upgrade code, and a rejected MSI is
        // renamed, not left installable (inspectMsi's doLast reads the exit value itself).
        val inspect = inspectMsiTask.get()
        if (!inspect.isIgnoreExitValue) problems += "inspectMsi lets Exec fail the build, so it never renames a rejected MSI"
        // Windows' own PowerShell 5.1, never whichever powershell.exe comes first on PATH.
        val shell = File(inspect.executable.orEmpty())
        if (!shell.isAbsolute || !shell.path.endsWith("\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", ignoreCase = true)) {
            problems += "inspectMsi runs ${inspect.executable}, not %SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        }
        val inspectArgs = inspect.argumentProviders.flatMap { it.asArguments() }
        fun passes(flag: String, value: String) {
            val at = inspectArgs.indexOf(flag)
            if (at < 0 || inspectArgs.getOrNull(at + 1) != value) problems += "inspectMsi doesn't pass $flag $value"
        }
        passes("-Version", desktopVersion)
        passes("-UpgradeCode", msiUpgradeUuid)
        passes("-Msi", layout.buildDirectory.file("compose/binaries/main/msi/Meal Planner-$desktopVersion.msi").get().asFile.absolutePath)
        // P7-PF4: the build refuses, before anything runs, a task graph that would make an MSI and not inspect it
        // (packageMsi -x inspectMsi), and the turned-off tasks; it allows packageMsi with its inspection.
        fun refuses(paths: Set<String>, what: String) {
            if (refusalFor(paths) == null) problems += "the build doesn't refuse $what"
        }
        fun allows(paths: Set<String>, what: String) {
            refusalFor(paths)?.let { problems += "the build refuses $what: $it" }
        }
        refuses(setOf(packageMsiPath), "packageMsi without inspectMsi")
        refuses(setOf(packageMsiPath, "${project.path}:checkRuntime"), "packageMsi without inspectMsi")
        allows(setOf(packageMsiPath, inspectMsiPath), "packageMsi with inspectMsi")
        allows(setOf(inspectMsiPath), "inspectMsi on its own")
        allows(setOf("${project.path}:createDistributable"), "createDistributable")
        for (path in refusedTasks.keys) refuses(setOf(path, packageMsiPath, inspectMsiPath), path)
        // P7-PF4 (re-review N1): inspectMsi judges whatever MSIs packageMsi's folder holds, not one expected name.
        val one = File("Meal Planner-$desktopVersion.msi")
        val renamed = File("Other-$desktopVersion.msi")
        fun plan(what: String, msis: List<File>, failed: Boolean, inspects: File?, rejects: Boolean) {
            val actual = msiPlan(msis, failed)
            if (actual.inspect != inspects || (actual.rejectWhy != null) != rejects) {
                problems += "inspectMsi's plan for $what is to inspect ${actual.inspect} and reject ${actual.rejectWhy != null}, not $inspects and $rejects"
            }
        }
        plan("the one MSI", listOf(one), failed = false, inspects = one, rejects = false)
        plan("one MSI under another name", listOf(renamed), failed = false, inspects = renamed, rejects = false)
        plan("no MSI after packageMsi", emptyList(), failed = false, inspects = null, rejects = true)
        plan("two MSIs", listOf(one, renamed), failed = false, inspects = null, rejects = true)
        plan("an MSI from a failed packageMsi", listOf(one), failed = true, inspects = null, rejects = true)
        plan("no MSI from a failed packageMsi", emptyList(), failed = true, inspects = null, rejects = false)
        val runArgs = runTask.get().allJvmArgs
        if (runArgs.any { it.startsWith("-Dmealplanner.installed") }) problems += "run passes mealplanner.installed"
        if (runArgs.none { it.startsWith("-Dmealplanner.dataDir=") }) problems += "run lost its preview folder"
        if (runArgs.none { it == "-Dmealplanner.syncPort=$previewSyncPort" }) problems += "run lost its preview sync port ($previewSyncPort)"
        if (problems.isNotEmpty()) throw GradleException("The packaging settings are wrong:\n" + problems.joinToString("\n"))
        logger.lifecycle("Packaged launcher: ${msi?.launcherJvmArgs?.get()}")
        logger.lifecycle("MSI: version $desktopVersion, per user, into %LOCALAPPDATA%\\${msiInstallDir.replace('/', '\\')}, upgrade code $msiUpgradeUuid")
    }
}
tasks.named("check") { dependsOn(checkPackagingConfig) }

// P7-R1, P7-PF8: say what to install instead of Compose's "'jpackage.exe' is missing". checkRuntime, which every
// packaging task needs, depends on it; it has no outputs, so it is never up to date and always looks.
val checkPackagingJdk = tasks.register("checkPackagingJdk") {
    group = "compose desktop"
    description = "Stops a packaging build without a full JDK in MEAL_PLANNER_PACKAGING_JDK (plan 7)."
    doLast {
        val install = "Install one (winget install --id EclipseAdoptium.Temurin.25.JDK -e) and set MEAL_PLANNER_PACKAGING_JDK " +
            "to its folder; see docs/WINDOWS.md, \"Build the installer\"."
        val jdk = packagingJdk ?: throw GradleException(
            "Building the app image or the installer needs a full JDK with jpackage, named by MEAL_PLANNER_PACKAGING_JDK, " +
                "which isn't set (Android Studio's JBR has no jpackage). $install",
        )
        if (!File(jdk, "bin/jpackage.exe").isFile) {
            throw GradleException("Building the app image or the installer needs a full JDK with jpackage, and $jdk has none. $install")
        }
    }
}

// P7-PF4: every MSI packageMsi makes is read back, read-only, by inspect-msi.ps1, which fails the build if anything in
// it could install into, or have the uninstall remove, %LOCALAPPDATA%\Meal Planner (the secrets folder) rather than
// %LOCALAPPDATA%\Meal-Planner. jpackage only warns when it rejects an install folder and falls back to that
// default, so this can't be left to a manual step. The script fails closed (P7-T2a) and stays read-only; a rejected
// MSI is renamed here to Meal Planner-<version>.msi.rejected, so it can't be handed out or installed by mistake.
val inspectMsi = tasks.register<Exec>("inspectMsi") {
    group = "verification"
    description = "Reads the MSI packageMsi made, without installing it, and checks where it installs (plan 7)."
    val script = file("inspect-msi.ps1")
    // packageMsi's folder (checkPackagingConfig checks it is its destination), and the MSI it should hold.
    val msiDir = layout.buildDirectory.dir("compose/binaries/main/msi")
    val expectedMsi = msiDir.map { it.file("Meal Planner-$desktopVersion.msi") }
    // The MSI msiPlan picked, set in doFirst; until then (checkPackagingConfig) the expected one.
    var picked: File? = null
    inputs.file(script)
    // Windows' own PowerShell 5.1 by its full path, not the first powershell.exe on PATH.
    val systemRoot = providers.environmentVariable("SystemRoot").getOrElse("C:\\Windows")
    executable = "$systemRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
            "-Msi", (picked ?: expectedMsi.get().asFile).absolutePath, "-Version", desktopVersion, "-UpgradeCode", msiUpgradeUuid,
        )
    })
    // The exit value is judged in doLast, which renames a rejected MSI before it fails the build.
    isIgnoreExitValue = true
    // Never up to date: it is cheap, and an MSI is only ever handed out after it passed.
    outputs.upToDateWhen { false }
    // It always runs, and judges every MSI in the folder, whatever its name (re-review N1): after packageMsi succeeded,
    // exactly one, else every MSI there is renamed; a finalizer runs after its task failed too, and then every MSI
    // there is renamed, whatever an inspection would say.
    doFirst {
        val msis = msiDir.get().asFile.listFiles { f -> f.isFile && f.name.endsWith(".msi", ignoreCase = true) }.orEmpty().sortedBy { it.name }
        val packageFailed = tasks.findByName("packageMsi")?.state?.failure != null
        val plan = msiPlan(msis, packageFailed)
        if (plan.inspect != null) {
            picked = plan.inspect
            return@doFirst
        }
        val why = plan.rejectWhy ?: throw StopExecutionException("packageMsi failed before it wrote an MSI: nothing to inspect.")
        val rejected = msis.map { rejectMsi(it) }
        throw GradleException(
            "No MSI was inspected, because $why. " +
                if (rejected.isEmpty()) "Build it with :desktopApp:packageMsi." else "Renamed: ${rejected.joinToString()}; never hand them out or install them.",
        )
    }
    doLast {
        val exit = executionResult.get().exitValue
        if (exit != 0) {
            val rejected = rejectMsi(checkNotNull(picked))
            throw GradleException(
                "The MSI is now $rejected, because the MSI check failed (inspect-msi.ps1 exited $exit; its FAIL lines are " +
                    "above): never hand it out or install it.",
            )
        }
    }
}

afterEvaluate {
    // P7-R3: Compose gives the packaged launcher (createDistributable, packageMsi) the same jvmArgs as run, and sets them
    // while it configures each task, so a configureEach here would be overwritten. The tasks are made here, after
    // Compose's own afterEvaluate registered them, and their options replaced: run's preview options off, the installed
    // app's on. checkPackagingConfig checks the result.
    tasks.withType<AbstractJPackageTask>().toList().forEach { task ->
        val composeArgs = task.launcherJvmArgs.get()
        task.launcherJvmArgs.set(composeArgs.filterNot { it in previewJvmArgs } + installedJvmArgs)
    }
    tasks.named("checkRuntime") { dependsOn(checkPackagingJdk) }
    // And before Compose fetches WiX (the root project's downloadWix, some 30 MB), so a build that can't package
    // downloads nothing.
    rootProject.tasks.named { it == "downloadWix" }.configureEach { dependsOn(checkPackagingJdk) }
    val packageMsi = tasks.named<AbstractJPackageTask>("packageMsi")
    // P7-PF4: excluding inspectMsi (-x) is refused before anything runs (refusalFor, below).
    packageMsi { finalizedBy(inspectMsi) }
}

// Tasks the build refuses before anything runs. The run*Distributable tasks would start the packaged app as the
// installed one, on the real Documents\Meal Planner and port 5000, writing the Start with Windows entry. The release
// variants run ProGuard, which this app isn't set up for (Ktor, Room, kotlinx.serialization, JmDNS and the ImageIO
// plugins load classes by reflection or ServiceLoader). P7-PF2: this is only ever checked with a dry run (-m).
val runsTheInstalledApp = "It would run the packaged app as the installed one, on your real Documents\\Meal Planner and " +
    "port 5000, and write the Start with Windows entry. Run apps\\desktopApp\\smoke-packaged.ps1 instead: it runs the " +
    "app image on throwaway folders."
val needsProguard = "Release builds run ProGuard, which this app isn't set up for. Build the installer with packageMsi."
val refusedTasks = mapOf(
    "${project.path}:runDistributable" to runsTheInstalledApp,
    "${project.path}:runReleaseDistributable" to runsTheInstalledApp,
    "${project.path}:createReleaseDistributable" to needsProguard,
    "${project.path}:packageReleaseMsi" to needsProguard,
    "${project.path}:packageReleaseDistributionForCurrentOS" to needsProguard,
)
val packageMsiPath = "${project.path}:packageMsi"
val inspectMsiPath = "${project.path}:inspectMsi"

// What a String passed to jpackage has that its args file would mangle, or null: the args-file reader takes a backslash
// inside quotes as an escape, a double quote ends the value, and a line break ends the line (checkPackagingConfig).
fun jpackageStringProblem(value: String): String? = when {
    '\\' in value -> "a backslash"
    '"' in value -> "a double quote"
    '\r' in value || '\n' in value -> "a line break"
    else -> null
}

// P7-PF4 (re-review N1): what inspectMsi does with the MSIs in packageMsi's folder, which Compose empties before every
// packageMsi. Exactly one is inspected, whatever it is called. After a packageMsi that didn't fail, none or several is
// refused and every MSI there renamed; after one that failed, every MSI there is renamed. With neither an MSI nor a
// successful packageMsi, there is nothing to do (both null).
class MsiPlan(val inspect: File?, val rejectWhy: String?)

fun msiPlan(msis: List<File>, packageFailed: Boolean): MsiPlan = when {
    packageFailed -> MsiPlan(null, if (msis.isEmpty()) null else "packageMsi failed")
    msis.size == 1 -> MsiPlan(msis.single(), null)
    msis.isEmpty() -> MsiPlan(null, "there is no MSI to inspect in packageMsi's folder")
    else -> MsiPlan(null, "packageMsi's folder holds ${msis.size} MSIs (${msis.joinToString { it.name }}), not one")
}

// A rejected MSI becomes <name>.msi.rejected, which Windows won't open as an installer.
fun rejectMsi(msi: File): File {
    val rejected = File(msi.parentFile, msi.name + ".rejected")
    Files.move(msi.toPath(), rejected.toPath(), StandardCopyOption.REPLACE_EXISTING)
    return rejected
}

// Why a task graph is refused, or null. checkPackagingConfig checks it on made-up graphs.
fun refusalFor(paths: Set<String>): String? {
    val refused = refusedTasks.keys.firstOrNull { it in paths }
    if (refused != null) return "$refused is turned off. ${refusedTasks.getValue(refused)}"
    // P7-PF4: packageMsi -x inspectMsi would leave an MSI nobody read back.
    if (packageMsiPath in paths && inspectMsiPath !in paths) {
        return "$packageMsiPath runs only with $inspectMsiPath, which reads every MSI back before it can be handed out; don't exclude it."
    }
    return null
}

gradle.taskGraph.whenReady {
    refusalFor(allTasks.map { it.path }.toSet())?.let { throw GradleException(it) }
}

/**
 * Task 13: the Google OAuth client built into the app, so a new install only needs Sign in with Google. Where it lives
 * is read from the Gradle property mealplanner.googleClient, else from the git-ignored apps/google-client.properties
 * (docs/WINDOWS.md, "Building with your Google client"). That file holds either clientJson=<path to the client JSON
 * Google's console downloads> (relative to the file's folder) or clientId= and clientSecret=. With neither the property
 * nor the file, no client is built in and Settings asks for the client file instead.
 *
 * The values are read only when the task runs, never while Gradle configures, and are never printed: a bad one fails
 * the build with what is wrong, not with the value. They are not task inputs either (Gradle would keep them in its
 * history), so the task always runs; it is cheap, and the resources only change when the client does.
 */
abstract class GoogleClientResource : DefaultTask() {
    /** The properties file named by -Pmealplanner.googleClient; it must exist. */
    @get:Input
    @get:Optional
    abstract val named: Property<String>

    /** apps/google-client.properties, used when it exists and no property names another. */
    @get:Input
    abstract val fallback: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun write() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val source = named.orNull?.let { path ->
            File(path).also { if (!it.isFile) throw GradleException("mealplanner.googleClient names $path, which isn't a file.") }
        } ?: File(fallback.get()).takeIf { it.isFile } ?: return
        val (id, secret) = read(source)
        // The same rule as GoogleClients.printable: printable ASCII without spaces, and not empty.
        fun printable(value: String) = value.isNotEmpty() && value.all { it in '!'..'~' }
        if (!printable(id)) throw GradleException("The Google client id from $source is empty or isn't printable ASCII without spaces.")
        if (!printable(secret)) throw GradleException("The Google client secret from $source is empty or isn't printable ASCII without spaces.")
        val json = groovy.json.JsonOutput.toJson(mapOf("installed" to mapOf("client_id" to id, "client_secret" to secret)))
        File(dir, "google-client.json").writeText(json, Charsets.UTF_8)
    }

    // A properties file read by hand, not with java.util.Properties: a Windows path's backslashes must stay as written.
    private fun read(source: File): Pair<String, String> {
        val values = mutableMapOf<String, String>()
        for (raw in source.readText(Charsets.UTF_8).removePrefix(BOM).lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val at = line.indexOf('=')
            if (at < 0) throw GradleException("A line in $source has no '=': use clientJson=, or clientId= and clientSecret=.")
            val key = line.substring(0, at).trim()
            if (key !in setOf("clientJson", "clientId", "clientSecret")) {
                throw GradleException("$source has a key other than clientJson, clientId and clientSecret.")
            }
            values[key] = unquote(line.substring(at + 1))
        }
        val jsonPath = values["clientJson"]
        return when {
            jsonPath != null && ("clientId" in values || "clientSecret" in values) ->
                throw GradleException("$source has both clientJson and clientId or clientSecret: keep one.")
            jsonPath != null -> fromJson(File(jsonPath).let { if (it.isAbsolute) it else File(source.absoluteFile.parentFile, jsonPath) })
            "clientId" in values || "clientSecret" in values -> values["clientId"].orEmpty() to values["clientSecret"].orEmpty()
            else -> throw GradleException("$source names no client: add clientJson=, or clientId= and clientSecret=.")
        }
    }

    // Google's client file, {"installed": {"client_id": ..., "client_secret": ...}}, as GoogleClients.fromJson reads it.
    private fun fromJson(file: File): Pair<String, String> {
        if (!file.isFile) throw GradleException("The Google client file $file doesn't exist.")
        // The parser's own message may quote the file, so it is never passed on.
        val parsed = try {
            groovy.json.JsonSlurper().parseText(file.readText(Charsets.UTF_8).removePrefix(BOM))
        } catch (e: Exception) {
            null
        }
        val installed = (parsed as? Map<*, *>)?.get("installed") as? Map<*, *>
            ?: throw GradleException("$file isn't a Google OAuth client for a desktop app (no \"installed\" client).")
        return (installed["client_id"] as? String).orEmpty() to (installed["client_secret"] as? String).orEmpty()
    }

    // One matching pair of surrounding quotes comes off, as for the .env (GoogleClients.unquote).
    private fun unquote(raw: String): String {
        val value = raw.trim()
        val quoted = value.length >= 2 && value.first() == value.last() && (value.first() == '"' || value.first() == '\'')
        return (if (quoted) value.substring(1, value.length - 1) else value).trim()
    }

    private companion object {
        // A byte order mark (U+FEFF), which Notepad may put first; built from its code so this file stays ASCII.
        val BOM = Char(0xFEFF).toString()
    }
}

val googleClientResource = tasks.register<GoogleClientResource>("googleClientResource") {
    named.set(providers.gradleProperty("mealplanner.googleClient").map { rootProject.file(it).absolutePath })
    fallback.set(rootProject.file("google-client.properties").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/google-client"))
}

// Plan 2026-11-17: the built-in Kroger client, written the same way (gradle/kroger-client.gradle.kts).
apply(from = rootProject.file("gradle/kroger-client.gradle.kts"))

// main's resources include the generated folders, empty when there is no client; the tasks run before processResources.
sourceSets.main {
    resources.srcDir(googleClientResource)
    resources.srcDir(tasks.named("krogerClientResource"))
}

// Backstop: a test that ever resolved the default data folder would land in build/, never in Documents.
tasks.withType<Test>().configureEach {
    systemProperty("mealplanner.dataDir", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    // DesignTokensTest checks the shared theme against DESIGN.md's tokens.
    val designDoc = rootProject.file("../DESIGN.md")
    systemProperty("designDoc", designDoc.absolutePath)
    inputs.file(designDoc)
    // PackagingIconTest checks the installer's icon (plan 7).
    val appIco = file("packaging/meal-planner.ico")
    systemProperty("appIco", appIco.absolutePath)
    inputs.file(appIco)
    // InspectMsiScriptTest runs the MSI inspector's decisions on tables it writes (plan 7, P7-PF4).
    val inspectMsiScript = file("inspect-msi.ps1")
    systemProperty("inspectMsiScript", inspectMsiScript.absolutePath)
    inputs.file(inspectMsiScript)
    val inspectMsiCases = file("src/test/inspect-msi-cases")
    systemProperty("inspectMsiCases", inspectMsiCases.absolutePath)
    inputs.dir(inspectMsiCases)
    // SmokeLauncherOptionsTest runs smoke-packaged.ps1's launcher-options decision (never the smoke script itself).
    val launcherOptionsScript = file("launcher-options.ps1")
    systemProperty("launcherOptionsScript", launcherOptionsScript.absolutePath)
    inputs.file(launcherOptionsScript)
    inputs.file(file("smoke-packaged.ps1"))
    // SmokeProcessTreeTest runs the smoke run's process-tree decisions on made-up process lists.
    inputs.file(file("process-tree.ps1"))
}
