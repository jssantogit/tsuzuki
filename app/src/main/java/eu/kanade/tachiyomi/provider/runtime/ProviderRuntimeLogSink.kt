package eu.kanade.tachiyomi.provider.runtime

fun interface ProviderRuntimeLogSink {
    fun info(providerId: String, message: String)
}
