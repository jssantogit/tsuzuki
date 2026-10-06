package tachiyomi.domain.tsuzuki.provider

@JvmInline
value class ProviderCursor(val value: String) {
    init {
        require(value.isNotBlank()) { "Provider cursor must not be blank" }
        require(value.length <= MAX_CURSOR_CHARS) { "Provider cursor is too large" }
    }

    private companion object {
        const val MAX_CURSOR_CHARS = 4096
    }
}

data class ProviderPage<T>(
    val items: List<T>,
    val nextCursor: ProviderCursor?,
) {
    init {
        require(items.size <= MAX_PAGE_ITEMS) { "Provider page contains too many items" }
    }

    private companion object {
        const val MAX_PAGE_ITEMS = 500
    }
}

enum class ProviderErrorCode {
    UNAVAILABLE,
    PERMISSION_DENIED,
    HOST_API_UNSUPPORTED,
    SCRIPT_ERROR,
    TIMEOUT,
    RUNTIME_DIED,
    NETWORK_POLICY,
    NETWORK_ERROR,
    BROWSER_ERROR,
    RESOURCE_LIMIT,
    MALFORMED_RESULT,
    AUTH_REQUIRED,
    ACQUISITION_FAILED,
}

data class ProviderError(
    val code: ProviderErrorCode,
    val retryable: Boolean,
)

sealed interface ProviderCallResult<out T> {
    data class Success<T>(val value: T) : ProviderCallResult<T>

    data class Failure(val error: ProviderError) : ProviderCallResult<Nothing>
}
