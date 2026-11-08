package com.naeblis11.mealplanner.release

import com.naeblis11.mealplanner.update.ManifestException
import com.naeblis11.mealplanner.update.ReleaseKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.PublicKey
import kotlin.system.exitProcess

/** A release step that can't go on; its message says why. It never carries a password or any key material. */
class ReleaseToolException(message: String) : Exception(message)

const val USAGE = """Usage (tools/release.ps1 and tools/release-key.ps1 run these; docs/RELEASING.md):
  releaseTool stage <gradle.properties> <msi> <apk> <empty folder>
  releaseTool sign <release-key.p12> <latest.json> [--password-stdin]
  releaseTool public-key <certificate> <ReleaseKeyData.kt> [--replace]"""

/**
 * The release tool. `sign` asks for the release key's password on the console itself, or, with `--password-stdin`
 * (tools/release.ps1, from the password tools/release-key-seal.ps1 sealed with DPAPI), reads it as one line of its
 * standard input. Never from an argument or the environment, which other programs can read.
 */
fun main(args: Array<String>) {
    val fromStdin = args.lastOrNull() == PASSWORD_STDIN
    exitProcess(
        runTool(
            args.toList(),
            password = {
                if (fromStdin) readLine()?.toCharArray()?.takeIf { it.isNotEmpty() } else System.console()?.readPassword("Release key password: ")
            },
        ),
    )
}

/** `sign`'s flag: the password comes on standard input. */
const val PASSWORD_STDIN = "--password-stdin"

/**
 * One command; 0 done, 1 refused (the reason on [err], never a stack trace), 2 usage. [builtIn] is the key built into
 * the apps (ReleaseKey); the tests pass their own.
 */
fun runTool(
    args: List<String>,
    password: () -> CharArray?,
    out: (String) -> Unit = ::println,
    err: (String) -> Unit = { System.err.println(it) },
    builtIn: () -> PublicKey? = ReleaseKey::builtIn,
): Int {
    try {
        when (args.firstOrNull()) {
            "stage" -> {
                if (args.size != 5) return usage(err)
                val manifest = Stage.stage(File(args[1]), File(args[2]), File(args[3]), File(args[4]))
                out("Staged ${manifest.tag} in ${args[4]}.")
            }
            "sign" -> {
                if (args.size != 3 && !(args.size == 4 && args[3] == PASSWORD_STDIN)) return usage(err)
                val secret = password() ?: throw ReleaseToolException(
                    if (args.size == 4) "No password came on standard input." else "There is no console to ask for the password on: run this in a PowerShell window.",
                )
                val signature = try {
                    val trusted = try {
                        builtIn()
                    } catch (e: IllegalArgumentException) {
                        throw ReleaseToolException("The key built into the apps (ReleaseKeyData.kt) isn't a valid EC P-256 public key.")
                    }
                    ReleaseSigner.signFile(File(args[2]), ReleaseSigner.loadKey(File(args[1]), secret), trusted)
                } finally {
                    secret.fill(' ')
                }
                out("Signed: $signature")
            }
            "public-key" -> {
                val replace = args.size == 4 && args[3] == "--replace"
                if (args.size != 3 && !replace) return usage(err)
                PublicKeyFile.write(File(args[1]), File(args[2]), replace)
                out("Wrote the release key's public half to ${args[2]}.")
            }
            else -> return usage(err)
        }
        return 0
    } catch (e: ReleaseToolException) {
        err("releaseTool: ${e.message}")
        return 1
    } catch (e: ManifestException) {
        err("releaseTool: ${e.message}")
        return 1
    } catch (e: GeneralSecurityException) {
        // Only the exception's kind: its message could describe a key.
        err("releaseTool: a signing or key operation failed (${e.javaClass.simpleName}).")
        return 1
    } catch (e: IOException) {
        err("releaseTool: couldn't read or write a file: ${e.message}")
        return 1
    }
}

private fun usage(err: (String) -> Unit): Int {
    err(USAGE)
    return 2
}
