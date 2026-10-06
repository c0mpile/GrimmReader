package com.c0mpile.grimmreader.core.common

/** Errors the UI knows how to explain. Messages never contain hosts, users or tokens. */
sealed class AppError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Unreachable(
        cause: Throwable? = null,
    ) : AppError("Server unreachable", cause)

    class Tls(
        cause: Throwable? = null,
    ) : AppError("TLS handshake failed", cause)

    /** The certificate is not trusted; the user may pin it after seeing its fingerprint. */
    class UntrustedCertificate(
        val spkiSha256: String,
        val subject: String,
        val issuer: String,
        val validUntil: String,
    ) : AppError("Untrusted certificate")

    /** A pinned certificate changed. Never silently accepted. */
    class CertificateChanged : AppError("Pinned certificate changed")

    /** An HTML login page answered instead of the API (forward-auth gateway in front of the server). */
    class GatewayLogin : AppError("A login page answered instead of the Grimmory API")

    class NotGrimmory : AppError("Not a Grimmory server")

    class UnsupportedVersion(
        val version: String,
    ) : AppError("Unsupported server version")

    class CleartextNotPermitted : AppError("Unencrypted HTTP is not allowed for this host")

    class Unauthorized : AppError("Not signed in")

    class Forbidden : AppError("Access denied")

    class Http(
        val code: Int,
    ) : AppError("HTTP $code")

    class Unexpected(
        cause: Throwable,
    ) : AppError("Unexpected error", cause)
}
