package com.c0mpile.grimmreader.core.dictionary

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.core.files.DocumentStore
import com.c0mpile.grimmreader.core.network.GuardedHttpClient
import com.c0mpile.grimmreader.core.network.NetworkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DictionariesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dictionaries = Dictionaries(context, DocumentStore(context), Dispatchers.IO, scope)
    private val server = MockWebServer()

    @After fun stop() {
        scope.cancel()
        server.close()
        File(context.filesDir, "dictionaries").deleteRecursively()
    }

    private fun sample(
        stem: String,
        name: String,
        articles: Map<String, String>,
        dictZip: Boolean = false,
    ): List<File> =
        TestDictionary.write(tmp.newFolder(stem), stem, name, articles, dictZipChunk = if (dictZip) 32 else null).listFiles()!!.toList()

    private fun zip(
        name: String,
        files: List<File>,
    ): File =
        File(tmp.root, name).also { zip ->
            ZipOutputStream(zip.outputStream()).use { out ->
                for (f in files) {
                    // Folders inside the archive are ignored; "../" can't escape.
                    out.putNextEntry(ZipEntry("../nested/${f.name}"))
                    out.write(f.readBytes())
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry("readme.txt"))
                out.write("ignored".toByteArray())
                out.closeEntry()
            }
        }

    private fun uri(file: File) = Uri.fromFile(file)

    @Test fun importsFromZipAndLooksUpBaseForms() =
        runBlocking {
            val result =
                dictionaries.importDocuments(
                    listOf(
                        uri(
                            zip(
                                "english.zip",
                                sample(
                                    "en",
                                    "Sample English",
                                    mapOf(
                                        "run" to "to move fast",
                                        "study" to "to learn",
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            assertEquals(listOf("Sample English"), result.added)
            assertEquals(1, dictionaries.installed.value.size)
            assertTrue(File(context.filesDir, "nested").exists().not())

            val running = dictionaries.lookup("Running")
            assertEquals("run", running.matched)
            assertEquals(listOf(Definition("Sample English", "run", "to move fast")), running.definitions)
            assertEquals("study", dictionaries.lookup("studies").matched)
            val none = dictionaries.lookup("xylophone")
            assertNull(none.matched)
            assertTrue(none.definitions.isEmpty())
        }

    @Test fun importsLooseFilesAndSkipsDuplicates() =
        runBlocking {
            val files = sample("loose", "Loose", mapOf("cat" to "an animal"), dictZip = true)
            assertEquals(listOf("Loose"), dictionaries.importDocuments(files.map(::uri)).added)
            val again = dictionaries.importDocuments(files.map(::uri))
            assertEquals(1, again.duplicates)
            assertTrue(again.added.isEmpty())
            assertEquals(
                "an animal",
                dictionaries
                    .lookup("cats")
                    .definitions
                    .single()
                    .html,
            )
        }

    @Test fun incompleteDictionaryFails() =
        runBlocking {
            val files = sample("half", "Half", mapOf("cat" to "an animal")).filterNot { it.name.endsWith(".dict") }
            val result = dictionaries.importDocuments(files.map(::uri))
            assertTrue(result.added.isEmpty())
            assertEquals(1, result.failed)
            assertTrue(dictionaries.installed.value.isEmpty())
            assertTrue(File(context.filesDir, "dictionaries").listFiles().orEmpty().none { !it.name.startsWith(".") })
        }

    @Test fun removesADictionary() =
        runBlocking {
            dictionaries.importDocuments(listOf(uri(zip("a.zip", sample("a", "First", mapOf("dog" to "an animal"))))))
            dictionaries.importDocuments(listOf(uri(zip("b.zip", sample("b", "Second", mapOf("dog" to "a pet"))))))
            assertEquals(
                setOf("an animal", "a pet"),
                dictionaries
                    .lookup("dog")
                    .definitions
                    .map { it.html }
                    .toSet(),
            )
            dictionaries.remove(
                dictionaries.installed.value
                    .first { it.name == "First" }
                    .id,
            )
            assertEquals(listOf("a pet"), dictionaries.lookup("dog").definitions.map { it.html })
        }

    @Test fun parsesCatalogues() {
        val wiktionary =
            DictionaryCatalog.parseWiktionary(
                """{"tag_name":"20260101","assets":[
                  {"name":"en-en.tar.zst","size":100,"browser_download_url":"https://github.com/xxyzz/wiktionary_stardict/releases/download/20260101/en-en.tar.zst","digest":"sha256:abababababababababababababababababababababababababababababababab"},
                  {"name":"en_images.tar.zst","size":5,"browser_download_url":"https://github.com/xxyzz/wiktionary_stardict/releases/download/20260101/en_images.tar.zst"},
                  {"name":"de-en.tar.zst","size":7,"browser_download_url":"https://elsewhere.example.com/de-en.tar.zst"},
                  {"name":"fr-fr.tar.zst","size":9,"browser_download_url":"https://github.com/xxyzz/wiktionary_stardict/releases/download/20260101/fr-fr.tar.zst"},
                  {"name":"en.json","size":1,"browser_download_url":"https://github.com/xxyzz/wiktionary_stardict/releases/download/20260101/en.json"}]}""",
            )
        assertEquals(listOf("wiktionary:en-en"), wiktionary.map { it.id })
        assertEquals("sha256:" + "ab".repeat(32), wiktionary.single().checksum)
        val freedict =
            DictionaryCatalog.parseFreeDict(
                """[{"name":"deu-eng","headwords":"1200","releases":[
                    {"platform":"dictd","URL":"https://download.freedict.org/dictionaries/deu-eng/1.0/freedict-deu-eng-1.0.dictd.tar.xz"},
                    {"platform":"stardict","URL":"https://download.freedict.org/dictionaries/deu-eng/1.0/freedict-deu-eng-1.0.stardict.tar.xz","size":"300","checksum":"cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd","version":"1.0"}]},
                  {"name":"eng-fra","releases":[{"platform":"stardict","URL":"https://download.freedict.org/dictionaries/eng-fra/1/freedict-eng-fra-1.stardict.tar.xz","checksum":"short"}]},
                  {"software":{}},
                  {"name":"fra-eng","releases":[{"platform":"stardict","URL":"http://download.freedict.org/dictionaries/x.stardict.tar.xz"}]}]""",
            )
        assertEquals(1, freedict.size)
        with(freedict.single()) {
            assertEquals("freedict:deu-eng", id)
            assertEquals(1200, words)
            assertEquals("sha512:" + "cd".repeat(64), checksum)
            assertEquals("freedict-deu-eng-1.0.stardict.tar.xz", fileName)
        }
    }

    private val localhost =
        object : NetworkPolicy {
            override fun isCleartextAllowed(host: String) = host == server.hostName

            override fun pinnedSpki(host: String): String? = null
        }

    private fun entry(
        archive: File,
        checksum: String,
    ) = CatalogEntry(
        id = "wiktionary:xx-en",
        source = DictionarySource.WIKTIONARY,
        from = "xx",
        to = "en",
        sizeBytes = archive.length(),
        words = null,
        url = server.url("/xx-en.zip").toString(),
        fileName = "xx-en.zip",
        checksum = checksum,
        version = "1",
    )

    @Test fun downloadsChecksAndInstalls() =
        runBlocking {
            val archive = zip("xx-en.zip", sample("xx", "Downloaded", mapOf("word" to "a unit of language")))
            val sha = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            server.enqueue(MockResponse.Builder().body(Buffer().write(archive.readBytes())).build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(archive.readBytes())).build())
            server.start()
            val downloads =
                DictionaryDownloads(context, { GuardedHttpClient.create(localhost, "test") }, dictionaries, Dispatchers.IO, scope)

            downloads.start(entry(archive, "sha256:" + "0".repeat(64)))
            val failed = withTimeout(10_000) { downloads.state.first { it["wiktionary:xx-en"] is DictionaryDownload.Failed } }
            assertTrue((failed["wiktionary:xx-en"] as DictionaryDownload.Failed).message.contains("checksum"))
            assertTrue(dictionaries.installed.value.isEmpty())

            downloads.start(entry(archive, "sha256:$sha"))
            val installed = withTimeout(10_000) { dictionaries.installed.first { it.isNotEmpty() } }
            assertEquals("wiktionary:xx-en", installed.single().catalogId)
            withTimeout(10_000) { downloads.state.first { it.isEmpty() } }
            assertTrue(File(context.cacheDir, "dictionary-downloads").listFiles().orEmpty().isEmpty())
        }
}
