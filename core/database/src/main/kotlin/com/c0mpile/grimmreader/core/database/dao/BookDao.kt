package com.c0mpile.grimmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.c0mpile.grimmreader.core.database.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM book ORDER BY title")
    fun observeAll(): Flow<List<BookEntity>>
}
