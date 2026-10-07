package com.c0mpile.grimmreader.core.database.di

import android.content.Context
import androidx.room.Room
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): GrimmDatabase = Room.databaseBuilder(context, GrimmDatabase::class.java, "grimm.db").build()

    @Provides fun serverDao(db: GrimmDatabase) = db.serverDao()

    @Provides fun bookDao(db: GrimmDatabase) = db.bookDao()

    @Provides fun bookFileDao(db: GrimmDatabase) = db.bookFileDao()

    @Provides fun readingPositionDao(db: GrimmDatabase) = db.readingPositionDao()

    @Provides fun outboxDao(db: GrimmDatabase) = db.outboxDao()

    @Provides fun downloadDao(db: GrimmDatabase) = db.downloadDao()

    @Provides fun libraryDao(db: GrimmDatabase) = db.libraryDao()
}
