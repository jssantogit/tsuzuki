package tachiyomi.domain.tsuzuki.provider.reading

import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import java.security.MessageDigest

class ProviderChapterEvidenceAdapter(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun toEvidence(
        canonicalTitleId: String,
        binding: ProviderBindingRef,
        observations: List<ProviderChapterObservation>,
    ): List<ChapterEvidence> {
        require(canonicalTitleId.isNotBlank()) { "Canonical title ID must not be blank" }

        return observations.map { observation ->
            ChapterEvidence(
                id = evidenceId(
                    canonicalTitleId = canonicalTitleId,
                    binding = binding,
                    providerChapterId = observation.providerChapterId,
                ),
                canonicalTitleId = canonicalTitleId,
                producerKind = ProducerKind.PROVIDER,
                producerId = binding.providerId.value,
                externalChapterKey = observation.providerChapterId,
                rawLabel = observation.rawLabel,
                rawNumber = observation.rawNumber,
                volume = observation.volume,
                title = observation.title,
                observedAt = clock(),
                confidence = PROVIDER_OBSERVATION_CONFIDENCE,
                authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
            )
        }
    }

    private fun evidenceId(
        canonicalTitleId: String,
        binding: ProviderBindingRef,
        providerChapterId: String,
    ): String {
        val identity = buildString {
            append(canonicalTitleId)
            append('\u0000')
            append(binding.providerId.value)
            append('\u0000')
            append(binding.facetId.orEmpty())
            append('\u0000')
            append(binding.externalWorkId)
            append('\u0000')
            append(providerChapterId)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return "provider:$digest"
    }

    private companion object {
        const val PROVIDER_OBSERVATION_CONFIDENCE = 0.95
    }
}
