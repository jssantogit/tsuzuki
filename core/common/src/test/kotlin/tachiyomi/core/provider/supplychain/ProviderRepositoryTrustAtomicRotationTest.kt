package tachiyomi.core.provider.supplychain

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

class ProviderRepositoryTrustAtomicRotationTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `trusted rotation is persisted atomically with the accepted index`() {
        val rootKey = ecKeyPair()
        val nextKey = ecKeyPair()
        val stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust").toFile())
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
            stateStore = stateStore,
        )

        trust.verifyAndAccept(
            signedIndex(
                keyId = "root-1",
                keyPair = rootKey,
                index = ProviderRepositoryIndex(
                    schemaVersion = 1,
                    repositoryId = "repo.example",
                    sequence = 1,
                    providers = listOf(descriptor(1, "v1")),
                    nextSigningKey = ProviderRepositorySigningKey(
                        keyId = "root-2",
                        publicKeyBase64 = Base64.getEncoder().encodeToString(nextKey.public.encoded),
                    ),
                ),
            ),
        )

        val restarted = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 3,
            trustedKeys = mapOf("root-1" to rootKey.public.encoded),
            stateStore = stateStore,
        )
        val rotated = restarted.verifyAndAccept(
            signedIndex(
                keyId = "root-2",
                keyPair = nextKey,
                index = ProviderRepositoryIndex(
                    schemaVersion = 1,
                    repositoryId = "repo.example",
                    sequence = 2,
                    providers = listOf(descriptor(2, "v2")),
                ),
            ),
        )

        rotated.verifiedKeyId shouldBe "root-2"
        rotated.index.sequence shouldBe 2L
    }

    private fun descriptor(versionCode: Long, bytes: String) =
        ProviderArtifactDescriptor(
            providerId = "reader.example",
            versionName = "1.0.$versionCode",
            versionCode = versionCode,
            artifactUrl = "https://repo.example/reader-$versionCode.tsz",
            sha256 = sha256Hex(bytes.encodeToByteArray()),
            minHostApi = 1,
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
