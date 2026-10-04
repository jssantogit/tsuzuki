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

class ProviderRepositoryCurrentIndexTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `accepted current index can be reverified after restart without advancing sequence`() {
        val keyPair = ecKeyPair()
        val stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust").toFile())
        val first = trust(keyPair, stateStore)
        val signed = signedIndex(
            keyPair = keyPair,
            index = index(sequence = 7, versionCode = 3),
        )

        first.verifyAndAccept(signed).index.sequence shouldBe 7L

        val restarted = trust(keyPair, stateStore)
        val current = restarted.verifyCurrent(signed)

        current.index.sequence shouldBe 7L
        current.index.providers.single().versionCode shouldBe 3L
    }

    @Test
    fun `explicit trust reset is idempotent and removes persisted anti replay state`() {
        val keyPair = ecKeyPair()
        val stateStore = FileProviderRepositoryTrustStore(
            tempDir.resolve("trust-reset").toFile(),
        )
        val trust = trust(keyPair, stateStore)
        trust.verifyAndAccept(
            signedIndex(
                keyPair = keyPair,
                index = index(sequence = 3, versionCode = 1),
            ),
        )

        stateStore.load("repo.example")!!.highestAcceptedSequence shouldBe 3L
        stateStore.remove("repo.example") shouldBe true
        stateStore.load("repo.example") shouldBe null
        stateStore.remove("repo.example") shouldBe false
    }

    @Test
    fun `same sequence with different signed payload is not the accepted current index`() {
        val keyPair = ecKeyPair()
        val stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("mismatch").toFile())
        val first = trust(keyPair, stateStore)

        first.verifyAndAccept(
            signedIndex(
                keyPair = keyPair,
                index = index(sequence = 4, versionCode = 1),
            ),
        )

        val restarted = trust(keyPair, stateStore)
        shouldThrow<ProviderSupplyChainException> {
            restarted.verifyCurrent(
                signedIndex(
                    keyPair = keyPair,
                    index = index(sequence = 4, versionCode = 2),
                ),
            )
        }
    }

    @Test
    fun `current index revalidation rejects older or newer sequence`() {
        val keyPair = ecKeyPair()
        val stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("sequence").toFile())
        val first = trust(keyPair, stateStore)
        first.verifyAndAccept(signedIndex(keyPair, index(sequence = 5, versionCode = 1)))

        val restarted = trust(keyPair, stateStore)
        listOf(4L, 6L).forEach { sequence ->
            shouldThrow<ProviderSupplyChainException> {
                restarted.verifyCurrent(
                    signedIndex(
                        keyPair = keyPair,
                        index = index(sequence = sequence, versionCode = 1),
                    ),
                )
            }
        }
    }

    private fun trust(
        keyPair: KeyPair,
        stateStore: ProviderRepositoryTrustStore,
    ) = ProviderRepositoryTrust(
        repositoryId = "repo.example",
        hostApiVersion = 3,
        trustedKeys = mapOf("root-1" to keyPair.public.encoded),
        stateStore = stateStore,
    )

    private fun index(
        sequence: Long,
        versionCode: Long,
    ) = ProviderRepositoryIndex(
        schemaVersion = 1,
        repositoryId = "repo.example",
        sequence = sequence,
        providers = listOf(
            ProviderArtifactDescriptor(
                providerId = "reader.example",
                versionName = "1.0.$versionCode",
                versionCode = versionCode,
                artifactUrl = "https://repo.example/reader-$versionCode.tsz",
                sha256 = sha256Hex("reader-$versionCode".encodeToByteArray()),
                minHostApi = 1,
            ),
        ),
    )

    private fun signedIndex(
        keyPair: KeyPair,
        index: ProviderRepositoryIndex,
    ): SignedProviderRepositoryIndex {
        val payload = json.encodeToString(index).encodeToByteArray()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        return SignedProviderRepositoryIndex(
            keyId = "root-1",
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
