package tachiyomi.core.provider.runtime

interface ProviderHostBridge {
    suspend fun httpGet(url: String): String

    suspend fun browserReadText(
        url: String,
        cssSelector: String,
    ): String
}
