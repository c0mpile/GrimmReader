package com.c0mpile.grimmreader.core.data.server

import com.c0mpile.grimmreader.api.grimmory.GrimmoryUrls
import com.c0mpile.grimmreader.api.grimmory.HealthcheckClassifier
import com.c0mpile.grimmreader.api.grimmory.HealthcheckResult
import com.c0mpile.grimmreader.api.grimmory.ServerVersion
import com.c0mpile.grimmreader.core.common.IoDispatcher
import com.c0mpile.grimmreader.core.network.CleartextNotPermittedException
import com.c0mpile.grimmreader.core.network.PinMismatchException
import com.c0mpile.grimmreader.core.network.ServerUrlParser
import com.c0mpile.grimmreader.core.network.UntrustedCertificateException
import com.c0mpile.grimmreader.core.network.findCause
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import java.text.DateFormat
import javax.inject.Inject
import javax.net.ssl.SSLException

/** Certificate details shown in the trust-on-first-use dialog. */
data class CertificateInfo(
    val spkiSha256: String,
    val subject: String,
    val issuer: String,
    val validUntil: String,
)

sealed interface ConnectionResult {
    data class Ok(
        val baseUrl: String,
        val host: String,
        val version: String?,
        val untestedVersion: Boolean,
        val oidcEnabled: Boolean,
    ) : ConnectionResult

    data object InvalidUrl : ConnectionResult

    data object CleartextNotAllowed : ConnectionResult

    data object Unreachable : ConnectionResult

    data class UntrustedCertificate(
        val certificate: CertificateInfo,
    ) : ConnectionResult

    data object CertificateChanged : ConnectionResult

    data object TlsError : ConnectionResult

    data object GatewayLoginPage : ConnectionResult

    data object NotGrimmory : ConnectionResult

    data class HttpError(
        val code: Int,
    ) : ConnectionResult

    data class UnsupportedVersion(
        val version: String?,
    ) : ConnectionResult
}

/**
 * Setup step "Test connection": URL → DNS/connect → TLS → `/api/v1/healthcheck` → version → public settings.
 * Uses the guarded client; [pin] is the fingerprint the user just accepted (trust on first use).
 */
class ConnectionTester
    @Inject
    constructor(
        private val client: Lazy<OkHttpClient>,
        private val policy: NetworkPolicyImpl,
        private val session: ServerSession,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        suspend fun test(
            input: String,
            allowCleartext: Boolean,
            pin: String? = null,
        ): ConnectionResult =
            withContext(io) {
                when (val parsed = ServerUrlParser.parse(input, allowCleartext)) {
                    ServerUrlParser.Result.Empty, ServerUrlParser.Result.Invalid -> ConnectionResult.InvalidUrl
                    ServerUrlParser.Result.CleartextNotAllowed -> ConnectionResult.CleartextNotAllowed
                    is ServerUrlParser.Result.Ok -> {
                        policy.setPending(parsed.host, parsed.isCleartext, pin)
                        probe(parsed)
                    }
                }
            }

        private suspend fun probe(parsed: ServerUrlParser.Result.Ok): ConnectionResult {
            val base = parsed.baseUrl.toHttpUrl()
            val health =
                try {
                    client.get().newCall(Request.Builder().url(GrimmoryUrls.healthcheck(base)).build()).execute().use { r ->
                        HealthcheckClassifier.classify(r.code, r.header("Content-Type"), r.peekBody(MAX_BODY_BYTES).string())
                    }
                } catch (e: IOException) {
                    return classify(e)
                }
            return when (health) {
                HealthcheckResult.LoginPage -> ConnectionResult.GatewayLoginPage
                HealthcheckResult.NotGrimmory -> ConnectionResult.NotGrimmory
                is HealthcheckResult.HttpError -> ConnectionResult.HttpError(health.code)
                is HealthcheckResult.Grimmory -> versionCheck(parsed, base, health.version)
            }
        }

        private suspend fun versionCheck(
            parsed: ServerUrlParser.Result.Ok,
            base: okhttp3.HttpUrl,
            version: String?,
        ): ConnectionResult {
            val support = ServerVersion.support(version)
            if (support == ServerVersion.Support.TooOld) return ConnectionResult.UnsupportedVersion(version)
            val oidc = runCatching { session.anonymousApi(base).publicSettings().oidcEnabled }.getOrDefault(false)
            return ConnectionResult.Ok(parsed.baseUrl, parsed.host, version, support != ServerVersion.Support.Supported, oidc)
        }

        private fun classify(e: IOException): ConnectionResult {
            e.findCause<CleartextNotPermittedException>()?.let { return ConnectionResult.CleartextNotAllowed }
            e.findCause<PinMismatchException>()?.let { return ConnectionResult.CertificateChanged }
            e.findCause<UntrustedCertificateException>()?.let { return ConnectionResult.UntrustedCertificate(it.info()) }
            e.findCause<UnknownHostException>()?.let { return ConnectionResult.Unreachable }
            return if (e.findCause<SSLException>() != null) ConnectionResult.TlsError else ConnectionResult.Unreachable
        }

        private fun UntrustedCertificateException.info() =
            CertificateInfo(
                spkiSha256 = spkiSha256,
                subject = certificate.subjectX500Principal.name,
                issuer = certificate.issuerX500Principal.name,
                validUntil = DateFormat.getDateInstance(DateFormat.MEDIUM).format(certificate.notAfter),
            )

        private companion object {
            const val MAX_BODY_BYTES = 4096L
        }
    }
