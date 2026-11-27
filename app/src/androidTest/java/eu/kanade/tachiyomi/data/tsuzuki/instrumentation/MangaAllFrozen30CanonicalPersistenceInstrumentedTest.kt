package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.integration.KitsuIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.MalIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.MangaUpdatesIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.ShikimoriIntegrationProvider
import eu.kanade.tachiyomi.data.tsuzuki.integration.ShikimoriRequestGate
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackage
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackageSource
import eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGateway
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.ProviderHttpPageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.TitleNameObservationRepositoryImpl
import tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl
import tachiyomi.data.tsuzuki.integration.CanonicalMetadataSnapshotRepositoryImpl
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.data.tsuzuki.kitsu.client.KitsuHttpClient
import tachiyomi.data.tsuzuki.provider.MangaAllSourceSelectionRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderProvisionalCursorRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderReadingBindingRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderWorkSourceAssociationRepositoryImpl
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityStatus
import tachiyomi.domain.tsuzuki.catalog.interactor.ResolveMangaAllIndependentCatalogIdentity
import tachiyomi.domain.tsuzuki.catalog.interactor.mangaAllCatalogCreatorKey
import tachiyomi.domain.tsuzuki.catalog.interactor.toCanonicalReadingMetadata
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.CreatorCreditRole
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.ReviewedCreatorAliasReference
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshot
import tachiyomi.domain.tsuzuki.integration.resolveReviewedCreatorAliases
import tachiyomi.domain.tsuzuki.integration.resolveVerifiedCreatorAliases
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
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.reading.CollectProviderReadingEvidence
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllCatalogReaderResearch
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelectionResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterReadingOption
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.reading.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderSourceEditionQualification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkSourcePortfolio
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkVerification
import tachiyomi.domain.tsuzuki.provider.reading.ResolveMangaAllSelectedChapter
import tachiyomi.domain.tsuzuki.provider.reading.ResolveMangaAllSelectedReadingOption
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading
import tachiyomi.domain.tsuzuki.provider.reading.SelectMangaAllSource
import tachiyomi.domain.tsuzuki.provider.reading.evidenceProducerId
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Nonshipping live gate using the real Android database, production collector,
 * reconciler and Reader option resolver. The runner clears a disposable
 * emulator before COLLECT and force-stops it before RESTORE. No signed install,
 * fabricated chapter/author, forced binding, or on-screen pixel claim.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllFrozen30CanonicalPersistenceInstrumentedTest {
    @Test(timeout = 1_800_000L)
    fun frozen30CanonicalPersistence(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("I_ACCEPT_FROZEN30_CANONICAL_RESEARCH", args.getString("mangaAllCanonicalOptIn"))
        val catalogScope = args.getString("mangaAllCanonicalCatalog") ?: "mangaupdates"
        require(catalogScope in setOf("mangaupdates", "shikimori", "explicit_plan"))
        val cohort = args.getString("mangaAllCanonicalCohort") ?: "manhwa"
        require(cohort in setOf("manhwa", "manga"))
        val japanese = cohort == "manga"
        val expectedFormat = if (japanese) CatalogItemFormat.MANGA else CatalogItemFormat.MANHWA
        val corpusSha = if (japanese) JAPANESE_CORPUS_SHA else CORPUS_SHA
        require(!japanese || catalogScope == "explicit_plan")
        val readerMode = args.getString("mangaAllCanonicalReaderProbe") ?: "0"
        require(readerMode in setOf("0", "1"))
        val phase = requireNotNull(args.getString("mangaAllCanonicalPhase"))
        require(phase in setOf("COLLECT", "RESTORE", "CREDIT_AUDIT"))
        val creditAudit = phase == "CREDIT_AUDIT"
        require(!creditAudit || args.getString("mangaAllCreditAuditOptIn") == "PUBLIC_CREDITS_ONLY")
        val shard = requireNotNull(args.getString("mangaAllFrozen30Shard")).toInt()
        require(shard in 0..2)
        val pendingMode = args.getString("mangaAllCanonicalPending14") ?: "0"
        require(pendingMode in setOf("0", "1"))
        require(pendingMode == "0" || catalogScope == "explicit_plan")
        require(!japanese || (pendingMode == "0" && !creditAudit))
        val pending14 = setOf(5, 6, 8, 11, 15, 16, 19, 20, 21, 23, 25, 27, 28, 29)
        require(!creditAudit || (pendingMode == "1" && readerMode == "0"))
        val remainingSix = setOf(8, 15, 21, 27, 28, 29)
        val remainingMode = args.getString("mangaAllCanonicalRemaining6") ?: "0"
        require(remainingMode in setOf("0", "1"))
        require(remainingMode == "0" || (pendingMode == "1" && !creditAudit))
        val auditScope = args.getString("mangaAllCreditAuditScope") ?: "PENDING13"
        require(auditScope in setOf("PENDING13", "REMAINING6"))
        require(auditScope == "PENDING13" || creditAudit)
        val indices = (shard * 10 + 1..shard * 10 + 10).filter {
            (pendingMode == "0" || it in pending14) &&
                (!creditAudit || it != 16) &&
                (auditScope != "REMAINING6" || it in remainingSix) &&
                (remainingMode != "1" || it in remainingSix)
        }
        val context = instrument.targetContext
        assertEquals("app.tsuzuki.dev", context.packageName)
        assertTrue(
            Build.FINGERPRINT.contains("generic") ||
                Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.PRODUCT.startsWith("sdk_gphone"),
        )
        val lockName = if (japanese) {
            "mangaall-japanese-manga-30-cohort-lock.json"
        } else {
            "mangaall-dual-source-30-cohort-lock.json"
        }
        val lock = instrument.context.assets.open(lockName)
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val works = requireNotNull(lock["works"]).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            if (japanese) "MANGA_30_FIXED_20261126" else "MANHWA_30_FIXED_20261123",
            lock["cohort"]?.jsonPrimitive?.content,
        )
        assertEquals(cohort, lock["format"]?.jsonPrimitive?.content)
        if (japanese) assertEquals("ja", lock["originalLanguage"]?.jsonPrimitive?.content)
        assertEquals(30, works.size)
        assertEquals(30, works.toSet().size)
        assertEquals(corpusSha, hash(JsonArray(works.map { JsonPrimitive(it) }).toString()))
        val plan = if (catalogScope == "explicit_plan") {
            val planName = if (japanese) {
                "mangaall-japanese-manga-30-catalog-plan.json"
            } else {
                "mangaall-frozen30-catalog-plan.json"
            }
            val planText = instrument.context.assets.open(planName)
                .bufferedReader().use { it.readText() }
            assertEquals(requireNotNull(args.getString("mangaAllCanonicalPlanSha256")), hash(planText))
            val doc = Json.parseToJsonElement(planText).jsonObject
            assertEquals(corpusSha, doc.getValue("corpusSha256").jsonPrimitive.content)
            assertEquals("RESEARCH_EXPLICIT_OWNERS_NO_FALLBACK", doc.getValue("scope").jsonPrimitive.content)
            val rows = doc.getValue("works").jsonArray.map { it.jsonObject }
            assertEquals((1..30).toList(), rows.map { it.getValue("index").jsonPrimitive.content.toInt() })
            rows
        } else {
            emptyList()
        }

        fun ownerScope(index: Int): String = if (catalogScope == "explicit_plan") {
            plan[index - 1].getValue("catalog").jsonPrimitive.content.also {
                require(it in setOf("mangaupdates", "shikimori") || (japanese && it in setOf("mal", "kitsu")))
            }
        } else {
            catalogScope
        }

        val attestedIdentities = if (japanese) {
            val doc = instrument.context.assets.open("attested-identities.json")
                .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            assertEquals("1", doc.getValue("schemaVersion").jsonPrimitive.content)
            assertEquals(corpusSha, doc.getValue("corpusSha256").jsonPrimitive.content)
            assertEquals(
                args.getString("mangaAllCanonicalPlanSha256"),
                doc.getValue("planSha256").jsonPrimitive.content,
            )
            val rows = doc.getValue("works").jsonArray.map { it.jsonObject }
            assertEquals((1..30).toList(), rows.map { it.getValue("index").jsonPrimitive.content.toInt() })
            val digests = rows.mapIndexed { offset, row ->
                assertEquals(ownerScope(offset + 1), row.getValue("catalog").jsonPrimitive.content)
                row.getValue("identityDigest").jsonPrimitive.content.also {
                    require(it.matches(Regex("[0-9a-f]{64}")))
                }
            }
            assertEquals(30, digests.toSet().size)
            digests
        } else {
            emptyList()
        }

        fun matchesAttestedOwner(index: Int, provider: String, externalId: String): Boolean =
            !japanese || hash("$provider:$externalId") == attestedIdentities[index - 1]

        fun nativeTitle(index: Int): String? = plan.getOrNull(index - 1)
            ?.get("searchTitle")?.jsonPrimitive?.content

        val bytes = instrument.context.assets.open("mangaall-dual-source-code11-unsigned-research.zip")
            .use { it.readBytes() }
        val parsed = ProviderPackageParser().parse(bytes)
        val manifest = parsed.manifest
        assertEquals("app.tsuzuki.mangaall", manifest.id)
        assertEquals(11L, manifest.version.code)
        if (japanese) assertEquals("MangaAll — frozen 30 manga source research", manifest.name)
        val origins = requireNotNull(manifest.permissions.network).origins
        assertTrue(origins.none { it.contains("mangafire", true) || it.contains("mfcdn", true) })
        val id = ProviderId(manifest.id)
        val repositoryId = "mangaall-frozen30-unsigned-research"
        val registration = ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = id,
                name = manifest.name,
                version = ProviderVersion(manifest.version.name, manifest.version.code),
                origin = ProviderOrigin.Repository(repositoryId),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = setOf(
                    ProviderCapabilities.ReadingLookupV1,
                    ProviderCapabilities.ReadingChaptersV1,
                    ProviderCapabilities.ReadingPagesV1,
                ),
                permissions = ProviderPermissionSet(
                    network = ProviderNetworkPermission(origins, localNetwork = false),
                ),
                settings = emptyList(),
                contentLanguages = setOf("en"),
            ),
            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
            facets = listOf(ProviderFacetRef(id, "en")),
            configurationFingerprint = "frozen30-canonical-research",
        )
        val registry = DefaultProviderRegistry(registrations = { listOf(registration) })
        val runtimeRoot = File(context.cacheDir, "mangaall-frozen30-canonical-runtime")
        val gateway = ScriptProviderReadingGateway(
            registry = registry,
            packageSource = ActiveProviderScriptPackageSource { requested ->
                if (requested != id) {
                    null
                } else {
                    ActiveProviderScriptPackage(repositoryId, manifest.version.code, bytes, parsed)
                }
            },
            runtimeClient = ProviderRuntimeClient(
                context = context,
                hostInvocationFactory = ProviderHostInvocationFactory(context = context, storageRoot = runtimeRoot),
            ),
        )
        val app = context.applicationContext as App
        val database = app.graph.database
        val titles = CanonicalTitleRepositoryImpl(database)
        val titleNames = TitleNameObservationRepositoryImpl(database)
        val metadata = CanonicalMetadataSnapshotRepositoryImpl(database)
        val chapters = CanonicalChapterRepositoryImpl(database)
        val evidence = ChapterEvidenceRepositoryImpl(database)
        val bindings = ProviderReadingBindingRepositoryImpl(database)
        val associations = ProviderWorkSourceAssociationRepositoryImpl(database)
        val cursors = ProviderProvisionalCursorRepositoryImpl(database)
        val selections = MangaAllSourceSelectionRepositoryImpl(database)
        val catalogs = buildMap<String, SearchProvider> {
            put("mangaupdates", MangaUpdatesIntegrationProvider(app.graph.trackerManager))
            put("shikimori", ShikimoriIntegrationProvider(app.graph.trackerManager, ShikimoriRequestGate()))
            if (japanese) {
                put("mal", MalIntegrationProvider(app.graph.trackerManager))
                put(
                    "kitsu",
                    KitsuIntegrationProvider(
                        KitsuCatalogProvider(KitsuHttpClient(app.graph.networkHelper, app.graph.json)),
                        app.graph.trackerManager,
                    ),
                )
            }
        }
        fun catalogRegistry(names: Set<String>): IntegrationRegistry {
            val selectedCatalogs = names.map(catalogs::getValue)
            return object : IntegrationRegistry {
                override fun isGlobalCapabilityActive(
                    integrationId: IntegrationId,
                    capability: IntegrationCapability,
                ): Boolean = integrationId.value in names && capability == IntegrationCapability.METADATA_BASIC

                override fun searchProviders(): List<SearchProvider> = selectedCatalogs
                override fun metadataProviders(): List<MetadataProvider> = selectedCatalogs.map {
                    it as MetadataProvider
                }
                override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
                override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
                override fun ratingsProviders(): List<RatingsProvider> = emptyList()
                override fun trackingProviders(): List<TrackingProvider> = emptyList()
            }
        }
        val metadataRegistry = catalogRegistry(catalogs.keys)
        // Retain only closed codes/counts for the fixed public corpus. Never
        // export actual titles, creator names, HIDs, URLs or chapter IDs.
        val acquisitionFacts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val diagnosticRecorder = object : StructuredDiagnosticRecorder {
            override val sessionId = "00000000-0000-0000-0000-000000000000"
            override val detailedCaptureActive = true
            override fun canonicalTitleReference(canonicalTitleId: String): String? = null
            override fun mihonMangaReference(mihonMangaId: Long): String? = null
            override fun record(event: StructuredDiagnosticEvent) {
                if (acquisitionFacts.size >= 2_048) return
                if (event.name !in setOf(
                        DiagnosticEventName.PROVIDER_READING_LOOKUP_ATTEMPT,
                        DiagnosticEventName.PROVIDER_READING_CANDIDATE,
                        DiagnosticEventName.PROVIDER_READING_SOURCE_ASSOCIATION,
                        DiagnosticEventName.PROVIDER_READING_CHAPTER_ATTEMPT,
                    )
                ) {
                    return
                }
                fun code(key: String): String? =
                    ((event.attributes[key] as? DiagnosticAttributeValue.Code)?.value as? Enum<*>)?.name
                val hid = (event.attributes["provider_work_id"] as? DiagnosticAttributeValue.Text)?.value
                val source = code("mangaall_network_source") ?: when (hid?.substringBefore(':')) {
                    "mangadex" -> "MANGADEX"
                    "mangadot" -> "MANGADOT"
                    else -> "UNKNOWN"
                }
                val rawError = (event.attributes["provider_error_code"] as? DiagnosticAttributeValue.Text)?.value
                val error = ProviderErrorCode.entries.firstOrNull { it.name == rawError }?.name
                val reason = code("provider_work_verification") ?: code("provider_detail_outcome")
                    ?: error ?: code("error_category") ?: "NONE"
                val candidates = (event.attributes["candidate_count"] as? DiagnosticAttributeValue.Number)?.value ?: 0
                acquisitionFacts += "source=$source|event=${event.name.name}|outcome=${event.outcome.name}" +
                    "|reason=$reason|candidates=$candidates"
                // Additional closed facts distinguish author acceptance from
                // missing coverage or an explicit edition exclusion.
                val qualifiers = listOfNotNull(
                    code("provider_coverage_outcome")?.let { "COVERAGE_$it" },
                    code("provider_edition_qualification")?.let { "EDITION_$it" },
                )
                for (qualifier in qualifiers) {
                    if (acquisitionFacts.size >= 2_048) break
                    acquisitionFacts += "source=$source|event=${event.name.name}|outcome=${event.outcome.name}" +
                        "|reason=$qualifier|candidates=$candidates"
                }
            }
        }
        fun refreshForBatch(
            batch: Int,
            references: List<ReviewedCreatorAliasReference> = emptyList(),
        ): RefreshChapterEvidence {
            val collector = CollectProviderReadingEvidence(
                canonicalTitleRepository = titles,
                providerRegistry = registry,
                gateway = gateway,
                bindingRepository = bindings,
                evidenceAdapter = ProviderChapterEvidenceAdapter(),
                clock = System::currentTimeMillis,
                diagnostics = diagnosticRecorder,
                canonicalMetadataSnapshotRepository = metadata,
                integrationRegistry = metadataRegistry,
                titleNameObservationRepository = titleNames,
                workSourceAssociationRepository = associations,
                provisionalCursorRepository = cursors,
                nativeMangaDotPortfolioResearch = catalogScope == "explicit_plan",
                researchTitleQueryBatch = batch,
                researchCreatorReferences = references,
            )
            return RefreshChapterEvidence(
                registry = metadataRegistry,
                reconcileChapterEvidence = ReconcileChapterEvidence(ParseCanonicalChapterLabel(), chapters, evidence),
                providerReadingEvidence = collector,
            )
        }
        val catalogReaderResearch = if (catalogScope == "explicit_plan") {
            MangaAllCatalogReaderResearch(titles, metadataRegistry, registry, associations, selections)
        } else {
            null
        }
        val resolver = ResolveProviderChapterReading(
            canonicalChapterRepository = chapters,
            evidenceRepository = evidence,
            bindingRepository = bindings,
            workSourceAssociationRepository = associations,
            providerRegistry = registry,
            gateway = gateway,
            catalogResearch = catalogReaderResearch,
        )
        val selected = ResolveMangaAllSelectedChapter(
            chapters,
            resolver,
            ResolveMangaAllSelectedReadingOption(selections),
        )
        val select = SelectMangaAllSource(associations, selections, catalogReaderResearch)

        suspend fun verifiedOption(titleId: String, option: ProviderChapterReadingOption): Boolean {
            val association = associations.get(option.bindingRef()) ?: return false
            if (association.canonicalTitleId != titleId ||
                association.editionQualification != ProviderSourceEditionQualification.UNRESOLVED
            ) {
                return false
            }
            return when (association.workVerification) {
                ProviderWorkVerification.AUTHOR_CORROBORATED -> option.catalogWorkFingerprint == null
                ProviderWorkVerification.CATALOG_CORROBORATED -> {
                    val proof = catalogReaderResearch?.proof(association) ?: return false
                    option.catalogWorkFingerprint == proof
                }
                else -> false
            }
        }
        val receiptNamespace = if (japanese) "mangaall-japanese-manga-30" else "mangaall-frozen30"
        val receipts = File(context.filesDir, "$receiptNamespace-canonical-receipts-$catalogScope").apply { mkdirs() }

        suspend fun fingerprint(titleId: String, catalogName: String, index: Int): String? {
            val title = titles.getById(titleId) ?: return null
            val snapshot = metadata.get(titleId) ?: return null
            val identities = titles.getExternalIdentities(titleId)
            if (identities.size != 1 || !identities.single().verified) return null
            val owner = identities.single()
            if (owner.provider != catalogName) return null
            if (!matchesAttestedOwner(index, owner.provider, owner.externalId)) return null
            val ownerIntegration = IntegrationId(owner.provider)
            if (snapshot.metadata.externalIds[ownerIntegration] != owner.externalId) return null
            val credits = listOfNotNull(snapshot.metadata.authors, snapshot.metadata.artists)
            if (credits.isEmpty() || credits.any {
                    it.providerId != ownerIntegration || it.externalId != owner.externalId || it.value.isEmpty()
                }
            ) {
                return null
            }
            if (titles.getByExternalIdentity(owner.provider, owner.externalId)?.id != titleId) return null
            val chapterRows = chapters.getByCanonicalTitleId(titleId)
            val one = chapterRows.filter {
                it.type == CanonicalChapterType.REGULAR && it.displayNumber == "1" &&
                    it.baseNumber == 1 && it.part == null && it.alphaSuffix == null &&
                    it.decimalFraction == null && it.confirmation != CanonicalChapterConfirmation.CONFLICTED
            }.singleOrNull() ?: return null
            val option = selected.execute(one.id, "en") ?: return null
            if (!verifiedOption(titleId, option)) return null
            if (option.catalogWorkFingerprint != null &&
                catalogReaderResearch?.permitsDelivery(titleId, option) != true
            ) {
                return null
            }
            val rows = evidence.getByCanonicalTitleId(titleId)
            if (rows.isEmpty() || rows.none {
                    it.mappedCanonicalChapterId == one.id &&
                        it.evidence.producerId == option.bindingRef().evidenceProducerId() &&
                        it.evidence.externalChapterKey == option.providerChapterId &&
                        (
                            option.catalogWorkFingerprint == null ||
                                it.evidence.providerMetadata?.catalogWorkFingerprint == option.catalogWorkFingerprint
                            )
                }
            ) {
                return null
            }
            val parts = buildList {
                add(title.id)
                add(owner.provider)
                add(owner.externalId)
                addAll(snapshot.metadata.externalIds.entries.map { "${it.key.value}:${it.value}" }.sorted())
                addAll(credits.flatMap { it.value }.sorted())
                addAll(
                    titleNames.getByTitle(titleId).map {
                        listOf(it.provider, it.sourceKey, it.value).joinToString("\u0000")
                    }.sorted(),
                )
                add(one.id)
                add(option.readerSourceKey)
                addAll(selected.options(one.id, "en").map { it.readerSourceKey }.sorted())
                addAll(
                    chapterRows.map {
                        listOf(it.id, it.sortKey, it.confirmation.name).joinToString("\u0000")
                    }.sorted(),
                )
                addAll(
                    rows.map {
                        listOf(
                            it.evidence.producerId,
                            it.evidence.externalChapterKey,
                            it.mappedCanonicalChapterId,
                            it.evidence.providerMetadata?.catalogWorkFingerprint,
                        ).joinToString("\u0000")
                    }.sorted(),
                )
            }
            return hash(JsonArray(parts.map { JsonPrimitive(it) }).toString())
        }

        suspend fun collect(index: Int, titleId: String): String {
            if (titles.getById(titleId) != null) return "DIRTY_DATABASE"
            val catalogName = ownerScope(index)
            val bootstrap = ResolveMangaAllIndependentCatalogIdentity(catalogRegistry(setOf(catalogName)))
            val required = if (japanese) {
                when (index) {
                    1 -> listOf("Ichiei Ishibumi", "Hiroji Mishima")
                    3 -> listOf("Tetsuto Uesu", "Miyakokasiwa")
                    5 -> listOf("Koushi Tachibana", "Sekihiko Inui")
                    25 -> listOf("Ichigo Takano")
                    else -> emptyList()
                }
            } else {
                emptyList()
            }
            var identity = bootstrap.execute(
                works[index - 1],
                expectedFormat,
                searchTitle = nativeTitle(index),
                requiredCreatorNames = required,
            )
            // Retry only transient catalog unavailability against the same
            // independent owner. Ambiguous or unverified identities stay red.
            for (attempt in 1..2) {
                if (identity.status != MangaAllCatalogIdentityStatus.CATALOG_UNAVAILABLE) break
                delay(attempt * 2_000L)
                identity = bootstrap.execute(
                    works[index - 1],
                    expectedFormat,
                    searchTitle = nativeTitle(index),
                    requiredCreatorNames = required,
                )
            }
            if (identity.status != MangaAllCatalogIdentityStatus.RESOLVED) return identity.status.name
            val owner = requireNotNull(identity.identity)
            if (!matchesAttestedOwner(index, owner.provider, owner.providerId)) return "CATALOG_ATTESTED_OWNER_CHANGED"
            val provenance = identity.toCanonicalReadingMetadata()
                ?: return "CATALOG_PROVENANCE_MISSING"
            if (owner.provider != catalogName || owner.format != expectedFormat ||
                !identity.creatorsAttested
            ) {
                return "CATALOG_FORMAT_OR_CREATOR_UNVERIFIED"
            }
            // These controls reject an incorrect adaptation; they never supply
            // missing credits or override independent catalog identity.
            if (japanese) {
                val observed = (owner.authors + owner.artists).map(::mangaAllCatalogCreatorKey).toSet()
                if (required.any { mangaAllCatalogCreatorKey(it) !in observed }) return "EDITION_CREDITS_UNVERIFIED"
            }
            // The fresh independent owner must publish the complete native
            // title before reviewed citations can attach to its exact ID.
            fun nativeKey(value: String): String? {
                val compact = Normalizer.normalize(value, Normalizer.Form.NFC).filterNot(Char::isWhitespace)
                return compact.takeIf { it.isNotEmpty() && it.all { char -> char in '가'..'힣' } }
            }
            val publishedNames = listOf(owner.title) + owner.titles.values
            val references = plan.getOrNull(index - 1)?.get("reviewedCreatorReferences")?.jsonArray.orEmpty()
                .mapNotNull { raw ->
                    val entry = raw.jsonObject
                    val native = nativeKey(entry.getValue("nativeTitle").jsonPrimitive.content)
                    if (native == null || publishedNames.none { nativeKey(it) == native }) return@mapNotNull null
                    ReviewedCreatorAliasReference(
                        catalogOwner = IntegrationId(owner.provider),
                        catalogWorkId = owner.providerId,
                        role = CreatorCreditRole.valueOf(entry.getValue("role").jsonPrimitive.content),
                        creditedName = entry.getValue("creditedName").jsonPrimitive.content,
                        creditUrl = entry.getValue("creditUrl").jsonPrimitive.content,
                        aliases = entry.getValue("aliases").jsonObject.mapValues { it.value.jsonPrimitive.content },
                    )
                }
            if (creditAudit) {
                // Separate opt-in report: only public credit spellings for the
                // locked research corpus. No HIDs, URLs, titles, raw responses,
                // user data, canonical writes, chapter inventory or Reader calls.
                fun publicCredits(names: Collection<String>) = JsonArray(
                    names.asSequence().map(String::trim)
                        .filter { it.length in 1..128 && it.matches(Regex("""[\p{L}\p{M}\p{N} .,'’·_()\-]+""")) }
                        .distinct().take(32).map(::JsonPrimitive).toList(),
                )
                val published = provenance.authors?.value.orEmpty() + provenance.artists?.value.orEmpty()
                val ownerId = IntegrationId(owner.provider)
                val aliases = resolveVerifiedCreatorAliases(
                    provenance,
                    mapOf(ownerId to owner.providerId),
                    metadataRegistry.metadataProviders(),
                )
                val reviewed = resolveReviewedCreatorAliases(
                    provenance,
                    mapOf(ownerId to owner.providerId),
                    references,
                )
                val verifiedNames = (published + aliases + reviewed).toSet()
                val names = (owner.titles + ("source:primary" to owner.title)).entries.sortedWith(
                    compareBy(
                        { if (it.key.lowercase() in setOf("en", "english")) 0 else 1 },
                        { if (it.key.lowercase() in setOf("romaji", "romanized")) 0 else 1 },
                    ),
                ).map { it.value }
                val availableQueries = (listOf(works[index - 1]) + names).map(String::trim)
                    .filter { it.isNotBlank() && it.length <= 1024 }
                    .distinctBy { it.lowercase().replace(Regex("""\s+"""), " ") }
                // The six-work opt-in audit may inspect four disjoint
                // batches. Normal application lookup limits are unchanged.
                val queries = availableQueries.take(if (auditScope == "REMAINING6") 24 else 6)
                val observations = mutableListOf<JsonObject>()
                val sourceStates = mutableMapOf<String, String>()
                val sourceAttempted = mutableMapOf<String, Int>()
                val sourceCompleted = mutableMapOf<String, Int>()
                for (source in listOf("mangadex", "mangadot")) {
                    val initial = ProviderCursor("""{"v":1,"remaining":{"$source":{"c":null,"o":0}}}""")
                    val seenCandidates = mutableSetOf<String>()
                    val attemptedQueries = mutableSetOf<String>()
                    val completedQueries = mutableSetOf<String>()
                    var failed = false
                    var truncated = false
                    var sourceObservations = 0
                    var allBatchesComplete = true
                    for (batch in queries.chunked(6)) {
                        val cursors = batch.associateWith { initial }.toMutableMap()
                        val seenCursors = batch.associateWith { mutableSetOf(initial.value) }
                        val completed = withTimeoutOrNull(20_000L) {
                            repeat(8) {
                                for (query in batch) {
                                    val cursor = cursors[query] ?: continue
                                    attemptedQueries += query
                                    val result = withTimeoutOrNull(6_000L) {
                                        gateway.lookup(
                                            id,
                                            ProviderReadingLookupRequest(
                                                titles = listOf(query),
                                                cursor = cursor,
                                                coverageEvidenceVersion = 1,
                                                requestedLanguage = "en",
                                            ),
                                        )
                                    }
                                    val page = (result as? ProviderCallResult.Success)?.value
                                    if (page == null || page.items.any { !it.externalWorkId.startsWith("$source:") }) {
                                        failed = true
                                        cursors.remove(query)
                                        continue
                                    }
                                    for (candidate in page.items) {
                                        val assessment = ProviderWorkSourcePortfolio.assess(
                                            id,
                                            "en",
                                            query,
                                            verifiedNames,
                                            listOf(candidate),
                                        ).single()
                                        if (assessment.workVerification ==
                                            ProviderWorkVerification.TITLE_UNVERIFIED
                                        ) {
                                            continue
                                        }
                                        val key = listOf(
                                            candidate.externalWorkId,
                                            candidate.authors.joinToString("|"),
                                            candidate.detailOutcome?.name.orEmpty(),
                                            candidate.coverageOutcome?.name.orEmpty(),
                                            assessment.editionQualification.name,
                                        ).joinToString("\u0000")
                                        if (!seenCandidates.add(key)) continue
                                        if (sourceObservations >= 12) {
                                            truncated = true
                                            continue
                                        }
                                        sourceObservations++
                                        observations += JsonObject(
                                            mapOf(
                                                "source" to JsonPrimitive(source),
                                                "queryOrdinal" to JsonPrimitive(queries.indexOf(query) + 1),
                                                "authors" to publicCredits(candidate.authors),
                                                "detail" to JsonPrimitive(candidate.detailOutcome?.name ?: "NONE"),
                                                "coverage" to JsonPrimitive(candidate.coverageOutcome?.name ?: "NONE"),
                                                "verification" to JsonPrimitive(assessment.workVerification.name),
                                                "edition" to JsonPrimitive(assessment.editionQualification.name),
                                            ),
                                        )
                                    }
                                    val next = page.nextCursor
                                    if (next == null) {
                                        completedQueries += query
                                        cursors.remove(query)
                                    } else if (!seenCursors.getValue(query).add(next.value)) {
                                        failed = true
                                        cursors.remove(query)
                                    } else {
                                        cursors[query] = next
                                    }
                                }
                                if (cursors.isEmpty()) return@withTimeoutOrNull true
                            }
                            false
                        }
                        if (completed != true) allBatchesComplete = false
                    }
                    sourceAttempted[source] = attemptedQueries.size
                    sourceCompleted[source] = completedQueries.size
                    sourceStates[source] = when {
                        failed -> "LOOKUP_FAILURE"
                        allBatchesComplete && !truncated && completedQueries.size == availableQueries.size -> "COMPLETE"
                        else -> "BOUNDED"
                    }
                }
                val report = JsonObject(
                    mapOf(
                        "index" to JsonPrimitive(index),
                        "catalog" to JsonPrimitive(catalogName),
                        "authors" to publicCredits(provenance.authors?.value.orEmpty()),
                        "artists" to publicCredits(provenance.artists?.value.orEmpty()),
                        "aliases" to publicCredits(aliases),
                        "queryCount" to JsonPrimitive(queries.size),
                        "availableQueryCount" to JsonPrimitive(availableQueries.size),
                        "sources" to JsonObject(sourceStates.mapValues { JsonPrimitive(it.value) }),
                        "attemptedQueries" to JsonObject(sourceAttempted.mapValues { JsonPrimitive(it.value) }),
                        "completedQueries" to JsonObject(sourceCompleted.mapValues { JsonPrimitive(it.value) }),
                        "candidates" to JsonArray(observations),
                    ),
                ).toString()
                check(report.encodeToByteArray().size <= 48 * 1024)
                val encoded = report.encodeToByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                instrument.sendStatus(
                    1,
                    Bundle().apply {
                        putString("stream", "MANGAALL_PUBLIC_CREDIT_AUDIT|index=$index|payload=$encoded")
                    },
                )
                return "IDENTITY_AUDITED"
            }
            val now = System.currentTimeMillis()
            val canonical = titles.getOrCreateByExternalIdentity(
                CanonicalTitle(titleId, works[index - 1], CanonicalIdentityState.RESOLVED, now, now),
                ExternalIdentity(titleId, owner.provider, owner.providerId, verified = true, createdAt = now),
            )
            if (canonical.id != titleId) return "DUPLICATE_CATALOG_OWNER"
            metadata.upsertIfNewer(CanonicalMetadataSnapshot(titleId, "frozen30-catalog", provenance, now))
            // Store only names published by the independently verified detail
            // owner, through the same repository used by normal app lookup.
            // Names remain retrieval hints; the collector still checks authors.
            val observedNames = owner.titles + ("source:primary" to owner.title)
            for ((key, value) in observedNames) {
                if (key.isBlank() || key.length > 128 || value.isBlank() || value.length > 1024) continue
                titleNames.upsert(TitleNameObservation(titleId, owner.provider, key, value, now))
            }
            // Exercise normal incremental refresh/cursor persistence, not a
            // larger per-invocation deadline or a synthetic chapter insertion.
            // A partial first refresh must not end the live test before a
            // bounded follow-up has had a chance to collect missing evidence.
            suspend fun exactChapterOnes() = chapters.getByCanonicalTitleId(titleId).filter {
                it.type == CanonicalChapterType.REGULAR && it.displayNumber == "1" &&
                    it.baseNumber == 1 && it.part == null && it.alphaSuffix == null &&
                    it.decimalFraction == null && it.confirmation != CanonicalChapterConfirmation.CONFLICTED
            }
            if (refreshForBatch(0, references).execute(titleId, forceRefresh = true).isFailure) {
                return "REFRESH_FAILED"
            }
            if (catalogScope == "explicit_plan") {
                for (batch in 1..3) {
                    val observed = exactChapterOnes()
                    // Multiple canonical chapter identities or release choices
                    // are not transport gaps; do not retry to hide ambiguity.
                    if (observed.size > 1) break
                    if (observed.singleOrNull()?.let { resolver.options(it.id).isNotEmpty() } == true) break
                    // Each refresh uses the same source/lane budget; only the
                    // bounded window of independently observed names changes.
                    if (refreshForBatch(batch, references).execute(titleId, forceRefresh = true).isFailure) {
                        return "REFRESH_FAILED"
                    }
                }
            }
            val chapterRows = chapters.getByCanonicalTitleId(titleId)
            val chapterOnes = exactChapterOnes()
            val one = chapterOnes.singleOrNull()
            val options = if (one == null) emptyList() else resolver.options(one.id)
            val sourceRows = associations.getByTitle(titleId)
            val mappedRows = evidence.getByCanonicalTitleId(titleId)
            val verifiedSources = sourceRows.filter {
                it.workVerification == ProviderWorkVerification.AUTHOR_CORROBORATED &&
                    it.editionQualification == ProviderSourceEditionQualification.UNRESOLVED
            }
            val dexOptions = options.count { it.contentSourceId == "mangadex" }
            val dotOptions = options.count { it.contentSourceId == "mangadot" }
            val rivalReleaseRefs = options.groupBy { it.bindingRef() }.count { it.value.size > 1 }
            instrument.sendStatus(
                1,
                Bundle().apply {
                    putString(
                        "stream",
                        "MANGAALL_CANONICAL_DIAGNOSTIC|index=$index|chapters=${chapterRows.size}" +
                            "|chapterOne=${chapterOnes.size}|evidence=${mappedRows.size}" +
                            "|associations=${sourceRows.size}|verified=${verifiedSources.size}" +
                            "|dexOptions=$dexOptions|dotOptions=$dotOptions|rivalReleaseRefs=$rivalReleaseRefs",
                    )
                },
            )
            // Read-only, source-scoped mapped chapter range. A first-page
            // lookup preview cannot tell whether chapter 1 was reconciled.
            for (source in listOf("mangadex", "mangadot")) {
                val producers = verifiedSources.filter { it.ref.externalWorkId.startsWith("$source:") }
                    .map { it.ref.evidenceProducerId() }.toSet()
                val proofs = buildMap {
                    for (association in sourceRows.filter { it.ref.externalWorkId.startsWith("$source:") }) {
                        val proof = catalogReaderResearch?.proof(association) ?: continue
                        put(association.ref.evidenceProducerId(), proof)
                    }
                }
                val mappedIds = mappedRows.filter {
                    val producer = it.evidence.producerId
                    producer in producers ||
                        (
                            proofs[producer] != null &&
                                proofs[producer] == it.evidence.providerMetadata?.catalogWorkFingerprint
                            )
                }
                    .mapNotNull { it.mappedCanonicalChapterId }.toSet()
                val regular = chapterRows.filter { it.id in mappedIds && it.type == CanonicalChapterType.REGULAR }
                val numbers = regular.mapNotNull { it.baseNumber }.filter { it in 0..1_000_000 }
                val exactOnes = chapterOnes.count { it.id in mappedIds }
                instrument.sendStatus(
                    1,
                    Bundle().apply {
                        putString(
                            "stream",
                            "MANGAALL_CANONICAL_RANGE|index=$index|source=$source|mapped=${mappedIds.size}" +
                                "|regular=${regular.size}|min=${numbers.minOrNull() ?: -1}" +
                                "|max=${numbers.maxOrNull() ?: -1}|chapterOne=$exactOnes",
                        )
                    },
                )
            }
            if (one == null) return "CANONICAL_CHAPTER_ONE_MISSING_OR_AMBIGUOUS"
            // Multiple releases of one exact HID are separate explicit picker
            // choices. Rival work HIDs within a source are still not equivalent.
            val bySource = options.groupBy { it.contentSourceId }
            if (bySource.isEmpty() || bySource.keys.any { it !in setOf("mangadex", "mangadot") } ||
                bySource.values.any { rows -> rows.map { it.bindingRef() }.distinct().size != 1 }
            ) {
                return "READER_OPTION_MISSING_OR_AMBIGUOUS"
            }
            // Validate every choice before changing the saved preference.
            for (option in options) {
                if (associations.get(option.bindingRef()) == null) return "SOURCE_ASSOCIATION_MISSING"
                if (!verifiedOption(titleId, option)) {
                    return "SOURCE_AUTHOR_OR_EDITION_UNVERIFIED"
                }
            }
            // Exercise each explicit source choice in stable test order, not
            // a production preference or automatic fallback. The last choice
            // is the one whose exact receipt must survive process restart.
            for (option in options.sortedBy { it.readerSourceKey }) {
                val result = select.select(titleId, option.bindingRef(), System.currentTimeMillis())
                if (result != MangaAllSourceSelectionResult.SELECTED &&
                    result != MangaAllSourceSelectionResult.ALREADY_SELECTED
                ) {
                    return "SOURCE_SELECTION_FAILED"
                }
                if (!selected.rememberRelease(option, System.currentTimeMillis())) {
                    return "SOURCE_SELECTION_FAILED"
                }
                if (selected.execute(one.id, "en")?.readerSourceKey != option.readerSourceKey) {
                    return "SOURCE_SELECTION_FAILED"
                }
            }
            val receipt = fingerprint(titleId, catalogName, index) ?: return "SQLITE_INVARIANT_FAILED"
            File(receipts, "$index.sha256").writeText(receipt)
            return "COLLECTED"
        }

        // Use the exact restored canonical option, not a fresh source lookup.
        // This exercises the same selected-source preparation and HTTP loader
        // used by the Reader, but makes no Activity/Viewer rendering claim.
        suspend fun readRestoredChapter(titleId: String, index: Int): String {
            val chapter = chapters.getByCanonicalTitleId(titleId).filter {
                it.type == CanonicalChapterType.REGULAR && it.displayNumber == "1" &&
                    it.baseNumber == 1 && it.part == null && it.alphaSuffix == null &&
                    it.decimalFraction == null && it.confirmation != CanonicalChapterConfirmation.CONFLICTED
            }.singleOrNull() ?: return "CHAPTER_MISSING"
            val option = selected.execute(chapter.id, "en") ?: return "SELECTED_OPTION_MISSING"
            val prepared = when (val result = resolver.preparedContent(option)) {
                is ProviderCallResult.Failure -> return "PREPARATION_FAILED"
                is ProviderCallResult.Success ->
                    result.value as? PreparedChapterContent.HttpPages
                        ?: return "UNEXPECTED_DELIVERY"
            }
            if (prepared.pages.any { !it.url.startsWith("https://") || it.allowLocalNetwork }) {
                return "INVALID_PAGE_AUTHORITY"
            }
            val cache = File(context.cacheDir, "mangaall-canonical-reader-$shard-$index")
            val loader = ProviderHttpPageLoader(requests = prepared.pages.take(1), cacheRoot = cache)
            try {
                val page = loader.getPages().single()
                loader.loadPage(page)
                if (page.status != Page.State.Ready) return "LOADER_FAILED"
                val stream = page.stream ?: return "LOADER_FAILED"
                val output = ByteArrayOutputStream()
                stream.invoke().use { input ->
                    val block = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0) break
                        if (output.size() + count > 16 * 1024 * 1024) return "IMAGE_LIMIT"
                        output.write(block, 0, count)
                    }
                }
                val image = output.toByteArray()
                val sampled = BitmapFactory.Options().apply { inSampleSize = 8 }
                val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, sampled)
                    ?: return "BITMAP_FAILED"
                try {
                    if (bitmap.width <= 0 || bitmap.height <= 0) return "BITMAP_FAILED"
                    bitmap.getPixel(0, 0)
                } finally {
                    bitmap.recycle()
                }
                return "FIRST_PAGE_DECODED"
            } finally {
                loader.recycle()
                cache.deleteRecursively()
            }
        }

        instrument.sendStatus(
            1,
            Bundle().apply {
                putString("stream", "MANGAALL_CANONICAL_PROCESS|phase=$phase|shard=$shard|pid=${Process.myPid()}")
            },
        )
        var passed = 0
        for (index in indices) {
            val titleId = "$receiptNamespace-canonical-$index"
            acquisitionFacts.clear()
            val result = try {
                if (phase == "COLLECT" || creditAudit) {
                    collect(index, titleId)
                } else {
                    val receipt = File(receipts, "$index.sha256")
                    if (!receipt.isFile) {
                        "NO_COLLECTED_RECEIPT"
                    } else if (fingerprint(titleId, ownerScope(index), index) != receipt.readText()) {
                        "SQLITE_RESTORE_MISMATCH"
                    } else {
                        "RESTORED"
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                "UNCLASSIFIED_FAILURE"
            }
            if (phase == "COLLECT") {
                val counts = synchronized(acquisitionFacts) { acquisitionFacts.toList().groupingBy { it }.eachCount() }
                for ((fact, count) in counts.toSortedMap()) {
                    instrument.sendStatus(
                        1,
                        Bundle().apply {
                            putString("stream", "MANGAALL_CANONICAL_ACQUISITION|index=$index|$fact|count=$count")
                        },
                    )
                }
            }
            if (phase == "RESTORE" && readerMode == "1") {
                val readingOutcome = if (result == "RESTORED") {
                    try {
                        readRestoredChapter(titleId, index)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        "UNCLASSIFIED_FAILURE"
                    }
                } else {
                    "PERSISTENCE_NOT_VERIFIED"
                }
                instrument.sendStatus(
                    1,
                    Bundle().apply {
                        putString(
                            "stream",
                            "MANGAALL_CANONICAL_READER|index=$index|outcome=$readingOutcome" +
                                "|catalog=${ownerScope(index)}|ui=NOT_TESTED",
                        )
                    },
                )
            }
            val expectedOutcome = when (phase) {
                "CREDIT_AUDIT" -> "IDENTITY_AUDITED"
                "COLLECT" -> "COLLECTED"
                else -> "RESTORED"
            }
            if (result == expectedOutcome) passed++
            instrument.sendStatus(
                1,
                Bundle().apply {
                    putString(
                        "stream",
                        "MANGAALL_CANONICAL_PERSISTENCE|phase=$phase|index=$index|outcome=$result" +
                            "|catalog=${ownerScope(index)}",
                    )
                },
            )
        }
        instrument.sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "MANGAALL_CANONICAL_PERSISTENCE|phase=$phase|shard=$shard|passed=$passed|expected=${indices.size}",
                )
            },
        )
        // Runner validates every outcome, including the failures, after restart.
        // A successful instrumentation process is not a cohort pass.
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val CORPUS_SHA = "19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1"
        const val JAPANESE_CORPUS_SHA = "5fbf70722e1ed87e2d9847f6b138728115b77be14dae64ac9730cb72c9d1dcc9"
    }
}
