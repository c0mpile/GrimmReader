package com.c0mpile.grimmreader.core.network.di

import com.c0mpile.grimmreader.core.network.GuardedHttpClient
import com.c0mpile.grimmreader.core.network.NetworkPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /**
     * The single guarded client. Built lazily by Dagger only when a server or catalog is used, so local
     * mode never constructs it. `@Named("userAgent")` is provided by the app module.
     */
    @Provides
    @Singleton
    fun guardedClient(
        policy: NetworkPolicy,
        @Named("userAgent") userAgent: String,
    ): OkHttpClient = GuardedHttpClient.create(policy, userAgent)
}
