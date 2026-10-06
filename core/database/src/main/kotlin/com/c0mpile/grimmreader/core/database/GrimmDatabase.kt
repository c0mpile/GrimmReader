package com.c0mpile.grimmreader.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookFileDao
import com.c0mpile.grimmreader.core.database.dao.DownloadDao
import com.c0mpile.grimmreader.core.database.dao.OutboxDao
import com.c0mpile.grimmreader.core.database.dao.ReadingPositionDao
import com.c0mpile.grimmreader.core.database.dao.ServerDao
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
import com.c0mpile.grimmreader.core.database.entity.ReadingPositionEntity
import com.c0mpile.grimmreader.core.database.entity.ServerEntity

/** Schema v1 may still change freely until the first release; after that every change needs a migration. */
@Database(
    entities = [
        ServerEntity::class,
        BookEntity::class,
        BookFileEntity::class,
        ReadingPositionEntity::class,
        OutboxOpEntity::class,
        DownloadEntity::class,
    ],
    version = 1,
)
abstract class GrimmDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    abstract fun bookDao(): BookDao

    abstract fun bookFileDao(): BookFileDao

    abstract fun readingPositionDao(): ReadingPositionDao

    abstract fun outboxDao(): OutboxDao

    abstract fun downloadDao(): DownloadDao
}
