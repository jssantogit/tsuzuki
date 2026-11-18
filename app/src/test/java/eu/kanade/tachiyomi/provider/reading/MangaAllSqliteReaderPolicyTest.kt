package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ProviderEvidenceMetadata
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderFacetRef
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelectionResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingGateway
import tachiyomi.domain.tsuzuki.provider.reading.ProviderSourceEditionQualification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkSourceAssociation
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkVerification
import tachiyomi.domain.tsuzuki.provider.reading.ResolveMangaAllSelectedChapter
import tachiyomi.domain.tsuzuki.provider.reading.ResolveMangaAllSelectedReadingOption
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading
import tachiyomi.domain.tsuzuki.provider.reading.SelectMangaAllSource
import tachiyomi.domain.tsuzuki.provider.reading.evidenceProducerId

/** SQLDelight policy tests; synthetic CONTRACT data never fed to live upstream reports. */
class MangaAllSqliteReaderPolicyTest {
    @Test
    fun `author corroborated unresolved source resolves only the exact SQL-mapped chapter`() = runBlocking {
        MangaAllDisposableSqlite.open().use { sqlite ->
            sqlite.titleRepository.insert(title())
            val ref = source()
            sqlite.workSourceRepository.observe(
                association(ref, ProviderWorkVerification.AUTHOR_CORROBORATED),
            ) shouldBe true
            val reconciler = ReconcileChapterEvidence(
                ParseCanonicalChapterLabel(),
                sqlite.chapterRepository,
                sqlite.evidenceRepository,
            )
            reconciler.execute(TITLE_ID, listOf(evidence(ref)))
            val chapter = sqlite.chapterRepository.getChaptersByCanonicalTitleId(TITLE_ID).single()
            val resolver = resolver(sqlite)
            val options = resolver.options(chapter.id)
            options.size shouldBe 1
            options.single().bindingRef() shouldBe ref
            options.single().providerChapterId shouldBe "mangafire:chapter-1"

            // Only an explicitly simulated user choice; never a background auto-pick.
            val selection = SelectMangaAllSource(
                sqlite.workSourceRepository,
                sqlite.sourceSelectionRepository,
            )
            selection.select(TITLE_ID, ref, at = 100L) shouldBe MangaAllSourceSelectionResult.SELECTED
            val selectedChapter = ResolveMangaAllSelectedChapter(
                sqlite.chapterRepository,
                resolver,
                ResolveMangaAllSelectedReadingOption(sqlite.sourceSelectionRepository),
            )
            selectedChapter.execute(chapter.id, "en") shouldBe options.single()
            selectedChapter.execute(chapter.id, "pt-br") shouldBe null
            selectedChapter.execute("nonexistent", "en") shouldBe null
        }
    }

    @Test
    fun `SQL evidence without independent association never authorizes Reader source`() = runBlocking {
        MangaAllDisposableSqlite.open().use { sqlite ->
            sqlite.titleRepository.insert(title())
            val ref = source()
            ReconcileChapterEvidence(
                ParseCanonicalChapterLabel(),
                sqlite.chapterRepository,
                sqlite.evidenceRepository,
            ).execute(TITLE_ID, listOf(evidence(ref)))
            val chapter = sqlite.chapterRepository.getChaptersByCanonicalTitleId(TITLE_ID).single()
            val resolver = resolver(sqlite)
            resolver.options(chapter.id) shouldBe emptyList()
            SelectMangaAllSource(
                sqlite.workSourceRepository,
                sqlite.sourceSelectionRepository,
            ).select(TITLE_ID, ref, at = 100L) shouldBe MangaAllSourceSelectionResult.NOT_ASSOCIATED
        }
    }

    @Test
    fun `author conflict never becomes Reader authority despite SQL chapter mapping`() = runBlocking {
        MangaAllDisposableSqlite.open().use { sqlite ->
            sqlite.titleRepository.insert(title())
            val ref = source()
            sqlite.workSourceRepository.observe(
                association(ref, ProviderWorkVerification.AUTHOR_CONFLICT),
            ) shouldBe true
            ReconcileChapterEvidence(
                ParseCanonicalChapterLabel(),
                sqlite.chapterRepository,
                sqlite.evidenceRepository,
            ).execute(TITLE_ID, listOf(evidence(ref)))
            val chapter = sqlite.chapterRepository.getChaptersByCanonicalTitleId(TITLE_ID).single()
            resolver(sqlite).options(chapter.id) shouldBe emptyList()
            SelectMangaAllSource(
                sqlite.workSourceRepository,
                sqlite.sourceSelectionRepository,
            ).select(TITLE_ID, ref, at = 100L) shouldBe MangaAllSourceSelectionResult.BLOCKED
        }
    }

    private fun title() = CanonicalTitle(
        id = TITLE_ID,
        displayTitle = "CONTRACT_ONLY_TITLE",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun source() = ProviderBindingRef(PROVIDER_ID, "en", "mangafire:work-1")

    private fun association(ref: ProviderBindingRef, verification: ProviderWorkVerification) =
        ProviderWorkSourceAssociation(
            canonicalTitleId = TITLE_ID,
            ref = ref,
            workVerification = verification,
            editionQualification = ProviderSourceEditionQualification.UNRESOLVED,
            observedAt = 10L,
        )

    private fun evidence(ref: ProviderBindingRef) = ChapterEvidence(
        id = "contract-reader-observation",
        canonicalTitleId = TITLE_ID,
        producerKind = ProducerKind.PROVIDER,
        producerId = ref.evidenceProducerId(),
        externalChapterKey = "mangafire:chapter-1",
        rawLabel = "Chapter 1",
        rawNumber = 1.0,
        volume = null,
        title = null,
        observedAt = 20L,
        confidence = 0.95,
        authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
        providerMetadata = ProviderEvidenceMetadata(language = "en"),
    )

    private fun resolver(sqlite: MangaAllDisposableSqlite) = ResolveProviderChapterReading(
        canonicalChapterRepository = sqlite.chapterRepository,
        evidenceRepository = sqlite.evidenceRepository,
        bindingRepository = sqlite.bindingRepository,
        workSourceAssociationRepository = sqlite.workSourceRepository,
        providerRegistry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = ProviderDescriptor(
                            id = PROVIDER_ID,
                            name = "MangaAll contract registration",
                            version = ProviderVersion("0.5.3", 8),
                            origin = ProviderOrigin.Repository("contract"),
                            runtime = ProviderRuntimeKind.SCRIPT,
                            capabilities = setOf(
                                ProviderCapabilities.ReadingLookupV1,
                                ProviderCapabilities.ReadingChaptersV1,
                                ProviderCapabilities.ReadingPagesV1,
                            ),
                            permissions = ProviderPermissionSet(),
                            settings = emptyList(),
                            contentLanguages = setOf("en"),
                        ),
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                        facets = listOf(ProviderFacetRef(PROVIDER_ID, "en")),
                        enabledCapabilities = setOf(
                            ProviderCapabilities.ReadingLookupV1,
                            ProviderCapabilities.ReadingChaptersV1,
                            ProviderCapabilities.ReadingPagesV1,
                        ),
                    ),
                )
            },
        ),
        gateway = mockk<ProviderReadingGateway>(),
    )

    private companion object {
        val PROVIDER_ID = ProviderId("app.tsuzuki.mangaall")
        const val TITLE_ID = "contract-reader-title"
    }
}
