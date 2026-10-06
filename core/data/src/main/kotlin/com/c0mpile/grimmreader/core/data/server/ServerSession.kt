package com.c0mpile.grimmreader.core.data.server

import com.c0mpile.grimmreader.api.grimmory.GrimmoryApi
import com.c0mpile.grimmreader.api.grimmory.RefreshRequestDto
import com.c0mpile.grimmreader.api.grimmory.TokenDto
import com.c0mpile.grimmreader.core.common.AppScope
import com.c0mpile.grimmreader.core.database.dao.ServerDao
import com.c0mpile.grimmreader.core.database.entity.ServerEntity
import com.c0mpile.grimmreader.core.datastore.SecretStore
import com.c0mpile.grimmreader.core.model.Permissions
import com.c0mpile.grimmreader.core.model.ServerStatus
import com.c0mpile.grimmreader.core.network.BearerAuthInterceptor
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in Grimmory server: its row, tokens and an authenticated API. The guarded client is injected
 * lazily, so local mode never builds it. Tokens live only in [SecretStore] (Keystore-encrypted) and in memory.
 */
@Singleton
class ServerSession
    @Inject
    constructor(
        private val serverDao: ServerDao,
        private val secrets: SecretStore,
        private val policy: NetworkPolicyImpl,
        private val guardedClient: Lazy<OkHttpClient>,
        @AppScope scope: CoroutineScope,
    ) {
        val server: StateFlow<ServerEntity?> = serverDao.observeCurrent().stateIn(scope, SharingStarted.Eagerly, null)

        val permissions: StateFlow<Permissions> =
            server
                .map { s ->
                    Permissions(
                        s
                            ?.permissions
                            ?.split(',')
                            ?.filter { it.isNotBlank() }
                            ?.toSet()
                            .orEmpty(),
                    )
                }.stateIn(scope, SharingStarted.Eagerly, Permissions.NONE)

        private val _status = MutableStateFlow(ServerStatus.ONLINE)
        val status: StateFlow<ServerStatus> = _status.asStateFlow()

        @Volatile private var accessToken: String? = null

        @Volatile private var cached: Pair<HttpUrl, GrimmoryApi>? = null

        init {
            server
                .onEach { s ->
                    policy.setConfigured(s?.let { mapOf(hostOf(it.baseUrl) to (it.allowCleartext to it.pinnedSpkiSha256)) }.orEmpty())
                    accessToken = s?.let { secrets.get(key(it.id, ACCESS)) }
                    cached = null
                }.launchIn(scope)
        }

        fun baseUrl(): HttpUrl? = server.value?.baseUrl?.toHttpUrlOrNull()

        /** API for the current server, or null in local mode. */
        fun api(): GrimmoryApi? {
            val base = baseUrl() ?: return null
            cached?.takeIf { it.first == base }?.let { return it.second }
            return GrimmoryApi.create(base, authedClient()).also { cached = base to it }
        }

        /** Guarded client with the bearer token for the current server's origin (also used by Coil and downloads). */
        fun authedClient(): OkHttpClient =
            guardedClient
                .get()
                .newBuilder()
                .addInterceptor(BearerAuthInterceptor({ baseUrl() }, { accessToken }))
                .authenticator(TokenAuthenticator())
                .build()

        /** Unauthenticated API against [base] (login, refresh, setup). */
        fun anonymousApi(base: HttpUrl): GrimmoryApi = GrimmoryApi.create(base, guardedClient.get())

        suspend fun storeTokens(
            serverId: Long,
            token: TokenDto,
        ) {
            secrets.put(key(serverId, ACCESS), token.accessToken)
            secrets.put(key(serverId, REFRESH), token.refreshToken)
            if (serverId == server.value?.id) accessToken = token.accessToken
            _status.value = ServerStatus.ONLINE
        }

        suspend fun setAccessTokenForNewServer(
            serverId: Long,
            token: TokenDto,
        ) {
            storeTokens(serverId, token)
            accessToken = token.accessToken
        }

        suspend fun clearTokens(serverId: Long) {
            secrets.clear("server.$serverId.")
            accessToken = null
        }

        fun reportOffline(offline: Boolean) {
            if (_status.value != ServerStatus.AUTH_EXPIRED) _status.value = if (offline) ServerStatus.OFFLINE else ServerStatus.ONLINE
        }

        /** Refreshes the access token once on 401; concurrent 401s share one refresh. */
        private inner class TokenAuthenticator : Authenticator {
            override fun authenticate(
                route: Route?,
                response: Response,
            ): Request? {
                val base = baseUrl() ?: return null
                val serverId = server.value?.id ?: return null
                val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
                if (response.priorResponse != null || sent == null) return null
                synchronized(this@ServerSession) {
                    val current = accessToken
                    if (current != null && current != sent) return response.request.withBearer(current)
                    val refreshed = runBlocking { refresh(base, serverId) } ?: return null
                    return response.request.withBearer(refreshed)
                }
            }
        }

        private suspend fun refresh(
            base: HttpUrl,
            serverId: Long,
        ): String? {
            val refreshToken = secrets.get(key(serverId, REFRESH)) ?: return expired()
            return try {
                val token = anonymousApi(base).refresh(RefreshRequestDto(refreshToken))
                storeTokens(serverId, token)
                token.accessToken
            } catch (e: HttpException) {
                if (e.code() == HTTP_UNAUTHORIZED || e.code() == HTTP_FORBIDDEN) expired() else null
            } catch (_: java.io.IOException) {
                null
            }
        }

        private fun expired(): String? {
            accessToken = null
            _status.value = ServerStatus.AUTH_EXPIRED
            return null
        }

        private fun Request.withBearer(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

        companion object {
            private const val ACCESS = "access"
            private const val REFRESH = "refresh"
            private const val HTTP_UNAUTHORIZED = 401
            private const val HTTP_FORBIDDEN = 403

            fun key(
                serverId: Long,
                name: String,
            ) = "server.$serverId.$name"

            fun hostOf(baseUrl: String): String = baseUrl.toHttpUrlOrNull()?.host.orEmpty()
        }
    }
