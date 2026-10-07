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
import java.io.RandomAccessFile
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
            // Pages of downloaded comics may be in here; cheap to rebuild for local ones.
            extractDir.deleteRecursively()
        }

        /** Evictable pages of CB7 and CBR comics, extracted once (see ExtractedComicArchive). */
        val extractDir: File get() = File(context.cacheDir, "extracted")

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

        fun saveCover(
            bookId: Long,
            bytes: ByteArray,
        ): File =
            coverFile(bookId).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }

        private fun displayName(
            resolver: ContentResolver,
            uri: Uri,
        ): String? =
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }

        companion object {
            fun partialMd5(file: File): String =
                RandomAccessFile(file, "r").use { raf ->
                    PartialMd5.compute(raf.length()) { offset, buffer ->
                        raf.seek(offset)
                        raf.read(buffer)
                    }
                }
        }
    }
