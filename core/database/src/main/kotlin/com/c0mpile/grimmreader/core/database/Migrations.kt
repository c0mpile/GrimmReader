package com.c0mpile.grimmreader.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * v3: CB7 and CBR were dropped (CBZ is the only comic format), so books stored with those formats go, with
 * their files, positions, pending syncs, downloads and the app's copies of their files and covers.
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val books = mutableListOf<Long>()
            val paths = mutableListOf<String>()
            db.query("SELECT bookId, localUri FROM book_file WHERE format IN ('CB7', 'CBR')").use { c ->
                while (c.moveToNext()) {
                    books += c.getLong(0)
                    if (!c.isNull(1)) paths += c.getString(1)
                }
            }
            if (books.isEmpty()) return
            val ids = books.distinct().joinToString(",")
            db.execSQL("DELETE FROM download WHERE bookFileId IN (SELECT id FROM book_file WHERE bookId IN ($ids))")
            db.execSQL("DELETE FROM book_file WHERE bookId IN ($ids)")
            db.execSQL("DELETE FROM reading_position WHERE bookId IN ($ids)")
            db.execSQL("DELETE FROM outbox_op WHERE entityId IN ($ids)")
            db.execSQL("DELETE FROM book WHERE id IN ($ids)")
            // Only files inside the app's own books directory (never a linked content:// document).
            val own = paths.filter { it.startsWith("/") && "/files/books/" in it }
            own.forEach { File(it).delete() }
            val filesDir = own.firstOrNull()?.substringBefore("/books/") ?: return
            books.distinct().forEach { File("$filesDir/covers/$it.img").delete() }
        }
    }
