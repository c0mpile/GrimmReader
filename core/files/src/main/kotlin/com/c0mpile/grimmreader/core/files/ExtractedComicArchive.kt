package com.c0mpile.grimmreader.core.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/**
 * A comic archive without random access (CB7, CBR), extracted once into [cacheRoot] and read from there.
 * Pages are stored under numbered names, never under entry names, so hostile paths cannot escape the cache.
 * Bounds: [MAX_PAGES] pages, [MAX_PAGE_BYTES] per page (larger pages are dropped), and a total of four times
 * the archive size (page images do not compress, so more means a decompression bomb). The cache is trimmed
 * to [MAX_CACHE] bytes, oldest first.
 */
class ExtractedComicArchive private constructor(
    private val pages: List<File>,
    private val names: List<String>,
) : ComicArchive {
    override val pageCount: Int get() = pages.size

    override fun open(index: Int): InputStream = FileInputStream(pages[index])

    override fun pageName(index: Int): String = names[index]

    override fun close() = Unit

    companion object {
        const val MAX_PAGES = 10_000
        const val MAX_PAGE_BYTES = 64L shl 20
        const val MAX_CACHE = 1L shl 30
        private const val INDEX = "index"
        private const val BUFFER = 64 * 1024
        private const val BOMB_RATIO = 4
        private val lock = Mutex()

        /**
         * Opens [file], extracting it first unless an earlier extraction is complete. [onProgress] gets 0..1
         * while extracting. Cancellable between buffers.
         */
        suspend fun open(
            file: File,
            cacheRoot: File,
            openArchive: (File) -> SequentialArchive,
            onProgress: (Float) -> Unit = {},
        ): ExtractedComicArchive =
            lock.withLock {
                val dir = File(cacheRoot, key(file))
                val archive = load(dir) ?: extract(file, dir, openArchive, onProgress)
                dir.setLastModified(System.currentTimeMillis())
                trim(cacheRoot, keep = dir)
                archive
            }

        private fun load(dir: File): ExtractedComicArchive? {
            val index = File(dir, INDEX).takeIf { it.isFile } ?: return null
            val entries =
                index.readLines().mapNotNull { line ->
                    val file = File(dir, line.substringBefore('\t'))
                    val name = URLDecoder.decode(line.substringAfter('\t'), "UTF-8")
                    if (file.isFile) file to name else null
                }
            return ExtractedComicArchive(entries.map { it.first }, entries.map { it.second })
        }

        private suspend fun extract(
            file: File,
            dir: File,
            openArchive: (File) -> SequentialArchive,
            onProgress: (Float) -> Unit,
        ): ExtractedComicArchive {
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw IOException("Cannot create the page cache")
            var done = false
            try {
                val extraction = Extraction(dir, budget = file.length() * BOMB_RATIO + MAX_PAGE_BYTES, currentCoroutineContext())
                openArchive(file).use { archive ->
                    extraction.run(archive) { onProgress((it.toFloat() / file.length().coerceAtLeast(1)).coerceIn(0f, 1f)) }
                }
                val sorted = extraction.pages.sortedWith { a, b -> NaturalOrder.compare(a.second, b.second) }
                writeIndex(dir, sorted)
                done = true
                return ExtractedComicArchive(sorted.map { File(dir, it.first) }, sorted.map { it.second })
            } finally {
                if (!done) dir.deleteRecursively()
            }
        }

        /** Copies entries to numbered files; pages over [MAX_PAGE_BYTES] are dropped, the [budget] is a hard stop. */
        private class Extraction(
            private val dir: File,
            private val budget: Long,
            private val context: CoroutineContext,
        ) {
            /** (stored file name, entry name) in archive order. */
            val pages = mutableListOf<Pair<String, String>>()
            private var total = 0L
            private val buffer = ByteArray(BUFFER)

            fun run(
                archive: SequentialArchive,
                onBytes: (Long) -> Unit,
            ) {
                while (pages.size < MAX_PAGES) {
                    val name = archive.nextEntry() ?: break
                    if (!ZipComicArchive.isImage(name) || name.substringAfterLast('/').startsWith(".")) continue
                    copy(archive, name)
                    onBytes(total)
                }
            }

            private fun copy(
                archive: SequentialArchive,
                name: String,
            ) {
                val target = File(dir, "${pages.size}.page")
                var size = 0L
                val complete =
                    target.outputStream().use { out ->
                        var n = archive.read(buffer)
                        while (n >= 0 && size + n <= MAX_PAGE_BYTES) {
                            context.ensureActive()
                            size += n
                            total += n
                            if (total > budget) throw IOException("Archive expands too much")
                            out.write(buffer, 0, n)
                            n = archive.read(buffer)
                        }
                        n < 0
                    }
                if (complete) pages += target.name to name else target.delete()
            }
        }

        private fun writeIndex(
            dir: File,
            pages: List<Pair<String, String>>,
        ) {
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(pages.joinToString("") { (page, name) -> "$page\t${URLEncoder.encode(name, "UTF-8")}\n" })
            if (!tmp.renameTo(File(dir, INDEX))) throw IOException("Cannot store the page index")
        }

        /** Same file, same size and date → same cache entry; a replaced file is extracted again. */
        private fun key(file: File): String {
            val id = "${file.canonicalPath}\u0000${file.length()}\u0000${file.lastModified()}"
            return MessageDigest
                .getInstance("SHA-256")
                .digest(id.toByteArray())
                .take(KEY_BYTES)
                .joinToString("") { "%02x".format(it) }
        }

        private const val KEY_BYTES = 16

        private fun trim(
            root: File,
            keep: File,
        ) {
            val dirs = root.listFiles().orEmpty().filter { it.isDirectory }
            val sizes = dirs.associateWith { d -> d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
            var total = sizes.values.sum()
            for (dir in dirs.sortedBy { it.lastModified() }) {
                if (total <= MAX_CACHE) break
                if (dir == keep) continue
                dir.deleteRecursively()
                total -= sizes.getValue(dir)
            }
        }
    }
}
