package eu.kanade.tachiyomi.provider.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageActivator
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.supplychain.EnrolledProviderRepository
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
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderReadingBindingRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
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
    private val json = Json { encodeDefaults = true; explicitNulls = false }

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
            assertEquals(ProviderRepositoryEntryStatus.INSTALLED,
                manager.snapshot(repositoryId)!!.entries.single { it.descriptor.providerId == providerId }.status)

            // The enrollment and accepted signature survive recreation of the
            // Host manager without creating another trust origin or provider ID.
            val restarted = manager(root, ProviderArtifactStore(File(root, "artifacts")), transport)
            assertEquals(6L, restarted.installed().single().versionCode)
            assertEquals(32L, restarted.refresh(repositoryId).sequence)
            assertEquals(1, restarted.repositories().size)
            assertEquals(enrollment.keyFingerprintSha256,
                restarted.repositories().single().keyFingerprintSha256)
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
            val database = (
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App
                ).graph.database
            val titleRepository = CanonicalTitleRepositoryImpl(database)
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
                id = "$"+"titleId-chapter-1",
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
            val progress = CanonicalChapterProgress(
                canonicalChapterId = chapter.id,
                read = false,
                lastPageRead = 3L,
                updatedAt = 2L,
            )
            titleRepository.insert(title)
            chapterRepository.upsert(chapter)
            bindingRepository.upsert(binding)
            readingRepository.upsertProgress(progress)
            suspend fun assertSqliteContinuity() {
                assertEquals(title.displayTitle, titleRepository.getById(title.id)?.displayTitle)
                assertEquals(chapter.displayNumber, chapterRepository.getById(chapter.id)?.displayNumber)
                assertEquals(binding, bindingRepository.get(title.id, ProviderId(providerId), "en"))
                assertEquals(progress, readingRepository.getProgress(chapter.id))
            }
            assertSqliteContinuity()

            assertEquals(ProviderRepositoryEntryStatus.AVAILABLE,
                manager.refresh(repositoryId).entries.single().status)
            manager.install(repositoryId, providerId)
            assertEquals(6L, store.current(providerId)?.versionCode)
            assertFalse(manager.canRollback(providerId))
            assertSqliteContinuity()
            val fingerprint = manager.repositories().single().keyFingerprintSha256

            // The code7 descriptor is test-signed with the SAME ephemeral key,
            // matching the official update protocol without using its secret.
            transport.index = sign(pair, index(33, d7))
            assertEquals(ProviderRepositoryEntryStatus.UPDATE_AVAILABLE,
                manager.refresh(repositoryId).entries.single().status)
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
            assertEquals(ProviderRepositoryEntryStatus.INSTALLED,
                manager.snapshot(repositoryId)!!.entries.single().status)

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
            assertTrue("Old signed sequence must not roll trust backward",
                runCatching { reopened.refresh(repositoryId) }.isFailure)
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

    private fun requireOptIn() {
        val args = InstrumentationRegistry.getArguments()
        assertEquals("true", args.getString("mangaAllSignedUpgradeOptIn"))
        assertEquals("app.tsuzuki.dev",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName)
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
            check(indexUrl ==
                "https://raw.githubusercontent.com/jssantogit/providers-tsuzuki/main/dist/index.json")
            return index
        }

        override suspend fun fetchArtifact(artifactUrl: String): ByteArray =
            artifacts[artifactUrl]
                ?: error("Unrecognized offline artifact URL; network access is forbidden")
    }
}
