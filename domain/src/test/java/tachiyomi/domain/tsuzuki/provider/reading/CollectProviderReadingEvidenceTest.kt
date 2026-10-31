package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderLookupDecision
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.TitleNameObservation
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
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository

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
    fun `detailed capture records bounded MangaFire page sample and chapter number range`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics(captureActive = true)
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderWorkCandidate("main", "Dandadan", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderChapterObservation("1595", "Chapter 1.5", rawNumber = 1.5),
                                ProviderChapterObservation("1598", "Chapter 2", rawNumber = 2.0),
                            ),
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
        val result = collector.execute(canonicalTitle.id)

        result.evidence.shouldHaveSize(2)
        val attempts = recorder.events.filter { it.name == DiagnosticEventName.PROVIDER_READING_CHAPTER_ATTEMPT }
        attempts.shouldHaveSize(2)
        attempts[0].outcome shouldBe DiagnosticOutcome.STARTED
        attempts[1].outcome shouldBe DiagnosticOutcome.SUCCEEDED
        attempts[0].attributes["page_index"] shouldBe DiagnosticAttributeValue.Number(1)
        attempts[1].attributes["item_count"] shouldBe DiagnosticAttributeValue.Number(2)
        val page = recorder.events.single { it.name == DiagnosticEventName.PROVIDER_READING_PAGE_DETAIL }
        page.attributes["page_index"] shouldBe DiagnosticAttributeValue.Number(1)
        page.attributes["item_count"] shouldBe DiagnosticAttributeValue.Number(2)
        page.attributes["chapter_number_min_milli"] shouldBe DiagnosticAttributeValue.Number(1500)
        page.attributes["chapter_number_max_milli"] shouldBe DiagnosticAttributeValue.Number(2000)
        page.attributes["provider_chapter_first_id"] shouldBe DiagnosticAttributeValue.Text("1595")
        page.attributes["provider_chapter_last_id"] shouldBe DiagnosticAttributeValue.Text("1598")
        (StructuredDiagnosticSanitizer.sanitize(page) != null) shouldBe true
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
    fun `automatic lookup tries active integration English title when canonical romaji has no exact match`(): Unit =
        runBlocking {
            val nanatsu = canonicalTitle.copy(displayTitle = "Nanatsu no Taizai")
            val names = object : TitleNameObservationRepository {
                override suspend fun getByTitle(canonicalTitleId: String): List<TitleNameObservation> = listOf(
                    TitleNameObservation(
                        canonicalTitleId = nanatsu.id,
                        provider = "mal",
                        sourceKey = "english",
                        value = "The Seven Deadly Sins",
                        updatedAt = 42L,
                    ),
                )
                override suspend fun upsert(observation: TitleNameObservation) = error("not used")
            }
            val integrations = mockk<IntegrationRegistry>(relaxed = true)
            every {
                integrations.isGlobalCapabilityActive(IntegrationId("mal"), IntegrationCapability.METADATA_BASIC)
            } returns true
            val queries = mutableListOf<String>()
            val bindings = MemoryBindingRepository()
            val collector = CollectProviderReadingEvidence(
                canonicalTitleRepository = titleRepository(nanatsu),
                providerRegistry = registry(),
                gateway = gateway(
                    lookupHandler = { request ->
                        val query = request.titles.single()
                        queries += query
                        ProviderCallResult.Success(
                            ProviderPage(
                                items = if (query == "The Seven Deadly Sins") {
                                    listOf(ProviderWorkCandidate("seven-deadly-sins", query, language = "en"))
                                } else {
                                    emptyList()
                                },
                                nextCursor = null,
                            ),
                        )
                    },
                    chaptersHandler = {
                        ProviderCallResult.Success(
                            ProviderPage(
                                items = listOf(ProviderChapterObservation("source-chapter-one", "Ch. 1")),
                                nextCursor = null,
                            ),
                        )
                    },
                ),
                bindingRepository = bindings,
                evidenceAdapter = ProviderChapterEvidenceAdapter(),
                clock = { 42L },
                titleNameObservationRepository = names,
                integrationRegistry = integrations,
            )
            val result = collector.execute(nanatsu.id)

            queries shouldBe listOf("Nanatsu no Taizai", "The Seven Deadly Sins")
            result.evidence.map { it.externalChapterKey } shouldBe listOf("source-chapter-one")
            bindings.getByTitle(nanatsu.id).single().ref.externalWorkId shouldBe "seven-deadly-sins"
            bindings.getByTitle(nanatsu.id).single().verification shouldBe ProviderBindingVerification.EXACT

            every {
                integrations.isGlobalCapabilityActive(IntegrationId("mal"), IntegrationCapability.METADATA_BASIC)
            } returns false
            val revokedQueries = mutableListOf<String>()
            val revokedCollector = CollectProviderReadingEvidence(
                canonicalTitleRepository = titleRepository(nanatsu),
                providerRegistry = registry(),
                gateway = gateway(
                    lookupHandler = { request ->
                        revokedQueries += request.titles.single()
                        ProviderCallResult.Success(ProviderPage(emptyList(), nextCursor = null))
                    },
                    chaptersHandler = { error("No binding must be created without consented aliases") },
                ),
                bindingRepository = MemoryBindingRepository(),
                evidenceAdapter = ProviderChapterEvidenceAdapter(),
                clock = { 43L },
                titleNameObservationRepository = names,
                integrationRegistry = integrations,
            )
            revokedCollector.execute(nanatsu.id).evidence shouldBe emptyList()
            revokedQueries shouldBe listOf("Nanatsu no Taizai")
        }

    @Test
    fun `existing available binding is reused without lookup`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics(captureActive = true)
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
            diagnostics = recorder,
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe true
        result.bindingCount shouldBe 1
        result.evidence.single().externalChapterKey shouldBe "chapter-2"
        lookupCalls shouldBe 0
        val reused = recorder.events.single { it.name == DiagnosticEventName.PROVIDER_READING_BINDING }
        reused.outcome shouldBe DiagnosticOutcome.REUSED
        reused.attributes["provider_work_id"] shouldBe DiagnosticAttributeValue.Text("dandadan-1")
        (StructuredDiagnosticSanitizer.sanitize(reused) != null) shouldBe true
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

        result.complete shouldBe false
        result.bindingCount shouldBe 0
        result.evidence shouldBe emptyList()
        bindings.getByTitle(canonicalTitle.id) shouldBe emptyList()
    }

    @Test
    fun `no source title match remains a fully checked empty lookup`(): Unit = runBlocking {
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderWorkCandidate("unrelated", "Naruto", language = "en")),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("No binding must fetch no chapters") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )

        val result = collector.execute(canonicalTitle.id)
        result.complete shouldBe true
        result.bindingCount shouldBe 0
        result.evidence shouldBe emptyList()
    }

    @Test
    fun `ambiguous lookup logs query and decision`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics(captureActive = true)
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate("work-1", "Dandadan", language = "en"),
                                ProviderWorkCandidate("work-2", "Dandadan", language = "en"),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("Ambiguous lookup must not fetch chapters") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )
        val result = collector.execute(canonicalTitle.id)

        result.complete shouldBe false
        val candidates = recorder.events.filter { it.name == DiagnosticEventName.PROVIDER_READING_CANDIDATE }
        candidates.shouldHaveSize(2)
        candidates[0].attributes["provider_lookup_title"] shouldBe DiagnosticAttributeValue.Text("Dandadan")
        val rejected = recorder.events.last {
            it.name == DiagnosticEventName.PROVIDER_READING_EVIDENCE &&
                it.stage == DiagnosticStage.LOOKUP && it.outcome == DiagnosticOutcome.REJECTED
        }
        rejected.attributes["lookup_decision"] shouldBe DiagnosticAttributeValue.Code(
            DiagnosticProviderLookupDecision.MULTIPLE_PRIMARY_IDENTITIES,
        )
    }

    @Test
    fun `unique primary title beats secondary colored alias without choosing a random work`(): Unit = runBlocking {
        val title = canonicalTitle.copy(displayTitle = "Vagabond")
        val bindings = MemoryBindingRepository()
        val requests = mutableListOf<String>()
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = { request ->
                    requests += request.titles.single()
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate("main", "Vagabond", language = "en"),
                                ProviderWorkCandidate(
                                    "colored",
                                    "Vagabond (Colored)",
                                    aliases = listOf("Vagabond"),
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { request ->
                    request.binding.externalWorkId shouldBe "main"
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderChapterObservation("main-chapter-1", "Ch. 1", rawNumber = 1.0)),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )

        val collected = collector.execute(title.id)

        requests shouldBe listOf("Vagabond")
        collected.evidence.map { it.externalChapterKey } shouldBe listOf("main-chapter-1")
        bindings.getByTitle(title.id).single().ref.externalWorkId shouldBe "main"
        bindings.getByTitle(title.id).single().verification shouldBe ProviderBindingVerification.EXACT
    }

    @Test
    fun `source attested colored alias distinguishes an otherwise identical work title`(): Unit = runBlocking {
        val title = canonicalTitle.copy(displayTitle = "Vagabond")
        val bindings = MemoryBindingRepository()
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate(
                                    externalWorkId = "colored",
                                    title = "Vagabond",
                                    aliases = listOf("Vagabond (Official Colored)"),
                                    language = "en",
                                ),
                                ProviderWorkCandidate("regular", "Vagabond", language = "en"),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { request ->
                    request.binding.externalWorkId shouldBe "regular"
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderChapterObservation("ch-1", "Ch. 1", rawNumber = 1.0)),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )
        val result = collector.execute(title.id)
        result.complete shouldBe true
        result.evidence.shouldHaveSize(1)
        bindings.getByTitle(title.id).single().ref.externalWorkId shouldBe "regular"
    }

    @Test
    fun `sole source candidate explicitly colored cannot stand in for unqualified canonical edition`(): Unit =
        runBlocking {
            val title = canonicalTitle.copy(displayTitle = "Vagabond")
            val bindings = MemoryBindingRepository()
            val collector = CollectProviderReadingEvidence(
                canonicalTitleRepository = titleRepository(title),
                providerRegistry = registry(),
                gateway = gateway(
                    lookupHandler = {
                        ProviderCallResult.Success(
                            ProviderPage(
                                items = listOf(
                                    ProviderWorkCandidate(
                                        "only-colored",
                                        "Vagabond",
                                        aliases = listOf("Vagabond (Official Colored)"),
                                        language = "en",
                                    ),
                                ),
                                nextCursor = null,
                            ),
                        )
                    },
                    chaptersHandler = { error("A colored-only source must not create an unqualified binding") },
                ),
                bindingRepository = bindings,
                evidenceAdapter = ProviderChapterEvidenceAdapter(),
                clock = { 42L },
            )
            val result = collector.execute(title.id)
            result.complete shouldBe false
            result.bindingCount shouldBe 0
            bindings.getByTitle(title.id) shouldBe emptyList()
        }

    @Test
    fun `colored alias cannot disambiguate more than one unqualified primary`(): Unit = runBlocking {
        val title = canonicalTitle.copy(displayTitle = "Bleach")
        val bindings = MemoryBindingRepository()
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate("colored", "Bleach", aliases = listOf("Bleach (Colored)")),
                                ProviderWorkCandidate("unmarked-a", "Bleach"),
                                ProviderWorkCandidate("unmarked-b", "Bleach"),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("Ambiguous source must not fetch chapters") },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )
        val result = collector.execute(title.id)
        result.complete shouldBe false
        result.bindingCount shouldBe 0
        bindings.getByTitle(title.id) shouldBe emptyList()
    }

    @Test
    fun `alternative consented title narrows ambiguous aliases only to a previously seen work`(): Unit = runBlocking {
        val title = canonicalTitle.copy(displayTitle = "Nanatsu no Taizai")
        val observations = object : TitleNameObservationRepository {
            override suspend fun getByTitle(canonicalTitleId: String): List<TitleNameObservation> = listOf(
                TitleNameObservation(
                    canonicalTitleId = title.id,
                    provider = "mal",
                    sourceKey = "english",
                    value = "The Seven Deadly Sins",
                    updatedAt = 42L,
                ),
            )
            override suspend fun upsert(observation: TitleNameObservation) = error("not used")
        }
        val integrations = mockk<IntegrationRegistry>(relaxed = true)
        every {
            integrations.isGlobalCapabilityActive(IntegrationId("mal"), IntegrationCapability.METADATA_BASIC)
        } returns true
        val lookups = mutableListOf<String>()
        val bindings = MemoryBindingRepository()
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = { request ->
                    val search = request.titles.single()
                    lookups += search
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate(
                                    "main",
                                    "The Seven Deadly Sins",
                                    aliases = listOf("Nanatsu no Taizai"),
                                    language = "en",
                                ),
                                ProviderWorkCandidate(
                                    "colored",
                                    "The Seven Deadly Sins (Colored)",
                                    aliases = listOf("Nanatsu no Taizai"),
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { request ->
                    request.binding.externalWorkId shouldBe "main"
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderChapterObservation("main-1", "Ch. 1", rawNumber = 1.0)),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            titleNameObservationRepository = observations,
            integrationRegistry = integrations,
        )

        val collected = collector.execute(title.id)

        lookups shouldBe listOf("Nanatsu no Taizai", "The Seven Deadly Sins")
        collected.evidence.map { it.externalChapterKey } shouldBe listOf("main-1")
        bindings.getByTitle(title.id).single().ref.externalWorkId shouldBe "main"
    }

    @Test
    fun `alias search must not select an unrelated work outside the original ambiguous set`(): Unit = runBlocking {
        val title = canonicalTitle.copy(displayTitle = "Nanatsu no Taizai")
        val names = object : TitleNameObservationRepository {
            override suspend fun getByTitle(canonicalTitleId: String): List<TitleNameObservation> = listOf(
                TitleNameObservation(title.id, "mal", "english", "The Seven Deadly Sins", 42L),
            )
            override suspend fun upsert(observation: TitleNameObservation) = error("not used")
        }
        val integrations = mockk<IntegrationRegistry>(relaxed = true)
        every {
            integrations.isGlobalCapabilityActive(IntegrationId("mal"), IntegrationCapability.METADATA_BASIC)
        } returns true
        val bindings = MemoryBindingRepository()
        val queries = mutableListOf<String>()
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = { request ->
                    val query = request.titles.single()
                    queries += query
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = if (query == "Nanatsu no Taizai") {
                                listOf(
                                    ProviderWorkCandidate(
                                        "existing-a",
                                        "An Edition",
                                        aliases = listOf(query),
                                        language = "en",
                                    ),
                                    ProviderWorkCandidate(
                                        "existing-b",
                                        "Another Edition",
                                        aliases = listOf(query),
                                        language = "en",
                                    ),
                                )
                            } else {
                                listOf(ProviderWorkCandidate("foreign-id", query, language = "en"))
                            },
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("Unrelated alternate work may not load chapters") },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            titleNameObservationRepository = names,
            integrationRegistry = integrations,
        )

        val result = collector.execute(title.id)

        queries shouldBe listOf("Nanatsu no Taizai", "The Seven Deadly Sins")
        result.bindingCount shouldBe 0
        result.evidence shouldBe emptyList()
        bindings.getByTitle(title.id) shouldBe emptyList()
    }

    @Test
    fun `unique primary title must not override another unmarked exact alias edition`(): Unit = runBlocking {
        val bindings = MemoryBindingRepository()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate("main", "Dandadan", language = "en"),
                                ProviderWorkCandidate(
                                    "unclear-edition",
                                    "Dandadan II",
                                    aliases = listOf("Dandadan"),
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("An unmarked competing edition must not be auto-bound") },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )

        val result = bridge.execute(canonicalTitle.id)

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

    @Test
    fun `edition picker includes recorded English alias but keeps binding explicitly user confirmed`() = runBlocking {
        val nanatsu = canonicalTitle.copy(displayTitle = "Nanatsu no Taizai")
        val requestedNames = mutableListOf<String>()
        val names = object : TitleNameObservationRepository {
            override suspend fun getByTitle(canonicalTitleId: String): List<TitleNameObservation> = listOf(
                TitleNameObservation(
                    canonicalTitleId = nanatsu.id,
                    provider = "mal",
                    sourceKey = "english",
                    value = "The Seven Deadly Sins",
                    updatedAt = 42L,
                ),
            )
            override suspend fun upsert(observation: TitleNameObservation) = error("not used")
        }
        val bindings = MemoryBindingRepository()
        val integrations = mockk<IntegrationRegistry>(relaxed = true)
        every {
            integrations.isGlobalCapabilityActive(IntegrationId("mal"), IntegrationCapability.METADATA_BASIC)
        } returns true
        val picker = SelectProviderReadingEdition(
            canonicalTitleRepository = titleRepository(nanatsu),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = { request ->
                    val query = request.titles.single()
                    requestedNames += query
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate(
                                    externalWorkId = if (query ==
                                        "The Seven Deadly Sins"
                                    ) {
                                        "seven-deadly-sins"
                                    } else {
                                        "other"
                                    },
                                    title = query,
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("Search must not fetch chapters") },
            ),
            bindingRepository = bindings,
            titleNameObservationRepository = names,
            integrationRegistry = integrations,
        )

        val candidates = picker.search(nanatsu.id, "Nanatsu no Taizai").candidates
        requestedNames shouldBe listOf("Nanatsu no Taizai", "The Seven Deadly Sins")
        candidates.map { it.ref.externalWorkId } shouldBe listOf("other", "seven-deadly-sins")
        bindings.getByTitle(nanatsu.id) shouldBe emptyList()

        requestedNames.clear()
        picker.confirm(nanatsu.id, candidates.last()) shouldBe ProviderEditionConfirmation.BOUND
        requestedNames shouldBe listOf("The Seven Deadly Sins")
        bindings.getByTitle(nanatsu.id).single().ref.externalWorkId shouldBe "seven-deadly-sins"
        bindings.getByTitle(nanatsu.id).single().verification shouldBe ProviderBindingVerification.USER_CONFIRMED

        // Revoking the metadata integration prevents automatic reuse of its old
        // aliases, without blocking an explicitly typed user search.
        every {
            integrations.isGlobalCapabilityActive(IntegrationId("mal"), IntegrationCapability.METADATA_BASIC)
        } returns false
        requestedNames.clear()
        picker.search(nanatsu.id, "Nanatsu no Taizai")
        requestedNames shouldBe listOf("Nanatsu no Taizai")
        requestedNames.clear()
        picker.search(nanatsu.id, "The Seven Deadly Sins")
        requestedNames shouldBe listOf("The Seven Deadly Sins")
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

    @Test
    fun `lookup timeout is distinguished from a chapter read timeout`() = runTest {
        val recorder = CapturingProviderDiagnostics()
        val neverCompletes = CompletableDeferred<Unit>()
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    neverCompletes.await()
                    error("unreachable")
                },
                chaptersHandler = { error("Lookup never selected a binding") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )
        val result = withTimeoutOrNull(21_000) { collector.execute(canonicalTitle.id) }
        result!!.complete shouldBe false
        recorder.events.any {
            it.stage == DiagnosticStage.LOOKUP && it.outcome == DiagnosticOutcome.TIMEOUT
        } shouldBe true
        recorder.events.none {
            it.stage == DiagnosticStage.READ && it.outcome == DiagnosticOutcome.TIMEOUT
        } shouldBe true
    }

    @Test
    fun `detailed capture records real candidate identities and rejection reasons`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics(captureActive = true)
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate(
                                    externalWorkId = "original-123",
                                    title = "Dandadan",
                                    aliases = listOf("Dan Da Dan"),
                                    url = "https://mangafire.to/title/original-123-dandadan",
                                    language = "en",
                                    chapterCount = 151,
                                    detailOutcome = ProviderWorkDetailOutcome.VERIFIED_WITH_ALIASES,
                                ),
                                ProviderWorkCandidate(
                                    externalWorkId = "other-456",
                                    title = "Dandadan",
                                    url = "https://mangafire.to/title/other-456-dandadan",
                                    language = "en",
                                    detailOutcome = ProviderWorkDetailOutcome.DETAIL_FAILED,
                                    detailHttpStatus = 503,
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("Ambiguous work must not load chapters") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )

        val result = collector.execute(canonicalTitle.id)
        result.bindingCount shouldBe 0
        val candidates = recorder.events.filter { it.name == DiagnosticEventName.PROVIDER_READING_CANDIDATE }
        candidates.shouldHaveSize(2)
        val lookupSteps = recorder.events.filter {
            it.name == DiagnosticEventName.PROVIDER_READING_LOOKUP_ATTEMPT
        }
        lookupSteps.shouldHaveSize(2)
        lookupSteps[0].outcome shouldBe DiagnosticOutcome.STARTED
        lookupSteps[1].outcome shouldBe DiagnosticOutcome.SUCCEEDED
        lookupSteps[0].attributes["query_index"] shouldBe DiagnosticAttributeValue.Number(1)
        lookupSteps[0].attributes["provider_lookup_title"] shouldBe DiagnosticAttributeValue.Text("Dandadan")
        lookupSteps[1].attributes["candidate_count"] shouldBe DiagnosticAttributeValue.Number(2)
        candidates[0].attributes["provider_work_id"] shouldBe DiagnosticAttributeValue.Text("original-123")
        candidates[0].attributes["provider_work_title"] shouldBe DiagnosticAttributeValue.Text("Dandadan")
        candidates[0].attributes["language"] shouldBe DiagnosticAttributeValue.Text("en")
        candidates[0].attributes["provider_work_url"] shouldBe
            DiagnosticAttributeValue.Text("https://mangafire.to/title/original-123-dandadan")
        candidates[0].attributes["provider_work_aliases"] shouldBe DiagnosticAttributeValue.Text("Dan Da Dan")
        candidates[0].attributes["provider_work_chapter_count"] shouldBe DiagnosticAttributeValue.Number(151)
        candidates[0].attributes["query_index"] shouldBe DiagnosticAttributeValue.Number(1)
        candidates[0].attributes["provider_detail_outcome"] shouldBe DiagnosticAttributeValue.Code(
            DiagnosticProviderDetailOutcome.VERIFIED_WITH_ALIASES,
        )
        candidates[1].attributes["provider_detail_outcome"] shouldBe DiagnosticAttributeValue.Code(
            DiagnosticProviderDetailOutcome.DETAIL_FAILED,
        )
        candidates[1].attributes["http_status"] shouldBe DiagnosticAttributeValue.Number(503)
        candidates.all { StructuredDiagnosticSanitizer.sanitize(it) != null } shouldBe true
        candidates[1].attributes.containsKey("provider_work_chapter_count") shouldBe false
    }

    @Test
    fun `normal diagnostics never emit raw provider candidates`(): Unit = runBlocking {
        val recorder = CapturingProviderDiagnostics()
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate("a", "Dandadan", language = "en"),
                                ProviderWorkCandidate("b", "Dandadan", language = "en"),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("Ambiguous work must not load chapters") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
            diagnostics = recorder,
        )
        collector.execute(canonicalTitle.id)
        recorder.events.none { it.name == DiagnosticEventName.PROVIDER_READING_CANDIDATE } shouldBe true
        recorder.events.any { it.stage == DiagnosticStage.LOOKUP && it.outcome == DiagnosticOutcome.REJECTED } shouldBe
            true
    }

    private class CapturingProviderDiagnostics(
        private val captureActive: Boolean = false,
    ) : StructuredDiagnosticRecorder {
        override val sessionId: String = "00000000-0000-0000-0000-000000000000"
        override val detailedCaptureActive: Boolean get() = captureActive
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
