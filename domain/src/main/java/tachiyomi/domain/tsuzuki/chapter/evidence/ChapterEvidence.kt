package tachiyomi.domain.tsuzuki.chapter.evidence

enum class ProducerKind {
    INTEGRATION,
    ADDON,
    PROVIDER,
}

enum class ChapterEvidenceAuthority {
    EDITORIAL,
    ADDON_PROVISIONAL,
    PROVIDER_PROVISIONAL,
}

enum class CanonicalChapterConfirmation {
    CONFIRMED,
    PROVISIONAL,
    CONFLICTED,
}

data class ChapterEvidence(
    val id: String,
    val canonicalTitleId: String,
    val producerKind: ProducerKind,
    val producerId: String,
    val externalChapterKey: String?,
    val rawLabel: String,
    val rawNumber: Double?,
    val volume: Int?,
    val title: String?,
    val observedAt: Long,
    val confidence: Double,
    val authority: ChapterEvidenceAuthority,
    val providerMetadata: ProviderEvidenceMetadata? = null,
) {
    init {
        require(
            providerMetadata == null ||
                (producerKind == ProducerKind.PROVIDER && authority == ChapterEvidenceAuthority.PROVIDER_PROVISIONAL),
        ) { "Provider release attributes require provisional Provider provenance" }
    }
}
