package com.c0mpile.grimmreader.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.entity.BookEntity

@Database(entities = [BookEntity::class], version = 1)
abstract class GrimmDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}
