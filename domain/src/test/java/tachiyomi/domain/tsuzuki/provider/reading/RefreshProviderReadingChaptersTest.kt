package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.provider.ProviderId

class RefreshProviderReadingChaptersTest {

    private val providerId = ProviderId("org.example.reader")
    private val binding = ProviderReadingBinding(
        id = "binding-1",
        canonicalTitleId = "title-1",
        ref = ProviderBindingRef(
            providerId = providerId,
            facetId = "en",
            externalWorkId = "work-1",
        ),
        verification = ProviderBindingVerification.EXACT,
        availability = ProviderBindingAvailability.AVAILABLE,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun `publishes one complete provider snapshot only after final chapter page`() = runBlocking {
        val cursors = mutableListOf<String?>()
        var published: List<ChapterEvidence>? = null
        val gateway = gateway { request ->
            cursors += request.cursor?.value
            when (request.cursor?.value) {
                null -> ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(
                            ProviderChapterObservation("c1", "Chapter 1", rawNumber = 1.0),
                            ProviderChapterObservation("c2", "Chapter 2", rawNumber = 2.0),
                        ),
                        nextCursor = ProviderCursor("next"),
                    ),
                )
                "next" -> ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(
                            ProviderChapterObservation("c3", "Chapter 3", rawNumber = 3.0),
                        ),
                        nextCursor = null,
                    ),
                )
                else -> error("unexpected cursor")
            }
        }
        var publishedProducerId: String? = null
        var publishedSnapshotObservedAt: Long? = null
        val interactor = RefreshProviderReadingChapters(
            gateway = gateway,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            publishSnapshot = { titleId, producerId, snapshotObservedAt, evidence ->
                titleId shouldBe "title-1"
                publishedProducerId = producerId
                publishedSnapshotObservedAt = snapshotObservedAt
                published = evidence
            },
            clock = { 42L },
        )

        interactor.execute(binding) shouldBe ProviderCallResult.Success(3)
        cursors shouldBe listOf(null, "next")
        publishedProducerId shouldBe binding.ref.evidenceProducerId()
        publishedSnapshotObservedAt shouldBe 42L
        published!!.map { it.externalChapterKey } shouldBe listOf("c1", "c2", "c3")
        published!!.map { it.observedAt }.distinct() shouldBe listOf(42L)
    }

    @Test
    fun `later page failure never publishes a partial canonical inventory`() = runBlocking {
        var published = false
        var calls = 0
        val interactor = RefreshProviderReadingChapters(
            gateway = gateway { request ->
                calls += 1
                if (request.cursor == null) {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderChapterObservation("c1", "Chapter 1")),
                            nextCursor = ProviderCursor("next"),
                        ),
                    )
                } else {
                    ProviderCallResult.Failure(
                        ProviderError(ProviderErrorCode.TIMEOUT, retryable = true),
                    )
                }
            },
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            publishSnapshot = { _, _, _, _ -> published = true },
            clock = { 1L },
        )

        interactor.execute(binding) shouldBe ProviderCallResult.Failure(
            ProviderError(ProviderErrorCode.TIMEOUT, retryable = true),
        )
        calls shouldBe 2
        published shouldBe false
    }

    @Test
    fun `repeated cursor or chapter identity fails closed without reconciliation`() = runBlocking {
        var published = false
        val looping = RefreshProviderReadingChapters(
            gateway = gateway {
                ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(ProviderChapterObservation("c1", "Chapter 1")),
                        nextCursor = ProviderCursor("same"),
                    ),
                )
            },
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            publishSnapshot = { _, _, _, _ -> published = true },
            clock = { 1L },
        )

        looping.execute(binding) shouldBe ProviderCallResult.Failure(
            ProviderError(ProviderErrorCode.MALFORMED_RESULT, retryable = false),
        )
        published shouldBe false

        var page = 0
        val duplicate = RefreshProviderReadingChapters(
            gateway = gateway {
                page += 1
                ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(ProviderChapterObservation("duplicate", "Chapter $page")),
                        nextCursor = if (page == 1) ProviderCursor("next") else null,
                    ),
                )
            },
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            publishSnapshot = { _, _, _, _ -> published = true },
            clock = { 1L },
        )

        duplicate.execute(binding) shouldBe ProviderCallResult.Failure(
            ProviderError(ProviderErrorCode.MALFORMED_RESULT, retryable = false),
        )
        published shouldBe false
    }

    @Test
    fun `complete empty provider inventory still publishes a snapshot to detach stale support`() = runBlocking {
        var published = false
        val interactor = RefreshProviderReadingChapters(
            gateway = gateway {
                ProviderCallResult.Success(
                    ProviderPage(
                        items = emptyList(),
                        nextCursor = null,
                    ),
                )
            },
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            publishSnapshot = { titleId, producerId, snapshotObservedAt, evidence ->
                titleId shouldBe "title-1"
                producerId shouldBe binding.ref.evidenceProducerId()
                snapshotObservedAt shouldBe 77L
                evidence shouldBe emptyList()
                published = true
            },
            clock = { 77L },
        )

        interactor.execute(binding) shouldBe ProviderCallResult.Success(0)
        published shouldBe true
    }

    @Test
    fun `unavailable binding never invokes Provider runtime`() = runBlocking {
        var invoked = false
        val interactor = RefreshProviderReadingChapters(
            gateway = gateway {
                invoked = true
                error("must not invoke")
            },
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            publishSnapshot = { _, _, _, _ -> error("must not publish") },
            clock = { 1L },
        )

        interactor.execute(
            binding.copy(availability = ProviderBindingAvailability.UNAVAILABLE),
        ) shouldBe ProviderCallResult.Failure(
            ProviderError(ProviderErrorCode.UNAVAILABLE, retryable = false),
        )
        invoked shouldBe false
    }

    private fun gateway(
        chapters: suspend (
            ProviderReadingChaptersRequest,
        ) -> ProviderCallResult<ProviderPage<ProviderChapterObservation>>,
    ) = object : ProviderReadingGateway {
        override suspend fun lookup(
            providerId: ProviderId,
            request: ProviderReadingLookupRequest,
        ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>> = error("not used")

        override suspend fun chapters(
            providerId: ProviderId,
            request: ProviderReadingChaptersRequest,
        ): ProviderCallResult<ProviderPage<ProviderChapterObservation>> {
            providerId shouldBe this@RefreshProviderReadingChaptersTest.providerId
            return chapters(request)
        }

        override suspend fun pages(
            providerId: ProviderId,
            request: ProviderReadingPagesRequest,
        ): ProviderCallResult<ProviderReadingDelivery> = error("not used")
    }
}
