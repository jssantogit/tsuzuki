package eu.kanade.tachiyomi.provider.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import tachiyomi.core.provider.runtime.ProviderBinaryHostService
import tachiyomi.core.provider.runtime.ProviderBrowserHostService
import tachiyomi.core.provider.runtime.ProviderCryptoHostService
import tachiyomi.core.provider.runtime.ProviderDomHostService
import tachiyomi.core.provider.runtime.ProviderHostModule
import tachiyomi.core.provider.runtime.ProviderHostServices
import tachiyomi.core.provider.runtime.ProviderHttpHostService
import tachiyomi.core.provider.runtime.ProviderImageHostService
import tachiyomi.core.provider.runtime.ProviderLogHostService
import tachiyomi.core.provider.runtime.ProviderQuickJsRuntime
import tachiyomi.core.provider.runtime.ProviderResourceHandle
import tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocolException
import tachiyomi.core.provider.runtime.ProviderScriptExecution
import tachiyomi.core.provider.runtime.ProviderScriptFailure
import tachiyomi.core.provider.runtime.ProviderSecretsHostService
import tachiyomi.core.provider.runtime.ProviderStorageHostService
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class ProviderRuntimeService : Service() {

    private val activeInvocations = ConcurrentHashMap<String, Job>()

    private val binder = object : IProviderRuntimeService.Stub() {

        override fun invoke(
            requestJson: String?,
            sourceFd: ParcelFileDescriptor?,
            hostBridge: IProviderHostBridge?,
        ): String {
            val request = try {
                ProviderRuntimeProtocol.decodeRequest(requestJson.orEmpty())
            } catch (_: ProviderRuntimeProtocolException) {
                runCatching { sourceFd?.close() }
                return failure(ProviderRuntimeFailureCode.MALFORMED_REQUEST)
            }

            val invocationJob = SupervisorJob()
            if (activeInvocations.putIfAbsent(request.invocationId, invocationJob) != null) {
                runCatching { sourceFd?.close() }
                invocationJob.cancel()
                return failure(ProviderRuntimeFailureCode.INVOCATION_CONFLICT)
            }

            try {
                val source = try {
                    readSource(sourceFd)
                } catch (_: SourceTooLargeException) {
                    return failure(ProviderRuntimeFailureCode.SOURCE_TOO_LARGE)
                } catch (_: Exception) {
                    return failure(ProviderRuntimeFailureCode.SOURCE_READ_ERROR)
                }

                val result = try {
                    runBlocking(Dispatchers.Default + invocationJob) {
                        ProviderQuickJsRuntime(
                            dispatcher = Dispatchers.Default,
                            limits = request.limits.toRuntimeLimits(),
                        ).evaluate(
                            source = source,
                            fileName = request.fileName,
                            hostServices = hostBridge.toHostServices(request.hostModules),
                        )
                    }
                } catch (_: CancellationException) {
                    return failure(ProviderRuntimeFailureCode.CANCELLED)
                }

                return when (result) {
                    is ProviderScriptExecution.Success ->
                        ProviderRuntimeProtocol.encodeResponse(
                            ProviderRuntimeInvocationResponse.success(result.value),
                        )
                    is ProviderScriptExecution.Failure ->
                        ProviderRuntimeProtocol.encodeResponse(
                            ProviderRuntimeInvocationResponse.failure(result.reason.toFailureCode()),
                        )
                }
            } finally {
                activeInvocations.remove(request.invocationId, invocationJob)
                invocationJob.cancel()
            }
        }

        override fun cancel(invocationId: String?) {
            if (invocationId.isNullOrBlank()) return
            activeInvocations[invocationId]?.cancel(
                CancellationException("Provider invocation cancelled by host"),
            )
        }

        override fun processUid(): Int = Process.myUid()

        override fun processPid(): Int = Process.myPid()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        activeInvocations.values.forEach { job ->
            job.cancel(CancellationException("Provider runtime service is shutting down"))
        }
        activeInvocations.clear()
        super.onDestroy()
    }

    private fun readSource(sourceFd: ParcelFileDescriptor?): String {
        val descriptor = sourceFd
            ?: throw IOException("Provider source descriptor is missing")

        descriptor.use {
            FileInputStream(it.fileDescriptor).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0

                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    total += read
                    if (total > ProviderRuntimeProtocol.MAX_SOURCE_BYTES) {
                        throw SourceTooLargeException()
                    }
                    output.write(buffer, 0, read)
                }

                return output.toByteArray().decodeToString()
            }
        }
    }

    private fun failure(code: ProviderRuntimeFailureCode): String =
        ProviderRuntimeProtocol.encodeResponse(
            ProviderRuntimeInvocationResponse.failure(code),
        )
}

private fun ProviderScriptFailure.toFailureCode(): ProviderRuntimeFailureCode = when (this) {
    ProviderScriptFailure.TIMEOUT -> ProviderRuntimeFailureCode.TIMEOUT
    ProviderScriptFailure.SCRIPT_ERROR -> ProviderRuntimeFailureCode.SCRIPT_ERROR
    ProviderScriptFailure.HOST_ERROR -> ProviderRuntimeFailureCode.HOST_ERROR
}

private fun IProviderHostBridge?.toHostServices(
    allowedModules: Set<ProviderHostModule>,
): ProviderHostServices {
    if (this == null) return ProviderHostServices()
    val bridge = this

    return ProviderHostServices(
        http = if (ProviderHostModule.HTTP in allowedModules) {
            object : ProviderHttpHostService {
                override suspend fun getText(url: String): String =
                    bridge.httpGet(url).orEmpty()

                override suspend fun getResource(url: String): ProviderResourceHandle =
                    ProviderResourceHandle(bridge.httpGetResource(url).orEmpty())
            }
        } else {
            null
        },
        dom = if (ProviderHostModule.DOM in allowedModules) {
            object : ProviderDomHostService {
                override suspend fun selectText(
                    resourceHandle: ProviderResourceHandle,
                    cssSelector: String,
                ): String = bridge.domSelectText(resourceHandle.value, cssSelector).orEmpty()
            }
        } else {
            null
        },
        browser = if (ProviderHostModule.BROWSER in allowedModules) {
            object : ProviderBrowserHostService {
                override suspend fun readText(
                    url: String,
                    cssSelector: String,
                ): String = bridge.browserReadText(url, cssSelector).orEmpty()
            }
        } else {
            null
        },
        storage = if (ProviderHostModule.STORAGE in allowedModules) {
            object : ProviderStorageHostService {
                override suspend fun get(key: String): String? = bridge.storageGet(key)

                override suspend fun set(key: String, value: String) {
                    bridge.storageSet(key, value)
                }

                override suspend fun remove(key: String) {
                    bridge.storageRemove(key)
                }
            }
        } else {
            null
        },
        secrets = if (ProviderHostModule.SECRETS in allowedModules) {
            object : ProviderSecretsHostService {
                override suspend fun get(key: String): String? = bridge.secretGet(key)
            }
        } else {
            null
        },
        binary = if (ProviderHostModule.BINARY in allowedModules) {
            object : ProviderBinaryHostService {
                override suspend fun fetch(url: String): ProviderResourceHandle =
                    ProviderResourceHandle(bridge.binaryFetch(url).orEmpty())

                override suspend fun zipEntry(
                    resourceHandle: ProviderResourceHandle,
                    entryName: String,
                ): ProviderResourceHandle =
                    ProviderResourceHandle(
                        bridge.binaryZipEntry(resourceHandle.value, entryName).orEmpty(),
                    )
            }
        } else {
            null
        },
        crypto = if (ProviderHostModule.CRYPTO in allowedModules) {
            object : ProviderCryptoHostService {
                override suspend fun aesCbcDecrypt(
                    resourceHandle: ProviderResourceHandle,
                    keyHex: String,
                    ivHex: String,
                ): ProviderResourceHandle =
                    ProviderResourceHandle(
                        bridge.cryptoAesCbcDecrypt(resourceHandle.value, keyHex, ivHex).orEmpty(),
                    )
            }
        } else {
            null
        },
        image = if (ProviderHostModule.IMAGE in allowedModules) {
            object : ProviderImageHostService {
                override suspend fun crop(
                    resourceHandle: ProviderResourceHandle,
                    x: Int,
                    y: Int,
                    width: Int,
                    height: Int,
                ): ProviderResourceHandle =
                    ProviderResourceHandle(
                        bridge.imageCrop(resourceHandle.value, x, y, width, height).orEmpty(),
                    )

                override suspend fun pixel(
                    resourceHandle: ProviderResourceHandle,
                    x: Int,
                    y: Int,
                ): String = bridge.imagePixel(resourceHandle.value, x, y).orEmpty()
            }
        } else {
            null
        },
        log = if (ProviderHostModule.LOG in allowedModules) {
            object : ProviderLogHostService {
                override suspend fun info(message: String) {
                    bridge.logInfo(message)
                }
            }
        } else {
            null
        },
    )
}

private class SourceTooLargeException : IOException()
