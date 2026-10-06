package com.c0mpile.grimmreader.core.network

/**
 * The user's network opt-ins, read on every request. Implemented by the data layer from the configured
 * server and OPDS catalogs. Hosts are compared case-insensitively.
 */
interface NetworkPolicy {
    /** True only for a host the user explicitly allowed to use unencrypted HTTP. */
    fun isCleartextAllowed(host: String): Boolean

    /** Base64 SHA-256 of the SubjectPublicKeyInfo the user accepted for [host], if any. */
    fun pinnedSpki(host: String): String?

    companion object {
        /** No opt-ins at all: https only, system trust only. */
        val Strict =
            object : NetworkPolicy {
                override fun isCleartextAllowed(host: String) = false

                override fun pinnedSpki(host: String): String? = null
            }
    }
}
