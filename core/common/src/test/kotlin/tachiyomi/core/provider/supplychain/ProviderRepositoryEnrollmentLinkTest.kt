package tachiyomi.core.provider.supplychain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ProviderRepositoryEnrollmentLinkTest {
    private val publicKey = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair().public.encoded
    }.let(Base64.getEncoder()::encodeToString)

    @Test
    fun `single HTTPS enrollment link describes repository and trust root`() {
        val doc = """
            {"repositoryId":"repo.example","indexUrl":"https://repo.example/index.json",
             "keyId":"root-1","publicKeyBase64":"$publicKey","displayName":" Example Repository "}
        """.trimIndent()
        val repo = decodeProviderRepositoryEnrollmentDocument(doc)
        repo.displayName shouldBe "Example Repository"
        repo.enrollment.repositoryId shouldBe "repo.example"
        repo.enrollment.signingKey.keyId shouldBe "root-1"
        repo.keyFingerprintSha256.length shouldBe 64
        decodeProviderRepositoryEnrollmentDocument(
            doc.replace(",\"displayName\":\" Example Repository \"", ""),
        ).displayName shouldBe "repo.example"
    }

    @Test
    fun `link resolution fails closed on unknown fields non HTTPS or invalid signing key`() {
        val valid = """
            {"repositoryId":"repo.example","indexUrl":"https://repo.example/index.json",
             "keyId":"root-1","publicKeyBase64":"$publicKey"}
        """.trimIndent()
        shouldThrow<ProviderSupplyChainException> {
            decodeProviderRepositoryEnrollmentDocument(
                valid.replace("https://repo.example", "http://repo.example"),
            )
        }
        shouldThrow<ProviderSupplyChainException> {
            decodeProviderRepositoryEnrollmentDocument(
                valid.dropLast(1) + ""","unexpected":true}""",
            )
        }
        shouldThrow<ProviderSupplyChainException> {
            decodeProviderRepositoryEnrollmentDocument(
                valid.replace(publicKey, "invalid"),
            )
        }
        shouldThrow<ProviderSupplyChainException> {
            decodeProviderRepositoryEnrollmentDocument(valid + " ".repeat(17 * 1024))
        }
    }
}
