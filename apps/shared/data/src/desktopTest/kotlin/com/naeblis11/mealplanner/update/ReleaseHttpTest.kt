package com.naeblis11.mealplanner.update

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseHttpTest {
    private val releases = FakeReleases()
    private val dir: File = Files.createTempDirectory("mp-release-http").toFile()

    @After
    fun tearDown() {
        releases.close()
        dir.deleteRecursively()
    }

    @Test
    fun theUpdateCheckAsksTheRenamedAccount() {
        // 1.4.1: the GitHub account was renamed from naeblis11 to VibeForgeDev; new builds never ask the old name, which
        // only redirects until someone else takes it.
        assertEquals("https://github.com/VibeForgeDev/meal-planner/releases", ReleaseEndpoints.GITHUB.releases)
    }

    @Test
    fun theOnlyHostsAreGitHubsOwn() {
        // Spec "Updates": github.com and GitHub's download hosts, over https; nothing else, ever.
        assertEquals(setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"), ReleaseHttp.GITHUB_HOSTS)
        assertEquals(ReleaseHttp.GITHUB_HOSTS, ReleaseHttp().hosts)
        assertEquals(
            "https://github.com/VibeForgeDev/meal-planner/releases/latest/download/latest.json",
            ReleaseEndpoints.GITHUB.latest("latest.json"),
        )
        assertEquals(
            "https://github.com/VibeForgeDev/meal-planner/releases/download/release-2/MealPlanner-1.0.1.msi",
            ReleaseEndpoints.GITHUB.asset("release-2", "MealPlanner-1.0.1.msi"),
        )
    }

    @Test
    fun anAddressOffTheListIsRefusedBeforeAnythingIsSent() {
        val github = ReleaseHttp()
        val refused = listOf(
            "http://github.com/x",
            "https://api.github.com/x",
            "https://github.com.example.org/x",
            "https://github.com@example.org/x",
            "https://example.org/github.com",
            "file:///C:/x",
            "not an address",
        )
        for (url in refused) assertThrows(url, IOException::class.java) { github.allowed(url) }
        assertEquals("github.com", github.allowed("https://github.com/VibeForgeDev/meal-planner/releases").host)
        assertThrows(IOException::class.java) { releases.http().fetch("http://localhost:${releases.port}/x", 10) }
        assertEquals(emptyList<String>(), releases.requests)
    }

    @Test
    fun redirectsAreFollowedOnTheList() {
        releases.publish("release-2", "latest.json", "{}".encodeToByteArray())
        assertEquals("{}", releases.http().fetch(releases.endpoints.latest("latest.json"), 100).decodeToString())
        assertEquals(
            listOf(
                "${FakeReleases.REPO}/releases/latest/download/latest.json",
                "${FakeReleases.REPO}/releases/download/release-2/latest.json",
                "/assets/release-2/latest.json",
            ),
            releases.requests,
        )
    }

    @Test
    fun aRedirectOffTheListIsRefused() {
        releases.publish("release-2", "latest.json", "{}".encodeToByteArray())
        releases.assetHost = "localhost"
        val e = assertThrows(IOException::class.java) { releases.http().fetch(releases.endpoints.latest("latest.json"), 100) }
        assertTrue(e.message, e.message!!.contains("localhost"))
        assertTrue(releases.requests.none { it.startsWith("/assets/") })
    }

    @Test
    fun endlessRedirectsAreRefused() {
        assertThrows(IOException::class.java) { releases.http().fetch(releases.url("/loop"), 100) }
        assertEquals(ReleaseHttp.MAX_REDIRECTS + 1, releases.requests.size)
    }

    @Test
    fun aReplyOverItsCapIsRefused() {
        releases.publish("release-2", "big", ByteArray(2_000))
        val url = releases.endpoints.asset("release-2", "big")
        // Declared up front, then streamed without a length: refused either way.
        assertThrows(ReleaseSizeException::class.java) { releases.http().fetch(url, 1_000) }
        releases.chunked = true
        assertThrows(ReleaseSizeException::class.java) { releases.http().fetch(url, 1_000) }
        assertEquals(2_000, releases.http().fetch(url, 2_000).size)
    }

    @Test
    fun aMissingFileIsItsStatus() {
        val e = assertThrows(ReleaseStatusException::class.java) { releases.http().fetch(releases.endpoints.latest("latest.json.sig"), 100) }
        assertEquals(404, e.status)
    }

    @Test
    fun aSlowReplyTimesOut() {
        releases.publish("release-2", "latest.json", "{}".encodeToByteArray())
        releases.delayMillis = 3_000
        val started = System.nanoTime()
        assertThrows(IOException::class.java) { releases.http(readTimeoutMillis = 300).fetch(releases.endpoints.latest("latest.json"), 100) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_500)
    }

    @Test
    fun aDownloadIsStreamedToItsFileAndHashed() {
        val body = ByteArray(300_000) { (it % 253).toByte() }
        releases.publish("release-2", "MealPlanner-1.0.1.msi", body)
        val target = File(dir, "MealPlanner-1.0.1.msi.part")
        var progress = 0L
        val sha = releases.http().download(releases.endpoints.asset("release-2", "MealPlanner-1.0.1.msi"), body.size.toLong(), target) { progress = it }
        assertEquals(Sha256.of(body), sha)
        assertArrayEquals(body, target.readBytes())
        assertEquals(body.size.toLong(), progress)
    }

    @Test
    fun aDownloadThatIsntTheListedSizeIsStopped() {
        releases.publish("release-2", "f.msi", ByteArray(300_000))
        val url = releases.endpoints.asset("release-2", "f.msi")
        val target = File(dir, "f.msi.part")
        // A Content-Length that differs from the list: refused before the file is opened.
        assertThrows(ReleaseSizeException::class.java) { releases.http().download(url, 1_000, target) }
        assertFalse(target.exists())
        // No length up front: stopped at the first read past the listed size.
        releases.chunked = true
        assertThrows(ReleaseSizeException::class.java) { releases.http().download(url, 1_000, target) }
        assertTrue(target.length() <= 1_000)
        // Shorter than listed.
        target.delete()
        assertThrows(ReleaseSizeException::class.java) { releases.http().download(url, 400_000, target) }
    }

    @Test
    fun aDownloadNeverWritesIntoSomethingAlreadyThere() {
        // The target is always made new (CREATE_NEW), so nothing planted at its name is written through.
        val body = ByteArray(1_000) { 3 }
        releases.publish("release-2", "f.msi", body)
        val url = releases.endpoints.asset("release-2", "f.msi")
        val target = File(dir, "f.msi.part").apply { writeBytes(byteArrayOf(9)) }
        assertThrows(IOException::class.java) { releases.http().download(url, body.size.toLong(), target) }
        assertArrayEquals(byteArrayOf(9), target.readBytes())
        val folder = File(dir, "g.msi.part").apply { mkdirs() }
        assertThrows(IOException::class.java) { releases.http().download(url, body.size.toLong(), folder) }
        assertTrue(folder.isDirectory)
    }

    @Test
    fun aSlowDripHitsTheOverallDeadline() {
        releases.publish("release-2", "slow", ByteArray(100))
        releases.dripMillis = 50
        val started = System.nanoTime()
        assertThrows(IOException::class.java) { releases.http(overallTimeoutMillis = 400).fetch(releases.endpoints.asset("release-2", "slow"), 1_000) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test
    fun aSlowDownloadHitsItsOwnDeadline() {
        releases.publish("release-2", "slow.msi", ByteArray(1_000))
        releases.dripMillis = 20
        val target = File(dir, "slow.msi.part")
        val started = System.nanoTime()
        assertThrows(IOException::class.java) {
            releases.http(downloadTimeoutMillis = 400).download(releases.endpoints.asset("release-2", "slow.msi"), 1_000, target)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        assertTrue(ReleaseHttp.DOWNLOAD_TIMEOUT_MILLIS >= 10 * 60_000L)
    }

    @Test
    fun aReplyExactlyAtItsCapIsAccepted() {
        releases.publish("release-2", "exact", ByteArray(1_000) { 7 })
        assertEquals(1_000, releases.http().fetch(releases.endpoints.asset("release-2", "exact"), 1_000).size)
    }

    @Test
    fun aRedirectWithoutAnAddressFailsClosed() {
        assertThrows(IOException::class.java) { releases.http().fetch(releases.url("/nolocation"), 100) }
    }

    @Test
    fun aRelativeRedirectIsResolvedAndChecked() {
        releases.publish("release-2", "rel", "ok".encodeToByteArray())
        assertEquals("ok", releases.http().fetch(releases.url("/relative"), 100).decodeToString())
    }

    @Test
    fun hostCaseDoesNotMatterAndHttpsPortsAreLimited() {
        val github = ReleaseHttp()
        assertEquals("GitHub.COM", github.allowed("https://GitHub.COM/x").host)
        github.allowed("https://github.com:443/x")
        assertThrows(IOException::class.java) { github.allowed("https://github.com:8443/x") }
    }
}
