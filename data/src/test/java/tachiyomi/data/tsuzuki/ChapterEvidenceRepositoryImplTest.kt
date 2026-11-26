package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl
import tachiyomi.data.tsuzuki.chapter.IndependentInventoryRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceWrite
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ProviderEvidenceMetadata
import tachiyomi.domain.tsuzuki.chapter.evidence.ProviderEvidenceMetadataCodec
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaim
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaimKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryDerivation
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchOutcome
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files

class ChapterEvidenceRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var queryCounter: SqlDriverQueryCounter
    private lateinit var database: Database
    private lateinit var evidenceRepository: ChapterEvidenceRepositoryImpl
    private lateinit var chapterRepository: CanonicalChapterRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        ChapterEvidenceRepositoryImplTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
            .use { input -> Files.copy(input, nativeLibrary) }
        nativeLibrary.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeLibraryDirectory.toString())
        queryCounter = SqlDriverQueryCounter(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        driver = queryCounter.driver
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "title-1",
            displayTitle = "Title",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        evidenceRepository = ChapterEvidenceRepositoryImpl(database)
        chapterRepository = CanonicalChapterRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        if (originalNativeLibraryPath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", originalNativeLibraryPath)
        }
        nativeLibraryDirectory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `independent inventory survives failed empty and stale refreshes without rewriting chapter identities`() =
        runBlocking<Unit> {
            val ledger = IndependentInventoryRepositoryImpl(database)
            val scope = InventoryScope(
                canonicalTitleId = "title-1",
                producerId = "mangadex",
                externalWorkId = "verified-manga",
                editionKey = null,
                language = "pt-br",
                workIdentityVerified = true,
                editionIdentityVerified = false,
            )
            val observed = InventoryClaim(
                sourceRecordId = "release-1",
                label = "12.05",
                volumeLabel = "1",
                language = "pt-br",
                kind = InventoryClaimKind.INDIVIDUAL,
                derivation = InventoryDerivation.RELEASE_RECORD,
                upstreamSourceId = "mangadex",
                observedAt = 10L,
            )
            val first = InventoryEvidenceBatch(
                scope = scope,
                pageKey = "offset:0",
                observedAt = 10L,
                outcome = InventoryFetchOutcome.PARTIAL,
                claims = listOf(observed),
                nextCursor = "offset:100",
            )
            ledger.saveBatch(first)
            ledger.saveBatch(
                first.copy(
                    observedAt = 20L,
                    outcome = InventoryFetchOutcome.FAILED,
                    claims = emptyList(),
                    nextCursor = null,
                ),
            )
            ledger.saveBatch(
                first.copy(
                    observedAt = 5L,
                    claims = listOf(observed.copy(label = "stale", observedAt = 5L)),
                ),
            )

            val retained = ledger.byTitle("title-1").single()
            retained.claim.sourceRecordId shouldBe "release-1"
            retained.claim.label shouldBe "12.05"
            retained.claim.observedAt shouldBe 10L
            retained.scope.editionIdentityVerified shouldBe false
            retained.scope.language shouldBe "pt-br"
            ledger.batchesByTitle("title-1").single().outcome shouldBe InventoryFetchOutcome.FAILED
            chapterRepository.getByCanonicalTitleId("title-1").size shouldBe 0
        }

    @Test
    fun `batch refresh preserves evidence id metadata and remains idempotent`() = runBlocking<Unit> {
        chapterRepository.upsert(chapter("chapter-1", 1))
        val initial = addonEvidence(id = "stable-id", label = "Chapter 1", key = "source-1")
        evidenceRepository.upsert(initial, "chapter-1")
        driver.execute(
            null,
            "UPDATE tsuzuki_chapter_evidence SET raw_metadata = X'010203' WHERE id = 'stable-id'",
            0,
        ).await()

        val refresh = ChapterEvidenceWrite(
            evidence = initial.copy(id = "new-observation-id", rawLabel = "Chapter 1 revised", observedAt = 2L),
            mappedCanonicalChapterId = "chapter-1",
        )
        val firstRefresh = evidenceRepository.upsertBatch(listOf(refresh)).single()
        val secondRefresh = evidenceRepository.upsertBatch(
            listOf(refresh.copy(evidence = refresh.evidence.copy(id = "third-id"))),
        ).single()

        firstRefresh.evidence.id shouldBe "stable-id"
        secondRefresh.evidence.id shouldBe "stable-id"
        firstRefresh.evidence.rawLabel shouldBe "Chapter 1 revised"
        secondRefresh.rawMetadata.toList() shouldBe listOf(1.toByte(), 2.toByte(), 3.toByte())
        evidenceRepository.getByCanonicalTitleId("title-1") shouldHaveSize 1
    }

    @Test
    fun `provider variant metadata roundtrips through SQLite and updates without changing evidence identity`() =
        runBlocking<Unit> {
            val base = addonEvidence("provider-stable", "Chapter 1", "remote-chapter").copy(
                producerKind = ProducerKind.PROVIDER,
                producerId = "provider-binding:stable",
                authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
                providerMetadata = ProviderEvidenceMetadata(
                    language = "ja",
                    scanlationGroup = "Group A",
                    releaseDateMillis = 1_700_000_000_000L,
                ),
            )
            evidenceRepository.upsert(base, null)
            val stored = evidenceRepository.getByCanonicalTitleId("title-1").single()
            stored.evidence.providerMetadata shouldBe base.providerMetadata
            ProviderEvidenceMetadataCodec.decode(stored.rawMetadata) shouldBe base.providerMetadata

            evidenceRepository.upsert(
                base.copy(
                    id = "new-random-id",
                    observedAt = 2,
                    providerMetadata = ProviderEvidenceMetadata(
                        language = "en",
                        scanlationGroup = "Group B",
                        releaseDateMillis = null,
                    ),
                ),
                null,
            )
            val newer = evidenceRepository.getByCanonicalTitleId("title-1").single()
            newer.evidence.id shouldBe "provider-stable"
            newer.evidence.providerMetadata?.language shouldBe "en"
            newer.evidence.providerMetadata?.scanlationGroup shouldBe "Group B"
        }

    @Test
    fun `chapter catalog proof persists locally and is cleared by an unattested refresh`() = runBlocking<Unit> {
        val proof = "catalog-work-v1:" + "a".repeat(64)
        val base = addonEvidence("catalog-evidence", "Chapter 1", "remote-chapter").copy(
            producerKind = ProducerKind.PROVIDER,
            producerId = "provider-binding:catalog",
            authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
            providerMetadata = ProviderEvidenceMetadata(language = "en", catalogWorkFingerprint = proof),
        )
        evidenceRepository.upsert(base, null)
        val reopened = ChapterEvidenceRepositoryImpl(database)
        reopened.getByCanonicalTitleId("title-1").single().evidence.providerMetadata shouldBe base.providerMetadata
        val refreshed = base.copy(
            observedAt = 2L,
            providerMetadata = ProviderEvidenceMetadata(language = "en"),
        )
        reopened.upsert(refreshed, null)
        val stored = reopened.getByCanonicalTitleId("title-1").single()
        stored.evidence.id shouldBe base.id
        stored.evidence.providerMetadata shouldBe refreshed.providerMetadata
        ProviderEvidenceMetadataCodec.decode(stored.rawMetadata) shouldBe refreshed.providerMetadata
    }

    @Test
    fun `unknown provider raw metadata remains byte exact during refresh`() = runBlocking<Unit> {
        val base = addonEvidence("opaque-provider", "Chapter 1", "opaque").copy(
            producerKind = ProducerKind.PROVIDER,
            producerId = "provider-binding:opaque",
            authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
        )
        evidenceRepository.upsert(base, null)
        driver.execute(
            null,
            "UPDATE tsuzuki_chapter_evidence SET raw_metadata = X'010203' WHERE id = 'opaque-provider'",
            0,
        ).await()
        evidenceRepository.upsert(
            base.copy(
                observedAt = 2,
                providerMetadata = ProviderEvidenceMetadata(language = "pt-BR"),
            ),
            null,
        )
        val persisted = evidenceRepository.getByCanonicalTitleId("title-1").single()
        persisted.rawMetadata.toList() shouldBe listOf(1.toByte(), 2.toByte(), 3.toByte())
        persisted.evidence.providerMetadata shouldBe null
    }

    @Test
    fun `batch insert uses one title snapshot instead of one external key query per write`() = runBlocking<Unit> {
        val writes = (1..100).map { index ->
            ChapterEvidenceWrite(
                evidence = addonEvidence(
                    id = "batch-$index",
                    label = "Chapter $index",
                    key = "source-$index",
                ),
                mappedCanonicalChapterId = null,
            )
        }

        queryCounter.reset()
        evidenceRepository.upsertBatch(writes)

        queryCounter.executeQueryCount shouldBe 1
        evidenceRepository.getByCanonicalTitleId("title-1") shouldHaveSize 100
    }

    @Test
    fun `reconciliation reuses its title evidence snapshot during persistence`() = runBlocking<Unit> {
        val reconciler = ReconcileChapterEvidence(
            ParseCanonicalChapterLabel(),
            chapterRepository,
            evidenceRepository,
        )

        queryCounter.reset()
        reconciler.execute(
            "title-1",
            listOf(
                editorialEvidence(
                    id = "reconcile-1",
                    label = "Chapter 1",
                    key = "source-1",
                ),
            ),
        )

        // One canonical snapshot, one evidence snapshot and one batched exact-ID
        // preflight; no extra SELECT per chapter in the inventory.
        queryCounter.executeQueryCount shouldBe 3
        evidenceRepository.getByCanonicalTitleId("title-1") shouldHaveSize 1
    }

    @Test
    fun `reconciliation preflights exact identities once for a large chapter inventory`() = runBlocking<Unit> {
        val reconciler = ReconcileChapterEvidence(
            ParseCanonicalChapterLabel(),
            chapterRepository,
            evidenceRepository,
        )
        val observations = (1..40).map { number ->
            editorialEvidence(
                id = "large-$number",
                label = "Chapter $number",
                key = "large-$number",
            )
        }

        queryCounter.reset()
        reconciler.execute("title-1", observations)

        // Query count must not grow with inventory size.
        queryCounter.executeQueryCount shouldBe 3
        chapterRepository.getByCanonicalTitleId("title-1") shouldHaveSize 40
    }

    @Test
    fun `MangaAll provisional 4341 chapters safely resume through SQLite`() = runBlocking<Unit> {
        val reconciler = ReconcileChapterEvidence(
            ParseCanonicalChapterLabel(),
            chapterRepository,
            evidenceRepository,
        )
        val source = "app.tsuzuki.mangaall:en:bleach-source-hid"
        val observations = (1..4_341).map { number ->
            ChapterEvidence(
                id = "mangaall-evidence-$number",
                canonicalTitleId = "title-1",
                producerKind = ProducerKind.PROVIDER,
                producerId = source,
                externalChapterKey = "mangadot-chapter-$number",
                rawLabel = "Chapter $number",
                rawNumber = number.toDouble(),
                volume = null,
                title = null,
                observedAt = 10L,
                confidence = 0.95,
                authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
            )
        }

        // Simulate two accepted page groups separated by a lost invocation.
        // Neither batch is a complete source snapshot, so stale rows must not be detached.
        queryCounter.reset()
        val start = System.nanoTime()
        reconciler.execute("title-1", observations.take(2_048))
        reconciler.execute("title-1", observations.drop(2_048))
        val initialMs = (System.nanoTime() - start) / 1_000_000L
        val initialQueryCount = queryCounter.executeQueryCount
        val stored = evidenceRepository.getByCanonicalTitleId("title-1")
        val chapters = chapterRepository.getByCanonicalTitleId("title-1")
        stored shouldHaveSize 4_341
        chapters shouldHaveSize 4_341
        stored.mapNotNull { it.mappedCanonicalChapterId }.toSet() shouldBe chapters.map { it.id }.toSet()
        chapters.all { it.confirmation.name == "PROVISIONAL" } shouldBe true
        val originalEvidenceIds = stored.associate { it.evidence.externalChapterKey to it.evidence.id }
        val originalChapterIds = stored.associate { it.evidence.externalChapterKey to it.mappedCanonicalChapterId }

        // An unfinished cursor may replay the same validated pages with new transport IDs.
        // The source-scoped external keys, canonical chapters and user state must stay stable.
        val replayed = observations.take(2_048).map { observation ->
            observation.copy(id = "replay-${observation.id}", observedAt = 11L)
        }
        reconciler.execute("title-1", replayed)
        reconciler.execute("title-1", emptyList())
        val afterReplay = evidenceRepository.getByCanonicalTitleId("title-1")
        afterReplay shouldHaveSize 4_341
        afterReplay.associate { it.evidence.externalChapterKey to it.evidence.id } shouldBe originalEvidenceIds
        afterReplay.associate { it.evidence.externalChapterKey to it.mappedCanonicalChapterId } shouldBe
            originalChapterIds
        chapterRepository.getByCanonicalTitleId("title-1") shouldHaveSize 4_341
        println(
            "MANGAALL_SQLITE_CAPACITY|source_only=TRUE|partial_batches=2|" +
                "rows=4341|canonical_grant=NONE|replay_duplicate_rows=0|" +
                "first_ingest_ms=$initialMs|first_ingest_selects=$initialQueryCount",
        )
    }

    @Test
    fun `support snapshot returns unique mapped chapter ids without per evidence queries`() = runBlocking<Unit> {
        chapterRepository.upsert(chapter("chapter-1", 1))
        chapterRepository.upsert(chapter("chapter-2", 2))
        evidenceRepository.upsert(
            addonEvidence(id = "a-1", label = "Chapter 1", key = "a-1"),
            "chapter-1",
        )
        evidenceRepository.upsert(
            addonEvidence(id = "a-1-duplicate", label = "Chapter 1", key = "a-1-duplicate"),
            "chapter-1",
        )
        evidenceRepository.upsert(
            addonEvidence(id = "b-1", label = "Chapter 1", key = "b-1").copy(producerId = "addon-b"),
            "chapter-1",
        )
        evidenceRepository.upsert(
            editorialEvidence(id = "editorial-2", label = "Chapter 2", key = "editorial-2"),
            "chapter-2",
        )

        queryCounter.reset()
        val snapshot = evidenceRepository.getSupportSnapshot("title-1")

        queryCounter.executeQueryCount shouldBe 1
        snapshot.mappedCanonicalChapterIds shouldBe setOf("chapter-1", "chapter-2")
        snapshot.addonMappedChapterIds shouldBe mapOf(
            "addon" to setOf("chapter-1"),
            "addon-b" to setOf("chapter-1"),
        )
    }

    @Test
    fun `reconciliation rolls back chapter and evidence after injected write failure`() = runBlocking<Unit> {
        val failingEvidenceRepository = object : ChapterEvidenceRepository by evidenceRepository {
            override suspend fun upsertBatch(writes: List<ChapterEvidenceWrite>) =
                evidenceRepository.upsertBatch(writes.take(1)).also {
                    error("Injected failure after first evidence write")
                }

            override suspend fun upsertResolvedBatch(values: List<PersistedChapterEvidence>) =
                evidenceRepository.upsertResolvedBatch(values.take(1)).also {
                    error("Injected failure after first resolved evidence write")
                }
        }
        val reconciler = ReconcileChapterEvidence(
            ParseCanonicalChapterLabel(),
            chapterRepository,
            failingEvidenceRepository,
        )

        shouldThrow<IllegalStateException> {
            runBlocking {
                reconciler.execute(
                    "title-1",
                    listOf(
                        editorialEvidence(id = "first", label = "Chapter 1", key = "source-1"),
                        editorialEvidence(id = "second", label = "Chapter 2", key = "source-2"),
                    ),
                )
            }
        }

        chapterRepository.getByCanonicalTitleId("title-1") shouldHaveSize 0
        evidenceRepository.getByCanonicalTitleId("title-1") shouldHaveSize 0
    }

    private class SqlDriverQueryCounter(delegate: SqlDriver) {
        private var queryCount = 0

        val driver = Proxy.newProxyInstance(
            SqlDriver::class.java.classLoader,
            arrayOf(SqlDriver::class.java),
        ) { _, method, arguments ->
            if (method.name == "executeQuery") queryCount++
            try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        } as SqlDriver

        val executeQueryCount: Int
            get() = queryCount

        fun reset() {
            queryCount = 0
        }
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }

    private fun chapter(id: String, number: Int) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = number.toString(),
        volume = null,
        title = null,
        type = CanonicalChapterType.REGULAR,
        baseNumber = number,
        part = null,
        alphaSuffix = null,
        confidence = 1.0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun addonEvidence(id: String, label: String, key: String) = ChapterEvidence(
        id = id,
        canonicalTitleId = "title-1",
        producerKind = ProducerKind.ADDON,
        producerId = "addon",
        externalChapterKey = key,
        rawLabel = label,
        rawNumber = null,
        volume = null,
        title = null,
        observedAt = 1L,
        confidence = 1.0,
        authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
    )

    private fun editorialEvidence(id: String, label: String, key: String) =
        addonEvidence(id, label, key).copy(
            producerKind = ProducerKind.INTEGRATION,
            producerId = "integration",
            authority = ChapterEvidenceAuthority.EDITORIAL,
        )
}
