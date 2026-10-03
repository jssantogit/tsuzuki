package tachiyomi.core.provider.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProviderRuntimeLimitsDto(
    val wallClockTimeoutMs: Long = 5_000L,
    val jsExecutionTimeoutMs: Long = 3_000L,
    val memoryLimitBytes: Long = 32L * 1024L * 1024L,
    val stackLimitBytes: Long = 512L * 1024L,
) {
    init {
        require(wallClockTimeoutMs in 25L..60_000L) {
            "Provider wall-clock timeout is outside supported bounds"
        }
        require(jsExecutionTimeoutMs in 25L..60_000L) {
            "Provider JavaScript timeout is outside supported bounds"
        }
        require(jsExecutionTimeoutMs <= wallClockTimeoutMs) {
            "Provider JavaScript timeout must not exceed wall-clock timeout"
        }
        require(memoryLimitBytes in MIN_MEMORY_BYTES..MAX_MEMORY_BYTES) {
            "Provider memory limit is outside supported bounds"
        }
        require(stackLimitBytes in MIN_STACK_BYTES..MAX_STACK_BYTES) {
            "Provider stack limit is outside supported bounds"
        }
    }

    fun toRuntimeLimits() = ProviderRuntimeLimits(
        wallClockTimeoutMs = wallClockTimeoutMs,
        jsExecutionTimeoutMs = jsExecutionTimeoutMs,
        memoryLimitBytes = memoryLimitBytes,
        stackLimitBytes = stackLimitBytes,
    )

    private companion object {
        const val MIN_MEMORY_BYTES = 4L * 1024L * 1024L
        const val MAX_MEMORY_BYTES = 128L * 1024L * 1024L
        const val MIN_STACK_BYTES = 64L * 1024L
        const val MAX_STACK_BYTES = 4L * 1024L * 1024L
    }
}

@Serializable
enum class ProviderHostModule {
    HTTP,
    DOM,
    BROWSER,
    STORAGE,
    SECRETS,
    BINARY,
    CRYPTO,
    IMAGE,
    LOG,
}

@Serializable
data class ProviderRuntimeInvocationRequest(
    val protocolVersion: Int,
    val invocationId: String,
    val providerId: String,
    val artifactVersionCode: Long,
    val capabilityId: String,
    val capabilityVersion: Int,
    val configurationFingerprint: String,
    val fileName: String,
    val hostModules: Set<ProviderHostModule> = emptySet(),
    val limits: ProviderRuntimeLimitsDto,
) {
    init {
        require(protocolVersion == ProviderRuntimeProtocol.VERSION) {
            "Unsupported Provider runtime protocol version"
        }
        require(INVOCATION_ID.matches(invocationId)) { "Provider invocation ID is invalid" }
        require(PROVIDER_ID.matches(providerId)) { "Provider ID is invalid" }
        require(artifactVersionCode > 0L) { "Provider artifact version code must be positive" }
        require(CAPABILITY_ID.matches(capabilityId)) { "Provider capability ID is invalid" }
        require(capabilityVersion > 0) { "Provider capability version must be positive" }
        require(configurationFingerprint.length <= 256) {
            "Provider configuration fingerprint is too long"
        }
        require(FILE_NAME.matches(fileName)) { "Provider entrypoint file name is invalid" }
    }

    private companion object {
        val INVOCATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val CAPABILITY_ID = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
        val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")
    }
}

@Serializable
data class ProviderPackageValidationRequest(
    val protocolVersion: Int,
    val invocationId: String,
    val providerId: String,
    val artifactVersionCode: Long,
    val limits: ProviderRuntimeLimitsDto,
) {
    init {
        require(protocolVersion == ProviderRuntimeProtocol.VERSION) {
            "Unsupported Provider runtime protocol version"
        }
        require(INVOCATION_ID.matches(invocationId)) { "Provider invocation ID is invalid" }
        require(PROVIDER_ID.matches(providerId)) { "Provider ID is invalid" }
        require(artifactVersionCode > 0L) { "Provider artifact version code must be positive" }
    }

    private companion object {
        val INVOCATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

@Serializable
enum class ProviderRuntimeFailureCode {
    TIMEOUT,
    SCRIPT_ERROR,
    HOST_ERROR,
    RUNTIME_DIED,
    CANCELLED,
    INVOCATION_CONFLICT,
    RESOURCE_LIMIT,
    PACKAGE_INVALID,
    MALFORMED_RESULT,
    MALFORMED_REQUEST,
    SOURCE_TOO_LARGE,
    SOURCE_READ_ERROR,
}

@Serializable
data class ProviderRuntimeInvocationResponse(
    val protocolVersion: Int,
    val value: String?,
    val failure: ProviderRuntimeFailureCode?,
) {
    init {
        require((failure == null) xor (value == null)) {
            "Provider runtime response must contain either a value or a failure"
        }
    }

    companion object {
        fun success(value: String?) = ProviderRuntimeInvocationResponse(
            protocolVersion = ProviderRuntimeProtocol.VERSION,
            value = value ?: "",
            failure = null,
        )

        fun failure(code: ProviderRuntimeFailureCode) = ProviderRuntimeInvocationResponse(
            protocolVersion = ProviderRuntimeProtocol.VERSION,
            value = null,
            failure = code,
        )
    }
}

class ProviderRuntimeProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ProviderRuntimeProtocol {
    const val VERSION = 1
    const val MAX_REQUEST_JSON_CHARS = 16 * 1024
    const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    const val MAX_PACKAGE_BYTES = 16 * 1024 * 1024
    const val MAX_INPUT_JSON_CHARS = 64 * 1024
    const val MAX_RESULT_JSON_CHARS = 64 * 1024

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: ProviderRuntimeInvocationRequest): String =
        json.encodeToString(request)

    fun encodeValidationRequest(request: ProviderPackageValidationRequest): String =
        json.encodeToString(request)

    fun decodeValidationRequest(value: String): ProviderPackageValidationRequest {
        if (value.length > MAX_REQUEST_JSON_CHARS) {
            throw ProviderRuntimeProtocolException("Provider validation request exceeds size limit")
        }
        return try {
            json.decodeFromString(value)
        } catch (error: Exception) {
            throw ProviderRuntimeProtocolException("Provider validation request is malformed", error)
        }
    }

    fun decodeRequest(value: String): ProviderRuntimeInvocationRequest {
        if (value.length > MAX_REQUEST_JSON_CHARS) {
            throw ProviderRuntimeProtocolException("Provider runtime request exceeds size limit")
        }
        return try {
            json.decodeFromString(value)
        } catch (error: Exception) {
            throw ProviderRuntimeProtocolException("Provider runtime request is malformed", error)
        }
    }

    fun encodeResponse(response: ProviderRuntimeInvocationResponse): String =
        json.encodeToString(response)

    fun decodeResponse(value: String): ProviderRuntimeInvocationResponse =
        try {
            json.decodeFromString(value)
        } catch (error: Exception) {
            throw ProviderRuntimeProtocolException("Provider runtime response is malformed", error)
        }
}
