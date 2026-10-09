package com.c0mpile.grimmreader.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.BookFileDao
import com.c0mpile.grimmreader.core.database.dao.BookmarkDao
import com.c0mpile.grimmreader.core.database.dao.DownloadDao
import com.c0mpile.grimmreader.core.database.dao.LibraryDao
import com.c0mpile.grimmreader.core.database.dao.LocalLibraryDao
import com.c0mpile.grimmreader.core.database.dao.OutboxDao
import com.c0mpile.grimmreader.core.database.dao.ReadingPositionDao
import com.c0mpile.grimmreader.core.database.dao.ServerDao
import com.c0mpile.grimmreader.core.database.dao.ShelfDao
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.database.entity.BookFileEntity
import com.c0mpile.grimmreader.core.database.entity.BookShelfEntity
import com.c0mpile.grimmreader.core.database.entity.BookmarkEntity
import com.c0mpile.grimmreader.core.database.entity.DownloadEntity
import com.c0mpile.grimmreader.core.database.entity.LibraryEntity
import com.c0mpile.grimmreader.core.database.entity.LocalLibraryEntity
import com.c0mpile.grimmreader.core.database.entity.OutboxOpEntity
import com.c0mpile.grimmreader.core.database.entity.ReadingPositionEntity
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.database.entity.ShelfEntity

/**
 * Test builds are installed on real devices, so schema changes ship with a migration (v2: libraries; v3: CB7 and
 * CBR books removed, see [MIGRATION_2_3]; v4: bookmarks; v5: shelves;
 * v6: book description, publisher and date; v7: on-device libraries).
 */
@Database(
    entities = [
        ServerEntity::class,
        BookEntity::class,
        BookFileEntity::class,
        ReadingPositionEntity::class,
        OutboxOpEntity::class,
        DownloadEntity::class,
        LibraryEntity::class,
        BookmarkEntity::class,
        ShelfEntity::class,
        BookShelfEntity::class,
        LocalLibraryEntity::class,
    ],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
    ],
)
abstract class GrimmDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    abstract fun bookDao(): BookDao

    abstract fun bookFileDao(): BookFileDao

    abstract fun readingPositionDao(): ReadingPositionDao

    abstract fun outboxDao(): OutboxDao

    abstract fun downloadDao(): DownloadDao

    abstract fun libraryDao(): LibraryDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun shelfDao(): ShelfDao

    abstract fun localLibraryDao(): LocalLibraryDao
}
