package com.c0mpile.grimmreader

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Named("userAgent")
    fun userAgent(): String = "GrimmReader/${BuildConfig.VERSION_NAME} (Android)"
}
