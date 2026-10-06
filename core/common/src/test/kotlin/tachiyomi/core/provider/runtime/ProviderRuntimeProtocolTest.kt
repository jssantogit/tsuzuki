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
            hostModules = setOf(
                ProviderHostModule.HTTP,
                ProviderHostModule.DOM,
            ),
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
        decoded.hostModules shouldBe setOf(
            ProviderHostModule.HTTP,
            ProviderHostModule.DOM,
        )
        decoded.limits.toRuntimeLimits() shouldBe ProviderRuntimeLimits(
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
    fun `rejects oversized runtime request envelopes`() {
        shouldThrow<ProviderRuntimeProtocolException> {
            ProviderRuntimeProtocol.decodeRequest(
                "x".repeat(ProviderRuntimeProtocol.MAX_REQUEST_JSON_CHARS + 1),
            )
        }
    }

    @Test
    fun `host module snapshot rejects unknown protocol values`() {
        val payload = json.encodeToString(validRequest())
            .replace(
                "\"hostModules\":[],",
                "\"hostModules\":[\"UNSUPPORTED\"],",
            )

        shouldThrow<ProviderRuntimeProtocolException> {
            ProviderRuntimeProtocol.decodeRequest(payload)
        }
    }

    @Test
    fun `round trips package validation request without capability authority`() {
        val request = ProviderPackageValidationRequest(
            protocolVersion = ProviderRuntimeProtocol.VERSION,
            invocationId = "validate-1",
            providerId = "org.example.reader",
            artifactVersionCode = 7,
            limits = ProviderRuntimeLimitsDto(),
        )

        ProviderRuntimeProtocol.decodeValidationRequest(
            ProviderRuntimeProtocol.encodeValidationRequest(request),
        ) shouldBe request
    }

    @Test
    fun `isolated runtime death is a typed sanitized failure`() {
        ProviderRuntimeInvocationResponse.failure(ProviderRuntimeFailureCode.RUNTIME_DIED) shouldBe
            ProviderRuntimeInvocationResponse(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                value = null,
                failure = ProviderRuntimeFailureCode.RUNTIME_DIED,
            )
    }

    @Test
    fun `runtime success accepts the result limit and rejects one character beyond it`() {
        val maximum = "x".repeat(ProviderRuntimeProtocol.MAX_RESULT_JSON_CHARS)
        ProviderRuntimeInvocationResponse.success(maximum) shouldBe
            ProviderRuntimeInvocationResponse(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                value = maximum,
                failure = null,
            )

        ProviderRuntimeInvocationResponse.success("$maximum!") shouldBe
            ProviderRuntimeInvocationResponse.failure(
                ProviderRuntimeFailureCode.MALFORMED_RESULT,
            )
    }

    @Test
    fun `http response protocol round trips bounded feed payload above legacy 64 KiB ceiling`() {
        val response = ProviderHttpResponse(
            statusCode = 200,
            body = "x".repeat(96 * 1024),
        )

        ProviderHttpProtocol.decodeResponse(
            ProviderHttpProtocol.encodeResponse(response),
        ) shouldBe response
    }

    @Test
    fun `http response protocol rejects text beyond bounded feed ceiling`() {
        shouldThrow<IllegalArgumentException> {
            ProviderHttpResponse(
                statusCode = 200,
                body = "x".repeat(ProviderHttpProtocol.MAX_RESPONSE_BODY_CHARS + 1),
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
