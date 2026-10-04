package tachiyomi.core.provider.supplychain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Base64

class ProviderRepositoryWireFormatTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `signed index envelope preserves exact raw payload bytes`() {
        val payload = "{\n  \"sequence\": 7\n}".encodeToByteArray()
        val signature = byteArrayOf(1, 2, 3, 4)
        val encoded = json.encodeToString(
            ProviderSignedIndexEnvelope(
                keyId = "root-1",
                payloadBase64 = Base64.getEncoder().encodeToString(payload),
                signatureBase64 = Base64.getEncoder().encodeToString(signature),
            ),
        )

        val decoded = decodeProviderSignedIndexEnvelope(encoded)

        decoded.keyId shouldBe "root-1"
        decoded.payload shouldBe payload
        decoded.signature shouldBe signature
    }

    @Test
    fun `signed index envelope rejects malformed authority fields`() {
        listOf(
            """{"keyId":"","payloadBase64":"e30=","signatureBase64":"AQ=="}""",
            """{"keyId":"root-1","payloadBase64":"***","signatureBase64":"AQ=="}""",
            """{"keyId":"root-1","payloadBase64":"e30=","signatureBase64":"***"}""",
        ).forEach { encoded ->
            shouldThrow<ProviderSupplyChainException> {
                decodeProviderSignedIndexEnvelope(encoded)
            }
        }
    }
}
