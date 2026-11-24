package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
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
import tachiyomi.data.tsuzuki.provider.MangaAllSourceSelectionRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderProvisionalCursorRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderReadingBindingRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderWorkSourceAssociationRepositoryImpl
import tachiyomi.domain.tsuzuki.catalog.interactor.MangaAllCatalogIdentityStatus
import tachiyomi.domain.tsuzuki.catalog.interactor.ResolveMangaAllIndependentCatalogIdentity
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
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshot
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
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelectionResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCursor
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
        val pending14 = setOf(5, 6, 8, 11, 15, 16, 19, 20, 21, 23, 25, 27, 28, 29)
        require(!creditAudit || (pendingMode == "1" && readerMode == "0"))
        val indices = (shard * 10 + 1..shard * 10 + 10).filter {
            (pendingMode == "0" || it in pending14) && (!creditAudit || it != 16)
        }
        val context = instrument.targetContext
        assertEquals("app.tsuzuki.dev", context.packageName)
        assertTrue(
            Build.FINGERPRINT.contains("generic") ||
                Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.PRODUCT.startsWith("sdk_gphone"),
        )
        val lock = instrument.context.assets.open("mangaall-dual-source-30-cohort-lock.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val works = requireNotNull(lock["works"]).jsonArray.map { it.jsonPrimitive.content }
        assertEquals("MANHWA_30_FIXED_20261123", lock["cohort"]?.jsonPrimitive?.content)
        assertEquals("manhwa", lock["format"]?.jsonPrimitive?.content)
        assertEquals(30, works.size)
        assertEquals(30, works.toSet().size)
        assertEquals(CORPUS_SHA, hash(JsonArray(works.map { JsonPrimitive(it) }).toString()))
        val plan = if (catalogScope == "explicit_plan") {
            val planText = instrument.context.assets.open("mangaall-frozen30-catalog-plan.json")
                .bufferedReader().use { it.readText() }
            assertEquals(requireNotNull(args.getString("mangaAllCanonicalPlanSha256")), hash(planText))
            val doc = Json.parseToJsonElement(planText).jsonObject
            assertEquals(CORPUS_SHA, doc.getValue("corpusSha256").jsonPrimitive.content)
            assertEquals("RESEARCH_EXPLICIT_OWNERS_NO_FALLBACK", doc.getValue("scope").jsonPrimitive.content)
            val rows = doc.getValue("works").jsonArray.map { it.jsonObject }
            assertEquals((1..30).toList(), rows.map { it.getValue("index").jsonPrimitive.content.toInt() })
            rows
        } else {
            emptyList()
        }

        fun ownerScope(index: Int): String = if (catalogScope == "explicit_plan") {
            plan[index - 1].getValue("catalog").jsonPrimitive.content.also {
                require(it in setOf("mangaupdates", "shikimori"))
            }
        } else {
            catalogScope
        }

        fun nativeTitle(index: Int): String? = plan.getOrNull(index - 1)
            ?.get("searchTitle")?.jsonPrimitive?.content

        val bytes = instrument.context.assets.open("mangaall-dual-source-code11-unsigned-research.zip")
            .use { it.readBytes() }
        val parsed = ProviderPackageParser().parse(bytes)
        val manifest = parsed.manifest
        assertEquals("app.tsuzuki.mangaall", manifest.id)
        assertEquals(11L, manifest.version.code)
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
        val catalogs = mapOf<String, SearchProvider>(
            "mangaupdates" to MangaUpdatesIntegrationProvider(app.graph.trackerManager),
            "shikimori" to ShikimoriIntegrationProvider(app.graph.trackerManager, ShikimoriRequestGate()),
        )
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
                val reason = code("provider_work_verification") ?: code("provider_detail_outcome")
                    ?: code("error_category") ?: "NONE"
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
        )
        val refresh = RefreshChapterEvidence(
            registry = metadataRegistry,
            reconcileChapterEvidence = ReconcileChapterEvidence(ParseCanonicalChapterLabel(), chapters, evidence),
            providerReadingEvidence = collector,
        )
        val resolver = ResolveProviderChapterReading(
            canonicalChapterRepository = chapters,
            evidenceRepository = evidence,
            bindingRepository = bindings,
            workSourceAssociationRepository = associations,
            providerRegistry = registry,
            gateway = gateway,
        )
        val selected = ResolveMangaAllSelectedChapter(
            chapters,
            resolver,
            ResolveMangaAllSelectedReadingOption(selections),
        )
        val select = SelectMangaAllSource(associations, selections)
        val receipts = File(context.filesDir, "mangaall-frozen30-canonical-receipts-$catalogScope").apply { mkdirs() }

        suspend fun fingerprint(titleId: String, catalogName: String): String? {
            val title = titles.getById(titleId) ?: return null
            val snapshot = metadata.get(titleId) ?: return null
            val identities = titles.getExternalIdentities(titleId)
            if (identities.size != 1 || !identities.single().verified) return null
            val owner = identities.single()
            if (owner.provider != catalogName) return null
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
            val association = associations.get(option.bindingRef()) ?: return null
            if (association.canonicalTitleId != titleId ||
                association.workVerification != ProviderWorkVerification.AUTHOR_CORROBORATED ||
                association.editionQualification != ProviderSourceEditionQualification.UNRESOLVED
            ) {
                return null
            }
            val rows = evidence.getByCanonicalTitleId(titleId)
            if (rows.isEmpty() || rows.none {
                    it.mappedCanonicalChapterId == one.id &&
                        it.evidence.producerId == option.bindingRef().evidenceProducerId() &&
                        it.evidence.externalChapterKey == option.providerChapterId
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
                        listOf(it.evidence.producerId, it.evidence.externalChapterKey, it.mappedCanonicalChapterId)
                            .joinToString("\u0000")
                    }.sorted(),
                )
            }
            return hash(JsonArray(parts.map { JsonPrimitive(it) }).toString())
        }

        suspend fun collect(index: Int, titleId: String): String {
            if (titles.getById(titleId) != null) return "DIRTY_DATABASE"
            val catalogName = ownerScope(index)
            val bootstrap = ResolveMangaAllIndependentCatalogIdentity(catalogRegistry(setOf(catalogName)))
            var identity = bootstrap.execute(
                works[index - 1],
                CatalogItemFormat.MANHWA,
                searchTitle = nativeTitle(index),
            )
            // Retry only transient catalog unavailability against the same
            // independent owner. Ambiguous or unverified identities stay red.
            for (attempt in 1..2) {
                if (identity.status != MangaAllCatalogIdentityStatus.CATALOG_UNAVAILABLE) break
                delay(attempt * 2_000L)
                identity = bootstrap.execute(
                    works[index - 1],
                    CatalogItemFormat.MANHWA,
                    searchTitle = nativeTitle(index),
                )
            }
            if (identity.status != MangaAllCatalogIdentityStatus.RESOLVED) return identity.status.name
            val owner = requireNotNull(identity.identity)
            val provenance = identity.toCanonicalReadingMetadata()
                ?: return "CATALOG_PROVENANCE_MISSING"
            if (owner.provider != catalogName || owner.format != CatalogItemFormat.MANHWA ||
                !identity.creatorsAttested
            ) {
                return "CATALOG_FORMAT_OR_CREATOR_UNVERIFIED"
            }
            if (creditAudit) {
                // Separate opt-in report: only public credit spellings for the
                // locked research corpus. No HIDs, URLs, titles, raw responses,
                // user data, canonical writes, chapter inventory or Reader calls.
                fun publicCredits(names: Collection<String>) = JsonArray(
                    names.asSequence().map(String::trim)
                        .filter { it.length in 1..128 && it.matches(Regex("[\\\\p{L}\\\\p{M}\\\\p{N} .,'’·_()\\\\-]+")) }
                        .distinct().take(32).map(::JsonPrimitive).toList(),
                )
                val published = provenance.authors?.value.orEmpty() + provenance.artists?.value.orEmpty()
                val ownerId = IntegrationId(owner.provider)
                val aliases = resolveVerifiedCreatorAliases(
                    provenance,
                    mapOf(ownerId to owner.providerId),
                    metadataRegistry.metadataProviders(),
                )
                val verifiedNames = (published + aliases).toSet()
                val names = (owner.titles + ("source:primary" to owner.title)).entries.sortedWith(
                    compareBy(
                        { if (it.key.lowercase() in setOf("en", "english")) 0 else 1 },
                        { if (it.key.lowercase() in setOf("romaji", "romanized")) 0 else 1 },
                    ),
                ).map { it.value }
                val queries = (listOf(works[index - 1]) + names).map(String::trim)
                    .filter { it.isNotBlank() && it.length <= 1024 }
                    .distinctBy { it.lowercase().replace(Regex("\\\\s+"), " ") }.take(3)
                val observations = mutableListOf<JsonObject>()
                val sourceStates = mutableMapOf<String, String>()
                for (source in listOf("mangadex", "mangadot")) {
                    val initial = ProviderCursor("""{"v":1,"remaining":{"$source":{"c":null,"o":0}}}""")
                    val cursors = queries.associateWith { initial }.toMutableMap()
                    val seenCursors = queries.associateWith { mutableSetOf(initial.value) }
                    val seenCandidates = mutableSetOf<String>()
                    var failed = false
                    val completed = withTimeoutOrNull(20_000L) {
                        repeat(8) {
                            for (query in queries) {
                                val cursor = cursors[query] ?: continue
                                val result = withTimeoutOrNull(6_000L) {
                                    gateway.lookup(id, ProviderReadingLookupRequest(
                                        titles = listOf(query),
                                        cursor = cursor,
                                        coverageEvidenceVersion = 1,
                                        requestedLanguage = "en",
                                    ))
                                }
                                val page = (result as? ProviderCallResult.Success)?.value
                                if (page == null || page.items.any { !it.externalWorkId.startsWith("$source:") }) {
                                    failed = true
                                    cursors.remove(query)
                                    continue
                                }
                                for (candidate in page.items) {
                                    val assessment = ProviderWorkSourcePortfolio.assess(
                                        id, "en", query, verifiedNames, listOf(candidate),
                                    ).single()
                                    if (assessment.workVerification == ProviderWorkVerification.TITLE_UNVERIFIED) continue
                                    val key = candidate.externalWorkId + candidate.authors.joinToString("|") +
                                        candidate.detailOutcome + candidate.coverageOutcome
                                    if (!seenCandidates.add(key) || observations.size >= 24) continue
                                    observations += JsonObject(
                                        mapOf(
                                            "source" to JsonPrimitive(source),
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
                    sourceStates[source] = when {
                        failed -> "LOOKUP_FAILURE"
                        completed == true -> "COMPLETE"
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
                        "sources" to JsonObject(sourceStates.mapValues { JsonPrimitive(it.value) }),
                        "candidates" to JsonArray(observations),
                    ),
                ).toString()
                check(report.encodeToByteArray().size <= 48 * 1024)
                val encoded = report.encodeToByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                instrument.sendStatus(1, Bundle().apply {
                    putString("stream", "MANGAALL_PUBLIC_CREDIT_AUDIT|index=$index|payload=$encoded")
                })
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
            if (refresh.execute(titleId, forceRefresh = true).isFailure) return "REFRESH_FAILED"
            if (catalogScope == "explicit_plan") {
                repeat(2) {
                    val observed = exactChapterOnes()
                    // Multiple canonical chapter identities or release choices
                    // are not transport gaps; do not retry to hide ambiguity.
                    if (observed.size > 1) return@repeat
                    if (observed.singleOrNull()?.let { resolver.options(it.id).isNotEmpty() } == true) return@repeat
                    if (refresh.execute(titleId, forceRefresh = true).isFailure) return "REFRESH_FAILED"
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
                val association = associations.get(option.bindingRef()) ?: return "SOURCE_ASSOCIATION_MISSING"
                if (association.canonicalTitleId != titleId ||
                    association.workVerification != ProviderWorkVerification.AUTHOR_CORROBORATED ||
                    association.editionQualification != ProviderSourceEditionQualification.UNRESOLVED
                ) {
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
            val receipt = fingerprint(titleId, catalogName) ?: return "SQLITE_INVARIANT_FAILED"
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
            val titleId = "mangaall-frozen30-canonical-$index"
            acquisitionFacts.clear()
            val result = try {
                if (phase == "COLLECT" || creditAudit) {
                    collect(index, titleId)
                } else {
                    val receipt = File(receipts, "$index.sha256")
                    if (!receipt.isFile) {
                        "NO_COLLECTED_RECEIPT"
                    } else if (fingerprint(titleId, ownerScope(index)) != receipt.readText()) {
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
    }
}
