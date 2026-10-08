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
        fun listBooks(tree: String): List<FolderDocument> {
            val uri = Uri.parse(tree)
            val found = mutableListOf<FolderDocument>()
            walk(uri, DocumentsContract.getTreeDocumentId(uri), 0, found)
            return found
        }

        private fun walk(
            tree: Uri,
            parentId: String,
            depth: Int,
            found: MutableList<FolderDocument>,
        ) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val cursor = provider("Folder cannot be listed") { resolver.query(children, COLUMNS, null, null, null) }
            val dirs = mutableListOf<String>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        dirs += id
                    } else if (isBookName(name)) {
                        found +=
                            FolderDocument(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, c.getLong(SIZE_COLUMN))
                    }
                }
            }
            if (depth < MAX_DEPTH) dirs.forEach { walk(tree, it, depth + 1, found) }
        }

        /** Opens a document for reading; only through this descriptor, reopening its path is not allowed. */
        fun open(uri: String): BookHandle = BookHandle(provider("Cannot open $uri") { resolver.openFileDescriptor(Uri.parse(uri), "r") })

        /**
         * Creates [name] in the root of [tree] (the provider adds " (1)" on clashes) and fills it from [input].
         * Octet-stream, so providers keep the name as given instead of fixing up the extension.
         */
        fun create(
            tree: String,
            name: String,
            input: InputStream,
        ): String {
            val treeUri = Uri.parse(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
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

        fun delete(uri: String): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(uri)) }.getOrDefault(false)

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
