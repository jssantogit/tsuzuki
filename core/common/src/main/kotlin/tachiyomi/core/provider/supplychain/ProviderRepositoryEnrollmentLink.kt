package tachiyomi.core.provider.supplychain

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Public enrollment document served at a single HTTPS URL. Never carries a private key.
 * Resolving it is NOT user consent: the Host must verify the signed index and display
 * the SHA-256 signing-key fingerprint before enrollment is explicitly approved.
 */
@Serializable
data class ProviderRepositoryEnrollmentDocument(
    val repositoryId: String,
    val indexUrl: String,
    val keyId: String,
    val publicKeyBase64: String,
    val displayName: String? = null,
)

private val enrollmentLinkJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

fun decodeProviderRepositoryEnrollmentDocument(value: String): EnrolledProviderRepository {
    if (value.encodeToByteArray().size > 16 * 1024) {
        throw ProviderSupplyChainException("Repository enrollment document is too large")
    }
    val document = try {
        enrollmentLinkJson.decodeFromString<ProviderRepositoryEnrollmentDocument>(value)
    } catch (error: Exception) {
        throw ProviderSupplyChainException("Repository enrollment document is malformed", error)
    }
    validateIdentifier(document.repositoryId, "Repository ID")
    validateHttpsUrl(document.indexUrl, "Repository index URL")
    if (document.keyId.isBlank() || document.keyId.length > 128) {
        throw ProviderSupplyChainException("Repository signing key ID is invalid")
    }
    val key = ProviderRepositorySigningKey(
        keyId = document.keyId,
        publicKeyBase64 = document.publicKeyBase64,
    )
    providerRepositoryKeyFingerprint(key)
    val name = document.displayName?.trim()?.takeIf(String::isNotEmpty)
        ?: document.repositoryId
    if (name.length > 120) {
        throw ProviderSupplyChainException("Repository display name is too long")
    }
    return EnrolledProviderRepository(
        displayName = name,
        enrollment = ProviderRepositoryEnrollment(
            repositoryId = document.repositoryId,
            indexUrl = document.indexUrl,
            signingKey = key,
        ),
    )
}
