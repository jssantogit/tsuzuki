package tachiyomi.domain.tsuzuki.model

import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings

/**
 * Atomic, permission-scoped work association. Discovery by title is never
 * sufficient: a trusted, previously verified external-work anchor is required.
 * This is a work identity, not a chapter or edition authority.
 */
data class VerifiedInventoryWorkAssociationRequest(
    val canonicalTitleId: String,
    val producerId: String,
    val externalWorkId: String,
    val expectedIntegrationSettings: IntegrationSettings,
    val expectedVerifiedCrosswalks: List<ExternalIdentity>,
)

enum class VerifiedInventoryWorkAssociationResult {
    ASSOCIATED,
    ALREADY_ASSOCIATED,
    REFUSED,
    UNSUPPORTED,
}
