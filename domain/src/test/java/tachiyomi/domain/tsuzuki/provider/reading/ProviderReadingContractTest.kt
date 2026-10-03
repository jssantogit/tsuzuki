package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.provider.ProviderId

class ProviderReadingContractTest {

    private val providerId = ProviderId("org.example.reader")
    private val binding = ProviderBindingRef(
        providerId = providerId,
        facetId = "en",
        externalWorkId = "work-42",
    )

    @Test
    fun `binding identity is provider neutral and never accepts blank external identity`() {
        binding.providerId shouldBe providerId
        binding.facetId shouldBe "en"
        binding.externalWorkId shouldBe "work-42"

        shouldThrow<IllegalArgumentException> {
            ProviderBindingRef(providerId, null, "")
        }
    }

    @Test
    fun `chapter observations remain provider evidence rather than canonical identity`() {
        val observation = ProviderChapterObservation(
            providerChapterId = "chapter-12",
            rawLabel = "Vol. 3 Ch. 12.5 — Bonus",
            rawNumber = 12.5,
            volume = 3,
            title = "Bonus",
            language = "en",
            scanlationGroup = "Group",
            releaseDateMillis = 1234,
        )

        val evidence = ProviderChapterEvidenceAdapter(
            clock = { 9999L },
        ).toEvidence(
            canonicalTitleId = "canonical-title",
            binding = binding,
            observations = listOf(observation),
        ).single()

        evidence.canonicalTitleId shouldBe "canonical-title"
        evidence.producerKind shouldBe ProducerKind.PROVIDER
        evidence.producerId shouldBe binding.evidenceProducerId()
        evidence.externalChapterKey shouldBe "chapter-12"
        evidence.rawLabel shouldBe "Vol. 3 Ch. 12.5 — Bonus"
        evidence.rawNumber shouldBe 12.5
        evidence.volume shouldBe 3
        evidence.title shouldBe "Bonus"
        evidence.authority shouldBe ChapterEvidenceAuthority.PROVIDER_PROVISIONAL
        evidence.observedAt shouldBe 9999L
    }

    @Test
    fun `one provider inventory snapshot shares one observed timestamp`() {
        var clock = 100L
        val adapter = ProviderChapterEvidenceAdapter(clock = { clock++ })

        val evidence = adapter.toEvidence(
            canonicalTitleId = "title",
            binding = binding,
            observations = listOf(
                ProviderChapterObservation("c1", "Chapter 1"),
                ProviderChapterObservation("c2", "Chapter 2"),
            ),
        )

        evidence.map { it.observedAt } shouldBe listOf(100L, 100L)
        clock shouldBe 101L
    }

    @Test
    fun `provider evidence producer identity is scoped to the exact binding`() {
        val same = binding.evidenceProducerId()
        binding.copy(facetId = "pt-br").evidenceProducerId() shouldBe
            binding.copy(facetId = "pt-br").evidenceProducerId()
        (binding.copy(facetId = "pt-br").evidenceProducerId() != same) shouldBe true
        (binding.copy(externalWorkId = "work-99").evidenceProducerId() != same) shouldBe true
        (binding.copy(providerId = ProviderId("org.example.other")).evidenceProducerId() != same) shouldBe true
    }

    @Test
    fun `evidence ids are stable within one provider binding and distinct across providers`() {
        val observation = ProviderChapterObservation(
            providerChapterId = "chapter-12",
            rawLabel = "Chapter 12",
        )
        val adapter = ProviderChapterEvidenceAdapter(clock = { 1L })

        val first = adapter.toEvidence("title", binding, listOf(observation)).single()
        val repeated = adapter.toEvidence("title", binding, listOf(observation)).single()
        val otherProvider = adapter.toEvidence(
            "title",
            binding.copy(providerId = ProviderId("org.example.other")),
            listOf(observation),
        ).single()

        repeated.id shouldBe first.id
        (otherProvider.id != first.id) shouldBe true
    }

    @Test
    fun `delivery plans expose only validated requests or host managed resources`() {
        val pages = ProviderReadingDelivery.PageList(
            pages = listOf(
                ProviderPageRequest(
                    url = "https://cdn.example/001.jpg",
                    headers = mapOf("Referer" to "https://reader.example/"),
                    allowedOrigins = setOf("https://cdn.example"),
                ),
            ),
        )
        pages.pages.single().url shouldBe "https://cdn.example/001.jpg"

        val archive = ProviderReadingDelivery.ManagedFile(
            resource = ProviderManagedResourceRef("managed:abc"),
            format = ProviderManagedFileFormat.CBZ,
        )
        archive.resource.value shouldBe "managed:abc"

        shouldThrow<IllegalArgumentException> {
            ProviderManagedResourceRef("/data/user/0/app.tsuzuki/files/private.cbz")
        }
    }

    @Test
    fun `provider page cursors are opaque bounded values scoped by caller context`() {
        ProviderPage(
            items = listOf("one"),
            nextCursor = ProviderCursor("cursor-token"),
        ).nextCursor?.value shouldBe "cursor-token"

        shouldThrow<IllegalArgumentException> {
            ProviderCursor("x".repeat(4097))
        }
    }
}
