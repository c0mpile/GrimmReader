package com.c0mpile.grimmreader.core.datastore.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.KeystoreCipher
import com.c0mpile.grimmreader.core.datastore.SecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun appPreferences(
        @ApplicationContext context: Context,
    ): AppPreferences = AppPreferences(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("app") })

    @Provides
    @Singleton
    fun secretStore(
        @ApplicationContext context: Context,
    ): SecretStore = SecretStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("secrets") }, KeystoreCipher())
}
