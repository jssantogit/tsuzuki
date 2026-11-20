package eu.kanade.tachiyomi.provider.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.provider.reading.MangaAllLookupV2ReadOnlyDiagnostics
import eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGateway
import eu.kanade.tachiyomi.provider.reading.StoredProviderScriptPackageSource
import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationFactory
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageActivator
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.supplychain.EnrolledProviderRepository
import tachiyomi.core.provider.supplychain.FileProviderLocalConfigurationStore
import tachiyomi.core.provider.supplychain.FileProviderRepositoryEnrollmentStore
import tachiyomi.core.provider.supplychain.FileProviderRepositoryTrustStore
import tachiyomi.core.provider.supplychain.ProviderArtifactDescriptor
import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.core.provider.supplychain.ProviderRepositoryEnrollment
import tachiyomi.core.provider.supplychain.ProviderRepositoryEntryStatus
import tachiyomi.core.provider.supplychain.ProviderRepositoryIndex
import tachiyomi.core.provider.supplychain.ProviderRepositoryManager
import tachiyomi.core.provider.supplychain.ProviderRepositorySigningKey
import tachiyomi.core.provider.supplychain.ProviderRepositoryTransport
import tachiyomi.core.provider.supplychain.SignedProviderRepositoryIndex
import tachiyomi.core.provider.supplychain.decodeProviderSignedIndexEnvelope
import tachiyomi.core.provider.supplychain.sha256Hex
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalLibraryRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderReadingBindingRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Disposable, fully offline Android acceptance of the REAL Host supply-chain
 * using original published code6 and unpublished code7 .tsz bytes.
 *
 * Test signatures for a hypothetical code7 update use a generated, throwaway
 * P-256 key. They do NOT represent the official repository root or a release.
 * Real public code6 uses the exact published signed envelope and trust key.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllSignedUpgradeIsolationTest {
    private val providerId = "app.tsuzuki.mangaall"
    private val repositoryId = "app.tsuzuki.providers"
    private val officialCode6Sha = "aa9c101e0764019bc0d09774aeea1600e6bb9f02e12e9c9096f37d0549720320"
    private val originalCode7Sha = "cbc7e890fda26138a20358a332c8a700b3aec19ba14c47c53834dc35001673f0"
    private val indexUrl = "https://raw.githubusercontent.com/jssantogit/providers-tsuzuki/main/dist/index.json"
    private val artifactBase = "https://raw.githubusercontent.com/jssantogit/providers-tsuzuki/main/dist/providers/"
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun publishedCode6IndexVerifiesWithItsActualPinnedRootAndInstallsOriginalArtifact() = runBlocking {
        requireOptIn()
        val envelope = testAsset("mangaall/code6-published-index.json").decodeToString()
        val publicEnrollment = JSONObject(testAsset("mangaall/code6-enrollment.json").decodeToString())
        val signed = decodeProviderSignedIndexEnvelope(envelope)
        val bytes = testAsset("mangaall/code6-published-package.tsz")
        assertEquals(officialCode6Sha, sha256Hex(bytes))
        assertEquals("tsuzuki-provider-root-2026-02", signed.keyId)
        assertEquals(repositoryId, publicEnrollment.getString("repositoryId"))
        assertEquals(indexUrl, publicEnrollment.getString("indexUrl"))
        val enrollment = EnrolledProviderRepository(
            displayName = publicEnrollment.getString("displayName"),
            enrollment = ProviderRepositoryEnrollment(
                repositoryId = repositoryId,
                indexUrl = indexUrl,
                signingKey = ProviderRepositorySigningKey(
                    keyId = signed.keyId,
                    publicKeyBase64 = publicEnrollment.getString("publicKeyBase64"),
                ),
            ),
        )
        val transport = OfflineTransport().apply {
            index = signed
            artifacts[artifactBase + "$providerId-6.tsz"] = bytes
        }
        withDisposableRoot("official-code6") { root ->
            val store = ProviderArtifactStore(File(root, "artifacts"))
            val manager = manager(root, store, transport)
            manager.enroll(enrollment)
            val before = manager.refresh(repositoryId)
            assertEquals(32L, before.sequence)
            assertEquals(signed.keyId, before.verifiedKeyId)
            val offered = before.entries.single { it.descriptor.providerId == providerId }
            assertEquals(6L, offered.descriptor.versionCode)
            assertEquals(officialCode6Sha, offered.descriptor.sha256)
            assertEquals(ProviderRepositoryEntryStatus.AVAILABLE, offered.status)
            val installed = manager.install(repositoryId, providerId)
            assertEquals(providerId, installed.manifest.id)
            assertEquals("0.5.1", installed.manifest.version.name)
            assertEquals(6L, installed.manifest.version.code)
            assertEquals(officialCode6Sha, sha256Hex(store.readCurrentArtifact(providerId)))
            assertEquals(
                ProviderRepositoryEntryStatus.INSTALLED,
                manager.snapshot(repositoryId)!!.entries.single { it.descriptor.providerId == providerId }.status,
            )

            // The enrollment and accepted signature survive recreation of the
            // Host manager without creating another trust origin or provider ID.
            val restarted = manager(root, ProviderArtifactStore(File(root, "artifacts")), transport)
            assertEquals(6L, restarted.installed().single().versionCode)
            assertEquals(32L, restarted.refresh(repositoryId).sequence)
            assertEquals(1, restarted.repositories().size)
            assertEquals(
                enrollment.keyFingerprintSha256,
                restarted.repositories().single().keyFingerprintSha256,
            )
        }
    }

    @Test
    fun disposableSameRootCode6ToCode7UpdateRejectsTamperingAndRollsBackAtomically() = runBlocking {
        requireOptIn()
        val code6 = testAsset("mangaall/code6-published-package.tsz")
        val code7 = testAsset("mangaall/code7-package.tsz")
        assertEquals(officialCode6Sha, sha256Hex(code6))
        assertEquals(originalCode7Sha, sha256Hex(code7))
        val parser = ProviderPackageParser()
        assertEquals(6L, parser.parse(code6).manifest.version.code)
        assertEquals(7L, parser.parse(code7).manifest.version.code)
        assertEquals(providerId, parser.parse(code7).manifest.id)
        val pair = keyPair()
        val temporaryKey = ProviderRepositorySigningKey(
            keyId = "ephemeral-upgrade-test",
            publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded),
        )
        val enrollment = EnrolledProviderRepository(
            "Disposable simulation of Tsuzuki Providers",
            ProviderRepositoryEnrollment(repositoryId, indexUrl, temporaryKey),
        )
        val d6 = descriptor("0.5.1", 6, code6)
        val d7 = descriptor("0.5.2", 7, code7)
        val transport = OfflineTransport().apply {
            index = sign(pair, index(32, d6))
            artifacts[d6.artifactUrl] = code6
            artifacts[d7.artifactUrl] = code7
        }
        withDisposableRoot("simulated-upgrade") { root ->
            val store = ProviderArtifactStore(File(root, "artifacts"))
            val manager = manager(root, store, transport)
            manager.enroll(enrollment)

            // Use the app's REAL SQLite database, not a fake persistence marker.
            // This protects the persisted Punpun-style preferred MangaDex/en
            // binding and canonical position against accidental update cleanup.
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App
            val database = app.graph.database
            val titleRepository = CanonicalTitleRepositoryImpl(database)
            val libraryRepository = CanonicalLibraryRepositoryImpl(database)
            val chapterRepository = CanonicalChapterRepositoryImpl(database)
            val bindingRepository = ProviderReadingBindingRepositoryImpl(database)
            val readingRepository = CanonicalReadingRepositoryImpl(database)
            val titleId = "mangaall-upgrade-" + System.nanoTime()
            val title = CanonicalTitle(
                id = titleId,
                displayTitle = "Oyasumi Punpun",
                identityState = CanonicalIdentityState.SOURCE_ONLY,
                createdAt = 1L,
                updatedAt = 1L,
            )
            val chapter = CanonicalChapter(
                id = titleId + "-chapter-1",
                canonicalTitleId = title.id,
                displayNumber = "1",
                type = CanonicalChapterType.REGULAR,
                baseNumber = 1,
                confidence = 1.0,
                confirmation = CanonicalChapterConfirmation.CONFIRMED,
                createdAt = 1L,
                updatedAt = 1L,
            )
            val binding = ProviderReadingBinding(
                id = "binding-" + title.id,
                canonicalTitleId = title.id,
                ref = ProviderBindingRef(
                    providerId = ProviderId(providerId),
                    facetId = "en",
                    externalWorkId = "mangadex:punpun-preferred-zero",
                ),
                verification = ProviderBindingVerification.USER_CONFIRMED,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L,
            )
            val libraryEntry = CanonicalLibraryEntry(
                canonicalTitleId = title.id,
                status = LibraryStatus.READING,
                favorite = true,
                addedAt = 1L,
                updatedAt = 1L,
            )
            val progress = CanonicalChapterProgress(
                canonicalChapterId = chapter.id,
                read = false,
                lastPageRead = 3L,
                updatedAt = 2L,
            )
            titleRepository.insert(title)
            libraryRepository.upsert(libraryEntry)
            chapterRepository.upsert(chapter)
            bindingRepository.upsert(binding)
            readingRepository.upsertProgress(progress)
            suspend fun assertSqliteContinuity() {
                assertEquals(title.displayTitle, titleRepository.getById(title.id)?.displayTitle)
                assertEquals(libraryEntry, libraryRepository.get(title.id))
                assertEquals(chapter.displayNumber, chapterRepository.getById(chapter.id)?.displayNumber)
                assertEquals(binding, bindingRepository.get(title.id, ProviderId(providerId), "en"))
                assertEquals(progress, readingRepository.getProgress(chapter.id))
            }
            assertSqliteContinuity()

            assertEquals(
                ProviderRepositoryEntryStatus.AVAILABLE,
                manager.refresh(repositoryId).entries.single().status,
            )
            manager.install(repositoryId, providerId)
            assertEquals(6L, store.current(providerId)?.versionCode)
            assertFalse(manager.canRollback(providerId))
            assertSqliteContinuity()
            val fingerprint = manager.repositories().single().keyFingerprintSha256

            // The code7 descriptor is test-signed with the SAME ephemeral key,
            // matching the official update protocol without using its secret.
            transport.index = sign(pair, index(33, d7))
            assertEquals(
                ProviderRepositoryEntryStatus.UPDATE_AVAILABLE,
                manager.refresh(repositoryId).entries.single().status,
            )
            transport.artifacts[d7.artifactUrl] = code6 // valid bytes, wrong signed code7 checksum
            val tampered = runCatching { manager.install(repositoryId, providerId) }
            assertTrue("Mismatched archive digest must fail closed", tampered.isFailure)
            assertEquals(6L, store.current(providerId)?.versionCode)
            assertEquals(officialCode6Sha, sha256Hex(store.readCurrentArtifact(providerId)))
            assertFalse(manager.canRollback(providerId))
            assertSqliteContinuity()

            transport.artifacts[d7.artifactUrl] = code7
            manager.install(repositoryId, providerId)
            assertEquals(7L, store.current(providerId)?.versionCode)
            assertEquals(originalCode7Sha, sha256Hex(store.readCurrentArtifact(providerId)))
            assertEquals(6L, store.previous(providerId)?.versionCode)
            assertTrue(manager.canRollback(providerId))
            assertEquals(fingerprint, manager.repositories().single().keyFingerprintSha256)
            assertEquals(1, manager.installed().size)
            assertSqliteContinuity()
            assertEquals(
                ProviderRepositoryEntryStatus.INSTALLED,
                manager.snapshot(repositoryId)!!.entries.single().status,
            )

            // Reopening the file-backed Host manager must not revert the
            // previously trusted sequence or its installed package state.
            val reopenedStore = ProviderArtifactStore(File(root, "artifacts"))
            val reopened = manager(root, reopenedStore, transport)
            assertEquals(7L, reopened.installed().single().versionCode)
            assertEquals(33L, reopened.refresh(repositoryId).sequence)
            assertEquals(6L, reopened.previous(providerId)?.versionCode)
            assertSqliteContinuity()
            val oldIndex = sign(pair, index(32, d6))
            transport.index = oldIndex
            assertTrue(
                "Old signed sequence must not roll trust backward",
                runCatching { reopened.refresh(repositoryId) }.isFailure,
            )
            assertEquals(7L, reopenedStore.current(providerId)?.versionCode)

            // A different root may NOT enroll over a current provider.
            val wrongRoot = keyPair()
            assertTrue(
                runCatching {
                    reopened.enroll(
                        enrollment.copy(
                            enrollment = enrollment.enrollment.copy(
                                signingKey = ProviderRepositorySigningKey(
                                    keyId = "other-root",
                                    publicKeyBase64 =
                                    Base64.getEncoder().encodeToString(wrongRoot.public.encoded),
                                ),
                            ),
                        ),
                    )
                }.isFailure,
            )
            assertEquals(fingerprint, reopened.repositories().single().keyFingerprintSha256)

            reopened.rollback(providerId)
            assertEquals(6L, reopenedStore.current(providerId)?.versionCode)
            assertEquals(officialCode6Sha, sha256Hex(reopenedStore.readCurrentArtifact(providerId)))
            assertEquals(7L, reopened.previous(providerId)?.versionCode)
            assertFalse(reopened.canRollback(providerId))
            assertEquals(1, reopened.repositories().size)
            assertSqliteContinuity()
        }
    }

    /**
     * Manual-only acceptance for the FUTURE officially P-256-indexed code9.
     *
     * This test cannot run until the real sequence-35 signed index exists;
     * it never constructs its own "official" signature or substitutes a
     * temporary key. The Host supply chain really verifies the index and
     * installs the code9 archive into an ephemeral Android file ledger,
     * then the real Host QuickJS executes V2 under its derived provenance.
     * All source HTTP is synthetic and intercepted, never sent to the sites.
     */
    @Test
    fun officiallySignedCode9FromIndex35_installsWithPinnedRootAndAuditsThreeSources() = runBlocking {
        assertEquals(
            "true",
            InstrumentationRegistry.getArguments().getString("mangaAllSignedCode9OptIn"),
        )
        assertEquals(
            "app.tsuzuki.dev",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val source = InstrumentationRegistry.getInstrumentation().context
        val oldIndex = decodeProviderSignedIndexEnvelope(
            source.assets.open("mangaall/code8-published-index.json")
                .bufferedReader().use { it.readText() },
        )
        val newIndex = decodeProviderSignedIndexEnvelope(
            source.assets.open("mangaall/code9-official-index.json")
                .bufferedReader().use { it.readText() },
        )
        val officialEnrollment = JSONObject(
            source.assets.open("mangaall/code9-official-enrollment.json")
                .bufferedReader().use { it.readText() },
        )
        val code8 = testAsset("mangaall/code8-published-package.tsz")
        val code9 = testAsset("mangaall/code9-official-package.tsz")
        val parser = ProviderPackageParser()
        assertEquals("0.5.3", parser.parse(code8).manifest.version.name)
        assertEquals(8L, parser.parse(code8).manifest.version.code)
        assertEquals("0.5.4", parser.parse(code9).manifest.version.name)
        assertEquals(9L, parser.parse(code9).manifest.version.code)
        assertEquals("tsuzuki-provider-root-2026-02", oldIndex.keyId)
        assertEquals(oldIndex.keyId, newIndex.keyId)
        assertEquals(repositoryId, officialEnrollment.getString("repositoryId"))
        assertEquals(indexUrl, officialEnrollment.getString("indexUrl"))
        val enrollment = EnrolledProviderRepository(
            displayName = officialEnrollment.getString("displayName"),
            enrollment = ProviderRepositoryEnrollment(
                repositoryId = repositoryId,
                indexUrl = indexUrl,
                signingKey = ProviderRepositorySigningKey(
                    keyId = newIndex.keyId,
                    publicKeyBase64 = officialEnrollment.getString("publicKeyBase64"),
                ),
            ),
        )
        val pinnedRoot = "20875348d95649ff0009e329f0d43de1b84ccf8af7966c215d5e0f449e697159"
        assertEquals(pinnedRoot, enrollment.keyFingerprintSha256)
        val syntheticOrigins = mutableSetOf<String>()
        val interceptorClient = OkHttpClient.Builder().addInterceptor { chain ->
            val uri = chain.request().url
            assertEquals("GET", chain.request().method)
            assertEquals("https", uri.scheme)
            val origin = "https://" + uri.host
            synchronized(syntheticOrigins) { syntheticOrigins.add(origin) }
            val (status, body) = when {
                origin == "https://api.mangadex.org" && uri.encodedPath == "/manga" ->
                    200 to """
                        {
                          "result":"ok",
                          "data":[{
                            "id":"11111111-1111-4111-8111-111111111111",
                            "type":"manga",
                            "attributes":{
                              "title":{"en":"Naruto"},
                              "altTitles":[{"en":"Naruto (Colored)"}]
                            }
                          }],
                          "offset":0,
                          "limit":25,
                          "total":1
                        }
                    """.trimIndent()
                origin == "https://mangadot.net" && uri.encodedPath == "/api/search" ->
                    200 to """{"results":[{"manga_id":42,"title":"Naruto"}]}"""
                origin == "https://mangafire.to" && uri.encodedPath == "/api/titles" ->
                    403 to """{"error":"synthetic challenge"}"""
                else -> error("Unexpected source request, no external networking allowed")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Synthetic source fixture")
                .body(body.toResponseBody())
                .build()
        }.build()
        val transport = OfflineTransport().apply {
            index = oldIndex
            artifacts[artifactBase + "$providerId-8.tsz"] = code8
            artifacts[artifactBase + "$providerId-9.tsz"] = code9
        }
        withDisposableRoot("official-code9") { root ->
            val store = ProviderArtifactStore(File(root, "artifacts"))
            val manager = manager(root, store, transport)
            manager.enroll(enrollment)
            val initial = manager.refresh(repositoryId)
            assertEquals(34L, initial.sequence)
            assertEquals(
                8L,
                initial.entries.single { it.descriptor.providerId == providerId }
                    .descriptor.versionCode,
            )
            manager.install(repositoryId, providerId)
            assertEquals(8L, store.current(providerId)?.versionCode)
            assertEquals(sha256Hex(code8), sha256Hex(store.readCurrentArtifact(providerId)))

            // Switch only to the real index envelope signed by the publisher.
            // No test signing/keygen or injected fake ledger root is accepted.
            transport.index = newIndex
            val updated = manager.refresh(repositoryId)
            assertEquals(35L, updated.sequence)
            val next = updated.entries.single { it.descriptor.providerId == providerId }
            assertEquals(ProviderRepositoryEntryStatus.UPDATE_AVAILABLE, next.status)
            assertEquals(9L, next.descriptor.versionCode)
            assertEquals(sha256Hex(code9), next.descriptor.sha256)
            manager.install(repositoryId, providerId)
            val installed = store.current(providerId)!!
            assertEquals(9L, installed.versionCode)
            assertEquals(sha256Hex(code9), installed.sha256)
            assertEquals(repositoryId, installed.repositoryId)
            assertEquals(pinnedRoot, installed.repositoryTrustAnchorSha256)
            assertEquals(8L, store.previous(providerId)?.versionCode)
            assertEquals(1, manager.installed().size)

            // Verify the Host genuinely re-reads its own installed ledger and
            // package bytes rather than trusting provider-authored strings.
            val source = StoredProviderScriptPackageSource(store)
            val active = source.get(ProviderId(providerId))!!
            assertEquals(9L, active.versionCode)
            assertEquals(sha256Hex(code9), active.installedArtifactSha256)
            assertEquals(pinnedRoot, active.repositoryTrustAnchorSha256)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val registry = InstalledScriptProviderRegistry(
                artifactStore = store,
                configurationStore = FileProviderLocalConfigurationStore(File(root, "local-config")),
            )
            val client = ProviderRuntimeClient(
                context = context,
                hostInvocationFactory = ProviderHostInvocationFactory(
                    context = context,
                    storageRoot = File(root, "runtime"),
                    baseHttpClient = interceptorClient,
                ),
            )
            val gateway = ScriptProviderReadingGateway(
                registry = registry,
                packageSource = source,
                runtimeClient = client,
                enableExperimentalMangaAllV2 = true,
            )
            val request = ProviderReadingLookupRequest(
                titles = listOf("Naruto"),
                requestedLanguage = "en",
            )
            val observed = gateway.lookupMangaAllV2ReadOnly(ProviderId(providerId), request)
            assertTrue("Signed code9 must decode read-only diagnostics", observed is ProviderCallResult.Success)
            val audit = (observed as ProviderCallResult.Success).value
            assertEquals(
                MangaAllLookupV2ReadOnlyDiagnostics.HostProvenance.Classification.SIGNED_INSTALLATION,
                audit.hostProvenance?.classification,
            )
            assertEquals(sha256Hex(code9), audit.hostProvenance?.artifactSha256)
            assertEquals(pinnedRoot, audit.hostProvenance?.trustAnchorSha256)
            assertEquals(listOf("mangadex", "mangadot", "mangafire"), audit.sources.map { it.source })
            assertEquals(
                listOf("SUCCESS_PAGE", "SUCCESS_PAGE", "FAILED"),
                audit.sources.map { it.status },
            )
            assertEquals("CHALLENGE_BLOCKED", audit.sources[2].failureCode)
            assertEquals(403, audit.sources[2].httpStatus)
            assertFalse(audit.lookupComplete)
            assertEquals("PARTIAL_EVIDENCE", audit.state)
            assertEquals(2, audit.observedWorks.size)
            assertEquals(
                listOf("mangadex", "mangadot"),
                audit.observedWorks.map { it.source },
            )
            assertEquals(
                listOf(
                    "mangadex:11111111-1111-4111-8111-111111111111",
                    "mangadot:42",
                ),
                audit.observedWorks.map { it.sourceWorkId },
            )
            assertEquals(listOf("Naruto", "Naruto"), audit.observedWorks.map { it.title })
            assertEquals(listOf("Naruto (Colored)"), audit.observedWorks[0].aliases)
            assertTrue(audit.observedWorks[1].aliases.isEmpty())
            assertTrue(audit.observedWorks.all { it.authors.isEmpty() })
            assertEquals(
                setOf("https://api.mangadex.org", "https://mangadot.net", "https://mangafire.to"),
                synchronized(syntheticOrigins) { syntheticOrigins.toSet() },
            )

            // Real signed code9 + actual QuickJS + an installed Host ledger:
            // two *competing* source IDs survive the third source's 403.
            // Follow the actual V2 continuation through the same unified
            // Provider and retain both observations, without editorial auto-pick.
            val accumulated = gateway.auditMangaAllV2ReadOnly(ProviderId(providerId), request)
            assertTrue(accumulated is ProviderCallResult.Success)
            val snapshot = (accumulated as ProviderCallResult.Success).value
            assertEquals(2, snapshot.pages)
            assertEquals(
                MangaAllLookupV2ReadOnlyDiagnostics.HostProvenance.Classification.SIGNED_INSTALLATION,
                snapshot.hostProvenance?.classification,
            )
            assertEquals(
                "d9c223146faa9fea0e5b1593711b74836bc0af3c2939b2c6f246dbb2e4646622",
                snapshot.hostProvenance?.artifactSha256,
            )
            assertEquals(2, snapshot.observedWorks.size)
            assertEquals(
                listOf("mangadex", "mangadot"),
                snapshot.observedWorks.map { it.source },
            )
            assertEquals(
                listOf("CHALLENGE_BLOCKED"),
                snapshot.sourceHistory.filter { it.hadFailure }.map { it.lastFailureCode },
            )
            assertEquals(null, snapshot.nextCursor)

            // The source is an installation-trusted diagnostic channel, not a
            // canonical binding or Reader authorization. Rollback to code8
            // immediately disables this V2 entrypoint.
            manager.rollback(providerId)
            assertEquals(8L, store.current(providerId)?.versionCode)
            val denied = gateway.lookupMangaAllV2ReadOnly(ProviderId(providerId), request)
            assertTrue(denied is ProviderCallResult.Failure)
            assertEquals("UNAVAILABLE", (denied as ProviderCallResult.Failure).error.code.name)
        }
    }

    private fun requireOptIn() {
        val args = InstrumentationRegistry.getArguments()
        assertEquals("true", args.getString("mangaAllSignedUpgradeOptIn"))
        assertEquals(
            "app.tsuzuki.dev",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }

    private fun testAsset(path: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(path)
            .use { it.readBytes() }

    private inline fun withDisposableRoot(suffix: String, block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "mangaall-supplychain-$suffix-${System.nanoTime()}")
        check(root.mkdirs()) { "Disposable test store must not reuse a prior installation" }
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manager(
        root: File,
        artifactStore: ProviderArtifactStore,
        transport: ProviderRepositoryTransport,
    ): ProviderRepositoryManager = ProviderRepositoryManager(
        hostApiVersion = 3,
        enrollmentStore = FileProviderRepositoryEnrollmentStore(File(root, "enrollments")),
        trustStore = FileProviderRepositoryTrustStore(File(root, "trust")),
        artifactStore = artifactStore,
        packageActivator = ProviderPackageActivator(
            hostApiVersion = 3,
            parser = ProviderPackageParser(),
            artifactStore = artifactStore,
            contractValidator = { _, _ -> true },
        ),
        transport = transport,
    )

    private fun descriptor(name: String, code: Long, bytes: ByteArray) =
        ProviderArtifactDescriptor(
            providerId = providerId,
            versionName = name,
            versionCode = code,
            artifactUrl = artifactBase + "$providerId-$code.tsz",
            sha256 = sha256Hex(bytes),
            minHostApi = 1,
        )

    private fun index(sequence: Long, descriptor: ProviderArtifactDescriptor) =
        ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = repositoryId,
            sequence = sequence,
            providers = listOf(descriptor),
        )

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    private fun sign(keyPair: KeyPair, index: ProviderRepositoryIndex): SignedProviderRepositoryIndex {
        val payload = json.encodeToString(index).encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return SignedProviderRepositoryIndex(
            keyId = "ephemeral-upgrade-test",
            payload = payload,
            signature = signature,
        )
    }

    private class OfflineTransport : ProviderRepositoryTransport {
        lateinit var index: SignedProviderRepositoryIndex
        val artifacts = mutableMapOf<String, ByteArray>()

        override suspend fun fetchIndex(indexUrl: String): SignedProviderRepositoryIndex {
            check(
                indexUrl ==
                    "https://raw.githubusercontent.com/jssantogit/providers-tsuzuki/main/dist/index.json",
            )
            return index
        }

        override suspend fun fetchArtifact(artifactUrl: String): ByteArray =
            artifacts[artifactUrl]
                ?: error("Unrecognized offline artifact URL; network access is forbidden")
    }
}
