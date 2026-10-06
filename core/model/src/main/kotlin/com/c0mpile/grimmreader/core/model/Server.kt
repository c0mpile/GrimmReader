package com.c0mpile.grimmreader.core.model

/**
 * A configured Grimmory server. [baseUrl] is normalised (`scheme://host[:port][/prefix]`, no trailing slash).
 * [allowCleartext] is the user's explicit opt-in for `http://` to exactly this host.
 */
data class ServerConfig(
    val id: Long,
    val baseUrl: String,
    val allowCleartext: Boolean = false,
    /** SHA-256 of the SubjectPublicKeyInfo the user accepted for a self-signed certificate (base64). */
    val pinnedSpkiSha256: String? = null,
    val username: String? = null,
    val serverVersion: String? = null,
)

/** The user's server permissions. `admin` implies every permission. */
data class Permissions(
    val flags: Set<String> = emptySet(),
) {
    val isAdmin: Boolean get() = ADMIN in flags

    fun has(flag: String): Boolean = isAdmin || flag in flags

    companion object {
        const val ADMIN = "admin"
        const val CAN_DOWNLOAD = "canDownload"
        const val CAN_UPLOAD = "canUpload"
        const val CAN_EDIT_METADATA = "canEditMetadata"
        const val CAN_DELETE_BOOK = "canDeleteBook"
        const val CAN_MANAGE_FONTS = "canManageFonts"
        const val CAN_ACCESS_OPDS = "canAccessOpds"
        val NONE = Permissions()
    }
}

enum class ServerStatus { ONLINE, OFFLINE, AUTH_EXPIRED, UNSUPPORTED }
