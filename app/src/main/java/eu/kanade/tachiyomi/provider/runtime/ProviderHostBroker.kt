package eu.kanade.tachiyomi.provider.runtime

import android.content.Context

data class ProviderHostPolicy(
    val allowedOrigins: Set<String>,
    val allowLocalNetwork: Boolean = false,
)

class ProviderHostBroker(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") policy: ProviderHostPolicy,
    @Suppress("UNUSED_PARAMETER") providerProfileName: String,
) {
    fun httpGet(url: String): String {
        throw UnsupportedOperationException("HTTP broker spike not implemented")
    }

    fun browserReadText(
        url: String,
        cssSelector: String,
    ): String {
        throw UnsupportedOperationException("Browser broker spike not implemented")
    }
}
