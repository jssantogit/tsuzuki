package tachiyomi.core.provider.supplychain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.provider.packageformat.ProviderPackageActivator
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProviderRepositoryManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `refresh install update and rollback preserve trusted repository flow`() = runBlocking {
        val keyPair = ecKeyPair()
        val repositoryId = "repo.example"
        val enrollment = ProviderRepositoryEnrollment(
            repositoryId = repositoryId,
            indexUrl = "https://repo.example/index.json",
            signingKey = ProviderRepositorySigningKey(
                keyId = "root-1",
                publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            ),
        )
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("enrollments").toFile(),
        )
        enrollmentStore.save(EnrolledProviderRepository("Example Repository", enrollment))

        val artifactStore = ProviderArtifactStore(tempDir.resolve("artifacts").toFile())
        val transport = FakeTransport()
        val manager = ProviderRepositoryManager(
            hostApiVersion = 3,
            enrollmentStore = enrollmentStore,
            trustStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust").toFile()),
            artifactStore = artifactStore,
            packageActivator = ProviderPackageActivator(
                hostApiVersion = 3,
                parser = ProviderPackageParser(),
                artifactStore = artifactStore,
                contractValidator = { _, _ -> true },
            ),
            transport = transport,
        )

        val v1 = tsz(versionCode = 1)
        transport.publish(
            signed = signedIndex(
                keyPair = keyPair,
                sequence = 1,
                artifactBytes = v1,
                versionCode = 1,
            ),
            artifactBytes = v1,
        )

        manager.refresh(repositoryId).entries.single().status shouldBe
            ProviderRepositoryEntryStatus.AVAILABLE
        manager.install(repositoryId, "reader.example")
        manager.installed().single().versionCode shouldBe 1L
        manager.canRollback("reader.example") shouldBe false
        manager.snapshot(repositoryId)!!.entries.single().status shouldBe
            ProviderRepositoryEntryStatus.INSTALLED

        val v2 = tsz(versionCode = 2)
        transport.publish(
            signed = signedIndex(
                keyPair = keyPair,
                sequence = 2,
                artifactBytes = v2,
                versionCode = 2,
            ),
            artifactBytes = v2,
        )

        manager.refresh(repositoryId).entries.single().status shouldBe
            ProviderRepositoryEntryStatus.UPDATE_AVAILABLE
        manager.install(repositoryId, "reader.example")
        manager.installed().single().versionCode shouldBe 2L
        manager.canRollback("reader.example") shouldBe true

        manager.rollback("reader.example")
        manager.installed().single().versionCode shouldBe 1L
        manager.previous("reader.example")?.versionCode shouldBe 2L
        manager.canRollback("reader.example") shouldBe false
    }

    @Test
    fun `changing enrolled repository metadata invalidates stale verified session`() = runBlocking {
        val keyPair = ecKeyPair()
        val signingKey = ProviderRepositorySigningKey(
            keyId = "root-1",
            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
        )
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("reenroll-enrollments").toFile(),
        )
        val trustStore = FileProviderRepositoryTrustStore(
            tempDir.resolve("reenroll-trust").toFile(),
        )
        val artifactStore = ProviderArtifactStore(
            tempDir.resolve("reenroll-artifacts").toFile(),
        )
        val transport = FakeTransport()
        val manager = manager(enrollmentStore, trustStore, artifactStore, transport)
        manager.enroll(
            EnrolledProviderRepository(
                "Original",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://repo.example/index.json",
                    signingKey = signingKey,
                ),
            ),
        )

        val bytes = tsz(versionCode = 1)
        transport.publish(
            signed = signedIndex(
                keyPair = keyPair,
                sequence = 1,
                artifactBytes = bytes,
                versionCode = 1,
            ),
            artifactBytes = bytes,
        )
        manager.refresh("repo.example")
        manager.snapshot("repo.example")!!.repository.displayName shouldBe "Original"

        manager.enroll(
            EnrolledProviderRepository(
                "Moved",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://mirror.example/index.json",
                    signingKey = signingKey,
                ),
            ),
        )

        manager.snapshot("repo.example") shouldBe null
        manager.install("repo.example", "reader.example")
        transport.requestedIndexUrls shouldBe listOf(
            "https://repo.example/index.json",
            "https://mirror.example/index.json",
        )
        manager.snapshot("repo.example")!!.repository.displayName shouldBe "Moved"
        manager.snapshot("repo.example")!!.repository.enrollment.indexUrl shouldBe
            "https://mirror.example/index.json"
        manager.installed().single().versionCode shouldBe 1L
    }

    @Test
    fun `stale verified session cannot pair with changed enrollment metadata`() = runBlocking {
        val keyPair = ecKeyPair()
        val signingKey = ProviderRepositorySigningKey(
            keyId = "root-1",
            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
        )
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("stale-session-enrollments").toFile(),
        )
        val trustStore = FileProviderRepositoryTrustStore(
            tempDir.resolve("stale-session-trust").toFile(),
        )
        val artifactStore = ProviderArtifactStore(
            tempDir.resolve("stale-session-artifacts").toFile(),
        )
        val transport = FakeTransport()
        val manager = manager(enrollmentStore, trustStore, artifactStore, transport)
        val original = EnrolledProviderRepository(
            "Original",
            ProviderRepositoryEnrollment(
                repositoryId = "repo.example",
                indexUrl = "https://repo.example/index.json",
                signingKey = signingKey,
            ),
        )
        manager.enroll(original)

        val bytes = tsz(versionCode = 1)
        transport.publish(
            signed = signedIndex(
                keyPair = keyPair,
                sequence = 1,
                artifactBytes = bytes,
                versionCode = 1,
            ),
            artifactBytes = bytes,
        )
        manager.refresh("repo.example")

        enrollmentStore.save(
            original.copy(
                displayName = "Moved",
                enrollment = original.enrollment.copy(
                    indexUrl = "https://mirror.example/index.json",
                ),
            ),
        )

        manager.snapshot("repo.example") shouldBe null
        manager.install("repo.example", "reader.example")
        transport.requestedIndexUrls shouldBe listOf(
            "https://repo.example/index.json",
            "https://mirror.example/index.json",
        )
        manager.snapshot("repo.example")!!.repository.displayName shouldBe "Moved"
        manager.snapshot("repo.example")!!.repository.enrollment.indexUrl shouldBe
            "https://mirror.example/index.json"
    }

    @Test
    fun `removing repository clears persisted trust state before explicit re-enrollment`() = runBlocking {
        val keyPair = ecKeyPair()
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("remove-enrollments").toFile(),
        )
        val trustStore = FileProviderRepositoryTrustStore(
            tempDir.resolve("remove-trust").toFile(),
        )
        val artifactStore = ProviderArtifactStore(
            tempDir.resolve("remove-artifacts").toFile(),
        )
        val transport = FakeTransport()
        enrollmentStore.save(
            EnrolledProviderRepository(
                "Example",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://repo.example/index.json",
                    signingKey = ProviderRepositorySigningKey(
                        keyId = "root-1",
                        publicKeyBase64 = Base64.getEncoder()
                            .encodeToString(keyPair.public.encoded),
                    ),
                ),
            ),
        )
        val bytes = tsz(versionCode = 1)
        transport.publish(
            signed = signedIndex(
                keyPair = keyPair,
                sequence = 1,
                artifactBytes = bytes,
                versionCode = 1,
            ),
            artifactBytes = bytes,
        )
        val manager = manager(enrollmentStore, trustStore, artifactStore, transport)

        manager.refresh("repo.example")
        (trustStore.load("repo.example") != null) shouldBe true

        manager.removeRepository("repo.example") shouldBe true
        trustStore.load("repo.example") shouldBe null
    }

    @Test
    fun `re-enrolling same repository id with a new trust root cannot inherit installed Providers`() = runBlocking {
        val originalKey = ecKeyPair()
        val replacementKey = ecKeyPair()
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("repin-enrollments").toFile(),
        )
        val trustStore = FileProviderRepositoryTrustStore(
            tempDir.resolve("repin-trust").toFile(),
        )
        val artifactStore = ProviderArtifactStore(
            tempDir.resolve("repin-artifacts").toFile(),
        )
        val transport = FakeTransport()
        val manager = manager(enrollmentStore, trustStore, artifactStore, transport)

        manager.enroll(
            EnrolledProviderRepository(
                "Original",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://repo.example/index.json",
                    signingKey = ProviderRepositorySigningKey(
                        keyId = "root-1",
                        publicKeyBase64 = Base64.getEncoder()
                            .encodeToString(originalKey.public.encoded),
                    ),
                ),
            ),
        )
        val v1 = tsz(versionCode = 1)
        transport.publish(
            signed = signedIndex(
                keyPair = originalKey,
                sequence = 1,
                artifactBytes = v1,
                versionCode = 1,
            ),
            artifactBytes = v1,
        )
        manager.refresh("repo.example")
        manager.install("repo.example", "reader.example")
        val originalTrustAnchor = sha256Hex(originalKey.public.encoded)
        manager.installed().single().repositoryTrustAnchorSha256 shouldBe originalTrustAnchor

        manager.removeRepository("repo.example") shouldBe true
        manager.enroll(
            EnrolledProviderRepository(
                "Replacement",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://replacement.example/index.json",
                    signingKey = ProviderRepositorySigningKey(
                        keyId = "root-1",
                        publicKeyBase64 = Base64.getEncoder()
                            .encodeToString(replacementKey.public.encoded),
                    ),
                ),
            ),
        )
        val v2 = tsz(versionCode = 2)
        transport.publish(
            signed = signedIndex(
                keyPair = replacementKey,
                sequence = 1,
                artifactBytes = v2,
                versionCode = 2,
            ),
            artifactBytes = v2,
        )

        manager.refresh("repo.example").entries.single().status shouldBe
            ProviderRepositoryEntryStatus.ORIGIN_CONFLICT
        shouldThrow<ProviderSupplyChainException> {
            manager.install("repo.example", "reader.example")
        }
        manager.installed().single().versionCode shouldBe 1L
        manager.installed().single().repositoryTrustAnchorSha256 shouldBe originalTrustAnchor
    }

    @Test
    fun `replacement trust root cannot revoke Provider installed by prior root`() = runBlocking {
        val originalKey = ecKeyPair()
        val replacementKey = ecKeyPair()
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("revocation-origin-enrollments").toFile(),
        )
        val trustStore = FileProviderRepositoryTrustStore(
            tempDir.resolve("revocation-origin-trust").toFile(),
        )
        val artifactStore = ProviderArtifactStore(
            tempDir.resolve("revocation-origin-artifacts").toFile(),
        )
        val transport = FakeTransport()
        val manager = manager(enrollmentStore, trustStore, artifactStore, transport)

        manager.enroll(
            EnrolledProviderRepository(
                "Original",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://repo.example/index.json",
                    signingKey = ProviderRepositorySigningKey(
                        keyId = "root-1",
                        publicKeyBase64 = Base64.getEncoder()
                            .encodeToString(originalKey.public.encoded),
                    ),
                ),
            ),
        )
        val v1 = tsz(versionCode = 1)
        val originalSigned = signedIndex(
            keyPair = originalKey,
            sequence = 1,
            artifactBytes = v1,
            versionCode = 1,
        )
        transport.publish(
            signed = originalSigned,
            artifactBytes = v1,
        )
        manager.refresh("repo.example")
        manager.install("repo.example", "reader.example")
        val installedBefore = manager.installed().single()
        installedBefore.revoked shouldBe false

        manager.removeRepository("repo.example")
        manager.enroll(
            EnrolledProviderRepository(
                "Replacement",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://replacement.example/index.json",
                    signingKey = ProviderRepositorySigningKey(
                        keyId = "root-1",
                        publicKeyBase64 = Base64.getEncoder()
                            .encodeToString(replacementKey.public.encoded),
                    ),
                ),
            ),
        )

        val replacementIndex = ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = "repo.example",
            sequence = 1,
            providers = listOf(
                ProviderArtifactDescriptor(
                    providerId = "reader.example",
                    versionName = "2.0.0",
                    versionCode = 2,
                    artifactUrl = "https://replacement.example/reader-2.tsz",
                    sha256 = sha256Hex(tsz(versionCode = 2)),
                    minHostApi = 1,
                ),
            ),
            revokedArtifactSha256 = setOf(installedBefore.sha256),
        )
        transport.publish(
            signed = signedIndex(
                keyPair = replacementKey,
                index = replacementIndex,
            ),
            artifactBytes = tsz(versionCode = 2),
        )

        manager.refresh("repo.example")

        manager.installed().single().revoked shouldBe false
        manager.installed().single().repositoryTrustAnchorSha256 shouldBe
            installedBefore.repositoryTrustAnchorSha256
    }

    @Test
    fun `signed key rotation can revoke artifacts from the original trust root`() = runBlocking {
        val rootKey = ecKeyPair()
        val rotatedKey = ecKeyPair()
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("rotation-revocation-enrollments").toFile(),
        )
        val trustStore = FileProviderRepositoryTrustStore(
            tempDir.resolve("rotation-revocation-trust").toFile(),
        )
        val artifactStore = ProviderArtifactStore(
            tempDir.resolve("rotation-revocation-artifacts").toFile(),
        )
        val transport = FakeTransport()
        val manager = manager(enrollmentStore, trustStore, artifactStore, transport)

        manager.enroll(
            EnrolledProviderRepository(
                "Example",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://repo.example/index.json",
                    signingKey = ProviderRepositorySigningKey(
                        keyId = "root-1",
                        publicKeyBase64 = Base64.getEncoder()
                            .encodeToString(rootKey.public.encoded),
                    ),
                ),
            ),
        )

        val v1 = tsz(versionCode = 1)
        val firstIndex = ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = "repo.example",
            sequence = 1,
            providers = listOf(
                ProviderArtifactDescriptor(
                    providerId = "reader.example",
                    versionName = "1.0.1",
                    versionCode = 1,
                    artifactUrl = "https://repo.example/reader-1.tsz",
                    sha256 = sha256Hex(v1),
                    minHostApi = 1,
                ),
            ),
            nextSigningKey = ProviderRepositorySigningKey(
                keyId = "root-2",
                publicKeyBase64 = Base64.getEncoder()
                    .encodeToString(rotatedKey.public.encoded),
            ),
        )
        transport.publish(
            signed = signedIndex(
                keyPair = rootKey,
                index = firstIndex,
                keyId = "root-1",
            ),
            artifactBytes = v1,
        )
        manager.refresh("repo.example")
        manager.install("repo.example", "reader.example")
        manager.installed().single().revoked shouldBe false

        val secondIndex = firstIndex.copy(
            sequence = 2,
            nextSigningKey = null,
            revokedArtifactSha256 = setOf(sha256Hex(v1)),
        )
        transport.publish(
            signed = signedIndex(
                keyPair = rotatedKey,
                index = secondIndex,
                keyId = "root-2",
            ),
            artifactBytes = v1,
        )

        manager.refresh("repo.example")

        manager.installed().single().revoked shouldBe true
        manager.installed().single().repositoryTrustAnchorSha256 shouldBe
            sha256Hex(rootKey.public.encoded)
    }

    @Test
    fun `removing repository waits for in flight refresh before clearing trust`() = runBlocking {
        val keyPair = ecKeyPair()
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("remove-race-enrollments").toFile(),
        )
        val trustStore = FileProviderRepositoryTrustStore(
            tempDir.resolve("remove-race-trust").toFile(),
        )
        val artifactStore = ProviderArtifactStore(
            tempDir.resolve("remove-race-artifacts").toFile(),
        )
        val transport = FakeTransport()
        val manager = manager(enrollmentStore, trustStore, artifactStore, transport)
        manager.enroll(
            EnrolledProviderRepository(
                "Example",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://repo.example/index.json",
                    signingKey = ProviderRepositorySigningKey(
                        keyId = "root-1",
                        publicKeyBase64 = Base64.getEncoder()
                            .encodeToString(keyPair.public.encoded),
                    ),
                ),
            ),
        )

        val bytes = tsz(versionCode = 1)
        transport.publish(
            signed = signedIndex(
                keyPair = keyPair,
                sequence = 1,
                artifactBytes = bytes,
                versionCode = 1,
            ),
            artifactBytes = bytes,
        )
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        transport.beforeFetchIndex = {
            fetchStarted.complete(Unit)
            releaseFetch.await()
        }

        val refresh = async { manager.refresh("repo.example") }
        fetchStarted.await()
        val removal = async { manager.removeRepository("repo.example") }
        yield()
        removal.isCompleted shouldBe false
        releaseFetch.complete(Unit)

        refresh.await()
        removal.await() shouldBe true
        manager.repositories() shouldBe emptyList()
        enrollmentStore.get("repo.example") shouldBe null
        trustStore.load("repo.example") shouldBe null
        manager.snapshot("repo.example") shouldBe null
    }

    @Test
    fun `repository cannot install Provider id reserved by a builtin Provider`() = runBlocking {
        val keyPair = ecKeyPair()
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("reserved-enrollments").toFile(),
        )
        val trustStore = FileProviderRepositoryTrustStore(
            tempDir.resolve("reserved-trust").toFile(),
        )
        val artifactStore = ProviderArtifactStore(
            tempDir.resolve("reserved-artifacts").toFile(),
        )
        val transport = FakeTransport()
        val manager = manager(
            enrollmentStore = enrollmentStore,
            trustStore = trustStore,
            artifactStore = artifactStore,
            transport = transport,
            reservedProviderIds = setOf("kitsu"),
        )
        manager.enroll(
            EnrolledProviderRepository(
                "Example",
                ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "https://repo.example/index.json",
                    signingKey = ProviderRepositorySigningKey(
                        keyId = "root-1",
                        publicKeyBase64 = Base64.getEncoder()
                            .encodeToString(keyPair.public.encoded),
                    ),
                ),
            ),
        )

        val artifactBytes = tsz(versionCode = 1)
        val index = ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = "repo.example",
            sequence = 1,
            providers = listOf(
                ProviderArtifactDescriptor(
                    providerId = "kitsu",
                    versionName = "1.0.1",
                    versionCode = 1,
                    artifactUrl = "https://repo.example/kitsu-1.tsz",
                    sha256 = sha256Hex(artifactBytes),
                    minHostApi = 1,
                ),
            ),
        )
        transport.publish(
            signed = signedIndex(keyPair = keyPair, index = index),
            artifactBytes = artifactBytes,
        )

        manager.refresh("repo.example").entries.single().status shouldBe
            ProviderRepositoryEntryStatus.ORIGIN_CONFLICT
        shouldThrow<ProviderSupplyChainException> {
            manager.install("repo.example", "kitsu")
        }
        transport.requestedArtifactUrls shouldBe emptyList()
        manager.installed() shouldBe emptyList()
    }

    @Test
    fun `refreshing unchanged accepted index after manager restart remains usable`() = runBlocking {
        val keyPair = ecKeyPair()
        val enrollment = ProviderRepositoryEnrollment(
            repositoryId = "repo.example",
            indexUrl = "https://repo.example/index.json",
            signingKey = ProviderRepositorySigningKey(
                keyId = "root-1",
                publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            ),
        )
        val enrollmentStore = FileProviderRepositoryEnrollmentStore(
            tempDir.resolve("restart-enrollments").toFile(),
        )
        enrollmentStore.save(EnrolledProviderRepository("Example", enrollment))
        val trustStore = FileProviderRepositoryTrustStore(tempDir.resolve("restart-trust").toFile())
        val artifactStore = ProviderArtifactStore(tempDir.resolve("restart-artifacts").toFile())
        val transport = FakeTransport()
        val bytes = tsz(versionCode = 1)
        transport.publish(
            signed = signedIndex(keyPair, sequence = 1, artifactBytes = bytes, versionCode = 1),
            artifactBytes = bytes,
        )

        manager(enrollmentStore, trustStore, artifactStore, transport).refresh("repo.example")
        val restarted = manager(enrollmentStore, trustStore, artifactStore, transport)

        restarted.refresh("repo.example").sequence shouldBe 1L
        restarted.snapshot("repo.example")!!.entries.single().status shouldBe
            ProviderRepositoryEntryStatus.AVAILABLE
    }

    private fun manager(
        enrollmentStore: ProviderRepositoryEnrollmentStore,
        trustStore: ProviderRepositoryTrustStore,
        artifactStore: ProviderArtifactStore,
        transport: ProviderRepositoryTransport,
        reservedProviderIds: Set<String> = emptySet(),
    ) = ProviderRepositoryManager(
        hostApiVersion = 3,
        enrollmentStore = enrollmentStore,
        trustStore = trustStore,
        artifactStore = artifactStore,
        packageActivator = ProviderPackageActivator(
            hostApiVersion = 3,
            parser = ProviderPackageParser(),
            artifactStore = artifactStore,
            contractValidator = { _, _ -> true },
        ),
        transport = transport,
        reservedProviderIds = reservedProviderIds,
    )

    private fun signedIndex(
        keyPair: KeyPair,
        index: ProviderRepositoryIndex,
        keyId: String = "root-1",
    ): SignedProviderRepositoryIndex {
        val payload = json.encodeToString(index).encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return SignedProviderRepositoryIndex(
            keyId = keyId,
            payload = payload,
            signature = signature,
        )
    }

    private fun signedIndex(
        keyPair: KeyPair,
        sequence: Long,
        artifactBytes: ByteArray,
        versionCode: Long,
    ): SignedProviderRepositoryIndex {
        val index = ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = "repo.example",
            sequence = sequence,
            providers = listOf(
                ProviderArtifactDescriptor(
                    providerId = "reader.example",
                    versionName = "1.0.$versionCode",
                    versionCode = versionCode,
                    artifactUrl = "https://repo.example/reader-$versionCode.tsz",
                    sha256 = sha256Hex(artifactBytes),
                    minHostApi = 1,
                ),
            ),
        )
        val payload = json.encodeToString(index).encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return SignedProviderRepositoryIndex(
            keyId = "root-1",
            payload = payload,
            signature = signature,
        )
    }

    private fun tsz(versionCode: Long): ByteArray {
        val manifest = """
            {
              "manifestVersion": 1,
              "id": "reader.example",
              "name": "Reader Example",
              "version": {"name": "1.0.$versionCode", "code": $versionCode},
              "minHostApi": 1,
              "entrypoint": "main.js",
              "capabilities": [{"id":"reading.chapters","version":1}],
              "permissions": {},
              "contentLanguages": ["en"],
              "settings": []
            }
        """.trimIndent()

        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("main.js"))
                zip.write(
                    "export default { reading: { chapters: async () => [] } };"
                        .encodeToByteArray(),
                )
                zip.closeEntry()
            }
        }.toByteArray()
    }

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    private class FakeTransport : ProviderRepositoryTransport {
        private lateinit var signed: SignedProviderRepositoryIndex
        private val artifacts = mutableMapOf<String, ByteArray>()
        val requestedIndexUrls = mutableListOf<String>()
        val requestedArtifactUrls = mutableListOf<String>()
        var beforeFetchIndex: suspend () -> Unit = {}

        fun publish(
            signed: SignedProviderRepositoryIndex,
            artifactBytes: ByteArray,
        ) {
            this.signed = signed
            val index = Json.decodeFromString<ProviderRepositoryIndex>(
                signed.payload.decodeToString(),
            )
            val descriptor = index.providers.single()
            artifacts[descriptor.artifactUrl] = artifactBytes
        }

        override suspend fun fetchIndex(indexUrl: String): SignedProviderRepositoryIndex {
            requestedIndexUrls += indexUrl
            beforeFetchIndex()
            return signed
        }

        override suspend fun fetchArtifact(artifactUrl: String): ByteArray {
            requestedArtifactUrls += artifactUrl
            return artifacts.getValue(artifactUrl)
        }
    }
}
