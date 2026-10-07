package com.c0mpile.grimmreader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.c0mpile.grimmreader.core.model.BookFormat
import com.c0mpile.grimmreader.core.model.BookSource
import com.c0mpile.grimmreader.core.model.ReadStatus
import com.c0mpile.grimmreader.core.model.ReadingDirection

@Entity(tableName = "server")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baseUrl: String,
    val allowCleartext: Boolean = false,
    val pinnedSpkiSha256: String? = null,
    val username: String? = null,
    val serverVersion: String? = null,
    /** Permission flags from `/api/v1/users/me`, comma-separated. */
    val permissions: String = "",
    val permissionsUpdatedAt: Long = 0,
)

/**
 * A Grimmory library as the signed-in user sees it. [formats] are the server's allowed formats
 * (comma-separated, e.g. `EPUB,PDF` or `CBX`), used to tell book libraries from comic libraries.
 */
@Entity(
    tableName = "library",
    primaryKeys = ["serverRowId", "serverLibraryId"],
    foreignKeys = [ForeignKey(ServerEntity::class, ["id"], ["serverRowId"], onDelete = ForeignKey.CASCADE)],
)
data class LibraryEntity(
    val serverRowId: Long,
    val serverLibraryId: Long,
    val name: String,
    val formats: String = "",
    val position: Int = 0,
)

/**
 * A book in the unified library. Server books carry [serverRowId] + [serverBookId]; when a server is removed
 * but downloads are kept they become [BookSource.LOCAL] and keep [serverBookId] for a later re-link.
 */
@Entity(
    tableName = "book",
    foreignKeys = [ForeignKey(ServerEntity::class, ["id"], ["serverRowId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["serverRowId", "serverBookId"], unique = true), Index("serverLibraryId"), Index("sortTitle")],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: BookSource,
    val serverRowId: Long? = null,
    val serverBookId: Long? = null,
    val serverLibraryId: Long? = null,
    val title: String,
    val sortTitle: String = title.lowercase(),
    /** Author names joined with [AUTHOR_SEPARATOR]. */
    val authors: String = "",
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val coverUri: String? = null,
    val coverUpdatedOn: String? = null,
    val readStatus: ReadStatus = ReadStatus.UNREAD,
    val progressPercent: Float? = null,
    val readingDirection: ReadingDirection? = null,
    val addedAt: Long = 0,
    val lastReadAt: Long? = null,
) {
    companion object {
        const val AUTHOR_SEPARATOR = "\u001F"
    }
}

@Entity(
    tableName = "book_file",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index("partialMd5")],
)
data class BookFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val format: BookFormat,
    val isPrimary: Boolean = true,
    val serverFileId: Long? = null,
    /** File path inside the app's storage or a persisted content:// URI. Null until downloaded or imported. */
    val localUri: String? = null,
    val sizeBytes: Long? = null,
    val partialMd5: String? = null,
)

/** Last reading position per book. [dirty] = changed locally and not yet pushed to the server. */
@Entity(
    tableName = "reading_position",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class ReadingPositionEntity(
    @PrimaryKey val bookId: Long,
    /** Serialised `Locator`. */
    val locator: String,
    val percent: Float,
    val localUpdatedAt: Long,
    /** Server `lastReadTime` (epoch ms) the local state was last reconciled with. */
    val serverSeenAt: Long? = null,
    val dirty: Boolean = false,
)

/** Pending server write. Ops for one entity are applied in id order; progress ops are coalesced. */
@Entity(tableName = "outbox_op", indices = [Index("kind", "entityId")])
data class OutboxOpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val entityId: Long,
    val payload: String,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val createdAt: Long,
)

enum class DownloadState { QUEUED, RUNNING, PAUSED, DONE, FAILED }

@Entity(
    tableName = "download",
    foreignKeys = [ForeignKey(BookFileEntity::class, ["id"], ["bookFileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookFileId"], unique = true)],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookFileId: Long,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val error: String? = null,
    val updatedAt: Long,
)

data class BookWithFiles(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "id", entityColumn = "bookId") val files: List<BookFileEntity>,
    @Relation(parentColumn = "id", entityColumn = "bookId") val position: ReadingPositionEntity?,
    @Relation(parentColumn = "id", entityColumn = "bookId") val shelves: List<BookShelfEntity> = emptyList(),
)

/**
 * A bookmark at [cfi] (ebooks) or [page] (comics and PDF, 1-based). [serverId] is null until the server knows
 * it; [deleted] keeps a synced bookmark as a tombstone until its deletion reaches the server.
 */
@Entity(
    tableName = "bookmark",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index("serverId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val serverId: Long? = null,
    val cfi: String? = null,
    val page: Int? = null,
    val title: String,
    /** Position (0..100) when made on this device, for ordering and display; unknown for others. */
    val percent: Float? = null,
    val createdAt: Long,
    val deleted: Boolean = false,
)

/** A server shelf ([magic] = rule-based, read-only) in server order. */
@Entity(
    tableName = "shelf",
    primaryKeys = ["serverRowId", "shelfId", "magic"],
    foreignKeys = [ForeignKey(ServerEntity::class, ["id"], ["serverRowId"], onDelete = ForeignKey.CASCADE)],
)
data class ShelfEntity(
    val serverRowId: Long,
    val shelfId: Long,
    val magic: Boolean,
    val name: String,
    val icon: String? = null,
    val position: Int = 0,
)

/** A book on a shelf (server shelf id); mirrored on refresh, changed locally first when the user (un)shelves. */
@Entity(
    tableName = "book_shelf",
    primaryKeys = ["bookId", "shelfId", "magic"],
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("shelfId")],
)
data class BookShelfEntity(
    val bookId: Long,
    val shelfId: Long,
    val magic: Boolean = false,
)
