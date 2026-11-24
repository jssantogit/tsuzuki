package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.integration.MangaUpdatesIntegrationProvider
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackage
import eu.kanade.tachiyomi.provider.reading.ActiveProviderScriptPackageSource
import eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGateway
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshot
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderSourceEditionQualification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkVerification
import tachiyomi.domain.tsuzuki.provider.reading.ResolveMangaAllSelectedChapter
import tachiyomi.domain.tsuzuki.provider.reading.ResolveMangaAllSelectedReadingOption
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading
import tachiyomi.domain.tsuzuki.provider.reading.SelectMangaAllSource
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
        val phase = requireNotNull(args.getString("mangaAllCanonicalPhase"))
        require(phase in setOf("COLLECT", "RESTORE"))
        val shard = requireNotNull(args.getString("mangaAllFrozen30Shard")).toInt()
        require(shard in 0..2)
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
        assertEquals(CORPUS_SHA, hash(JsonArray(works.map(::JsonPrimitive)).toString()))
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
        val metadata = CanonicalMetadataSnapshotRepositoryImpl(database)
        val chapters = CanonicalChapterRepositoryImpl(database)
        val evidence = ChapterEvidenceRepositoryImpl(database)
        val bindings = ProviderReadingBindingRepositoryImpl(database)
        val associations = ProviderWorkSourceAssociationRepositoryImpl(database)
        val cursors = ProviderProvisionalCursorRepositoryImpl(database)
        val selections = MangaAllSourceSelectionRepositoryImpl(database)
        val catalog = MangaUpdatesIntegrationProvider(app.graph.trackerManager)
        val catalogRegistry = object : IntegrationRegistry {
            override fun searchProviders(): List<SearchProvider> = listOf(catalog)
            override fun metadataProviders(): List<MetadataProvider> = listOf(catalog)
            override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
            override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
            override fun ratingsProviders(): List<RatingsProvider> = emptyList()
            override fun trackingProviders(): List<TrackingProvider> = emptyList()
        }
        val bootstrap = ResolveMangaAllIndependentCatalogIdentity(catalogRegistry)
        val collector = CollectProviderReadingEvidence(
            canonicalTitleRepository = titles,
            providerRegistry = registry,
            gateway = gateway,
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = System::currentTimeMillis,
            canonicalMetadataSnapshotRepository = metadata,
            workSourceAssociationRepository = associations,
            provisionalCursorRepository = cursors,
        )
        val refresh = RefreshChapterEvidence(
            registry = catalogRegistry,
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
        val receipts = File(context.filesDir, "mangaall-frozen30-canonical-receipts").apply { mkdirs() }

        suspend fun fingerprint(titleId: String): String? {
            val title = titles.getById(titleId) ?: return null
            val snapshot = metadata.get(titleId) ?: return null
            val identities = titles.getExternalIdentities(titleId)
            if (identities.size != 1 || !identities.single().verified) return null
            val owner = identities.single()
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
                        it.evidence.externalChapterKey == option.providerChapterId
                }
            ) {
                return null
            }
            val parts = buildList {
                add(title.id)
                add(owner.provider)
                add(owner.externalId)
                add(snapshot.metadata.externalIds.toString())
                add(one.id)
                add(option.readerSourceKey)
                addAll(chapterRows.map { it.id }.sorted())
                addAll(rows.map {
                    listOf(it.evidence.producerId, it.evidence.externalChapterKey, it.mappedCanonicalChapterId)
                        .joinToString("\u0000")
                }.sorted())
            }
            return hash(JsonArray(parts.map(::JsonPrimitive)).toString())
        }

        suspend fun collect(index: Int, titleId: String): String {
            if (titles.getById(titleId) != null) return "DIRTY_DATABASE"
            val identity = bootstrap.execute(works[index - 1], CatalogItemFormat.MANHWA)
            if (identity.status != MangaAllCatalogIdentityStatus.RESOLVED) return identity.status.name
            val owner = requireNotNull(identity.identity)
            val provenance = identity.toCanonicalReadingMetadata()
                ?: return "CATALOG_PROVENANCE_MISSING"
            if (owner.format != CatalogItemFormat.MANHWA || !identity.creatorsAttested) {
                return "CATALOG_FORMAT_OR_CREATOR_UNVERIFIED"
            }
            val now = System.currentTimeMillis()
            val canonical = titles.getOrCreateByExternalIdentity(
                CanonicalTitle(titleId, owner.title, CanonicalIdentityState.RESOLVED, now, now),
                ExternalIdentity(titleId, owner.provider, owner.providerId, verified = true, createdAt = now),
            )
            if (canonical.id != titleId) return "DUPLICATE_CATALOG_OWNER"
            metadata.upsertIfNewer(CanonicalMetadataSnapshot(titleId, "frozen30-catalog", provenance, now))
            if (refresh.execute(titleId, forceRefresh = true).isFailure) return "REFRESH_FAILED"
            val one = chapters.getByCanonicalTitleId(titleId).filter {
                it.type == CanonicalChapterType.REGULAR && it.displayNumber == "1" &&
                    it.baseNumber == 1 && it.part == null && it.alphaSuffix == null &&
                    it.decimalFraction == null && it.confirmation != CanonicalChapterConfirmation.CONFLICTED
            }.singleOrNull() ?: return "CANONICAL_CHAPTER_ONE_MISSING_OR_AMBIGUOUS"
            // No synthetic user-confirmed edition and no first-HID fallback.
            val option = resolver.options(one.id).singleOrNull() ?: return "READER_OPTION_MISSING_OR_AMBIGUOUS"
            val association = associations.get(option.bindingRef()) ?: return "SOURCE_ASSOCIATION_MISSING"
            if (association.workVerification != ProviderWorkVerification.AUTHOR_CORROBORATED ||
                association.editionQualification != ProviderSourceEditionQualification.UNRESOLVED
            ) {
                return "SOURCE_AUTHOR_OR_EDITION_UNVERIFIED"
            }
            // Simulate the picker only for a unique, already validated option.
            if (select.select(titleId, option.bindingRef(), now) != MangaAllSourceSelectionResult.SELECTED) {
                return "SOURCE_SELECTION_FAILED"
            }
            val receipt = fingerprint(titleId) ?: return "SQLITE_INVARIANT_FAILED"
            File(receipts, "$index.sha256").writeText(receipt)
            return "COLLECTED"
        }

        var passed = 0
        for (index in (shard * 10 + 1)..(shard * 10 + 10)) {
            val titleId = "mangaall-frozen30-canonical-$index"
            val result = try {
                if (phase == "COLLECT") {
                    collect(index, titleId)
                } else {
                    val receipt = File(receipts, "$index.sha256")
                    if (!receipt.isFile) {
                        "NO_COLLECTED_RECEIPT"
                    } else if (fingerprint(titleId) != receipt.readText()) {
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
            if (result == if (phase == "COLLECT") "COLLECTED" else "RESTORED") passed++
            instrument.sendStatus(
                1,
                Bundle().apply {
                    putString("stream", "MANGAALL_CANONICAL_PERSISTENCE|phase=$phase|index=$index|outcome=$result")
                },
            )
        }
        instrument.sendStatus(
            1,
            Bundle().apply {
                putString("stream", "MANGAALL_CANONICAL_PERSISTENCE|phase=$phase|shard=$shard|passed=$passed|expected=10")
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
