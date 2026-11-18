// Plan 2026-11-17: the Kroger client built into the desktop and phone apps, applied by both builds. It is read from the
// git-ignored apps/kroger-client.properties (or the file the Gradle property mealplanner.krogerClient names), which
// holds clientId= and clientSecret= (client_id= and client_secret= are read too), and optionally
// environment=certification for a test client (else production). With neither, no client is built in and Settings
// asks for one.
//
// The values are read only when the task runs and are never printed: a bad one fails the build with what is wrong, not
// with the value. They are not task inputs either (Gradle would keep them in its history), so the task always runs.

val krogerClientOut = layout.buildDirectory.dir("generated/kroger-client")
val krogerNamed = providers.gradleProperty("mealplanner.krogerClient").map { rootProject.file(it).absolutePath }
val krogerFallback = rootProject.file("kroger-client.properties").absolutePath

tasks.register("krogerClientResource") {
    description = "Writes the built-in Kroger client (kroger-client.json) from apps/kroger-client.properties, when there is one."
    val out = krogerClientOut
    val named = krogerNamed
    val fallback = krogerFallback
    outputs.dir(out)
    outputs.upToDateWhen { false }
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val source = named.orNull?.let { path ->
            File(path).also { if (!it.isFile) throw GradleException("mealplanner.krogerClient names $path, which isn't a file.") }
        } ?: File(fallback).takeIf { it.isFile } ?: return@doLast
        val values = mutableMapOf<String, String>()
        // Read by hand, not with java.util.Properties: nothing here is escaped. A byte order mark (U+FEFF) may come first.
        for (raw in source.readText(Charsets.UTF_8).removePrefix(Char(0xFEFF).toString()).lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val at = line.indexOf('=')
            if (at < 0) throw GradleException("A line in $source has no '=': use clientId=, clientSecret= and environment=.")
            val key = when (val k = line.substring(0, at).trim()) {
                "clientId", "client_id" -> "id"
                "clientSecret", "client_secret" -> "secret"
                "environment" -> "environment"
                else -> throw GradleException("$source has a key other than clientId, clientSecret and environment.")
            }
            var value = line.substring(at + 1).trim()
            if (value.length >= 2 && value.first() == value.last() && (value.first() == '"' || value.first() == '\'')) value = value.substring(1, value.length - 1).trim()
            values[key] = value
        }
        // The same rule as KrogerAccount.printable: printable ASCII without spaces, not empty.
        fun printable(value: String?) = !value.isNullOrEmpty() && value.length <= 200 && value.all { it in '!'..'~' }
        if (!printable(values["id"])) throw GradleException("The Kroger client id in $source is missing or isn't printable ASCII without spaces.")
        if (!printable(values["secret"])) throw GradleException("The Kroger client secret in $source is missing or isn't printable ASCII without spaces.")
        val environment = values["environment"] ?: "production"
        if (environment != "production" && environment != "certification") throw GradleException("environment in $source must be production or certification.")
        val json = groovy.json.JsonOutput.toJson(mapOf("client_id" to values["id"], "client_secret" to values["secret"], "environment" to environment))
        File(dir, "kroger-client.json").writeText(json, Charsets.UTF_8)
    }
}
