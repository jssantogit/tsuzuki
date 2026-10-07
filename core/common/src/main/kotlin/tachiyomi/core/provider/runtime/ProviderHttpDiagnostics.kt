package tachiyomi.core.provider.runtime

enum class ProviderHttpDiagnosticPhase {
    STARTED,
    SUCCEEDED,
    FAILED,
}

enum class ProviderHttpFailureFamily {
    DNS,
    CONNECT,
    TLS,
    READ_TIMEOUT,
    HOST_DEADLINE,
    CANCELLED,
    NETWORK_POLICY,
    HTTP_STATUS,
    RESPONSE_LIMIT,
    UNKNOWN,
}

data class ProviderHttpDiagnostic(
    val phase: ProviderHttpDiagnosticPhase,
    val host: String,
    val elapsedMs: Long,
    val timeoutMs: Long,
    val statusCode: Int? = null,
    val failureFamily: ProviderHttpFailureFamily? = null,
)
