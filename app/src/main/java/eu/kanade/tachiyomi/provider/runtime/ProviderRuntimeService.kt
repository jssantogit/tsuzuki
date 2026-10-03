package eu.kanade.tachiyomi.provider.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tachiyomi.core.provider.runtime.ProviderHostBridge
import tachiyomi.core.provider.runtime.ProviderQuickJsRuntime
import tachiyomi.core.provider.runtime.ProviderRuntimeLimits
import tachiyomi.core.provider.runtime.ProviderScriptExecution

class ProviderRuntimeService : Service() {

    private val binder = object : IProviderRuntimeService.Stub() {

        override fun evaluate(
            source: String?,
            wallClockTimeoutMs: Long,
            jsExecutionTimeoutMs: Long,
        ): String {
            return evaluateInternal(
                source = source,
                wallClockTimeoutMs = wallClockTimeoutMs,
                jsExecutionTimeoutMs = jsExecutionTimeoutMs,
            )
        }

        override fun evaluateWithHost(
            source: String?,
            wallClockTimeoutMs: Long,
            jsExecutionTimeoutMs: Long,
            hostBridge: IProviderHostBridge?,
        ): String {
            if (hostBridge == null) {
                return "error:HOST_UNAVAILABLE"
            }
            val bridge = object : ProviderHostBridge {
                override suspend fun httpGet(url: String): String = hostBridge.httpGet(url).orEmpty()

                override suspend fun browserReadText(
                    url: String,
                    cssSelector: String,
                ): String = hostBridge.browserReadText(url, cssSelector).orEmpty()
            }
            return evaluateInternal(
                source = source,
                wallClockTimeoutMs = wallClockTimeoutMs,
                jsExecutionTimeoutMs = jsExecutionTimeoutMs,
                hostBridge = bridge,
            )
        }

        override fun processUid(): Int = Process.myUid()

        override fun processPid(): Int = Process.myPid()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun evaluateInternal(
        source: String?,
        wallClockTimeoutMs: Long,
        jsExecutionTimeoutMs: Long,
        hostBridge: ProviderHostBridge? = null,
    ): String {
        val limits = ProviderRuntimeLimits(
            wallClockTimeoutMs = wallClockTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
            jsExecutionTimeoutMs = jsExecutionTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
        )
        val result = runBlocking {
            ProviderQuickJsRuntime(
                dispatcher = Dispatchers.Default,
                limits = limits,
            ).evaluate(
                source = source.orEmpty(),
                hostBridge = hostBridge,
            )
        }
        return when (result) {
            is ProviderScriptExecution.Success -> "ok:${result.value.orEmpty()}"
            is ProviderScriptExecution.Failure -> "error:${result.reason.name}"
        }
    }

    private companion object {
        const val MIN_TIMEOUT_MS = 25L
        const val MAX_TIMEOUT_MS = 60_000L
    }
}
