package com.c0mpile.grimmreader.core.network

/**
 * Finds a [T] among the causes and suppressed exceptions of this throwable. OkHttp reports the error of the
 * last route it tried and attaches earlier ones (for example a TLS failure on IPv4 before a refused IPv6
 * connect) as suppressed exceptions, so the interesting error is often not in the cause chain.
 */
inline fun <reified T : Throwable> Throwable.findCause(): T? {
    val seen = HashSet<Throwable>()
    val queue = ArrayDeque<Throwable>().apply { add(this@findCause) }
    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        if (!seen.add(t)) continue
        if (t is T) return t
        t.cause?.let(queue::add)
        queue.addAll(t.suppressed)
    }
    return null
}

/** TLS trust failures are decisions, not transient errors: never retry them. */
fun Throwable.isCertificateFailure(): Boolean =
    findCause<java.security.cert.CertificateException>() != null || findCause<javax.net.ssl.SSLPeerUnverifiedException>() != null
