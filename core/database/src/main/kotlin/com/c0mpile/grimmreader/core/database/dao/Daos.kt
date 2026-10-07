package com.c0mpile.grimmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.BookWithFiles
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.LibraryEntity
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
import com.c0mpile.grimmreader.core.database.entity.ReadingPositionEntity
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
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
