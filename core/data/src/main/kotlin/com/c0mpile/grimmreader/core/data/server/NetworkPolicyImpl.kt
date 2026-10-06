package com.c0mpile.grimmreader.core.data.server

import com.c0mpile.grimmreader.core.network.NetworkPolicy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's opt-ins as the network layer sees them: cleartext and certificate pins per host, from the
 * configured server and from the setup flow while a server is being added. Read on every request, so it is
 * a lock-free snapshot.
 */
@Singleton
class NetworkPolicyImpl
    @Inject
    constructor() : NetworkPolicy {
        private data class HostRules(
            val cleartext: Boolean,
            val pin: String?,
        )

        private val configured = ConcurrentHashMap<String, HostRules>()
        private val pending = ConcurrentHashMap<String, HostRules>()

        override fun isCleartextAllowed(host: String): Boolean = rules(host)?.cleartext == true

        override fun pinnedSpki(host: String): String? = rules(host)?.pin

        private fun rules(host: String) = pending[host.lowercase()] ?: configured[host.lowercase()]

        /** Replaces the rules of saved servers and catalogs. */
        fun setConfigured(hosts: Map<String, Pair<Boolean, String?>>) {
            configured.clear()
            hosts.forEach { (host, rule) -> configured[host.lowercase()] = HostRules(rule.first, rule.second) }
        }

        /** Rules for a server that is being set up (before it is saved). */
        fun setPending(
            host: String,
            cleartext: Boolean,
            pin: String?,
        ) {
            pending[host.lowercase()] = HostRules(cleartext, pin)
        }

        fun clearPending() = pending.clear()
    }
