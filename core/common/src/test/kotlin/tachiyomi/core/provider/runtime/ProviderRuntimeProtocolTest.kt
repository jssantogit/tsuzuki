package tachiyomi.core.provider.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ProviderRuntimeProtocolTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    @Test
    fun `round trips a bounded immutable invocation context`() {
        val request = ProviderRuntimeInvocationRequest(
            protocolVersion = 1,
            invocationId = "invocation-1",
            providerId = "org.example.reader",
            artifactVersionCode = 7,
            capabilityId = "reading.chapters",
            capabilityVersion = 1,
            configurationFingerprint = "config-v2",
            fileName = "main.js",
            limits = ProviderRuntimeLimitsDto(
                wallClockTimeoutMs = 5_000,
                jsExecutionTimeoutMs = 3_000,
                memoryLimitBytes = 32L * 1024L * 1024L,
                stackLimitBytes = 512L * 1024L,
            ),
        )

        val encoded = json.encodeToString(request)
        val decoded = ProviderRuntimeProtocol.decodeRequest(encoded)

        decoded shouldBe request
        decoded.toRuntimeLimits() shouldBe ProviderRuntimeLimits(
            wallClockTimeoutMs = 5_000,
            jsExecutionTimeoutMs = 3_000,
            memoryLimitBytes = 32L * 1024L * 1024L,
            stackLimitBytes = 512L * 1024L,
        )
    }

    @Test
    fun `rejects malformed invocation identity capability and protocol`() {
        val validJson = json.encodeToString(validRequest())

        shouldThrow<ProviderRuntimeProtocolException> {
            ProviderRuntimeProtocol.decodeRequest(
                validJson.replace(
                    "\"providerId\":\"org.example.reader\"",
                    "\"providerId\":\"../escape\"",
                ),
            )
        }
        shouldThrow<ProviderRuntimeProtocolException> {
            ProviderRuntimeProtocol.decodeRequest(
                validJson.replace(
                    "\"capabilityId\":\"reading.chapters\"",
                    "\"capabilityId\":\"Reading Chapters\"",
                ),
            )
        }
        shouldThrow<ProviderRuntimeProtocolException> {
            ProviderRuntimeProtocol.decodeRequest(
                validJson.replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
            )
        }
    }

    @Test
    fun `runtime responses never carry provider exception text`() {
        ProviderRuntimeInvocationResponse.failure(ProviderRuntimeFailureCode.SCRIPT_ERROR) shouldBe
            ProviderRuntimeInvocationResponse(
                protocolVersion = 1,
                value = null,
                failure = ProviderRuntimeFailureCode.SCRIPT_ERROR,
            )
    }

    private fun validRequest() = ProviderRuntimeInvocationRequest(
        protocolVersion = 1,
        invocationId = "invocation-1",
        providerId = "org.example.reader",
        artifactVersionCode = 1,
        capabilityId = "reading.chapters",
        capabilityVersion = 1,
        configurationFingerprint = "config-v1",
        fileName = "main.js",
        limits = ProviderRuntimeLimitsDto(),
    )
}
