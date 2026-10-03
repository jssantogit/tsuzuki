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

                override suspend fun binaryFetch(url: String): String =
                    hostBridge.binaryFetch(url).orEmpty()

                override suspend fun binaryZipEntry(
                    resourceHandle: String,
                    entryName: String,
                ): String = hostBridge.binaryZipEntry(resourceHandle, entryName).orEmpty()

                override suspend fun binaryAesCbcDecrypt(
                    resourceHandle: String,
                    keyHex: String,
                    ivHex: String,
                ): String = hostBridge.binaryAesCbcDecrypt(resourceHandle, keyHex, ivHex).orEmpty()

                override suspend fun binaryImageCrop(
                    resourceHandle: String,
                    x: Int,
                    y: Int,
                    width: Int,
                    height: Int,
                ): String = hostBridge.binaryImageCrop(resourceHandle, x, y, width, height).orEmpty()

                override suspend fun binaryImagePixel(
                    resourceHandle: String,
                    x: Int,
                    y: Int,
                ): String = hostBridge.binaryImagePixel(resourceHandle, x, y).orEmpty()
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
