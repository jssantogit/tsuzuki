package eu.kanade.tachiyomi.provider.runtime

class ProviderRuntimeLogSink(
    private val write: (String) -> Unit,
) {

    fun info(providerId: String, message: String) {
        write("Provider[$providerId] $message")
    }
}
