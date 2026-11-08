package com.naeblis11.mealplanner.release

import com.naeblis11.mealplanner.update.DesktopRelease
import com.naeblis11.mealplanner.update.ReleaseManifest
import java.io.File
import java.nio.file.Files
import java.security.cert.CertificateFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The commands, on temp files and throwaway keytool keys only; never the owner's release key or ReleaseKeyData.kt. */
class ReleaseToolTest {
    private val dir: File = Files.createTempDirectory("mp-release-tool").toFile()
    private val password = "test-only-password"
    private val errors = mutableListOf<String>()
    private val printed = mutableListOf<String>()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun run(vararg args: String, secret: () -> CharArray? = { null }, builtIn: () -> java.security.PublicKey? = { null }): Int =
        runTool(args.toList(), password = secret, out = { printed += it }, err = { errors += it }, builtIn = builtIn)

    @Test
    fun theCommandsCheckTheirArgumentsAndNeedAConsoleToSign() {
        assertEquals(2, runTool(emptyList(), password = { null }, out = {}, err = { errors += it }))
        assertEquals(2, runTool(listOf("sign", "only-one"), password = { null }, out = {}, err = { errors += it }))
        assertEquals(2, runTool(listOf("publish"), password = { null }, out = {}, err = { errors += it }))
        // No console to ask on: nothing is read, nothing signed.
        assertEquals(1, runTool(listOf("sign", "release-key.p12", "latest.json"), password = { null }, out = {}, err = { errors += it }))
        assertTrue(errors.last(), errors.last().contains("console"))
    }

    @Test
    fun signTakesItsPasswordOnStandardInputOnlyWithTheFlag() {
        // Owner, 2026-11-08: release.ps1 hands over the DPAPI-sealed password on standard input.
        assertEquals(2, run("sign", "release-key.p12", "latest.json", "--password"))
        assertEquals(2, run("sign", "release-key.p12", "latest.json", PASSWORD_STDIN, "extra"))
        assertEquals(1, run("sign", "release-key.p12", "latest.json", PASSWORD_STDIN))
        assertTrue(errors.last(), errors.last().contains("standard input"))
        // With the flag and a password, it signs as from the console.
        val store = Keytool.makeStore(dir, password)
        val cert = Keytool.exportCertificate(store, password, File(dir, "test-key.cer"))
        val trusted = cert.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }.publicKey
        val list = File(dir, "latest.json").apply { writeText(ReleaseManifest("release-2", DesktopRelease("1.0.1", "MealPlanner-1.0.1.msi", 10, "a".repeat(64)), null).toJson()) }
        assertEquals(0, run("sign", store.path, list.path, PASSWORD_STDIN, secret = { password.toCharArray() }, builtIn = { trusted }))
        assertTrue(File(dir, "latest.json.sig").isFile)
    }

    @Test
    fun aPipedPasswordLosesPowerShellsByteOrderMarkAndItsLineEnd() {
        fun line(bytes: ByteArray) = passwordLine(bytes.inputStream())?.concatToString()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("test-only", line(bom + "test-only\r\n".toByteArray()))
        assertEquals("test-only", line("test-only\n".toByteArray()))
        // UTF-8 through, a character outside ASCII too.
        assertEquals("päss", line(bom + "päss\r\n".toByteArray(Charsets.UTF_8)))
        assertEquals(null, line(ByteArray(0)))
        assertEquals(null, line(bom + "\r\n".toByteArray()))
    }

    @Test
    fun publicKeyTakesOnlyReplaceAsAFourthArgument() {
        val target = File(dir, "ReleaseKeyData.kt").apply { writeText(PublicKeyFile.render("")) }
        val cert = File(dir, "test-key.cer")
        assertEquals(2, run("public-key", cert.path, target.path, "--force"))
        assertEquals(2, run("public-key", cert.path, target.path, "--replace", "extra"))
        assertEquals(2, run("public-key", cert.path))
        assertEquals("", PublicKeyFile.current(target))

        val first = Keytool.exportCertificate(Keytool.makeStore(File(dir, "a").apply { mkdirs() }, password), password, cert)
        assertEquals(0, run("public-key", first.path, target.path))
        val trusted = PublicKeyFile.current(target)
        val second = Keytool.exportCertificate(Keytool.makeStore(File(dir, "b").apply { mkdirs() }, password), password, File(dir, "b.cer"))
        assertEquals(1, run("public-key", second.path, target.path))
        assertEquals(trusted, PublicKeyFile.current(target))
        assertEquals(0, run("public-key", second.path, target.path, "--replace"))
        assertFalse(trusted == PublicKeyFile.current(target))
    }

    @Test
    fun stageWritesTheReleaseOrSaysWhyNot() {
        val props = File(dir, "gradle.properties").apply {
            writeText("mealplanner.desktopVersion=1.0.1\nmealplanner.androidVersionCode=2\nmealplanner.androidVersionName=1.0.1\n")
        }
        val msi = File(dir, "in.msi").apply { writeBytes(ByteArray(100) { 1 }) }
        val apk = File(dir, "in.apk").apply { writeBytes(ByteArray(50) { 2 }) }
        val out = File(dir, "stage")
        assertEquals(0, run("stage", props.path, msi.path, apk.path, out.path))
        assertEquals("release-2", ReleaseManifest.parse(File(out, "latest.json").readText()).tag)
        assertTrue(printed.last(), printed.last().contains("release-2"))

        assertEquals(1, run("stage", props.path, msi.path, apk.path, out.path))
        assertTrue(errors.last(), errors.last().contains("isn't empty"))
        assertEquals(1, run("stage", props.path, File(dir, "none.msi").path, apk.path, File(dir, "other").path))
        assertEquals(2, run("stage", props.path, msi.path, apk.path))
    }

    @Test
    fun signRefusesWithAReasonAndWipesThePassword() {
        val store = Keytool.makeStore(dir, password)
        val list = File(dir, "latest.json").apply { writeText("{}") }
        // A built-in key the apps couldn't use is a refusal with a reason, not a stack trace.
        var secret = password.toCharArray()
        val captured = secret
        assertEquals(1, run("sign", store.path, list.path, secret = { secret }, builtIn = { throw IllegalArgumentException("bad") }))
        assertTrue(errors.last(), errors.last().contains("ReleaseKeyData"))
        assertTrue(captured.all { it == ' ' })

        // An invalid list, with the right key trusted: refused, nothing written.
        val cert = Keytool.exportCertificate(store, password, File(dir, "test-key.cer"))
        val trusted = cert.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }.publicKey
        secret = password.toCharArray()
        assertEquals(1, run("sign", store.path, list.path, secret = { secret }, builtIn = { trusted }))
        assertFalse(File(dir, "latest.json.sig").exists())
        assertTrue(errors.none { it.contains(password) })
    }
}
