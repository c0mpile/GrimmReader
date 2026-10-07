package com.c0mpile.grimmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.BookShelfEntity
import com.c0mpile.grimmreader.core.database.entity.BookWithFiles
import com.c0mpile.grimmreader.core.database.entity.BookmarkEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.LibraryEntity
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
import com.c0mpile.grimmreader.core.database.entity.ReadingPositionEntity
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.database.entity.ShelfEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM server ORDER BY id LIMIT 1")
    fun observeCurrent(): Flow<ServerEntity?>

    @Query("SELECT * FROM server ORDER BY id LIMIT 1")
    suspend fun current(): ServerEntity?

    @Upsert
    suspend fun upsert(server: ServerEntity): Long

    @Query("UPDATE server SET permissions = :permissions, permissionsUpdatedAt = :at WHERE id = :id")
    suspend fun updatePermissions(
        id: Long,
        permissions: String,
        at: Long,
    )

    @Query("DELETE FROM server WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BookDao {
    /** Library grid: everything, or one server library; local books are included when [libraryId] is null. */
    @Transaction
    @Query(
        """SELECT * FROM book WHERE (:libraryId IS NULL OR serverLibraryId = :libraryId)
           ORDER BY sortTitle""",
    )
    fun observeLibrary(libraryId: Long?): Flow<List<BookWithFiles>>

    @Transaction
    @Query("SELECT * FROM book WHERE id = :id")
    fun observe(id: Long): Flow<BookWithFiles?>

    @Transaction
    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun get(id: Long): BookWithFiles?

    @Query("SELECT * FROM book WHERE serverRowId = :serverRowId AND serverBookId = :serverBookId")
    suspend fun byServerId(
        serverRowId: Long,
        serverBookId: Long,
    ): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Upsert
    suspend fun upsert(book: BookEntity): Long

    @Query("UPDATE book SET progressPercent = :percent, lastReadAt = :at WHERE id = :id")
    suspend fun updateProgress(
        id: Long,
        percent: Float,
        at: Long,
    )

    /** Server rows that disappeared from a full refresh; downloaded ones are kept. */
    @Query(
        """DELETE FROM book WHERE serverRowId = :serverRowId AND serverBookId NOT IN (:keep)
           AND id NOT IN (SELECT bookId FROM book_file WHERE localUri IS NOT NULL)""",
    )
    suspend fun deleteServerBooksNotIn(
        serverRowId: Long,
        keep: List<Long>,
    )

    @Query("DELETE FROM book WHERE serverRowId = :serverRowId")
    suspend fun deleteAllForServer(serverRowId: Long)

    /** Detach books from a removed server, keeping downloaded ones as local books. */
    @Query("UPDATE book SET source = 'LOCAL', serverRowId = NULL WHERE serverRowId = :serverRowId")
    suspend fun detachFromServer(serverRowId: Long)

    @Query("DELETE FROM book WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BookFileDao {
    @Upsert
    suspend fun upsert(file: BookFileEntity): Long

    @Query("SELECT * FROM book_file WHERE id = :id")
    suspend fun get(id: Long): BookFileEntity?

    @Query("SELECT * FROM book_file WHERE bookId = :bookId")
    suspend fun forBook(bookId: Long): List<BookFileEntity>

    @Query("UPDATE book_file SET localUri = :localUri, sizeBytes = :size, partialMd5 = :md5 WHERE id = :id")
    suspend fun setLocal(
        id: Long,
        localUri: String?,
        size: Long?,
        md5: String?,
    )
}

@Dao
interface ReadingPositionDao {
    @Query("SELECT * FROM reading_position WHERE bookId = :bookId")
    suspend fun get(bookId: Long): ReadingPositionEntity?

    @Query("SELECT * FROM reading_position WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<ReadingPositionEntity?>

    @Upsert
    suspend fun upsert(position: ReadingPositionEntity)

    @Query("UPDATE reading_position SET dirty = 0, serverSeenAt = :serverSeenAt WHERE bookId = :bookId AND localUpdatedAt = :ifUpdatedAt")
    suspend fun markSynced(
        bookId: Long,
        serverSeenAt: Long,
        ifUpdatedAt: Long,
    )
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(op: OutboxOpEntity): Long

    /** Progress is last-write-wins: replace any pending op for the same entity. */
    @Transaction
    suspend fun replace(op: OutboxOpEntity): Long {
        deleteFor(op.kind, op.entityId)
        return insert(op)
    }

    @Query("DELETE FROM outbox_op WHERE kind = :kind AND entityId = :entityId")
    suspend fun deleteFor(
        kind: String,
        entityId: Long,
    )

    @Query("SELECT * FROM outbox_op WHERE nextAttemptAt <= :now ORDER BY id LIMIT :limit")
    suspend fun due(
        now: Long,
        limit: Int = 50,
    ): List<OutboxOpEntity>

    @Query("SELECT * FROM outbox_op WHERE kind = :kind ORDER BY id")
    suspend fun ofKind(kind: String): List<OutboxOpEntity>

    @Query("SELECT COUNT(*) FROM outbox_op")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox_op WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE outbox_op SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun backoff(
        id: Long,
        nextAttemptAt: Long,
    )
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(download: DownloadEntity): Long

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM download WHERE bookFileId = :bookFileId")
    suspend fun forFile(bookFileId: Long): DownloadEntity?

    @Query("SELECT * FROM download ORDER BY id")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("DELETE FROM download WHERE bookFileId = :bookFileId")
    suspend fun delete(bookFileId: Long)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library WHERE serverRowId = :serverRowId ORDER BY position")
    fun observe(serverRowId: Long): Flow<List<LibraryEntity>>

    /** Replaces the server's library list in one transaction. */
    @Transaction
    suspend fun replaceAll(
        serverRowId: Long,
        libraries: List<LibraryEntity>,
    ) {
        deleteAll(serverRowId)
        insertAll(libraries)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(libraries: List<LibraryEntity>)

    @Query("DELETE FROM library WHERE serverRowId = :serverRowId")
    suspend fun deleteAll(serverRowId: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmark WHERE bookId = :bookId AND deleted = 0")
    fun observe(bookId: Long): Flow<List<BookmarkEntity>>

    /** Including tombstones. */
    @Query("SELECT * FROM bookmark WHERE bookId = :bookId")
    suspend fun forBook(bookId: Long): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark WHERE id = :id")
    suspend fun get(id: Long): BookmarkEntity?

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmark SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(
        id: Long,
        serverId: Long,
    )

    @Query("UPDATE bookmark SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: Long)

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ShelfDao {
    @Query("SELECT * FROM shelf WHERE serverRowId = :serverRowId ORDER BY magic, position")
    fun observe(serverRowId: Long): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelf WHERE serverRowId = :serverRowId ORDER BY magic, position")
    suspend fun forServer(serverRowId: Long): List<ShelfEntity>

    @Query("DELETE FROM shelf WHERE serverRowId = :serverRowId")
    suspend fun deleteForServer(serverRowId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shelves: List<ShelfEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shelf: ShelfEntity)

    @Query("SELECT shelfId, magic, COUNT(*) AS books FROM book_shelf GROUP BY shelfId, magic")
    fun observeCounts(): Flow<List<ShelfCount>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM shelf WHERE serverRowId = :serverRowId AND magic = 0")
    suspend fun nextPosition(serverRowId: Long): Int

    /** Replaces every membership of one shelf. */
    @Transaction
    suspend fun replaceMembers(
        shelfId: Long,
        magic: Boolean,
        bookIds: List<Long>,
    ) {
        deleteMembers(shelfId, magic)
        addMembers(bookIds.map { BookShelfEntity(it, shelfId, magic) })
    }

    @Query("DELETE FROM book_shelf WHERE shelfId = :shelfId AND magic = :magic")
    suspend fun deleteMembers(
        shelfId: Long,
        magic: Boolean,
    )

    /** Memberships of shelves that no longer exist. */
    @Query(
        "DELETE FROM book_shelf WHERE NOT EXISTS " +
            "(SELECT 1 FROM shelf s WHERE s.shelfId = book_shelf.shelfId AND s.magic = book_shelf.magic)",
    )
    suspend fun deleteOrphanMembers()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMembers(members: List<BookShelfEntity>)

    @Query("DELETE FROM book_shelf WHERE bookId = :bookId AND shelfId = :shelfId AND magic = 0")
    suspend fun removeMember(
        bookId: Long,
        shelfId: Long,
    )
}

data class ShelfCount(
    val shelfId: Long,
    val magic: Boolean,
    val books: Int,
)
