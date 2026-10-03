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

class ProviderRepositoryEnrollmentTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `https enrollment pins repository identity and signing key`() {
        val keyPair = ecKeyPair()
        val trust = ProviderRepositoryTrust.fromEnrollment(
            enrollment = ProviderRepositoryEnrollment(
                repositoryId = "repo.example",
                indexUrl = "https://repo.example/index.json",
                signingKey = ProviderRepositorySigningKey(
                    keyId = "root-1",
                    publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
                ),
            ),
            hostApiVersion = 1,
            stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("trust").toFile()),
        )
        val index = ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = "repo.example",
            sequence = 1,
            providers = emptyList(),
        )
        val payload = json.encodeToString(index).encodeToByteArray()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload)

        val verified = trust.verifyAndAccept(
            SignedProviderRepositoryIndex(
                keyId = "root-1",
                payload = payload,
                signature = signer.sign(),
            ),
        )

        verified.index.repositoryId shouldBe "repo.example"
        verified.verifiedKeyId shouldBe "root-1"
    }

    @Test
    fun `production enrollment rejects insecure repository transport`() {
        val keyPair = ecKeyPair()

        shouldThrow<ProviderSupplyChainException> {
            ProviderRepositoryTrust.fromEnrollment(
                enrollment = ProviderRepositoryEnrollment(
                    repositoryId = "repo.example",
                    indexUrl = "http://repo.example/index.json",
                    signingKey = ProviderRepositorySigningKey(
                        keyId = "root-1",
                        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
                    ),
                ),
                hostApiVersion = 1,
                stateStore = FileProviderRepositoryTrustStore(tempDir.resolve("insecure").toFile()),
            )
        }
    }

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
}
