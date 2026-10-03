package io.github.anbu00001.nocturne.core.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nocturne holds a sleep history, so what can talk to the network is kept to one small, tested place (Phase 5): core-model's
 * `core/sync` package, which connects only to a paired laptop on a private network. This reads the repository's own sources
 * and fails if anything else grows network code, a network library, or the INTERNET permission in another manifest.
 */
class NetworkSurfaceTest {

    /** The repository root: Gradle runs a module's tests in the module's directory. */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun sources(vararg extensions: String): List<File> = root.walkTopDown()
        .onEnter { it.name !in setOf("build", ".gradle", ".git", ".idea", ".venv", "node_modules") }
        .filter { it.isFile && it.extension in extensions }
        .toList()

    private fun relative(file: File) = file.relativeTo(root).invariantSeparatorsPath

    private fun isMain(file: File) = "/src/main/" in "/" + relative(file)

    @Test
    fun `only the sync package touches the network from code`() {
        val network = Regex("""\b(java\.net\.|javax\.net\.|okhttp3|HttpURLConnection|HttpsURLConnection|WebView|retrofit2|io\.ktor|java\.nio\.channels\.SocketChannel)""")
        val offenders = sources("kt", "java").filter { isMain(it) }
            .filter { "/core/sync/" !in "/" + relative(it) }
            .filter { network.containsMatchIn(it.readText()) }
            .map(::relative)
        assertEquals(emptyList(), offenders, "network code outside core-model's core/sync package")
        assertTrue(sources("kt").any { relative(it).endsWith("core/sync/SyncClient.kt") }, "the check found the sync package")
    }

    @Test
    fun `no network or telemetry library is a dependency`() {
        val libraries = Regex("""okhttp|retrofit|ktor|firebase|crashlytics|sentry|coil|glide|volley|play-services|analytics|appsflyer|mixpanel|amplitude""", RegexOption.IGNORE_CASE)
        val offenders = sources("kts", "toml", "gradle").filter { libraries.containsMatchIn(it.readText()) }.map(::relative)
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `only the app asks for the INTERNET permission`() {
        val manifests = sources("xml").filter { it.name == "AndroidManifest.xml" && isMain(it) }
        val asking = manifests.filter { Regex("""<uses-permission[^>]*android\.permission\.INTERNET"[^>]*/>""").findAll(it.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")).any { m -> "tools:node=\"remove\"" !in m.value } }
            .map(::relative)
        assertEquals(listOf("app/src/main/AndroidManifest.xml"), asking)
    }
}
