package com.c0mpile.grimmreader.core.data.server

import com.c0mpile.grimmreader.api.grimmory.LoginRequestDto
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.database.dao.BookDao
import com.c0mpile.grimmreader.core.database.dao.ServerDao
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.AppPreferences
import com.c0mpile.grimmreader.core.datastore.SetupState
import com.c0mpile.grimmreader.core.files.LocalFileStore
import kotlinx.coroutines.CoroutineDispatcher
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
        private val bookDao: BookDao,
        private val session: ServerSession,
        private val policy: NetworkPolicyImpl,
        private val prefs: AppPreferences,
        private val files: LocalFileStore,
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
         * Removes the server. With [keepDownloads] the downloaded books stay as local books (they keep their
         * server id for a later re-link); otherwise every book of the server is deleted from the library.
         */
        suspend fun remove(keepDownloads: Boolean) =
            withContext(io) {
                val server = serverDao.current() ?: return@withContext
                signOut()
                if (keepDownloads) {
                    bookDao.deleteServerBooksNotIn(server.id, keep = emptyList())
                    bookDao.detachFromServer(server.id)
                } else {
                    bookDao.deleteAllForServer(server.id)
                    files.deleteServerFiles(server.id)
                }
                serverDao.delete(server.id)
                prefs.setSetupState(SetupState.LOCAL_ONLY)
            }

        private companion object {
            const val HTTP_UNAUTHORIZED = 401
            const val HTTP_BAD_REQUEST = 400
        }
    }
