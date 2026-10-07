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
import com.c0mpile.grimmreader.core.network.BearerCredentials
import com.c0mpile.grimmreader.core.network.isSameOrigin
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in Grimmory server: its row, tokens and an authenticated API. The guarded client is injected
 * lazily, so local mode never builds it. Tokens live only in [SecretStore] (Keystore-encrypted) and in memory,
 * always bound to the origin that issued them: the interceptor reads origin and token from one snapshot, so a
 * token can never be sent to another server while the configured server changes.
 */
@Singleton
class ServerSession
    @Inject
    constructor(
        serverDao: ServerDao,
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

        private data class Session(
            val serverId: Long,
            val credentials: BearerCredentials,
        )

        @Volatile private var current: Session? = null

        @Volatile private var cached: Pair<HttpUrl, GrimmoryApi>? = null

        init {
            server.onEach(::onServerChanged).launchIn(scope)
        }

        private suspend fun onServerChanged(s: ServerEntity?) {
            val origin = s?.baseUrl?.toHttpUrlOrNull()
            val session = current
            // Credentials for anything but exactly this server and origin are dropped first.
            if (session != null && (session.serverId != s?.id || session.credentials.origin != origin)) current = null
            policy.setConfigured(s?.let { mapOf(hostOf(it.baseUrl) to (it.allowCleartext to it.pinnedSpkiSha256)) }.orEmpty())
            cached = null
            if (s == null || origin == null || current != null) return
            // A token is only reused for the origin that issued it (never after the row's URL changed).
            val record = readTokens(s.id)?.takeIf { it.origin == origin.toString() } ?: return
            if (server.value?.id == s.id &&
                server.value?.baseUrl == s.baseUrl
            ) {
                current = Session(s.id, BearerCredentials(origin, record.access))
            }
        }

        fun baseUrl(): HttpUrl? = server.value?.baseUrl?.toHttpUrlOrNull()

        /** API for the current server, or null in local mode. */
        fun api(): GrimmoryApi? {
            val base = baseUrl() ?: return null
            cached?.takeIf { it.first == base }?.let { return it.second }
            return GrimmoryApi.create(base, authedClient()).also { cached = base to it }
        }

        /** Guarded client with the origin-bound bearer token (also used by Coil and downloads). */
        fun authedClient(): OkHttpClient =
            guardedClient
                .get()
                .newBuilder()
                .addInterceptor(BearerAuthInterceptor { current?.credentials })
                .authenticator(TokenAuthenticator())
                .build()

        /** Unauthenticated API against [base] (login, refresh, setup). */
        fun anonymousApi(base: HttpUrl): GrimmoryApi = GrimmoryApi.create(base, guardedClient.get())

        /**
         * Saves tokens issued by the server at [origin] as one record (origin, access, refresh), written in a
         * single store operation: a refresh token can only ever be read together with the origin it belongs to.
         */
        suspend fun storeTokens(
            serverId: Long,
            origin: HttpUrl,
            token: TokenDto,
        ) {
            current = null
            val record = TokenRecord(origin.toString(), token.accessToken, token.refreshToken)
            secrets.put(tokensKey(serverId), json.encodeToString(TokenRecord.serializer(), record))
            current = Session(serverId, BearerCredentials(origin, token.accessToken))
            _status.value = ServerStatus.ONLINE
        }

        suspend fun clearTokens(serverId: Long) {
            secrets.clear("server.$serverId.")
            if (current?.serverId == serverId) current = null
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
                val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
                if (response.priorResponse != null || sent == null) return null
                synchronized(this@ServerSession) {
                    val session = current ?: return null
                    if (!response.request.url.isSameOrigin(session.credentials.origin)) return null
                    if (session.credentials.accessToken != sent) return response.request.withBearer(session.credentials.accessToken)
                    val refreshed = runBlocking { refresh(session) } ?: return null
                    return response.request.withBearer(refreshed)
                }
            }
        }

        private suspend fun refresh(session: Session): String? {
            val origin = session.credentials.origin
            val record = readTokens(session.serverId) ?: return expired(session)
            // Tokens were replaced (re-login, other server): never send them to this session's origin.
            if (record.origin != origin.toString()) return null
            return try {
                val token = anonymousApi(origin).refresh(RefreshRequestDto(record.refresh))
                // The server may have changed while refreshing: keep the result only for the same session.
                if (current != session) return null
                storeTokens(session.serverId, origin, token)
                token.accessToken
            } catch (e: HttpException) {
                if (e.code() == HTTP_UNAUTHORIZED || e.code() == HTTP_FORBIDDEN) expired(session) else null
            } catch (_: IOException) {
                null
            }
        }

        private fun expired(session: Session): String? {
            if (current == session) current = null
            _status.value = ServerStatus.AUTH_EXPIRED
            return null
        }

        private fun Request.withBearer(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

        private suspend fun readTokens(serverId: Long): TokenRecord? =
            secrets.get(tokensKey(serverId))?.let { runCatching { json.decodeFromString(TokenRecord.serializer(), it) }.getOrNull() }

        @Serializable
        private data class TokenRecord(
            val origin: String,
            val access: String,
            val refresh: String,
        )

        companion object {
            private val json = Json { ignoreUnknownKeys = true }
            private const val HTTP_UNAUTHORIZED = 401
            private const val HTTP_FORBIDDEN = 403

            fun tokensKey(serverId: Long) = "server.$serverId.tokens"

            fun hostOf(baseUrl: String): String = baseUrl.toHttpUrlOrNull()?.host.orEmpty()
        }
    }
