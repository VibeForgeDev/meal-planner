package com.naeblis11.mealplanner.update

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUpdatesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    // A throwaway key pair, in memory; the owner's is never read here.
    private val keys: KeyPair = testKeys()
    private val key = keys.public
    private val never = { false }

    private var releases: PhoneReleases? = null

    @After
    fun tearDown() {
        releases?.close()
    }

    @Test
    fun thePhoneOnlyEverContactsGitHub() {
        // Spec "Updates": the only hosts the app contacts are GitHub's (in phase 2 also the paired PC and Google).
        val updates = AndroidUpdates.create(context, never, key, release = true)
        assertEquals(setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"), updates.http.hosts)
        assertEquals(ReleaseEndpoints.GITHUB, updates.endpoints)
        assertEquals(File(context.cacheDir, "updates"), updates.dir)
        // ReleaseHttp's defaults: https on the usual port only (allowed() sends nothing).
        updates.http.allowed("https://github.com/VibeForgeDev/meal-planner/releases/latest/download/latest.json")
        assertThrows(IOException::class.java) { updates.http.allowed("http://github.com/x") }
        assertThrows(IOException::class.java) { updates.http.allowed("https://github.com:8443/x") }
        assertThrows(IOException::class.java) { updates.http.allowed("https://127.0.0.1/x") }
        // Nothing is made until a download needs it.
        assertFalse(updates.dir.exists())
    }

    @Test
    fun aDebugBuildNeverChecks() {
        // The tests run the debug build (applicationIdSuffix ".debug", debuggable), which the release APK can't update.
        // This also keeps every Robolectric test that starts MainActivity offline.
        assertEquals("com.naeblis11.mealplanner.debug", context.packageName)
        assertFalse(AndroidUpdates.isRelease(context))
        val updates = AndroidUpdates.create(context, never, key)
        assertFalse(updates.state.value.offered)
        assertEquals(AndroidUpdates.DEBUG_BUILD, updates.state.value.unavailableReason)
    }

    @Test
    fun theReleasedAppChecksWithItsOwnVersionCode() {
        val props = Properties().apply {
            File(System.getProperty("gradleProperties") ?: error("gradleProperties is not set; run through Gradle")).reader(Charsets.UTF_8).use { load(it) }
        }
        val updates = AndroidUpdates.create(context, never, key, release = true)
        assertTrue(updates.state.value.offered)
        assertFalse(updates.state.value.installClosesApp)
        val running = updates.running as RunningApp.Android
        assertEquals(props.getProperty("mealplanner.androidVersionCode").toLong(), running.versionCode)
    }

    @Test
    fun theKeyHostsAndAddressesAreNeverConfigurable() {
        // CARRY (Tasks 2-4): the default key is Updates.releaseKey()'s, which turns any key problem (even an Error from
        // ReleaseSignature's initialiser) into "no update"; ReleaseHttp() and ReleaseEndpoints.GITHUB, never configuration.
        val updates = AndroidUpdates.create(context, never, release = true)
        val expected = if (Updates.releaseKey() == null) UpdateMessages.NO_KEY else null
        assertEquals(expected, updates.state.value.unavailableReason)
        val main = File(System.getProperty("androidMainDir") ?: error("androidMainDir is not set; run through Gradle"), "java/com/naeblis11/mealplanner")
        val source = File(main, "update/AndroidUpdates.kt").readText()
        assertTrue(source.contains("Updates.releaseKey()"))
        assertTrue(source.contains("ReleaseHttp()"))
        assertTrue(source.contains("ReleaseEndpoints.GITHUB"))
        assertFalse(Regex("ReleaseHttp\\((?!\\))").containsMatchIn(source))
        assertFalse(Regex("ReleaseEndpoints\\(").containsMatchIn(source))
        assertFalse(source.contains("getenv"))
        assertFalse(source.contains("getProperty"))
        // Nothing on the phone's path touches the key or the signature outside Updates.
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("ReleaseKey.builtIn") || it.contains("ReleaseSignature") } }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
        // The update package doesn't reach into the app's: the app hands it what it needs (its foreground count).
        val reaching = File(main, "update").listFiles()!!.filter { it.readText().contains("mealplanner.app.MealPlannerApplication") }
        assertEquals(emptyList<File>(), reaching)
    }

    @Test
    fun aDownloadFinishedInTheBackgroundWaitsForInstallUntilTheAppIsBack() {
        // P8-PF11: the user left Meal Planner during the download, so the installer isn't started (Android would block
        // it without a word); the verified file waits and the notice offers Install again, which uses it at once.
        val fake = PhoneReleases(keys).also { releases = it }
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        addApkHandler(context, providerUri(context, PhoneReleases.CACHED), SYSTEM_INSTALLER, system = true)
        val updates = fake.updates(context, keys)
        runBlocking {
            updates.check(manual = false)
            assertEquals(PhoneReleases.VERSION, updates.state.value.offer?.version)
            updates.installOffer()
        }
        val waiting = updates.state.value
        assertTrue(waiting.waitingToInstall)
        assertTrue(waiting.showsNotice)
        assertNull(waiting.problem)
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(1, fake.assetRequests())

        // Back in the app: Install hands over the file already verified, without downloading it again.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        runBlocking { updates.installOffer() }
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(AndroidUpdateInstaller.APK_TYPE, started.type)
        assertEquals(SYSTEM_INSTALLER, started.`package`)
        assertEquals("/updates/${PhoneReleases.CACHED}", started.data!!.path)
        assertFalse(updates.state.value.waitingToInstall)
        assertNull(updates.state.value.problem)
        assertEquals(1, fake.assetRequests())
        activity.pause().stop().destroy()
    }

    @Test
    fun aDownloadFinishedInTheForegroundInstallsAtOnce() {
        val fake = PhoneReleases(keys).also { releases = it }
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        addApkHandler(context, providerUri(context, PhoneReleases.CACHED), SYSTEM_INSTALLER, system = true)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val updates = fake.updates(context, keys)
        runBlocking {
            updates.check(manual = true)
            updates.installOffer()
        }
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(SYSTEM_INSTALLER, started.`package`)
        assertEquals("/updates/${PhoneReleases.CACHED}", started.data!!.path)
        assertFalse(updates.state.value.waitingToInstall)
        activity.pause().stop().destroy()
    }

    @Test
    fun withoutASystemInstallerTheInstallFailsAndNothingIsStarted() {
        // Only a downloaded app claims APKs: the update and its read grant never go to it.
        val fake = PhoneReleases(keys).also { releases = it }
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        addApkHandler(context, providerUri(context, PhoneReleases.CACHED), OTHER_INSTALLER, system = false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val updates = fake.updates(context, keys)
        runBlocking {
            updates.check(manual = true)
            updates.installOffer()
        }
        assertEquals(UpdateMessages.INSTALL_FAILED, updates.state.value.problem)
        assertNull(shadowOf(context).nextStartedActivity)
        activity.pause().stop().destroy()
    }

    @Test
    fun theCheckRunsOffTheMainThread() {
        // NetworkOnMainThreadException: Check for updates returns at once and the fetch happens on another thread. The
        // fake holds latest.json until released, so a fetch on this (the main) thread would never get past checkNow().
        assertTrue(Looper.getMainLooper().isCurrentThread)
        val fake = PhoneReleases(keys, holdList = true).also { releases = it }
        val updates = fake.updates(context, keys)
        updates.checkNow()
        assertTrue(fake.listAsked.await(10, TimeUnit.SECONDS))
        assertEquals(UpdatePhase.CHECKING, updates.state.value.phase)
        fake.release.countDown()
        val done = runBlocking { withTimeout(10_000) { updates.state.first { it.phase == UpdatePhase.IDLE && it.offer != null } } }
        assertEquals(PhoneReleases.VERSION, done.offer?.version)
    }
}
