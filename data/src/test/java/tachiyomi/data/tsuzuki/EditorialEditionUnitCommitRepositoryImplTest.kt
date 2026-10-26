package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
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
import tachiyomi.domain.tsuzuki.chapter.interactor.ChapterMutationGate
import tachiyomi.domain.tsuzuki.chapter.inventory.EditorialUnitCommitResult
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryCandidateProvenance
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryCandidateSourceKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaim
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaimKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryDerivation
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchOutcome
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionCommitResult
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionConfirmation
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionPreview
import tachiyomi.domain.tsuzuki.chapter.inventory.UserAdoptionReviewRequest
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import java.nio.file.Files

/** Entirely synthetic editorial fixture; nothing is installed as an external producer. */
class EditorialEditionUnitCommitRepositoryImplTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var chapters: CanonicalChapterRepositoryImpl
    private var oldNativePath: String? = null
    private var nativeDir: java.nio.file.Path? = null

    @BeforeEach
    fun setUp(): Unit = runBlocking<Unit> {
        oldNativePath = System.getProperty("org.sqlite.lib.path")
        nativeDir = Files.createTempDirectory("tsuzuki-editorial-unit-test")
        val native = nativeDir!!.resolve("libsqlitejdbc.so")
        EditorialEditionUnitCommitRepositoryImplTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${architecture()}/libsqlitejdbc.so")!!
            .use { Files.copy(it, native) }
        native.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeDir.toString())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
        database.tsuzuki_titlesQueries.insertTsuzukiTitle("title", "Synthetic", "SOURCE_ONLY", 1L, 1L)
        database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
            integrationId = "synthetic-editorial",
            enabled = true,
            configJson = """{"chapter_inventory":true}""",
            updatedAt = 10L,
        )
        database.tsuzuki_editorial_edition_consentsQueries.upsertTsuzukiEditorialEditionConsent(
            canonicalTitleId = "title",
            producerId = "synthetic-editorial",
            externalWorkId = "work",
            editionKey = "original",
            scopeLanguage = "ja",
            grantedAt = 10L,
            integrationSettingsRevision = 10L,
        )

        chapters = CanonicalChapterRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        if (oldNativePath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", oldNativePath)
        }
        nativeDir?.toFile()?.deleteRecursively()
    }

    @Test
    fun `selected edition candidate pagination visits every stored unit without crossing scopes or release records`(): Unit =
        runBlocking<Unit> {
            val selection = InventoryScope(
                canonicalTitleId = "title",
                producerId = "epub3-user-selected",
                externalWorkId = "selected-book",
                editionKey = "selected-ja",
                language = "ja",
                workIdentityVerified = false,
                editionIdentityVerified = false,
            )
            val ledger = tachiyomi.data.tsuzuki.chapter.IndependentInventoryRepositoryImpl(database)
            val provenance = InventoryCandidateProvenance(
                sourceKind = InventoryCandidateSourceKind.USER_SELECTED_EPUB3_TOC,
                originReference = "user-selected:edition",
                exactRevision = "r1",
                snapshotDigest = "sha256:" + "b".repeat(64),
                sourceUnitId = "chapter-1.xhtml",
                structuralCategory = "toc-chapter",
            )
            fun candidate(number: Int) = InventoryClaim(
                sourceRecordId = "unit-" + number.toString().padStart(3, '0'),
                label = "Chapter $number",
                volumeLabel = null,
                language = "ja",
                kind = InventoryClaimKind.INDIVIDUAL,
                derivation = InventoryDerivation.USER_SUPPLIED_EDITION_TOC,
                upstreamSourceId = "r1",
                observedAt = 10L,
                provenance = provenance.copy(sourceUnitId = "chapter-$number.xhtml"),
            )
            ledger.saveBatch(
                InventoryEvidenceBatch(
                    scope = selection,
                    pageKey = "edition-1",
                    observedAt = 10L,
                    outcome = InventoryFetchOutcome.PARTIAL,
                    claims = (1..75).map(::candidate) + candidate(76).copy(
                        derivation = InventoryDerivation.RELEASE_RECORD,
                        provenance = null,
                    ),
                ),
            )
            ledger.saveBatch(
                InventoryEvidenceBatch(
                    scope = selection.copy(editionKey = "other-edition"),
                    pageKey = "other-edition",
                    observedAt = 10L,
                    outcome = InventoryFetchOutcome.PARTIAL,
                    claims = listOf(candidate(77)),
                ),
            )
            val adoption = UserAdoptedInventoryRepositoryImpl(database, ledger, chapters, ChapterMutationGate())
            val pages = mutableListOf<String>()
            var cursor: String? = null
            do {
                val page = adoption.candidatePage(selection, cursor)
                pages += page.candidates.map { it.sourceRecordId }
                cursor = page.nextCursor
            } while (cursor != null)
            pages shouldBe (1..75).map { "unit-" + it.toString().padStart(3, '0') }
            val ninth = adoption.preview(UserAdoptionReviewRequest(selection, listOf("unit-009")))
            (ninth is UserAdoptionPreview.Ready) shouldBe true
            chapters.getByCanonicalTitleId("title") shouldBe emptyList()
        }

    @Test
    fun `user explicitly adopts selected local EPUB units with a durable non-editorial receipt`(): Unit =
        runBlocking<Unit> {
            val selection = InventoryScope(
                canonicalTitleId = "title",
                producerId = "epub3-user-selected",
                externalWorkId = "user-book",
                editionKey = "selected-ja",
                language = "ja",
                workIdentityVerified = false,
                editionIdentityVerified = false,
            )
            val provenance = InventoryCandidateProvenance(
                sourceKind = InventoryCandidateSourceKind.USER_SELECTED_EPUB3_TOC,
                originReference = "user-selected:local-edition",
                exactRevision = "r1",
                snapshotDigest = "sha256:" + "b".repeat(64),
                sourceUnitId = "chap12.xhtml",
                structuralCategory = "toc-chapter",
            )
            val candidate = InventoryClaim(
                sourceRecordId = "local-chap-12",
                label = "Chapter 12.05",
                volumeLabel = "1",
                language = "ja",
                kind = InventoryClaimKind.INDIVIDUAL,
                derivation = InventoryDerivation.USER_SUPPLIED_EDITION_TOC,
                upstreamSourceId = "r1",
                observedAt = 10L,
                provenance = provenance,
            )
            val ledger = tachiyomi.data.tsuzuki.chapter.IndependentInventoryRepositoryImpl(database)
            ledger.saveBatch(
                InventoryEvidenceBatch(
                    scope = selection,
                    pageKey = "selected-r1",
                    observedAt = 10L,
                    outcome = InventoryFetchOutcome.PARTIAL,
                    claims = listOf(candidate),
                ),
            )
            val repository = UserAdoptedInventoryRepositoryImpl(database, ledger, chapters, ChapterMutationGate())
            val request = UserAdoptionReviewRequest(selection, listOf(candidate.sourceRecordId))
            val plan = repository.preview(request) as UserAdoptionPreview.Ready
            repository.adopt(UserAdoptionConfirmation(request, plan.fingerprint, 20L, false)) shouldBe
                UserAdoptionCommitResult.Refused
            chapters.getByCanonicalTitleId("title") shouldBe emptyList()
            val adopted = repository.adopt(UserAdoptionConfirmation(request, plan.fingerprint, 21L, true))
            (adopted is UserAdoptionCommitResult.Adopted) shouldBe true
            val row = database.tsuzuki_user_adoption_receiptsQueries
                .getTsuzukiUserAdoptionReceiptsByTitle("title").awaitAsList().single()
            row.review_fingerprint shouldBe plan.fingerprint
            row.exact_revision shouldBe "r1"
            row.source_kind shouldBe "USER_SELECTED_EPUB3_TOC"
            row.canonical_chapter_id shouldBe (adopted as UserAdoptionCommitResult.Adopted).canonicalChapterIds.single()
            chapters.getByCanonicalTitleId("title").single().displayNumber shouldBe "12.05"
            repository.adopt(UserAdoptionConfirmation(request, plan.fingerprint, 22L, true)) shouldBe
                UserAdoptionCommitResult.Refused
            chapters.getByCanonicalTitleId("title").size shouldBe 1
        }

    @Test
    fun `stale source revision cannot pass a reviewed fingerprint into local chapter persistence`(): Unit =
        runBlocking<Unit> {
            val selection = InventoryScope(
                canonicalTitleId = "title",
                producerId = "epub3-user-selected",
                externalWorkId = "book",
                editionKey = "edition",
                language = "ja",
                workIdentityVerified = false,
                editionIdentityVerified = false,
            )
            fun candidate(revision: String, observed: Long) = InventoryClaim(
                sourceRecordId = "chapter-1",
                label = "1",
                volumeLabel = null,
                language = "ja",
                kind = InventoryClaimKind.INDIVIDUAL,
                derivation = InventoryDerivation.USER_SUPPLIED_EDITION_TOC,
                upstreamSourceId = revision,
                observedAt = observed,
                provenance = InventoryCandidateProvenance(
                    sourceKind = InventoryCandidateSourceKind.USER_SELECTED_EPUB3_TOC,
                    originReference = "user-selected:book",
                    exactRevision = revision,
                    snapshotDigest = "sha256:" + (if (revision == "r1") "a" else "b").repeat(64),
                    sourceUnitId = "1.xhtml",
                    structuralCategory = "toc-chapter",
                ),
            )
            val ledger = tachiyomi.data.tsuzuki.chapter.IndependentInventoryRepositoryImpl(database)
            ledger.saveBatch(
                InventoryEvidenceBatch(
                    selection,
                    "page1",
                    10L,
                    InventoryFetchOutcome.PARTIAL,
                    listOf(candidate("r1", 10L)),
                ),
            )
            val repository = UserAdoptedInventoryRepositoryImpl(database, ledger, chapters, ChapterMutationGate())
            val request = UserAdoptionReviewRequest(selection, listOf("chapter-1"))
            val preview = repository.preview(request) as UserAdoptionPreview.Ready
            ledger.saveBatch(
                InventoryEvidenceBatch(
                    selection,
                    "page1",
                    11L,
                    InventoryFetchOutcome.PARTIAL,
                    listOf(candidate("r2", 11L)),
                ),
            )
            repository.adopt(UserAdoptionConfirmation(request, preview.fingerprint, 12L, true)) shouldBe
                UserAdoptionCommitResult.Refused
            chapters.getByCanonicalTitleId("title") shouldBe emptyList()
        }

    @Test
    fun `production Host rejects self-declared editorial claims even with local consent`(): Unit =
        runBlocking<Unit> {
            val request = batch(claim("18"))
            val productionWriter = EditorialEditionUnitCommitRepositoryImpl(
                database = database,
                chapters = chapters,
                mutationGate = ChapterMutationGate(),
                hostAuthorization = DisabledEditorialEditionHostAuthority(),
            )
            productionWriter.commit(request) shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title") shouldBe emptyList()
        }

    @Test
    fun `synthetic exact editorial units create chapters without reading providers and replay unchanged`(): Unit =
        runBlocking<Unit> {
            val selected = batch(claim("12.05"), claim("12.5"))
            val first = commitWithSyntheticHost(selected, grant(selected.scope))
            (first is EditorialUnitCommitResult.Applied) shouldBe true
            val written = first as EditorialUnitCommitResult.Applied
            written.inserted shouldBe 2
            val chaptersAfter = chapters.getByCanonicalTitleId("title")
            chaptersAfter.size shouldBe 2
            chaptersAfter.map { it.identity }.distinct().size shouldBe 2
            chaptersAfter.all { it.confirmation.name == "CONFIRMED" } shouldBe true
            val replay = commitWithSyntheticHost(selected, grant(selected.scope))
            replay shouldBe EditorialUnitCommitResult.Applied(written.chapterIds, 0)
            chapters.getByCanonicalTitleId("title") shouldBe chaptersAfter
            database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
                .awaitAsList().size shouldBe 2
        }

    @Test
    fun `sync evidence lookup identifies only persisted edition scoped ownership`() = runBlocking<Unit> {
        val selected = batch(claim("12"))
        val applied = commitWithSyntheticHost(selected, grant(selected.scope)) as EditorialUnitCommitResult.Applied
        val ordinary = CanonicalChapter(
            id = "old-local",
            canonicalTitleId = "title",
            displayNumber = "13",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 13,
            createdAt = 1L,
            updatedAt = 1L,
        )
        chapters.upsert(ordinary)
        val syncEvidence = tachiyomi.data.tsuzuki.sync.ChapterSyncEvidenceRepositoryImpl(database)
        syncEvidence.isEditionScopedChapter(applied.chapterIds.single()) shouldBe true
        syncEvidence.isEditionScopedChapter(ordinary.id) shouldBe false
        syncEvidence.isEditionScopedChapter("missing") shouldBe false
    }

    @Test
    fun `enabled integration alone cannot mint editorial authority without selected edition consent`(): Unit =
        runBlocking<Unit> {
            val request = batch(claim("11"))
            database.tsuzuki_editorial_edition_consentsQueries.revokeTsuzukiEditorialEditionConsent(
                canonicalTitleId = "title",
                producerId = "synthetic-editorial",
                externalWorkId = "work",
                editionKey = "original",
                scopeLanguage = "ja",
            )
            commitWithSyntheticHost(request, grant(request.scope)) shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title") shouldBe emptyList()
        }

    @Test
    fun `integration inventory toggle must be explicitly enabled and revocation blocks replay`(): Unit =
        runBlocking<Unit> {
            val request = batch(claim("12"))
            database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
                integrationId = "synthetic-editorial",
                enabled = true,
                configJson = "{}",
                updatedAt = 11L,
            )
            commitWithSyntheticHost(request, grant(request.scope)) shouldBe EditorialUnitCommitResult.Refused
            database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
                integrationId = "synthetic-editorial",
                enabled = false,
                configJson = """{"chapter_inventory":true}""",
                updatedAt = 12L,
            )
            commitWithSyntheticHost(request, grant(request.scope)) shouldBe EditorialUnitCommitResult.Refused
            database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
                integrationId = "synthetic-editorial",
                enabled = true,
                configJson = """{"chapter_inventory":true}""",
                updatedAt = 13L,
            )
            database.tsuzuki_editorial_edition_consentsQueries.upsertTsuzukiEditorialEditionConsent(
                canonicalTitleId = "title",
                producerId = "synthetic-editorial",
                externalWorkId = "work",
                editionKey = "original",
                scopeLanguage = "ja",
                grantedAt = 14L,
                integrationSettingsRevision = 13L,
            )
            (commitWithSyntheticHost(request, grant(request.scope)) is EditorialUnitCommitResult.Applied) shouldBe true
            database.tsuzuki_editorial_edition_consentsQueries.revokeTsuzukiEditorialEditionConsent(
                canonicalTitleId = "title",
                producerId = "synthetic-editorial",
                externalWorkId = "work",
                editionKey = "original",
                scopeLanguage = "ja",
            )
            commitWithSyntheticHost(request, grant(request.scope)) shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title").size shouldBe 1
        }

    @Test
    fun `re-enabling inventory after a disable never silently revives old edition consent`(): Unit =
        runBlocking<Unit> {
            val requested = batch(claim("17"))
            database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
                integrationId = "synthetic-editorial",
                enabled = false,
                configJson = """{"chapter_inventory":true}""",
                updatedAt = 11L,
            )
            commitWithSyntheticHost(requested, grant(requested.scope)) shouldBe EditorialUnitCommitResult.Refused
            database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
                integrationId = "synthetic-editorial",
                enabled = true,
                configJson = """{"chapter_inventory":true}""",
                updatedAt = 12L,
            )
            commitWithSyntheticHost(requested, grant(requested.scope)) shouldBe EditorialUnitCommitResult.Refused
            database.tsuzuki_editorial_edition_consentsQueries.upsertTsuzukiEditorialEditionConsent(
                canonicalTitleId = "title",
                producerId = "synthetic-editorial",
                externalWorkId = "work",
                editionKey = "original",
                scopeLanguage = "ja",
                grantedAt = 13L,
                integrationSettingsRevision = 12L,
            )
            (commitWithSyntheticHost(requested, grant(requested.scope)) is EditorialUnitCommitResult.Applied) shouldBe
                true
            chapters.getByCanonicalTitleId("title").size shouldBe 1
        }

    @Test
    fun `grant for another edition work or language must never authorize a valid producer callback`(): Unit =
        runBlocking<Unit> {
            for (alternate in listOf(scope("revised"), scope().copy(language = "en"))) {
                val attempted = batch(claim("1").copy(language = alternate.language)).copy(scope = alternate)
                commitWithSyntheticHost(attempted, grant(alternate)) shouldBe EditorialUnitCommitResult.Refused
            }
            val foreignWork = batch(claim("1")).copy(scope = scope().copy(externalWorkId = "work-other"))
            commitWithSyntheticHost(foreignWork, grant(foreignWork.scope)) shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title") shouldBe emptyList()
        }

    @Test
    fun `second edition with same chapter number cannot cross-link or overwrite earlier edition`(): Unit =
        runBlocking<Unit> {
            val first = batch(claim("1"))
            val second = batch(claim("1")).copy(scope = scope("revised"))
            (commitWithSyntheticHost(first, grant(first.scope)) is EditorialUnitCommitResult.Applied) shouldBe true
            commitWithSyntheticHost(second, grant(second.scope)) shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title").size shouldBe 1
            database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
                .awaitAsList().size shouldBe 1
        }

    @Test
    fun `two distinct editions may reuse their publisher local source record id`(): Unit = runBlocking<Unit> {
        val original = batch(claim("1").copy(sourceRecordId = "chapter-index-1"))
        val revised = batch(claim("2").copy(sourceRecordId = "chapter-index-1"))
            .copy(scope = scope("revised"))
        (commitWithSyntheticHost(original, grant(original.scope)) is EditorialUnitCommitResult.Applied) shouldBe true
        database.tsuzuki_editorial_edition_consentsQueries.upsertTsuzukiEditorialEditionConsent(
            canonicalTitleId = "title",
            producerId = "synthetic-editorial",
            externalWorkId = "work",
            editionKey = "revised",
            scopeLanguage = "ja",
            grantedAt = 12L,
            integrationSettingsRevision = 10L,
        )
        (commitWithSyntheticHost(revised, grant(revised.scope)) is EditorialUnitCommitResult.Applied) shouldBe true
        chapters.getByCanonicalTitleId("title").size shouldBe 2
        database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
            .awaitAsList().map { it.edition_key }.toSet() shouldBe setOf("original", "revised")
    }

    @Test
    fun `last Host check can revoke persisted edition consent and still force atomic rollback`(): Unit =
        runBlocking<Unit> {
            val request = batch(claim("9"))
            var checks = 0
            val result = commitWithSyntheticHost(request) { _, _ ->
                checks++
                if (checks == 2) {
                    runBlocking {
                        database.tsuzuki_editorial_edition_consentsQueries.revokeTsuzukiEditorialEditionConsent(
                            canonicalTitleId = "title",
                            producerId = "synthetic-editorial",
                            externalWorkId = "work",
                            editionKey = "original",
                            scopeLanguage = "ja",
                        )
                    }
                }
                request.scope
            }

            checks shouldBe 2
            result shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title") shouldBe emptyList()
            database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
                .awaitAsList() shouldBe emptyList()
        }

    @Test
    fun `a capability switch during final Host validation cannot leave orphaned editorial chapters`(): Unit =
        runBlocking<Unit> {
            val request = batch(claim("10"))
            var checks = 0
            val result = commitWithSyntheticHost(request) { _, _ ->
                checks++
                if (checks == 2) {
                    runBlocking {
                        database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
                            integrationId = "synthetic-editorial",
                            enabled = false,
                            configJson = """{"chapter_inventory":true}""",
                            updatedAt = 14L,
                        )
                    }
                }
                request.scope
            }
            checks shouldBe 2
            result shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title") shouldBe emptyList()
            database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
                .awaitAsList() shouldBe emptyList()
        }

    @Test
    fun `host revocation after insertion rolls back both chapter and scoped ownership`(): Unit = runBlocking<Unit> {
        val selected = batch(claim("1"), claim("2"))
        var checks = 0
        val revoked = commitWithSyntheticHost(selected) { _, _ ->
            checks++
            if (checks == 1) selected.scope else null
        }
        revoked shouldBe EditorialUnitCommitResult.Refused
        chapters.getByCanonicalTitleId("title") shouldBe emptyList()
        database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
            .awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `legacy count chapter cannot be silently rekeyed or reused by new editorial unit`(): Unit =
        runBlocking<Unit> {
            val old = CanonicalChapter(
                id = "tsuzuki:count:old-title:12",
                canonicalTitleId = "title",
                displayNumber = "12",
                type = CanonicalChapterType.REGULAR,
                baseNumber = 12,
                createdAt = 1L,
                updatedAt = 1L,
            )
            chapters.upsert(old)
            val requested = batch(claim("12.05"))
            commitWithSyntheticHost(requested, grant(requested.scope)) shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title") shouldBe listOf(old)
        }

    @Test
    fun `edition owned chapter is protected from legacy duplicate consolidation`(): Unit = runBlocking<Unit> {
        val selected = batch(claim("8"))
        val owner = commitWithSyntheticHost(selected, grant(selected.scope)) as EditorialUnitCommitResult.Applied
        val editorialChapterId = owner.chapterIds.single()
        val competing = CanonicalChapter(
            id = "legacy-competing-eight",
            canonicalTitleId = "title",
            displayNumber = "8",
            volume = 1,
            type = CanonicalChapterType.REGULAR,
            baseNumber = 8,
            createdAt = 2L,
            updatedAt = 2L,
        )
        chapters.upsert(competing)

        chapters.consolidateExactDuplicateIfSafe(
            canonicalTitleId = "title",
            preferredChapterId = competing.id,
            duplicateChapterId = editorialChapterId,
        ) shouldBe false

        chapters.getById(editorialChapterId)?.canonicalTitleId shouldBe "title"
        chapters.getById(competing.id) shouldBe competing
        database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
            .awaitAsList().single().canonical_chapter_id shouldBe editorialChapterId
    }

    @Test
    fun `malformed or self-declared grant cannot persist chapters`(): Unit = runBlocking<Unit> {
        val selected = batch(claim("1"))
        commitWithSyntheticHost(selected) { _, _ -> null } shouldBe EditorialUnitCommitResult.Refused
        commitWithSyntheticHost(selected, grant(scope("other-edition"))) shouldBe EditorialUnitCommitResult.Refused
        commitWithSyntheticHost(
            selected.copy(claims = listOf(claim("1").copy(derivation = InventoryDerivation.RELEASE_RECORD))),
            grant(selected.scope),
        ) shouldBe EditorialUnitCommitResult.Refused
        chapters.getByCanonicalTitleId("title") shouldBe emptyList()
    }

    @Test
    fun `title convergence refuses to destroy scoped ownership and rolls back`(): Unit = runBlocking<Unit> {
        val selected = batch(claim("1"))
        val written = commitWithSyntheticHost(selected, grant(selected.scope))
        (written is EditorialUnitCommitResult.Applied) shouldBe true
        database.tsuzuki_titlesQueries.insertTsuzukiTitle("target", "Target", "SOURCE_ONLY", 2L, 2L)
        val merging = runCatching { CanonicalTitleMergeRepositoryImpl(database).convergeTo("target", "title") }
        merging.isFailure shouldBe true
        chapters.getByCanonicalTitleId("title").size shouldBe 1
        database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
            .awaitAsList().size shouldBe 1
    }

    private suspend fun commitWithSyntheticHost(
        batch: InventoryEvidenceBatch,
        authority: tachiyomi.domain.tsuzuki.chapter.inventory.HostEditorialEditionAuthorization,
    ): EditorialUnitCommitResult = EditorialEditionUnitCommitRepositoryImpl(
        database = database,
        chapters = chapters,
        mutationGate = ChapterMutationGate(),
        hostAuthorization = authority,
    ).commit(batch)

    private fun grant(approved: InventoryScope) =
        tachiyomi.domain.tsuzuki.chapter.inventory.HostEditorialEditionAuthorization { _, _ -> approved }

    private fun scope(edition: String = "original") = InventoryScope(
        canonicalTitleId = "title",
        producerId = "synthetic-editorial",
        externalWorkId = "work",
        editionKey = edition,
        language = "ja",
        workIdentityVerified = true,
        editionIdentityVerified = true,
    )

    private fun claim(label: String) = InventoryClaim(
        sourceRecordId = "publisher-unit-$label",
        label = label,
        volumeLabel = "1",
        language = "ja",
        kind = InventoryClaimKind.INDIVIDUAL,
        derivation = InventoryDerivation.PRIMARY_EDITORIAL,
        upstreamSourceId = "synthetic-publisher-feed",
        observedAt = 42L,
    )

    private fun batch(vararg claims: InventoryClaim) = InventoryEvidenceBatch(
        scope = scope(),
        pageKey = "first",
        observedAt = 42L,
        outcome = InventoryFetchOutcome.PARTIAL,
        claims = claims.toList(),
    )

    private fun architecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported architecture")
    }
}
