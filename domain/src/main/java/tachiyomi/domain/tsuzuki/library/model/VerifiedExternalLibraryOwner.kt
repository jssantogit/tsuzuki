package tachiyomi.domain.tsuzuki.library.model

import java.security.MessageDigest

/**
 * Owner of an account-linked external list. Derived only from a provider's
 * authenticated /me identity response, never from a login Boolean, email
 * field, access token or arbitrary list item.
 *
 * The persisted key is a deterministic, provider-separated SHA-256 fingerprint
 * so account handles do not appear in the local library database or logs.
 */
data class VerifiedExternalLibraryOwner private constructor(
    val provider: String,
    val ownerKey: String,
) {
    companion object {
        fun fromAuthenticatedIdentity(provider: String, remoteSubject: String): VerifiedExternalLibraryOwner {
            require(provider.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) {
                "Invalid account-linked library provider"
            }
            val subject = remoteSubject.trim()
            require(subject.isNotEmpty() && subject.length <= 512 && subject.none(Char::isISOControl)) {
                "Invalid authenticated remote subject"
            }
            val input = "$provider\u0000$subject".toByteArray(Charsets.UTF_8)
            val hex = "0123456789abcdef"
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            val fingerprint = buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    append(hex[unsigned ushr 4])
                    append(hex[unsigned and 15])
                }
            }
            return VerifiedExternalLibraryOwner(provider, fingerprint)
        }
    }
}
