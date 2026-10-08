package com.c0mpile.grimmreader.core.data.server

import android.content.Context
import coil3.SingletonImageLoader
import com.c0mpile.grimmreader.api.grimmory.LoginRequestDto
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.data.library.CoverPrefetchWorker
import com.c0mpile.grimmreader.core.data.library.LibraryRepository
import com.c0mpile.grimmreader.core.database.GrimmDatabase
import com.c0mpile.grimmreader.core.database.dao.ServerDao
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.SetupState
import com.c0mpile.grimmreader.core.files.LocalFileStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LoginResult {
    data class Ok(
        val defaultPassword: Boolean,
    ) : LoginResult

    data object WrongCredentials : LoginResult

    data object Unreachable : LoginResult

    data class Failed(
        val code: Int,
    ) : LoginResult
}

/** Adds, signs in, signs out of and removes the Grimmory server (one server in the MVP, decision D5). */
@Singleton
class ServerRepository
    @Inject
    constructor(
        private val serverDao: ServerDao,
        private val db: GrimmDatabase,
        private val session: ServerSession,
        private val policy: NetworkPolicyImpl,
        private val prefs: AppPreferences,
        private val files: LocalFileStore,
        private val library: Lazy<LibraryRepository>,
        @ApplicationContext private val context: Context,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /**
         * Signs in to a server that passed the connection test and saves it. The password is never stored;
         * only the tokens are kept (encrypted).
         */
        suspend fun login(
            ok: ConnectionResult.Ok,
            allowCleartext: Boolean,
            pin: String?,
            username: String,
            password: String,
        ): LoginResult =
            withContext(io) {
                val api = session.anonymousApi(ok.baseUrl.toHttpUrl())
                val token =
                    try {
                        api.login(LoginRequestDto(username, password))
                    } catch (e: HttpException) {
                        return@withContext if (e.code() == HTTP_UNAUTHORIZED || e.code() == HTTP_BAD_REQUEST) {
                            LoginResult.WrongCredentials
                        } else {
                            LoginResult.Failed(e.code())
                        }
                    } catch (_: IOException) {
                        return@withContext LoginResult.Unreachable
                    }
                val existing = serverDao.current()
                val row =
                    ServerEntity(
                        id = existing?.id ?: 0,
                        baseUrl = ok.baseUrl,
                        allowCleartext = allowCleartext,
                        pinnedSpkiSha256 = pin,
                        username = username,
                        serverVersion = ok.version,
                    )
                val id = serverDao.upsert(row).takeIf { it > 0 } ?: row.id
                session.storeTokens(id, ok.baseUrl.toHttpUrl(), token)
                policy.clearPending()
                prefs.setSetupState(SetupState.SERVER)
                refreshPermissions()
                // Best effort: the next refresh lists them again. Each server library gets an on-device library.
                library.get().refreshLibraries()
                LoginResult.Ok(token.isDefaultPassword)
            }

        /** Loads the full permission set from `/api/v1/users/me` and caches it in Room. */
        suspend fun refreshPermissions() =
            withContext(io) {
                val server = serverDao.current() ?: return@withContext
                val api = session.api() ?: return@withContext
                runCatching { api.me() }.onSuccess { me ->
                    serverDao.updatePermissions(server.id, me.permissionFlags().sorted().joinToString(","), System.currentTimeMillis())
                }
            }

        /** Ends the session but keeps the server and the library. */
        suspend fun signOut() =
            withContext(io) {
                val server = serverDao.current() ?: return@withContext
                runCatching { session.api()?.logout() }
                session.clearTokens(server.id)
                files.deleteStreamCache(server.id)
            }

        /**
         * Removes the server. The on-device libraries stay (no longer linked to a server library). With
         * [keepDownloads] the downloaded books stay as local books in them (they keep their server id for a later
         * re-link); otherwise every book of the server is deleted from the library, with its downloaded files
         * (also those saved to a picked download folder).
         */
        suspend fun remove(keepDownloads: Boolean) =
            withContext(io) {
                val server = serverDao.current() ?: return@withContext
                signOut()
                if (keepDownloads) {
                    db.bookDao().deleteServerBooksNotIn(server.id, keep = emptyList())
                    db.bookDao().detachFromServer(server.id)
                } else {
                    val downloadFolder = prefs.downloadFolder.first()
                    db.bookFileDao().serverDocuments(server.id).forEach { file ->
                        file.localUri?.let { files.deleteBookFile(it, downloadFolder) }
                    }
                    db.bookDao().deleteAllForServer(server.id)
                    files.deleteServerFiles(server.id)
                }
                serverDao.delete(server.id)
                library.get().serverRemoved()
                prefs.setSetupState(SetupState.LOCAL_ONLY)
                // The image disk cache only holds server thumbnails (extracted covers live in app storage).
                CoverPrefetchWorker.cancel(context)
                SingletonImageLoader.get(context).run {
                    memoryCache?.clear()
                    diskCache?.clear()
                }
            }

        private companion object {
            const val HTTP_UNAUTHORIZED = 401
            const val HTTP_BAD_REQUEST = 400
        }
    }
