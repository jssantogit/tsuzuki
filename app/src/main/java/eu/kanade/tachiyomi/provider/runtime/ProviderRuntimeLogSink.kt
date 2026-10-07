package eu.kanade.tachiyomi.provider.runtime

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class ProviderRuntimeLogSink(
    private val write: (String) -> Unit = ::writeProviderRuntimeLog,
) {

    fun info(providerId: String, message: String) {
        write("Provider[$providerId] $message")
    }
}

private object ProviderRuntimeLogger

private fun writeProviderRuntimeLog(line: String) {
    ProviderRuntimeLogger.logcat(LogPriority.INFO) { line }
}
