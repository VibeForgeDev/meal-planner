package com.naeblis11.mealplanner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec "Updates" and the Decisions table's Android row: the phone talks only to GitHub for updates (and, in phase 2,
 * to the paired PC and Google). So in code the phone runs (commonMain and androidMain of shared/core, shared/data and
 * shared/ui, and androidApp's main), only update/ReleaseHttp.kt, sync/SyncHttp.kt (the paired PC, pinned) and
 * kroger/KrogerHttp.kt (Kroger's API, plan 2026-11-17) may open a connection. The phone's mDNS look for its PC (stage 2c, androidApp's sync/NsdPcFinder.kt) goes through Android's
 * NsdManager and imports nothing from java.net, so it needs no entry.
 */
class UpdateNetworkCodeTest {
    @Test
    fun onlyTheUpdateClientGoesOnlineInCodeThePhoneRuns() {
        val root = File(System.getProperty("srcDir") ?: error("srcDir is not set; run through Gradle"))
        val network = Regex(
            """^\s*import\s+(java\.net\..+|javax\.net\..+|okhttp3\..+|io\.ktor\.client\..+)\s*$|\.openConnection\(|\.openStream\(|\bjava\.net\.(URL|Socket|HttpURLConnection)\b|\bDownloadManager\b|\bWebView\b""",
        )
        val shipped = Regex("""^(shared/[a-z]+/src/(commonMain|androidMain)|androidApp/src/(?!test/|androidTest/)[^/]+)/""")
        val allowed = setOf(
            "shared/data/src/commonMain/kotlin/com/naeblis11/mealplanner/update/ReleaseHttp.kt",
            // LAN sync (stage 2b): talks only to the paired PC, pinned to its certificate's fingerprint.
            "shared/data/src/commonMain/kotlin/com/naeblis11/mealplanner/sync/SyncHttp.kt",
            // Kroger (plan 2026-11-17): only Kroger's API hosts, https only (KrogerAccountTest pins them).
            "shared/data/src/commonMain/kotlin/com/naeblis11/mealplanner/kroger/KrogerHttp.kt",
        )
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.relativeTo(root).invariantSeparatorsPath }
            .filter { (_, path) -> shipped.containsMatchIn(path) && path !in allowed }
            .filter { (file, _) -> file.readLines(Charsets.UTF_8).any { network.containsMatchIn(it) } }
            .map { it.second }
            .toList()
        assertEquals("Only update/ReleaseHttp.kt, sync/SyncHttp.kt and kroger/KrogerHttp.kt may go online in code the phone runs", emptyList<String>(), offenders)
        for (path in allowed) assertTrue("$path has moved: update this test", File(root, path).isFile)
    }
}
