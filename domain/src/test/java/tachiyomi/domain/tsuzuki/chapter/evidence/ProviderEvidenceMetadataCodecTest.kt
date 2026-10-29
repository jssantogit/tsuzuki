package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterObservation

class ProviderEvidenceMetadataCodecTest {

    @Test
    fun `versioned metadata preserves language group and release time without editorial authority`() {
        val metadata = ProviderEvidenceMetadata(
            language = "pt-BR",
            scanlationGroup = "Grupo Ñ",
            releaseDateMillis = 1_702_345_678_901L,
        )
        val bytes = ProviderEvidenceMetadataCodec.encode(metadata)
        ProviderEvidenceMetadataCodec.decode(bytes) shouldBe metadata
        ProviderEvidenceMetadataCodec.isPortable(bytes) shouldBe true
        ProviderEvidenceMetadataCodec.encode(ProviderEvidenceMetadata()) shouldBe byteArrayOf()
    }

    @Test
    fun `opaque or malformed metadata is not interpreted or declared portable`() {
        val unknown = listOf(
            byteArrayOf(1, 2, 3),
            "private-access-token".toByteArray(),
            byteArrayOf(0, 0, 0),
        )
        unknown.forEach { raw ->
            ProviderEvidenceMetadataCodec.decode(raw) shouldBe null
            ProviderEvidenceMetadataCodec.isPortable(raw) shouldBe false
        }
        val good = ProviderEvidenceMetadataCodec.encode(ProviderEvidenceMetadata(language = "ja"))
        ProviderEvidenceMetadataCodec.decode(good + byteArrayOf(7)) shouldBe null
        ProviderEvidenceMetadataCodec.isPortable(good + byteArrayOf(7)) shouldBe false
    }

    @Test
    fun `provider adapter retains bounded language group and publication timestamp as provisional evidence`() {
        val adapter = ProviderChapterEvidenceAdapter(clock = { 1L })
        val values = adapter.toEvidence(
            canonicalTitleId = "work",
            binding = ProviderBindingRef(ProviderId("org.example"), "pt-BR", "remote-work"),
            observations = listOf(
                ProviderChapterObservation(
                    providerChapterId = "ch-01",
                    rawLabel = "Capítulo 1",
                    rawNumber = 1.0,
                    language = "pt-BR",
                    scanlationGroup = "Grupo Ñ",
                    releaseDateMillis = 1_702_345_678_901L,
                ),
            ),
        )
        values.single().providerMetadata shouldBe ProviderEvidenceMetadata(
            language = "pt-BR",
            scanlationGroup = "Grupo Ñ",
            releaseDateMillis = 1_702_345_678_901L,
        )
        values.single().authority shouldBe ChapterEvidenceAuthority.PROVIDER_PROVISIONAL
    }
}
