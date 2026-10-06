package com.c0mpile.grimmreader.core.network

import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/** The server presented a certificate the system does not trust and the user has not pinned. */
class UntrustedCertificateException(
    val certificate: X509Certificate,
    cause: Throwable?,
) : CertificateException("Certificate not trusted", cause) {
    val spkiSha256: String = spkiSha256(certificate)
}

/** A pin exists for the host but the presented key differs. Never accepted silently. */
class PinMismatchException : CertificateException("Pinned certificate changed")

fun spkiSha256(certificate: X509Certificate): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

/**
 * System trust OR the exact public key the user pinned for that host (trust on first use). There is no
 * global trust-all: a host without a pin gets plain system validation.
 */
class PinningTrustManager(
    private val policy: NetworkPolicy,
    private val system: X509ExtendedTrustManager = systemTrustManager(),
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) = verify(chain, peerHost(socket)) { system.checkServerTrusted(chain, authType, socket) }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = verify(chain, engine?.peerHost) { system.checkServerTrusted(chain, authType, engine) }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = verify(chain, host = null) { system.checkServerTrusted(chain, authType) }

    private inline fun verify(
        chain: Array<X509Certificate>,
        host: String?,
        systemCheck: () -> Unit,
    ) {
        try {
            systemCheck()
        } catch (e: CertificateException) {
            val pin = host?.let(policy::pinnedSpki) ?: throw UntrustedCertificateException(chain.first(), e)
            if (spkiSha256(chain.first()) != pin) throw PinMismatchException()
        }
    }

    private fun peerHost(socket: Socket?): String? {
        val session: SSLSession? = (socket as? SSLSocket)?.handshakeSession
        return session?.peerHost ?: socket?.inetAddress?.hostName
    }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) = system.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = system.checkClientTrusted(chain, authType, engine)

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    companion object {
        fun systemTrustManager(): X509ExtendedTrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
        }
    }
}
