package com.c0mpile.grimmreader.core.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.c0mpile.grimmreader.core.model.BookFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A book file found in a folder. */
data class FolderDocument(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
)

/**
 * Folders the user picked with the system folder picker (SAF tree URIs, permission persisted). Book files in
 * them are read in place through content URIs built on the tree, so the folder's grant covers them.
 */
@Singleton
class DocumentStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val resolver get() = context.contentResolver

        fun persist(tree: Uri) = resolver.takePersistableUriPermission(tree, FLAGS)

        fun release(tree: String) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(tree), FLAGS) }
        }

        /** Every folder the app holds a persisted grant for. */
        fun grantedTrees(): List<String> = resolver.persistedUriPermissions.map { it.uri.toString() }

        /** False once the user revoked the grant or the volume is gone. */
        fun hasAccess(tree: String): Boolean = resolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isReadPermission }

        /** The folder's name for the settings list ("Books"), else the last path segment. */
        fun folderName(tree: String): String {
            val uri = Uri.parse(tree)
            val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            return runCatching {
                resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifEmpty { tree }
        }

        /**
         * Book files under [tree], subfolders included (hidden ones skipped, at most [MAX_DEPTH] deep). Throws
         * [IOException] when the folder can't be listed, so callers never mistake that for an empty folder.
         */
        fun listBooks(tree: String): List<FolderDocument> = listFiles(tree) { isBookName(it) }

        /** Like [listBooks], for the files whose name [accept] takes. */
        fun listFiles(
            tree: String,
            accept: (String) -> Boolean,
        ): List<FolderDocument> {
            val uri = Uri.parse(tree)
            val found = mutableListOf<FolderDocument>()
            walk(uri, DocumentsContract.getTreeDocumentId(uri), 0, accept, found)
            return found
        }

        private fun walk(
            tree: Uri,
            parentId: String,
            depth: Int,
            accept: (String) -> Boolean,
            found: MutableList<FolderDocument>,
        ) {
            val dirs = mutableListOf<String>()
            for (child in children(tree, parentId)) {
                if (child.name.startsWith(".")) continue
                if (child.isDir) {
                    dirs += child.id
                } else if (accept(child.name)) {
                    found += FolderDocument(DocumentsContract.buildDocumentUriUsingTree(tree, child.id).toString(), child.name, child.size)
                }
            }
            if (depth < MAX_DEPTH) dirs.forEach { walk(tree, it, depth + 1, accept, found) }
        }

        private class Child(
            val id: String,
            val name: String,
            val isDir: Boolean,
            val size: Long,
        )

        private fun children(
            tree: Uri,
            parentId: String,
        ): List<Child> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val cursor = provider("Folder cannot be listed") { resolver.query(uri, COLUMNS, null, null, null) }
            return cursor.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        add(Child(c.getString(0), name, c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(SIZE_COLUMN)))
                    }
                }
            }
        }

        /** Opens a document for reading; only through this descriptor, reopening its path is not allowed. */
        fun open(uri: String): BookHandle = BookHandle(provider("Cannot open $uri") { resolver.openFileDescriptor(Uri.parse(uri), "r") })

        /**
         * Creates [name] inside its own folder [folder] in the root of [tree] (one book per folder, so metadata files
         * can sit next to it) and fills it from [input]. A folder of that name is reused while it holds no book file,
         * else "[folder] (2)", "(3)", … is used. Octet-stream, so providers keep the name as given instead of
         * fixing up the extension.
         */
        fun createInFolder(
            tree: String,
            folder: String,
            name: String,
            input: InputStream,
        ): String {
            val treeUri = Uri.parse(tree)
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val taken = children(treeUri, rootId).associateBy { it.name }
            val dirName =
                generateSequence(1) { it + 1 }
                    .map { if (it == 1) folder else "$folder ($it)" }
                    .first { candidate ->
                        val existing = taken[candidate]
                        existing == null || (existing.isDir && children(treeUri, existing.id).none { isBookName(it.name) })
                    }
            val existing = taken[dirName]
            val dir =
                if (existing != null) {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.id)
                } else {
                    val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
                    provider("Cannot create $dirName") { DocumentsContract.createDocument(resolver, root, Document.MIME_TYPE_DIR, dirName) }
                }
            try {
                return write(dir, name, input)
            } catch (e: IOException) {
                if (existing == null) runCatching { DocumentsContract.deleteDocument(resolver, dir) }
                throw e
            }
        }

        private fun write(
            parent: Uri,
            name: String,
            input: InputStream,
        ): String {
            val doc =
                provider("Cannot create $name") { DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name) }
            try {
                provider("Cannot write $name") { resolver.openOutputStream(doc, "w") }.use { input.copyTo(it) }
            } catch (e: IOException) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                throw e
            }
            return doc.toString()
        }

        /**
         * Runs a provider call, turning its ways of failing (null, revoked grant, document gone) into one
         * [IOException], so a missing folder or file never crashes a scan or a download.
         */
        private inline fun <T : Any> provider(
            message: String,
            call: () -> T?,
        ): T {
            val result =
                try {
                    call()
                } catch (e: SecurityException) {
                    unavailable(message, e)
                } catch (e: IllegalArgumentException) {
                    unavailable(message, e)
                }
            return result ?: unavailable(message)
        }

        private fun unavailable(
            message: String,
            cause: Throwable? = null,
        ): Nothing = throw IOException(message, cause)

        /**
         * True only when the provider says the document [uri] no longer exists (deleted in a file manager). A
         * document whose folder grant is gone, or whose provider cannot be asked (SD card out), is not "gone": it
         * may come back.
         */
        fun isGone(uri: String): Boolean {
            val doc = Uri.parse(uri)
            val tree =
                runCatching { DocumentsContract.buildTreeDocumentUri(doc.authority, DocumentsContract.getTreeDocumentId(doc)) }
                    .getOrNull() ?: return false
            if (!hasAccess(tree.toString())) return false
            return try {
                resolver.query(doc, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { !it.moveToFirst() } ?: false
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                // How ExternalStorageProvider reports a missing file (FileNotFoundException inside).
                true
            } catch (_: java.io.FileNotFoundException) {
                true
            }
        }

        fun delete(uri: String): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(uri)) }.getOrDefault(false)

        /**
         * Deletes [uri] and then its folder when that is now empty and not the root of the tree (a book's own folder
         * in the download folder, see [createInFolder]).
         */
        fun deleteWithEmptyFolder(uri: String): Boolean {
            val doc = Uri.parse(uri)
            val parentId =
                runCatching { DocumentsContract.findDocumentPath(resolver, doc)?.path }
                    .getOrNull()
                    ?.takeIf { it.size > 2 }
                    ?.let { it[it.size - 2] }
            val deleted = delete(uri)
            if (deleted && parentId != null) {
                val tree = DocumentsContract.buildTreeDocumentUri(doc.authority, DocumentsContract.getTreeDocumentId(doc))
                runCatching {
                    if (children(tree, parentId).isEmpty()) {
                        DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId))
                    }
                }
            }
            return deleted
        }

        companion object {
            private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            private const val MAX_DEPTH = 8
            private const val SIZE_COLUMN = 3
            private val COLUMNS =
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)

            fun isDocument(localUri: String) = localUri.startsWith("content://")

            /** Whether [uri] is a document reached through [tree]. */
            fun inTree(
                uri: String,
                tree: String,
            ) = uri.startsWith("$tree/document/")

            /** Book extensions only; plain `.zip` files are too often not comics to pick up from a folder. */
            fun isBookName(name: String): Boolean {
                val ext = name.substringAfterLast('.', "").lowercase()
                return ext != "zip" && BookFormat.fromExtension(ext) != null
            }
        }
    }
