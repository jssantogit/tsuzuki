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
    fun `accepts signed artifact and keeps a rollback target`() {
        val rootKey = ecKeyPair()
        val artifactV1 = "provider-v1".encodeToByteArray()
        val artifactV2 = "provider-v2".encodeToByteArray()
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
        )

        val verifiedV1 = trust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 1,
                    artifact = descriptor(
                        versionCode = 1,
                        sha256 = sha256Hex(artifactV1),
                    ),
                ),
            ),
        )
        val artifact1 = trust.verifyArtifact(
            repository = verifiedV1,
            providerId = "reader.example",
            artifactBytes = artifactV1,
            installedVersionCode = null,
        )

        val store = ProviderArtifactStore(tempDir.toFile())
        store.activate(artifact1)
        store.current("reader.example")?.versionCode shouldBe 1L

        val verifiedV2 = trust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 2,
                    artifact = descriptor(
                        versionCode = 2,
                        sha256 = sha256Hex(artifactV2),
                    ),
                ),
            ),
        )
        val artifact2 = trust.verifyArtifact(
            repository = verifiedV2,
            providerId = "reader.example",
            artifactBytes = artifactV2,
            installedVersionCode = 1,
        )

        store.activate(artifact2)
        store.current("reader.example")?.versionCode shouldBe 2L
        store.previous("reader.example")?.versionCode shouldBe 1L

        store.rollback("reader.example")
        store.current("reader.example")?.versionCode shouldBe 1L
        store.readCurrentArtifact("reader.example") shouldBe artifactV1
    }

    @Test
    fun `rejects tampered repository payload and artifact bytes`() {
        val rootKey = ecKeyPair()
        val artifact = "provider".encodeToByteArray()
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
        )
        val signed = signedIndex(
            keyId = "root-1",
            keyPair = rootKey,
            index = index(
                sequence = 1,
                artifact = descriptor(
                    versionCode = 1,
                    sha256 = sha256Hex(artifact),
                ),
            ),
        )

        shouldThrow<ProviderSupplyChainException> {
            trust.verifyAndAccept(
                signed.copy(payload = signed.payload + byteArrayOf(0x20)),
            )
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
    }

    @Test
    fun `rejects replay downgrade incompatible host and revoked artifact`() {
        val rootKey = ecKeyPair()
        val artifact = "provider".encodeToByteArray()
        val digest = sha256Hex(artifact)
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
        )

        val accepted = trust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 5,
                    artifact = descriptor(
                        versionCode = 5,
                        sha256 = digest,
                    ),
                ),
            ),
        )

        shouldThrow<ProviderSupplyChainException> {
            trust.verifyAndAccept(
                signedIndex(
                    keyId = "root-1",
                    keyPair = rootKey,
                    index = index(
                        sequence = 4,
                        artifact = descriptor(
                            versionCode = 6,
                            sha256 = digest,
                        ),
                    ),
                ),
            )
        }

        shouldThrow<ProviderSupplyChainException> {
            trust.verifyArtifact(
                repository = accepted,
                providerId = "reader.example",
                artifactBytes = artifact,
                installedVersionCode = 5,
            )
        }

        val incompatibleTrust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 2,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
        )
        val incompatible = incompatibleTrust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 1,
                    artifact = descriptor(
                        versionCode = 6,
                        sha256 = digest,
                        minHostApi = 3,
                    ),
                ),
            ),
        )
        shouldThrow<ProviderSupplyChainException> {
            incompatibleTrust.verifyArtifact(
                repository = incompatible,
                providerId = "reader.example",
                artifactBytes = artifact,
                installedVersionCode = null,
            )
        }

        val revokedTrust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
        )
        val revoked = revokedTrust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 1,
                    artifact = descriptor(
                        versionCode = 6,
                        sha256 = digest,
                    ),
                    revokedArtifactSha256 = setOf(digest),
                ),
            ),
        )
        shouldThrow<ProviderSupplyChainException> {
            revokedTrust.verifyArtifact(
                repository = revoked,
                providerId = "reader.example",
                artifactBytes = artifact,
                installedVersionCode = null,
            )
        }
    }

    @Test
    fun `accepts signing key rotation only after trusted index introduces it`() {
        val rootKey = ecKeyPair()
        val nextKey = ecKeyPair()
        val artifact = "provider".encodeToByteArray()
        val digest = sha256Hex(artifact)
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
        )

        shouldThrow<ProviderSupplyChainException> {
            trust.verifyAndAccept(
                signedIndex(
                    keyId = "root-2",
                    keyPair = nextKey,
                    index = index(
                        sequence = 1,
                        artifact = descriptor(1, digest),
                    ),
                ),
            )
        }

        val rotation = ProviderRepositorySigningKey(
            keyId = "root-2",
            publicKeyBase64 = Base64.getEncoder().encodeToString(nextKey.public.encoded),
        )
        val rotationIndex = trust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = index(
                    sequence = 1,
                    artifact = descriptor(1, digest),
                    nextSigningKey = rotation,
                ),
            ),
        )
        trust.acceptSigningKeyRotation(rotationIndex)

        val rotated = trust.verifyAndAccept(
            signedIndex(
                keyId = "root-2",
                keyPair = nextKey,
                index = index(
                    sequence = 2,
                    artifact = descriptor(2, digest),
                ),
            ),
        )

        rotated.verifiedKeyId shouldBe "root-2"
        rotated.index.sequence shouldBe 2L
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
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        return SignedProviderRepositoryIndex(
            keyId = keyId,
            payload = payload,
            signature = signer.sign(),
        )
    }

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
}
