package eu.kanade.tachiyomi.provider.runtime

import java.security.MessageDigest

/**
 * One canonical profile namespace for the Browser Host and debug-only
 * interactive MangaALL session verification. Never export session material.
 */
internal object ProviderBrowserProfiles {
    fun nameFor(providerId: String): String {
        require(providerId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return "tsuzuki-provider-${digest.take(24)}"
    }
}
