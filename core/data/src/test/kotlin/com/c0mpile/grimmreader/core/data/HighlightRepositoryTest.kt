package com.c0mpile.grimmreader.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.core.data.highlight.HighlightRepository
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import com.c0mpile.grimmreader.core.model.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlightRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, GrimmDatabase::class.java).allowMainThreadQueries().build()
    private val repo = HighlightRepository(db.highlightDao(), Dispatchers.IO)

    @After fun close() = db.close()

    private suspend fun book(title: String) = db.bookDao().insert(BookEntity(source = BookSource.LOCAL, title = title, addedAt = 0))

    @Test fun highlightsComeBackInReadingOrderPerBook() =
        runBlocking {
            val a = book("ebook sample 1")
            val b = book("ebook sample 2")
            repo.add(a, "epubcfi(/6/8!/4,/1:0,/1:4)", "late", "#FACC15", "Chapter 2", 60f)
            repo.add(a, "epubcfi(/6/2!/4,/1:0,/1:5)", "early", "#4ADE80", "Chapter 1", 10f)
            repo.add(b, "epubcfi(/6/2!/4,/1:0,/1:5)", "other book", "#FACC15", null, 5f)
            assertEquals(listOf("early", "late"), repo.observe(a).first().map { it.text })
            assertEquals(listOf("other book"), repo.observe(b).first().map { it.text })
        }

    @Test fun removeDeletesOnlyThatHighlight() =
        runBlocking {
            val a = book("ebook sample 1")
            val keep = repo.add(a, "epubcfi(/6/2!/4,/1:0,/1:5)", "keep", "#FACC15", null, 1f)
            val drop = repo.add(a, "epubcfi(/6/4!/4,/1:0,/1:5)", "drop", "#FACC15", null, 2f)
            repo.remove(drop)
            assertEquals(listOf(keep), repo.observe(a).first().map { it.id })
        }

    @Test fun highlightsGoWhenTheBookGoes() =
        runBlocking {
            val a = book("ebook sample 1")
            repo.add(a, "epubcfi(/6/2!/4,/1:0,/1:5)", "gone", "#FACC15", null, 1f)
            db.bookDao().delete(a)
            assertTrue(repo.observe(a).first().isEmpty())
        }
}
