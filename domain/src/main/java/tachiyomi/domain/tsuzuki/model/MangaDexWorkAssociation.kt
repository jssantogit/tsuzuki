package tachiyomi.domain.tsuzuki.model

import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings

/** Persisted permission and trusted identity snapshot required to associate a MangaDex work. */
data class MangaDexWorkAssociationRequest(
    val canonicalTitleId: String,
    val mangaDexWorkId: String,
    val expectedIntegrationSettings: IntegrationSettings,
    val expectedVerifiedCrosswalks: List<ExternalIdentity>,
)

enum class MangaDexWorkAssociationResult {
    ASSOCIATED,
    ALREADY_ASSOCIATED,
    REFUSED,
    UNSUPPORTED,
}
