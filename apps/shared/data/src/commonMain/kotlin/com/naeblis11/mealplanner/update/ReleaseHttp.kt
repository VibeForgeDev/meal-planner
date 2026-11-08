package com.naeblis11.mealplanner.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.security.MessageDigest

/** Where Meal Planner's releases are: the public repository's GitHub Releases. Tests point it at FakeReleases. */
data class ReleaseEndpoints(val releases: String) {
    /** A file of the newest release: GitHub redirects this to the release's own address. */
    fun latest(name: String): String = "$releases/latest/download/$name"

    /** A file of the release [tag]. */
    fun asset(tag: String, name: String): String = "$releases/download/$tag/$name"

    companion object {
        /** The public repository, under the owner's GitHub account (renamed from naeblis11 to VibeForgeDev, 1.4.1). */
        val GITHUB = ReleaseEndpoints("https://github.com/VibeForgeDev/meal-planner/releases")
    }
}

/** A reply other than 200, after the redirects. */
class ReleaseStatusException(val status: Int) : IOException("GitHub answered $status.")

/** A reply bigger than allowed, or a download that isn't the size the signed list gives. */
class ReleaseSizeException(message: String) : IOException(message)

/**
 * The only code on the phone that goes online (UpdateNetworkCodeTest), and the PC's way to GitHub for updates (spec
 * "Updates"): https only, to [hosts] only, checked before anything is sent and again at every redirect, which is
 * followed here by hand (never by the platform), at most MAX_REDIRECTS times. Short timeouts. HttpURLConnection, because
 * Android has no java.net.http. Nothing here logs; a failure is an IOException whose message names a host at most.
 * Tests pass hosts = 127.0.0.1 and httpsOnly = false, for FakeReleases.
 */
class ReleaseHttp(
    val hosts: Set<String> = GITHUB_HOSTS,
    private val httpsOnly: Boolean = true,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    private val overallTimeoutMillis: Long = OVERALL_TIMEOUT_MILLIS,
    private val downloadTimeoutMillis: Long = DOWNLOAD_TIMEOUT_MILLIS,
) {
    /** [url] as a URI, or an IOException unless it is https (or, in tests, http) on one of [hosts]. Sends nothing. */
    fun allowed(url: String): URI {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IOException("That isn't an address Meal Planner can use.")
        }
        val scheme = uri.scheme
        val schemeOk = scheme == "https" || (!httpsOnly && scheme == "http")
        val host = uri.host?.lowercase(Locale.ROOT)
        if (!schemeOk || host == null || host !in hosts) {
            throw IOException("Meal Planner only fetches its updates from GitHub; it won't contact $scheme://${uri.host}.")
        }
        // https means the default port (443) only; plain http is for the tests' ephemeral ports.
        if (httpsOnly && uri.port != -1 && uri.port != 443) {
            throw IOException("Meal Planner only fetches its updates from GitHub's usual port.")
        }
        return uri
    }

    /** [url]'s whole reply, refusing one over [maxBytes] (latest.json, its signature). */
    fun fetch(url: String, maxBytes: Int): ByteArray {
        require(maxBytes in 0 until Int.MAX_VALUE)
        val deadline = System.nanoTime() + overallTimeoutMillis * 1_000_000L
        val connection = open(url)
        try {
            val declared = connection.contentLengthLong
            if (declared > maxBytes) throw ReleaseSizeException("The reply is $declared bytes; at most $maxBytes are allowed.")
            connection.inputStream.use { input ->
                val bytes = readAtMost(input, maxBytes + 1, deadline)
                if (bytes.size > maxBytes) throw ReleaseSizeException("The reply is over $maxBytes bytes.")
                return bytes
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams [url] into [target], always made new (CREATE_NEW: anything already at that name, a file, a folder or a
     * link, is an IOException and is never written through), and returns the SHA-256 of what it wrote, in lowercase hex. It
     * refuses a declared length other than [expectedSize] before opening [target], stops at the first read past
     * [expectedSize], and refuses a shorter reply; each is a ReleaseSizeException. The whole download has a deadline
     * (downloadTimeoutMillis, checked between reads, an IOException). [onProgress] gets the bytes so far after each
     * read; whatever it throws (a cancelled coroutine's CancellationException) stops the download and closes the
     * connection. The caller compares the hash and deletes [target] on any failure.
     */
    fun download(url: String, expectedSize: Long, target: File, onProgress: (Long) -> Unit = {}): String {
        val deadline = System.nanoTime() + downloadTimeoutMillis * 1_000_000L
        val connection = open(url)
        try {
            val declared = connection.contentLengthLong
            if (declared >= 0 && declared != expectedSize) {
                throw ReleaseSizeException("GitHub offers $declared bytes, not the $expectedSize the update list gives.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (System.nanoTime() - deadline > 0) throw IOException("GitHub took too long to send the update.")
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > expectedSize) throw ReleaseSizeException("The download is bigger than the $expectedSize bytes the update list gives.")
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        onProgress(total)
                    }
                }
            }
            if (total != expectedSize) throw ReleaseSizeException("The download is $total bytes, not the $expectedSize the update list gives.")
            return Sha256.hex(digest.digest())
        } finally {
            connection.disconnect()
        }
    }

    // A 200 reply for [start], following redirects by hand, each one checked against the list before it is asked.
    private fun open(start: String): HttpURLConnection {
        var current = allowed(start)
        repeat(MAX_REDIRECTS + 1) {
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
            when (status) {
                HttpURLConnection.HTTP_OK -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) throw IOException("GitHub sent a redirect without an address.")
                    val next = try {
                        current.resolve(location)
                    } catch (e: IllegalArgumentException) {
                        throw IOException("GitHub sent a redirect Meal Planner can't read.")
                    }
                    current = allowed(next.toString())
                }
                else -> {
                    connection.disconnect()
                    throw ReleaseStatusException(status)
                }
            }
        }
        throw IOException("GitHub sent more than $MAX_REDIRECTS redirects.")
    }

    // Up to [limit] bytes; the caller tells "exactly at the cap" from "over it" by asking for one more than it allows.
    private fun readAtMost(input: InputStream, limit: Int, deadline: Long): ByteArray {
        require(limit in 1..Int.MAX_VALUE)
        val buffer = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            if (System.nanoTime() - deadline > 0) throw IOException("GitHub took too long to answer.")
            val n = input.read(buffer, filled, limit - filled)
            if (n < 0) break
            filled += n
        }
        return buffer.copyOf(filled)
    }

    companion object {
        /**
         * github.com answers the release addresses and redirects a file to its asset host: release-assets.githubusercontent.com
         * (measured 2026-10-06), or objects.githubusercontent.com, which it used before. No API host: nothing uses it.
         */
        val GITHUB_HOSTS: Set<String> = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
        const val OVERALL_TIMEOUT_MILLIS = 60_000L

        /** The whole of one download (at most MAX_FILE_BYTES): 30 minutes, about 1.4 Mbit/s for the largest file allowed. */
        const val DOWNLOAD_TIMEOUT_MILLIS = 30L * 60_000
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "MealPlanner-update-check"
        private const val BUFFER = 64 * 1024
    }
}
