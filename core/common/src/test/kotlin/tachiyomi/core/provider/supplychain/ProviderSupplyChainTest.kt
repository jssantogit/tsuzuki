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

        val corruptV2 = VerifiedProviderArtifact(v2.descriptor, "different".encodeToByteArray())
        shouldThrow<ProviderSupplyChainException> { store.activate(corruptV2) }
        store.current("reader.example")?.versionCode shouldBe 1L
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
