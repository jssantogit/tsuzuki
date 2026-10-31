package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
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
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class CollectProviderReadingEvidenceTest {

    private val providerId = ProviderId("app.tsuzuki.mangafire")
    private val canonicalTitle = CanonicalTitle(
        id = "title-1",
        displayTitle = "Dandadan",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `stalled provider chapters stop before deadline without reporting a complete empty inventory`() = runTest {
        val recorder = CapturingProviderDiagnostics()
        val bindings = MemoryBindingRepository()
        val neverCompletes = CompletableDeferred<Unit>()
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate("work-1", canonicalTitle.displayTitle, language = "en"),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = {
                    neverCompletes.await()
                    error("unreachable")
                },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )

        // An outer test deadline proves the collector itself has a smaller budget.
        val result = withTimeoutOrNull(21_000) { collector.execute(canonicalTitle.id) }

        (result != null) shouldBe true
        result!!.complete shouldBe false
        result.evidence shouldBe emptyList()
        result.snapshots shouldBe emptyList()
        bindings.getByTitle(canonicalTitle.id).shouldHaveSize(1)
        val timedOut = recorder.events.single {
            it.stage == DiagnosticStage.READ && it.outcome == DiagnosticOutcome.TIMEOUT
        }
        (StructuredDiagnosticSanitizer.sanitize(timedOut) != null) shouldBe true
        val summary = recorder.events.single {
            it.stage == DiagnosticStage.SUMMARY && it.outcome != DiagnosticOutcome.STARTED
        }
        summary.outcome shouldBe DiagnosticOutcome.PARTIAL
        summary.attributes["completed"] shouldBe DiagnosticAttributeValue.Flag(false)
        summary.attributes["chapter_count"] shouldBe DiagnosticAttributeValue.Number(0)
        recorder.events.none { it.outcome == DiagnosticOutcome.CANCELLED } shouldBe true
    }

    @Test
    fun `page progress survives next page timeout as sanitized counts not canonical evidence`() = runTest {
        val recorder = CapturingProviderDiagnostics()
        val neverCompletes = CompletableDeferred<Unit>()
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderWorkCandidate("work-1", "Dandadan", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { request ->
                    if (request.cursor == null) {
                        ProviderCallResult.Success(
                            ProviderPage(
                                items = listOf(ProviderChapterObservation("chapter-one", "Chapter 1")),
                                nextCursor = ProviderCursor("more"),
                            ),
                        )
                    } else {
                        neverCompletes.await()
                        error("unreachable")
                    }
                },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )

        val result = withTimeoutOrNull(21_000) { collector.execute(canonicalTitle.id) }
        (result != null) shouldBe true
        result!!.complete shouldBe false
        result.evidence shouldBe emptyList()
        result.snapshots shouldBe emptyList()
        result.partialEvidence.map { it.externalChapterKey } shouldBe listOf("chapter-one")
        result.partialEvidence.all { it.authority == ChapterEvidenceAuthority.PROVIDER_PROVISIONAL } shouldBe true
        val page = recorder.events.single { it.name == DiagnosticEventName.PROVIDER_READING_PAGE }
        page.stage shouldBe DiagnosticStage.READ
        page.outcome shouldBe DiagnosticOutcome.PARTIAL
        page.attributes["page_count"] shouldBe DiagnosticAttributeValue.Number(1)
        page.attributes["observation_count"] shouldBe DiagnosticAttributeValue.Number(1)
        val sanitized = StructuredDiagnosticSanitizer.sanitize(page)
        (sanitized != null) shouldBe true
        recorder.events.any {
            it.name == DiagnosticEventName.PROVIDER_READING_EVIDENCE &&
                it.stage == DiagnosticStage.READ && it.outcome == DiagnosticOutcome.TIMEOUT
        } shouldBe true
    }

    @Test
    fun `unique exact lookup creates binding and returns provider chapter evidence`(): Unit = runBlocking {
        val bindings = MemoryBindingRepository()
        var lookupCalls = 0
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    lookupCalls++
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate(
                                    externalWorkId = "dandadan-1",
                                    title = "Dandadan",
                                    aliases = listOf("Dan Da Dan"),
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderChapterObservation(
                                    providerChapterId = "chapter-1",
                                    rawLabel = "Chapter 1",
                                    rawNumber = 1.0,
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe true
        result.bindingCount shouldBe 1
        result.evidence.shouldHaveSize(1)
        result.evidence.single().canonicalTitleId shouldBe canonicalTitle.id
        result.evidence.single().externalChapterKey shouldBe "chapter-1"
        result.evidence.single().observedAt shouldBe 42L
        bindings.get(canonicalTitle.id, providerId, "en")?.ref?.externalWorkId shouldBe "dandadan-1"
        lookupCalls shouldBe 1
    }

    @Test
    fun `existing available binding is reused without lookup`(): Unit = runBlocking {
        val bindings = MemoryBindingRepository()
        bindings.upsert(
            ProviderReadingBinding(
                id = "binding-existing",
                canonicalTitleId = canonicalTitle.id,
                ref = ProviderBindingRef(providerId, "en", "dandadan-1"),
                verification = ProviderBindingVerification.EXACT,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        var lookupCalls = 0
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    lookupCalls++
                    error("existing binding must be reused")
                },
                chaptersHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderChapterObservation("chapter-2", "Chapter 2")),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 2L },
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe true
        result.bindingCount shouldBe 1
        result.evidence.single().externalChapterKey shouldBe "chapter-2"
        lookupCalls shouldBe 0
    }

    @Test
    fun `ambiguous exact lookup does not auto bind`(): Unit = runBlocking {
        val bindings = MemoryBindingRepository()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate("work-1", "Dandadan", language = "en"),
                                ProviderWorkCandidate("work-2", "DANDADAN", language = "en"),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("ambiguous lookup must not request chapters") },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 3L },
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe true
        result.bindingCount shouldBe 0
        result.evidence shouldBe emptyList()
        bindings.getByTitle(canonicalTitle.id) shouldBe emptyList()
    }

    @Test
    fun `lookup failure marks collection incomplete`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Failure(
                        ProviderError(
                            code = ProviderErrorCode.NETWORK_ERROR,
                            retryable = true,
                        ),
                    )
                },
                chaptersHandler = { error("failed lookup must not request chapters") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 4L },
            diagnostics = recorder,
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe false
        result.bindingCount shouldBe 0
        result.evidence shouldBe emptyList()
        val lookupFailure = recorder.events.single { it.stage == DiagnosticStage.LOOKUP }
        lookupFailure.attributes["provider_error_code"] shouldBe DiagnosticAttributeValue.Text("NETWORK_ERROR")
        val safe = StructuredDiagnosticSanitizer.sanitize(lookupFailure)
        (safe != null) shouldBe true
    }

    @Test
    fun `source-attested alias uses proven matching and safe counters`(): Unit = runBlocking {
        val title = canonicalTitle.copy(displayTitle = "Haikyuu!!")
        val recorder = CapturingProviderDiagnostics()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            listOf(
                                ProviderWorkCandidate(
                                    "haikyu-source-work",
                                    "Haikyu!!",
                                    aliases = listOf("Haikyuu!!"),
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            listOf(ProviderChapterObservation("chapter-one", "Chapter 1", rawNumber = 1.0)),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )
        val result = bridge.execute(title.id)
        result.complete shouldBe true
        result.bindingCount shouldBe 1
        result.evidence.shouldHaveSize(1)
        val lookup = recorder.events.single { it.stage == DiagnosticStage.LOOKUP }
        lookup.attributes["provider_id"] shouldBe DiagnosticAttributeValue.Text("app.tsuzuki.mangafire")
        lookup.attributes["candidate_count"] shouldBe DiagnosticAttributeValue.Number(1)
        lookup.attributes["accepted_count"] shouldBe DiagnosticAttributeValue.Number(1)
        val chapters = recorder.events.single {
            it.name == DiagnosticEventName.PROVIDER_READING_EVIDENCE &&
                it.stage == DiagnosticStage.READ
        }
        chapters.attributes["observation_count"] shouldBe DiagnosticAttributeValue.Number(1)
        chapters.attributes["chapter_count"] shouldBe DiagnosticAttributeValue.Number(1)
        val dump = recorder.events.joinToString()
        (dump.contains("Haikyuu") || dump.contains("haikyu-source-work")) shouldBe false
        recorder.events.all {
            it.name == DiagnosticEventName.PROVIDER_READING_EVIDENCE ||
                it.name == DiagnosticEventName.PROVIDER_READING_PAGE
        } shouldBe true
    }

    @Test
    fun `empty successful chapter inventory remains complete and visibly zero for that provider`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            listOf(ProviderWorkCandidate("dandadan-1", "Dandadan", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = {
                    ProviderCallResult.Success(ProviderPage(emptyList(), nextCursor = null))
                },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )

        val result = bridge.execute(canonicalTitle.id)
        result.complete shouldBe true
        result.bindingCount shouldBe 1
        result.evidence shouldBe emptyList()
        result.snapshots.size shouldBe 1

        val lookup = recorder.events.single { it.stage == DiagnosticStage.LOOKUP }
        lookup.attributes["candidate_count"] shouldBe DiagnosticAttributeValue.Number(1)
        lookup.attributes["binding_count"] shouldBe DiagnosticAttributeValue.Number(1)
        val chapters = recorder.events.single {
            it.name == DiagnosticEventName.PROVIDER_READING_EVIDENCE &&
                it.stage == DiagnosticStage.READ
        }
        chapters.outcome shouldBe DiagnosticOutcome.EMPTY
        chapters.attributes["observation_count"] shouldBe DiagnosticAttributeValue.Number(0)
        chapters.attributes["chapter_count"] shouldBe DiagnosticAttributeValue.Number(0)
        (StructuredDiagnosticSanitizer.sanitize(chapters) != null) shouldBe true
    }

    @Test
    fun `complete provider collection publishes an aggregate summary with accepted count`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            listOf(ProviderWorkCandidate("work-1", "Dandadan", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            listOf(ProviderChapterObservation("chapter-1", "Chapter 1", rawNumber = 1.0)),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe true
        val summary = recorder.events.single {
            it.stage == DiagnosticStage.SUMMARY && it.outcome != DiagnosticOutcome.STARTED
        }
        summary.outcome shouldBe DiagnosticOutcome.SUCCEEDED
        summary.attributes["target_source_count"] shouldBe DiagnosticAttributeValue.Number(1)
        summary.attributes["binding_count"] shouldBe DiagnosticAttributeValue.Number(1)
        summary.attributes["chapter_count"] shouldBe DiagnosticAttributeValue.Number(1)
        summary.attributes["completed"] shouldBe DiagnosticAttributeValue.Flag(true)
        val safe = StructuredDiagnosticSanitizer.sanitize(summary)
        safe?.attributes?.size shouldBe 4
    }

    @Test
    fun `failed lookup publishes an incomplete aggregate summary with zero chapters`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Failure(ProviderError(ProviderErrorCode.NETWORK_ERROR, retryable = true))
                },
                chaptersHandler = { error("Failed lookup must not request chapters") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )
        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe false
        val summary = recorder.events.single {
            it.stage == DiagnosticStage.SUMMARY && it.outcome != DiagnosticOutcome.STARTED
        }
        summary.outcome shouldBe DiagnosticOutcome.PARTIAL
        summary.attributes["target_source_count"] shouldBe DiagnosticAttributeValue.Number(1)
        summary.attributes["binding_count"] shouldBe DiagnosticAttributeValue.Number(0)
        summary.attributes["chapter_count"] shouldBe DiagnosticAttributeValue.Number(0)
        summary.attributes["completed"] shouldBe DiagnosticAttributeValue.Flag(false)
        (StructuredDiagnosticSanitizer.sanitize(summary) != null) shouldBe true
    }

    @Test
    fun `no enabled provider reports complete empty aggregate without network`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = DefaultProviderRegistry(registrations = { emptyList() }),
            gateway = gateway(
                lookupHandler = { error("Disabled inventory must not search") },
                chaptersHandler = { error("Disabled inventory must not request chapters") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )
        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe true
        result.evidence shouldBe emptyList()
        val summary = recorder.events.single {
            it.stage == DiagnosticStage.SUMMARY && it.outcome != DiagnosticOutcome.STARTED
        }
        summary.outcome shouldBe DiagnosticOutcome.EMPTY
        summary.attributes["target_source_count"] shouldBe DiagnosticAttributeValue.Number(0)
        summary.attributes["binding_count"] shouldBe DiagnosticAttributeValue.Number(0)
        summary.attributes["chapter_count"] shouldBe DiagnosticAttributeValue.Number(0)
        summary.attributes["completed"] shouldBe DiagnosticAttributeValue.Flag(true)
        (StructuredDiagnosticSanitizer.sanitize(summary) != null) shouldBe true
    }

    @Test
    fun `two source alias hits remain ambiguous and no chapters are fetched`(): Unit = runBlocking {
        val title = canonicalTitle.copy(displayTitle = "Haikyuu!!")
        val recorder = CapturingProviderDiagnostics()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            listOf(
                                ProviderWorkCandidate("work-a", "Haikyu!!", aliases = listOf("Haikyuu!!")),
                                ProviderWorkCandidate("work-b", "Haikyu!!", aliases = listOf("Haikyuu!!")),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("ambiguous aliases cannot authorize chapters") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )
        val result = bridge.execute(title.id)
        result.bindingCount shouldBe 0
        result.evidence shouldBe emptyList()
        val lookup = recorder.events.single { it.stage == DiagnosticStage.LOOKUP }
        lookup.attributes["candidate_count"] shouldBe DiagnosticAttributeValue.Number(2)
        lookup.attributes["accepted_count"] shouldBe DiagnosticAttributeValue.Number(2)
        lookup.attributes["binding_count"] shouldBe DiagnosticAttributeValue.Number(0)
    }

    @Test
    fun `malformed follow-up page cannot salvage partial Provider inventory`() = runBlocking {
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderWorkCandidate("work-1", "Dandadan", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { request ->
                    if (request.cursor == null) {
                        ProviderCallResult.Success(
                            ProviderPage(
                                items = listOf(ProviderChapterObservation("chapter-one", "Chapter 1")),
                                nextCursor = ProviderCursor("2"),
                            ),
                        )
                    } else {
                        ProviderCallResult.Failure(
                            ProviderError(code = ProviderErrorCode.MALFORMED_RESULT, retryable = false),
                        )
                    }
                },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )
        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe false
        result.partialEvidence shouldBe emptyList()
        result.snapshots shouldBe emptyList()
    }

    @Test
    fun `edition picker shows both exact matches but persists only the user confirmed work`(): Unit = runBlocking {
        val bindings = MemoryBindingRepository()
        val picker = SelectProviderReadingEdition(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate(
                                    "manga-standard",
                                    "Dandadan",
                                    url = "https://mangafire.to/title/standard-dandadan",
                                    language = "en",
                                ),
                                ProviderWorkCandidate(
                                    "manga-colored",
                                    "Dandadan",
                                    url = "https://mangafire.to/title/colored-dandadan",
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("Choosing an edition must not fetch chapters") },
            ),
            bindingRepository = bindings,
        )

        val results = picker.search(canonicalTitle.id, "Dandadan")

        results.candidates.map { it.ref.externalWorkId } shouldBe listOf("manga-standard", "manga-colored")
        bindings.getByTitle(canonicalTitle.id) shouldBe emptyList()
        picker.confirm(canonicalTitle.id, results.candidates[1]) shouldBe ProviderEditionConfirmation.BOUND
        bindings.get(canonicalTitle.id, providerId, "en")?.let { binding ->
            binding.ref.externalWorkId shouldBe "manga-colored"
            binding.verification shouldBe ProviderBindingVerification.USER_CONFIRMED
        }
        bindings.getByTitle(canonicalTitle.id).shouldHaveSize(1)
    }

    @Test
    fun `edition confirmation refuses stale candidate not returned by a fresh lookup`(): Unit = runBlocking {
        val bindings = MemoryBindingRepository()
        val picker = SelectProviderReadingEdition(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderWorkCandidate("real", "Dandadan", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("not used") },
            ),
            bindingRepository = bindings,
        )
        val candidate = picker.search(canonicalTitle.id, "Dandadan").candidates.single()
        picker.confirm(canonicalTitle.id, candidate.copy(ref = candidate.ref.copy(externalWorkId = "forged"))) shouldBe
            ProviderEditionConfirmation.STALE
        bindings.getByTitle(canonicalTitle.id) shouldBe emptyList()
    }

    @Test
    fun `edition confirmation never silently replaces an existing readable work binding`(): Unit = runBlocking {
        val bindings = MemoryBindingRepository()
        val old = ProviderReadingBinding(
            id = "existing-reading",
            canonicalTitleId = canonicalTitle.id,
            ref = ProviderBindingRef(providerId, "en", "manga-standard"),
            verification = ProviderBindingVerification.EXACT,
            availability = ProviderBindingAvailability.AVAILABLE,
            createdAt = 1L,
            updatedAt = 1L,
        )
        bindings.upsert(old)
        val picker = SelectProviderReadingEdition(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderWorkCandidate("manga-colored", "Dandadan", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("not used") },
            ),
            bindingRepository = bindings,
        )

        val chosen = picker.search(canonicalTitle.id, "Dandadan").candidates.single()
        picker.confirm(canonicalTitle.id, chosen) shouldBe ProviderEditionConfirmation.BLOCKED
        bindings.getByTitle(canonicalTitle.id) shouldBe listOf(old)
    }

    @Test
    fun `edition confirmation revalidates only the chosen Provider instead of unrelated sources`(): Unit = runBlocking {
        val mangaFire = registry().providers().single()
        val unrelatedId = ProviderId("app.tsuzuki.unrelated")
        val unrelated = mangaFire.copy(
            descriptor = mangaFire.descriptor.copy(id = unrelatedId, name = "Unrelated source"),
            facets = listOf(ProviderFacetRef(unrelatedId, "en")),
        )
        val activeProviders = DefaultProviderRegistry(registrations = { listOf(mangaFire, unrelated) })
        val lookups = mutableListOf<ProviderId>()
        val editionGateway = object : ProviderReadingGateway {
            override suspend fun lookup(
                providerId: ProviderId,
                request: ProviderReadingLookupRequest,
            ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>> {
                lookups += providerId
                return ProviderCallResult.Success(
                    ProviderPage(
                        items = if (providerId == this@CollectProviderReadingEvidenceTest.providerId) {
                            listOf(ProviderWorkCandidate("manga-standard", "Dandadan", language = "en"))
                        } else {
                            emptyList()
                        },
                        nextCursor = null,
                    ),
                )
            }

            override suspend fun chapters(
                providerId: ProviderId,
                request: ProviderReadingChaptersRequest,
            ): ProviderCallResult<ProviderPage<ProviderChapterObservation>> = error("not used")

            override suspend fun pages(
                providerId: ProviderId,
                request: ProviderReadingPagesRequest,
            ): ProviderCallResult<ProviderReadingDelivery> = error("not used")
        }
        val picker = SelectProviderReadingEdition(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = activeProviders,
            gateway = editionGateway,
            bindingRepository = MemoryBindingRepository(),
        )
        val selected = picker.search(canonicalTitle.id, "Dandadan").candidates.single()
        lookups.clear()

        picker.confirm(canonicalTitle.id, selected) shouldBe ProviderEditionConfirmation.BOUND
        lookups shouldBe listOf(providerId)
    }

    // Cross-module regression: the persisted slot is reserved by only one edition.
    @Test
    fun `manual edition selection loses a concurrent binding reservation without overwriting it`() = runBlocking<Unit> {
        val bindings = MemoryBindingRepository()
        bindings.onBeforeConditionalInsert = { attempted ->
            attempted.copy(
                id = "auto-work",
                ref = attempted.ref.copy(externalWorkId = "manga-standard"),
                verification = ProviderBindingVerification.EXACT,
            )
        }
        val picker = SelectProviderReadingEdition(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderWorkCandidate("manga-colored", "Dandadan", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("Edition selection must not fetch chapters") },
            ),
            bindingRepository = bindings,
        )

        val chosen = picker.search(canonicalTitle.id, "Dandadan").candidates.single()
        picker.confirm(canonicalTitle.id, chosen) shouldBe ProviderEditionConfirmation.BLOCKED
        bindings.getByTitle(canonicalTitle.id).map { it.ref.externalWorkId } shouldBe listOf("manga-standard")
    }

    @Test
    fun `background discovery cannot replace a concurrent user confirmed edition`() = runBlocking<Unit> {
        val bindings = MemoryBindingRepository()
        bindings.onBeforeConditionalInsert = { attempted ->
            attempted.copy(
                id = "confirmed-work",
                ref = attempted.ref.copy(externalWorkId = "manga-colored"),
                verification = ProviderBindingVerification.USER_CONFIRMED,
            )
        }
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderWorkCandidate("manga-standard", "Dandadan", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("A losing discovery must not read an unconfirmed edition") },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )

        val result = bridge.execute(canonicalTitle.id)
        result.bindingCount shouldBe 0
        result.evidence shouldBe emptyList()
        bindings.getByTitle(canonicalTitle.id).map { it.ref.externalWorkId } shouldBe listOf("manga-colored")
    }

    private class CapturingProviderDiagnostics : StructuredDiagnosticRecorder {
        override val sessionId: String = "00000000-0000-0000-0000-000000000000"
        val events = mutableListOf<StructuredDiagnosticEvent>()
        override fun canonicalTitleReference(canonicalTitleId: String): String? = null
        override fun mihonMangaReference(mihonMangaId: Long): String? = null
        override fun record(event: StructuredDiagnosticEvent) {
            events += event
        }
    }

    private fun registry() = DefaultProviderRegistry(
        registrations = {
            listOf(
                ProviderRegistration(
                    descriptor = ProviderDescriptor(
                        id = providerId,
                        name = "MangaFire",
                        version = ProviderVersion("0.1.1", 2),
                        origin = ProviderOrigin.Repository("app.tsuzuki.providers"),
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
                    facets = listOf(ProviderFacetRef(providerId, "en")),
                ),
            )
        },
    )

    private fun gateway(
        lookupHandler: suspend (
            ProviderReadingLookupRequest,
        ) -> ProviderCallResult<ProviderPage<ProviderWorkCandidate>>,
        chaptersHandler: suspend (
            ProviderReadingChaptersRequest,
        ) -> ProviderCallResult<ProviderPage<ProviderChapterObservation>>,
    ) = object : ProviderReadingGateway {
        override suspend fun lookup(
            providerId: ProviderId,
            request: ProviderReadingLookupRequest,
        ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>> {
            providerId shouldBe this@CollectProviderReadingEvidenceTest.providerId
            return lookupHandler(request)
        }

        override suspend fun chapters(
            providerId: ProviderId,
            request: ProviderReadingChaptersRequest,
        ): ProviderCallResult<ProviderPage<ProviderChapterObservation>> {
            providerId shouldBe this@CollectProviderReadingEvidenceTest.providerId
            return chaptersHandler(request)
        }

        override suspend fun pages(
            providerId: ProviderId,
            request: ProviderReadingPagesRequest,
        ): ProviderCallResult<ProviderReadingDelivery> = error("not used")
    }

    private fun titleRepository(title: CanonicalTitle) = object : CanonicalTitleRepository {
        override suspend fun getById(id: String): CanonicalTitle? = title.takeIf { it.id == id }
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flowOf(title.takeIf { it.id == id })
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = error("not used")
        override suspend fun insert(title: CanonicalTitle) = error("not used")
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = error("not used")
    }

    private class MemoryBindingRepository : ProviderReadingBindingRepository {
        private val values = linkedMapOf<String, ProviderReadingBinding>()
        var onBeforeConditionalInsert: ((ProviderReadingBinding) -> ProviderReadingBinding?)? = null

        override suspend fun get(
            canonicalTitleId: String,
            providerId: ProviderId,
            facetId: String?,
        ): ProviderReadingBinding? = values.values.firstOrNull {
            it.canonicalTitleId == canonicalTitleId &&
                it.ref.providerId == providerId &&
                it.ref.facetId == facetId
        }

        override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> =
            values.values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun insertIfAbsent(binding: ProviderReadingBinding): Boolean {
            onBeforeConditionalInsert?.invoke(binding)?.let { upsert(it) }
            if (get(binding.canonicalTitleId, binding.ref.providerId, binding.ref.facetId) != null) return false
            upsert(binding)
            return true
        }

        override suspend fun upsert(binding: ProviderReadingBinding) {
            values[binding.id] = binding
        }

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            values[bindingId]?.let { existing ->
                values[bindingId] = existing.copy(
                    availability = ProviderBindingAvailability.UNAVAILABLE,
                    updatedAt = updatedAt,
                )
            }
        }
    }
}
