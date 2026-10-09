package com.c0mpile.grimmreader.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GrimmDatabase::class.java)

    private val filesDir = ApplicationProvider.getApplicationContext<Context>().filesDir

    @Test fun cb7AndCbrBooksAreRemovedWithTheirFiles() {
        val comic = File(filesDir, "books/local/old.cb7").apply { parentFile?.mkdirs() }.apply { writeText("7z") }
        val cover = File(filesDir, "covers/2.img").apply { parentFile?.mkdirs() }.apply { writeText("img") }
        val keptCover = File(filesDir, "covers/1.img").apply { writeText("img") }
        helper.createDatabase(DB, 2).use { db ->
            for ((id, format, uri) in listOf(
                Triple(1, "CBZ", "/x/files/books/local/a.cbz"),
                Triple(2, "CB7", comic.path),
                Triple(3, "CBR", null),
            )) {
                db.execSQL(
                    "INSERT INTO book (id, source, title, sortTitle, authors, readStatus, addedAt) " +
                        "VALUES ($id, 'LOCAL', 'b$id', 'b$id', '', 'UNREAD', 0)",
                )
                db.execSQL(
                    "INSERT INTO book_file (bookId, format, isPrimary, localUri) VALUES ($id, '$format', 1, ${uri?.let { "'$it'" }})",
                )
                db.execSQL("INSERT INTO reading_position (bookId, locator, percent, localUpdatedAt, dirty) VALUES ($id, '{}', 1, 0, 1)")
                db.execSQL(
                    "INSERT INTO outbox_op (kind, entityId, payload, attempts, nextAttemptAt, createdAt) " +
                        "VALUES ('progress', $id, '{}', 0, 0, 0)",
                )
            }
        }
        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3).use { db ->
            for (table in listOf("book", "book_file", "reading_position", "outbox_op")) {
                db.query("SELECT COUNT(*) FROM $table").use { c ->
                    c.moveToFirst()
                    assertEquals(table, 1, c.getInt(0))
                }
            }
            db.query("SELECT format FROM book_file").use { c ->
                c.moveToFirst()
                assertEquals("CBZ", c.getString(0))
            }
        }
        // v4 adds the bookmark table (auto-migration).
        helper.runMigrationsAndValidate(DB, 4, true).close()
        assertFalse(comic.exists())
        assertFalse(cover.exists())
        assertTrue(keptCover.exists())
    }

    @Test fun onDeviceLibrariesAreAddedWithoutTouchingBooks() {
        helper.createDatabase(DB_V6, 6).use { db ->
            db.execSQL(
                "INSERT INTO book (id, source, title, sortTitle, authors, readStatus, addedAt) " +
                    "VALUES (1, 'LOCAL', 'b1', 'b1', '', 'UNREAD', 0)",
            )
        }
        helper.runMigrationsAndValidate(DB_V6, 7, true).use { db ->
            db.query("SELECT localLibraryId FROM book WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
            db.query("SELECT COUNT(*) FROM local_library").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test fun librariesStartAsBooksLibraries() {
        helper.createDatabase(DB_V7, 7).use { db ->
            db.execSQL("INSERT INTO local_library (id, name, position) VALUES (1, 'Library', 0)")
        }
        helper.runMigrationsAndValidate(DB_V7, 8, true).use { db ->
            db.query("SELECT isComics FROM local_library WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    private companion object {
        const val DB_V7 = "migration-test-v7.db"
        const val DB_V6 = "migration-test-v6.db"
        const val DB = "migration-test.db"
    }
}
