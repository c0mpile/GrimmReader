package com.c0mpile.grimmreader.core.network

class TestPolicy(
    private val cleartextHosts: Set<String> = emptySet(),
    private val pins: Map<String, String> = emptyMap(),
) : NetworkPolicy {
    override fun isCleartextAllowed(host: String) = host.lowercase() in cleartextHosts

    override fun pinnedSpki(host: String) = pins[host.lowercase()]
}
