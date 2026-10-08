package com.c0mpile.grimmreader.core.dictionary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.c0mpile.grimmreader.core.common.AppScope
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.files.DocumentStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** An installed dictionary; [catalogId] is set when it was downloaded from the catalogue. */
data class InstalledDictionary(
    val id: String,
    val name: String,
    val words: Int,
    val sizeBytes: Long,
    val catalogId: String? = null,
)

/** One dictionary's article for the word; [html] is the safe subset described in [DefinitionHtml]. */
data class Definition(
    val dictionary: String,
    val word: String,
    val html: String,
)

/**
 * The result of looking up [query]: [matched] is the form found (e.g. "run" for "running"), null if none;
 * [dictionaries] is how many were searched (0: none installed).
 */
data class Lookup(
    val query: String,
    val matched: String?,
    val definitions: List<Definition>,
    val dictionaries: Int,
)

data class ImportResult(
    val added: List<String> = emptyList(),
    val duplicates: Int = 0,
    val failed: Int = 0,
)

/**
 * The StarDict dictionaries in app storage (`files/dictionaries/<id>/`), copied there on import so no
 * folder grant is needed later. Lookups go through every dictionary in the order they were added.
 */
@Singleton
class Dictionaries
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val documents: DocumentStore,
        @IoDispatcher private val io: CoroutineDispatcher,
        @AppScope scope: CoroutineScope,
    ) {
        private val root = File(context.filesDir, "dictionaries")
        private val lock = Mutex()

        /** Imports take minutes for large dictionaries; they don't hold [lock], so lookups keep working. */
        private val importLock = Mutex()
        private val opened = mutableMapOf<String, StarDictionary>()
        private val _installed = MutableStateFlow<List<InstalledDictionary>>(emptyList())
        val installed: StateFlow<List<InstalledDictionary>> = _installed.asStateFlow()

        /** Completes once the installed list was first read from storage. */
        private val loaded = CompletableDeferred<Unit>()

        init {
            scope.launch(io) {
                lock.withLock { refresh() }
                loaded.complete(Unit)
            }
        }

        /** The first form of [word] (see [WordForms]) any dictionary has, with every dictionary's articles for it. */
        suspend fun lookup(word: String): Lookup =
            withContext(io) {
                loaded.await()
                lock.withLock {
                    val dictionaries = _installed.value.mapNotNull { d -> dictionary(d.id)?.let { d to it } }
                    for (form in WordForms.of(word)) {
                        val found =
                            dictionaries
                                .flatMap { (d, dict) ->
                                    dict.lookup(form).map { Definition(d.name, it.word, DefinitionHtml.of(it.fields)) }
                                }.filter { it.html.isNotEmpty() }
                        if (found.isNotEmpty()) return@withLock Lookup(word, form, found, dictionaries.size)
                    }
                    Lookup(word, null, emptyList(), dictionaries.size)
                }
            }

        /** Imports picked files: StarDict files and archives holding them. */
        suspend fun importDocuments(uris: List<Uri>): ImportResult = import(uris.map { uri -> displayName(uri) to { open(uri) } })

        /** Imports the dictionary files in a picked folder and its subfolders. */
        suspend fun importFolder(tree: Uri): ImportResult =
            withContext(io) {
                val files = runCatching { documents.listFiles(tree.toString(), DictionaryUnpacker::isImportable) }.getOrNull()
                if (files == null) return@withContext ImportResult(failed = 1)
                import(files.map { f -> f.name to { open(Uri.parse(f.uri)) } })
            }

        /** Installs a downloaded archive [file] as catalogue entry [catalogId]. */
        internal suspend fun importDownload(
            name: String,
            file: File,
            catalogId: String,
        ): ImportResult = import(listOf(name to { file.inputStream() }), catalogId)

        suspend fun remove(id: String) =
            withContext(io) {
                lock.withLock {
                    // Ids are the folder names this class creates (digits); nothing else may be deleted.
                    if (id.isNotEmpty() && id.all { it.isDigit() }) {
                        opened.remove(id)?.close()
                        File(root, id).deleteRecursively()
                    }
                    refresh()
                }
            }

        private suspend fun import(
            sources: List<Pair<String, () -> InputStream>>,
            catalogId: String? = null,
        ): ImportResult =
            withContext(io) {
                importLock.withLock {
                    root.mkdirs()
                    val staging = File(root, ".staging").apply { deleteRecursively() }
                    staging.mkdirs()
                    try {
                        var failed = 0
                        val unpacker = DictionaryUnpacker(staging)
                        for ((name, open) in sources) {
                            try {
                                open().use { unpacker.add(name, it) }
                            } catch (_: IOException) {
                                failed++
                            }
                        }
                        install(staging, catalogId).let { it.copy(failed = it.failed + failed) }
                    } finally {
                        staging.deleteRecursively()
                        refresh()
                    }
                }
            }

        /** Moves each complete dictionary (`.ifo` with its `.idx` and `.dict`) from [staging] into a folder of its own. */
        private fun install(
            staging: File,
            catalogId: String?,
        ): ImportResult {
            val added = mutableListOf<String>()
            var duplicates = 0
            var failed = 0
            val ifos = staging.listFiles { f -> f.name.endsWith(".ifo") }.orEmpty().sortedBy { it.name }
            for (ifo in ifos) {
                val stem = ifo.name.removeSuffix(".ifo")
                val parts = staging.listFiles { f -> f.name.startsWith("$stem.") && f.name.removePrefix("$stem.") in PARTS }.orEmpty()
                val info = StarDictInfo.parse(ifo.readText())
                if (info == null || _installed.value.any { it.name == info.name && it.words == info.wordCount }) {
                    if (info == null) failed++ else duplicates++
                    continue
                }
                val dir = File(root, newId())
                dir.mkdirs()
                parts.forEach { it.renameTo(File(dir, it.name)) }
                val ok = runCatching { StarDictionary.open(dir).close() }.isSuccess
                if (ok) {
                    catalogId?.let { File(dir, CATALOG_FILE).writeText(it) }
                    added += info.name
                    refresh()
                } else {
                    dir.deleteRecursively()
                    failed++
                }
            }
            if (ifos.isEmpty()) failed++
            return ImportResult(added, duplicates, failed)
        }

        private fun newId(): String {
            var n = System.currentTimeMillis()
            while (File(root, n.toString()).exists()) n++
            return n.toString()
        }

        private fun dictionary(id: String): StarDictionary? =
            opened[id] ?: runCatching { StarDictionary.open(File(root, id)) }.getOrNull()?.also { opened[id] = it }

        private fun refresh() {
            _installed.value =
                root
                    .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                    .orEmpty()
                    .sortedBy { it.name.toLongOrNull() ?: Long.MAX_VALUE }
                    .mapNotNull { dir ->
                        val info = dir.listFiles { f -> f.name.endsWith(".ifo") }?.singleOrNull()?.let { StarDictInfo.parse(it.readText()) }
                        info?.let {
                            InstalledDictionary(
                                id = dir.name,
                                name = it.name,
                                words = it.wordCount,
                                sizeBytes = dir.listFiles().orEmpty().sumOf { f -> f.length() },
                                catalogId = File(dir, CATALOG_FILE).takeIf { f -> f.isFile }?.readText()?.trim(),
                            )
                        }
                    }
        }

        private fun open(uri: Uri): InputStream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open")

        private fun displayName(uri: Uri): String =
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()

        private companion object {
            const val CATALOG_FILE = "catalog.txt"
            val PARTS = setOf("ifo", "idx", "syn", "dict", "dict.dz")
        }
    }
