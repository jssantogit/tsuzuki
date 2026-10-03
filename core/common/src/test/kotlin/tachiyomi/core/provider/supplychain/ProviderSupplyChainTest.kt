package tachiyomi.core.provider.supplychain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ProviderSupplyChainTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `repository sequence and rotated keys survive verifier restart`() {
        val rootKey = ecKeyPair()
        val nextKey = ecKeyPair()
        val stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust").toFile())
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
            stateStore = stateStore,
        )
        val rotationIndex = trust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 5,
                    artifact = descriptor(5, sha256Hex("v5".encodeToByteArray())),
                    nextSigningKey = ProviderRepositorySigningKey(
                        keyId = "root-2",
                        publicKeyBase64 = Base64.getEncoder().encodeToString(nextKey.public.encoded),
                    ),
                ),
            ),
        )
        trust.acceptSigningKeyRotation(rotationIndex)

        val restarted = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
            stateStore = stateStore,
        )

        shouldThrow<ProviderSupplyChainException> {
            restarted.verifyAndAccept(
                signedIndex(
                    keyId = "root-1",
                    keyPair = rootKey,
                    index = index(
                        sequence = 5,
                        artifact = descriptor(6, sha256Hex("v6".encodeToByteArray())),
                    ),
                ),
            )
        }

        val rotated = restarted.verifyAndAccept(
            signedIndex(
                keyId = "root-2",
                keyPair = nextKey,
                index = index(
                    sequence = 6,
                    artifact = descriptor(6, sha256Hex("v6".encodeToByteArray())),
                ),
            ),
        )
        rotated.verifiedKeyId shouldBe "root-2"
        rotated.index.sequence shouldBe 6L
    }

    @Test
    fun `rejects wrong key tampering downgrade revocation and host incompatibility`() {
        val rootKey = ecKeyPair()
        val wrongKey = ecKeyPair()
        val artifact = "provider-v5".encodeToByteArray()
        val digest = sha256Hex(artifact)
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
            stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust-main").toFile()),
        )
        val signed = signedIndex(
            keyId = "root-1",
            keyPair = rootKey,
            index = index(sequence = 1, artifact = descriptor(5, digest)),
        )

        shouldThrow<ProviderSupplyChainException> {
            trust.verifyAndAccept(signed.copy(signature = sign(wrongKey, signed.payload)))
        }
        shouldThrow<ProviderSupplyChainException> {
            trust.verifyAndAccept(signed.copy(payload = signed.payload + byteArrayOf(0x20)))
        }

        val verified = trust.verifyAndAccept(signed)
        shouldThrow<ProviderSupplyChainException> {
            trust.verifyArtifact(
                repository = verified,
                providerId = "reader.example",
                artifactBytes = "tampered".encodeToByteArray(),
                installedVersionCode = null,
            )
        }

        val downgrade = trust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(sequence = 2, artifact = descriptor(4, digest)),
            ),
        )
        shouldThrow<ProviderSupplyChainException> {
            trust.verifyArtifact(downgrade, "reader.example", artifact, installedVersionCode = 5)
        }

        val revokedTrust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
            stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust-revoked").toFile()),
        )
        val revoked = revokedTrust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 1,
                    artifact = descriptor(6, digest),
                    revokedArtifactSha256 = setOf(digest),
                ),
            ),
        )
        shouldThrow<ProviderSupplyChainException> {
            revokedTrust.verifyArtifact(revoked, "reader.example", artifact, installedVersionCode = null)
        }

        val oldHost = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 2,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
            stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust-old-host").toFile()),
        )
        val incompatible = oldHost.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 1,
                    artifact = descriptor(6, digest, minHostApi = 3),
                ),
            ),
        )
        shouldThrow<ProviderSupplyChainException> {
            oldHost.verifyArtifact(incompatible, "reader.example", artifact, installedVersionCode = null)
        }
    }

    @Test
    fun `artifact activation is immutable atomic and rollback capable`() {
        val rootKey = ecKeyPair()
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
            stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust-store").toFile()),
        )
        val store = ProviderArtifactStore(tempDir.resolve("artifacts").toFile())

        fun verified(versionCode: Long, bytes: ByteArray, sequence: Long): VerifiedProviderArtifact {
            val repository = trust.verifyAndAccept(
                signedIndex(
                    keyId = "root-1",
                    keyPair = rootKey,
                    index = index(
                        sequence = sequence,
                        artifact = descriptor(versionCode, sha256Hex(bytes)),
                    ),
                ),
            )
            return trust.verifyArtifact(
                repository = repository,
                providerId = "reader.example",
                artifactBytes = bytes,
                installedVersionCode = store.current("reader.example")?.versionCode,
            )
        }

        val v1 = verified(1, "provider-v1".encodeToByteArray(), 1)
        store.activate(v1)
        val v2 = verified(2, "provider-v2".encodeToByteArray(), 2)
        store.activate(v2)

        store.current("reader.example")?.versionCode shouldBe 2L
        store.previous("reader.example")?.versionCode shouldBe 1L

        store.rollback("reader.example")
        store.current("reader.example")?.versionCode shouldBe 1L
        store.readCurrentArtifact("reader.example") shouldBe "provider-v1".encodeToByteArray()

        val corruptV2 = VerifiedProviderArtifact(
            repositoryId = v2.repositoryId,
            descriptor = v2.descriptor,
            bytes = "different".encodeToByteArray(),
        )
        shouldThrow<ProviderSupplyChainException> { store.activate(corruptV2) }
        store.current("reader.example")?.versionCode shouldBe 1L
    }

    @Test
    fun `later repository revocation disables active artifact and blocks rollback to it`() {
        val rootKey = ecKeyPair()
        val stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust-active-revocation").toFile())
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
            stateStore = stateStore,
        )
        val store = ProviderArtifactStore(tempDir.resolve("artifacts-active-revocation").toFile())

        val v1Bytes = "provider-v1".encodeToByteArray()
        val v1Digest = sha256Hex(v1Bytes)
        val v1Repository = trust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 1,
                    artifact = descriptor(1, v1Digest),
                ),
            ),
        )
        store.activate(
            trust.verifyArtifact(
                repository = v1Repository,
                providerId = "reader.example",
                artifactBytes = v1Bytes,
                installedVersionCode = null,
            ),
        )

        val v2Bytes = "provider-v2".encodeToByteArray()
        val v2Repository = trust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 2,
                    artifact = descriptor(2, sha256Hex(v2Bytes)),
                    revokedArtifactSha256 = setOf(v1Digest),
                ),
            ),
        )

        trust.applyRevocations(v2Repository, store)

        store.current("reader.example")?.revoked shouldBe true
        shouldThrow<ProviderSupplyChainException> {
            store.readCurrentArtifact("reader.example")
        }

        store.activate(
            trust.verifyArtifact(
                repository = v2Repository,
                providerId = "reader.example",
                artifactBytes = v2Bytes,
                installedVersionCode = store.current("reader.example")?.versionCode,
            ),
        )

        store.current("reader.example")?.versionCode shouldBe 2L
        store.current("reader.example")?.revoked shouldBe false
        store.previous("reader.example")?.versionCode shouldBe 1L
        store.previous("reader.example")?.revoked shouldBe true
        shouldThrow<ProviderSupplyChainException> {
            store.rollback("reader.example")
        }
    }

    @Test
    fun `repository revocation cannot disable artifact from another repository`() {
        val sourceKey = ecKeyPair()
        val sourceTrust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("source-root" to sourceKey.public.encoded),
            stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust-source-repo").toFile()),
        )
        val store = ProviderArtifactStore(tempDir.resolve("artifacts-repository-scope").toFile())

        val artifactBytes = "provider-v1".encodeToByteArray()
        val artifactDigest = sha256Hex(artifactBytes)
        val sourceRepository = sourceTrust.verifyAndAccept(
            signedIndex(
                keyId = "source-root",
                keyPair = sourceKey,
                index = index(
                    sequence = 1,
                    artifact = descriptor(1, artifactDigest),
                ),
            ),
        )
        store.activate(
            sourceTrust.verifyArtifact(
                repository = sourceRepository,
                providerId = "reader.example",
                artifactBytes = artifactBytes,
                installedVersionCode = null,
            ),
        )

        val otherKey = ecKeyPair()
        val otherTrust = ProviderRepositoryTrust(
            repositoryId = "repo.other",
            hostApiVersion = 3,
            trustedKeys = mapOf("other-root" to otherKey.public.encoded),
            stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust-other-repo").toFile()),
        )
        val otherIndex = ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = "repo.other",
            sequence = 1,
            providers = listOf(descriptor(2, sha256Hex("other-v2".encodeToByteArray()))),
            revokedArtifactSha256 = setOf(artifactDigest),
        )
        val otherRepository = otherTrust.verifyAndAccept(
            signedIndex(
                keyId = "other-root",
                keyPair = otherKey,
                index = otherIndex,
            ),
        )

        otherTrust.applyRevocations(otherRepository, store) shouldBe emptySet()
        store.current("reader.example")?.revoked shouldBe false
        store.readCurrentArtifact("reader.example") shouldBe artifactBytes
    }

    private fun index(
        sequence: Long,
        artifact: ProviderArtifactDescriptor,
        revokedArtifactSha256: Set<String> = emptySet(),
        nextSigningKey: ProviderRepositorySigningKey? = null,
    ) = ProviderRepositoryIndex(
        schemaVersion = 1,
        repositoryId = "repo.example",
        sequence = sequence,
        providers = listOf(artifact),
        revokedArtifactSha256 = revokedArtifactSha256,
        nextSigningKey = nextSigningKey,
    )

    private fun descriptor(
        versionCode: Long,
        sha256: String,
        minHostApi: Int = 1,
    ) = ProviderArtifactDescriptor(
        providerId = "reader.example",
        versionName = "1.0.$versionCode",
        versionCode = versionCode,
        artifactUrl = "https://repo.example/reader-$versionCode.tsz",
        sha256 = sha256,
        minHostApi = minHostApi,
    )

    private fun signedIndex(
        keyId: String,
        keyPair: KeyPair,
        index: ProviderRepositoryIndex,
    ): SignedProviderRepositoryIndex {
        val payload = json.encodeToString(index).encodeToByteArray()
        return SignedProviderRepositoryIndex(
            keyId = keyId,
            payload = payload,
            signature = sign(keyPair, payload),
        )
    }

    private fun sign(keyPair: KeyPair, payload: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
}
