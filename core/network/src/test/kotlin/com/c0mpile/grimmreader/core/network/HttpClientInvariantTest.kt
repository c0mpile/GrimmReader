package com.c0mpile.grimmreader.core.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Same rule as scripts/check-http-clients: no HTTP client outside GuardedHttpClient.kt. */
class HttpClientInvariantTest {
    private val pattern =
        Regex("""OkHttpClient\(\)|OkHttpClient\.Builder\(|HttpURLConnection|HttpsURLConnection|\.openStream\(|\.openConnection\(""")

    @Test fun onlyTheGuardedClientBuildsHttpClients() {
        val root = File(System.getProperty("grimm.root") ?: error("grimm.root not set"))
        val offenders =
            root
                .walkTopDown()
                .onEnter { it.name !in setOf("build", ".gradle", ".git", ".local", "spikes", ".kotlin") }
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") && it.name != "GuardedHttpClient.kt" }
                .filter { it.name != "HttpClientInvariantTest.kt" }
                .filter { pattern.containsMatchIn(it.readText()) }
                .map { it.relativeTo(root).path }
                .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
