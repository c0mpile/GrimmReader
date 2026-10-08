package com.c0mpile.grimmreader.core.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.c0mpile.grimmreader.core.common.PartialMd5
import com.c0mpile.grimmreader.core.model.BookFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A file copied into app storage, ready to become a local book. */
data class ImportedFile(
    val file: File,
    val format: BookFormat,
    val sizeBytes: Long,
    val partialMd5: String,
    val displayName: String,
)

class UnsupportedFormatException : IOException("Unsupported file format")

/**
 * App-private book storage: `files/books/local/…` for imports, `files/books/server/<server>/<book>/…` for
 * downloads, `files/covers/<bookId>.img`. Single opened files are copied in, so a later revoked URI
 * permission cannot break the library.
 */
@Singleton
class LocalFileStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val documents: DocumentStore,
    ) {
        val booksDir: File get() = File(context.filesDir, "books")

        fun coverFile(bookId: Long) = File(context.filesDir, "covers/$bookId.img")

        fun downloadTarget(
            serverRowId: Long,
            serverBookId: Long,
            fileId: Long,
        ): File = File(booksDir, "server/$serverRowId/$serverBookId/$fileId.book").apply { parentFile?.mkdirs() }

        fun deleteServerFiles(serverRowId: Long) {
            File(booksDir, "server/$serverRowId").deleteRecursively()
            deleteStreamCache(serverRowId)
        }

        /** Evictable copies of books and comic pages read online (never kept as downloads). */
        val streamDir: File get() = File(context.cacheDir, "stream")

        fun deleteStreamCache(serverRowId: Long) {
            File(streamDir, serverRowId.toString()).deleteRecursively()
        }

        fun importFrom(uri: Uri): ImportedFile {
            val resolver = context.contentResolver
            val name = displayName(resolver, uri) ?: "book"
            val dir = File(booksDir, "local").apply { mkdirs() }
            val tmp = File(dir, "${UUID.randomUUID()}.part")
            try {
                (resolver.openInputStream(uri) ?: throw IOException("Cannot open file")).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val format = FormatSniffer.sniff(tmp, name) ?: throw UnsupportedFormatException()
                val target = File(dir, tmp.nameWithoutExtension + "." + format.extensions.first())
                check(tmp.renameTo(target)) { "rename failed" }
                return ImportedFile(target, format, target.length(), partialMd5(target), name)
            } finally {
                tmp.delete()
            }
        }

        /** Deletes a book file: a path in app storage or a document in a picked folder. */
        fun deleteBookFile(localUri: String) {
            if (DocumentStore.isDocument(localUri)) documents.delete(localUri) else File(localUri).delete()
        }

        /** Ids of the books that have an extracted cover, from one directory listing. */
        fun coverIds(): Set<Long> =
            File(context.filesDir, "covers")
                .list()
                .orEmpty()
                .mapNotNullTo(HashSet()) { it.removeSuffix(".img").toLongOrNull() }

        fun saveCover(
            bookId: Long,
            bytes: ByteArray,
        ): File =
            coverFile(bookId).apply {
                parentFile?.mkdirs()
                writeBytes(CoverThumbnail.shrink(bytes))
            }

        /**
         * Shrinks covers saved at full size by earlier builds (see [CoverThumbnail]). Cheap once done: small files
         * are skipped by their size alone. Returns how many were shrunk.
         */
        fun shrinkLargeCovers(): Int {
            val large =
                File(context.filesDir, "covers").listFiles().orEmpty().filter { it.length() > CoverThumbnail.SMALL_BYTES }
            var shrunk = 0
            for (file in large) {
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                val small = CoverThumbnail.shrink(bytes)
                if (small === bytes) continue
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeBytes(small)
                if (tmp.renameTo(file)) shrunk++ else tmp.delete()
            }
            return shrunk
        }

        private fun displayName(
            resolver: ContentResolver,
            uri: Uri,
        ): String? =
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }

        companion object {
            fun partialMd5(file: File): String = BookHandle.open(file).use(::partialMd5)

            fun partialMd5(book: BookHandle): String =
                PartialMd5.compute(book.size) { offset, buffer -> book.read(offset, ByteBuffer.wrap(buffer)) }
        }
    }
