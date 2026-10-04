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
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderPackageException
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderBinaryHostService
import tachiyomi.core.provider.runtime.ProviderBrowserHostService
import tachiyomi.core.provider.runtime.ProviderCryptoHostService
import tachiyomi.core.provider.runtime.ProviderDomHostService
import tachiyomi.core.provider.runtime.ProviderHostModule
import tachiyomi.core.provider.runtime.ProviderHostServices
import tachiyomi.core.provider.runtime.ProviderHttpHostService
import tachiyomi.core.provider.runtime.ProviderHttpProtocol
import tachiyomi.core.provider.runtime.ProviderHttpRequest
import tachiyomi.core.provider.runtime.ProviderHttpResponse
import tachiyomi.core.provider.runtime.ProviderImageHostService
import tachiyomi.core.provider.runtime.ProviderInvocationLimiter
import tachiyomi.core.provider.runtime.ProviderLogHostService
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.core.provider.runtime.ProviderPackageContract
import tachiyomi.core.provider.runtime.ProviderPackageExecution
import tachiyomi.core.provider.runtime.ProviderPackageFailure
import tachiyomi.core.provider.runtime.ProviderPackageValidationRequest
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.core.provider.runtime.ProviderP2pAcquireResponse
import tachiyomi.core.provider.runtime.ProviderP2pHostService
import tachiyomi.core.provider.runtime.ProviderP2pProtocol
import tachiyomi.core.provider.runtime.ProviderQuickJsRuntime
import tachiyomi.core.provider.runtime.ProviderResourceHandle
import tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocolException
import tachiyomi.core.provider.runtime.ProviderScriptExecution
import tachiyomi.core.provider.runtime.ProviderScriptFailure
import tachiyomi.core.provider.runtime.ProviderScriptPackageRuntime
import tachiyomi.core.provider.runtime.ProviderSecretsHostService
import tachiyomi.core.provider.runtime.ProviderStorageHostService
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class ProviderRuntimeService : Service() {

    private val activeInvocations = ConcurrentHashMap<String, Job>()
    private val invocationLimiter = ProviderInvocationLimiter()

    private val binder = object : IProviderRuntimeService.Stub() {

        override fun invoke(
            requestJson: String?,
            sourceFd: ParcelFileDescriptor?,
            hostBridge: IProviderHostBridge?,
        ): String {
            val request = try {
                ProviderRuntimeProtocol.decodeRequest(requestJson.orEmpty())
            } catch (_: ProviderRuntimeProtocolException) {
                closeQuietly(sourceFd)
                return failure(ProviderRuntimeFailureCode.MALFORMED_REQUEST)
            }

            return executeInSlot(
                providerId = request.providerId,
                invocationId = request.invocationId,
                onRejected = { closeQuietly(sourceFd) },
            ) {
                val source = try {
                    readDescriptor(
                        descriptor = sourceFd,
                        maxBytes = ProviderRuntimeProtocol.MAX_SOURCE_BYTES,
                    ).decodeToString()
                } catch (_: DescriptorTooLargeException) {
                    return@executeInSlot ProviderRuntimeInvocationResponse.failure(
                        ProviderRuntimeFailureCode.SOURCE_TOO_LARGE,
                    )
                } catch (_: Exception) {
                    return@executeInSlot ProviderRuntimeInvocationResponse.failure(
                        ProviderRuntimeFailureCode.SOURCE_READ_ERROR,
                    )
                }

                when (
                    val result = ProviderQuickJsRuntime(
                        dispatcher = Dispatchers.Default,
                        limits = request.limits.toRuntimeLimits(),
                    ).evaluate(
                        source = source,
                        fileName = request.fileName,
                        hostServices = hostBridge.toHostServices(request.hostModules),
                    )
                ) {
                    is ProviderScriptExecution.Success ->
                        ProviderRuntimeInvocationResponse.success(result.value)
                    is ProviderScriptExecution.Failure ->
                        ProviderRuntimeInvocationResponse.failure(result.reason.toFailureCode())
                }
            }
        }

        override fun validatePackage(
            requestJson: String?,
            packageFd: ParcelFileDescriptor?,
        ): String {
            val request = try {
                ProviderRuntimeProtocol.decodeValidationRequest(requestJson.orEmpty())
            } catch (_: ProviderRuntimeProtocolException) {
                closeQuietly(packageFd)
                return failure(ProviderRuntimeFailureCode.MALFORMED_REQUEST)
            }

            return executeInSlot(
                providerId = request.providerId,
                invocationId = request.invocationId,
                onRejected = { closeQuietly(packageFd) },
            ) {
                val providerPackage = readAndParsePackage(
                    descriptor = packageFd,
                    providerId = request.providerId,
                    artifactVersionCode = request.artifactVersionCode,
                ) ?: return@executeInSlot ProviderRuntimeInvocationResponse.failure(
                    ProviderRuntimeFailureCode.PACKAGE_INVALID,
                )

                val runtime = ProviderScriptPackageRuntime(
                    dispatcher = Dispatchers.Default,
                    limits = request.limits.toRuntimeLimits(),
                )
                when (runtime.validateContract(providerPackage)) {
                    ProviderPackageContract.Valid ->
                        ProviderRuntimeInvocationResponse.success("valid")
                    is ProviderPackageContract.Invalid ->
                        ProviderRuntimeInvocationResponse.failure(
                            ProviderRuntimeFailureCode.PACKAGE_INVALID,
                        )
                }
            }
        }

        override fun invokePackage(
            requestJson: String?,
            packageFd: ParcelFileDescriptor?,
            inputJson: String?,
            hostBridge: IProviderHostBridge?,
        ): String {
            val request = try {
                ProviderRuntimeProtocol.decodeRequest(requestJson.orEmpty())
            } catch (_: ProviderRuntimeProtocolException) {
                closeQuietly(packageFd)
                return failure(ProviderRuntimeFailureCode.MALFORMED_REQUEST)
            }
            val input = inputJson.orEmpty()
            if (input.length > ProviderRuntimeProtocol.MAX_INPUT_JSON_CHARS) {
                closeQuietly(packageFd)
                return failure(ProviderRuntimeFailureCode.MALFORMED_REQUEST)
            }

            return executeInSlot(
                providerId = request.providerId,
                invocationId = request.invocationId,
                onRejected = { closeQuietly(packageFd) },
            ) {
                val providerPackage = readAndParsePackage(
                    descriptor = packageFd,
                    providerId = request.providerId,
                    artifactVersionCode = request.artifactVersionCode,
                ) ?: return@executeInSlot ProviderRuntimeInvocationResponse.failure(
                    ProviderRuntimeFailureCode.PACKAGE_INVALID,
                )

                val runtime = ProviderScriptPackageRuntime(
                    dispatcher = Dispatchers.Default,
                    limits = request.limits.toRuntimeLimits(),
                )
                when (
                    val result = runtime.invoke(
                        providerPackage = providerPackage,
                        capabilityId = request.capabilityId,
                        capabilityVersion = request.capabilityVersion,
                        inputJson = input,
                        hostServices = hostBridge.toHostServices(request.hostModules),
                    )
                ) {
                    is ProviderPackageExecution.Success ->
                        ProviderRuntimeInvocationResponse.success(result.json)
                    is ProviderPackageExecution.Failure ->
                        ProviderRuntimeInvocationResponse.failure(result.reason.toFailureCode())
                }
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

    private fun executeInSlot(
        providerId: String,
        invocationId: String,
        onRejected: () -> Unit,
        block: suspend () -> ProviderRuntimeInvocationResponse,
    ): String {
        val invocationJob = SupervisorJob()
        if (activeInvocations.putIfAbsent(invocationId, invocationJob) != null) {
            onRejected()
            invocationJob.cancel()
            return failure(ProviderRuntimeFailureCode.INVOCATION_CONFLICT)
        }

        val concurrencyLease = invocationLimiter.tryAcquire(providerId)
        if (concurrencyLease == null) {
            activeInvocations.remove(invocationId, invocationJob)
            onRejected()
            invocationJob.cancel()
            return failure(ProviderRuntimeFailureCode.RESOURCE_LIMIT)
        }

        return try {
            try {
                ProviderRuntimeProtocol.encodeResponse(
                    runBlocking(Dispatchers.Default + invocationJob) {
                        block()
                    },
                )
            } catch (_: CancellationException) {
                failure(ProviderRuntimeFailureCode.CANCELLED)
            }
        } finally {
            activeInvocations.remove(invocationId, invocationJob)
            invocationJob.cancel()
            concurrencyLease.close()
        }
    }

    private fun readAndParsePackage(
        descriptor: ParcelFileDescriptor?,
        providerId: String,
        artifactVersionCode: Long,
    ): ParsedProviderPackage? {
        val bytes = try {
            readDescriptor(
                descriptor = descriptor,
                maxBytes = ProviderRuntimeProtocol.MAX_PACKAGE_BYTES,
            )
        } catch (_: Exception) {
            return null
        }

        val parsed = try {
            ProviderPackageParser().parse(bytes)
        } catch (_: ProviderPackageException) {
            return null
        }

        if (
            parsed.manifest.id != providerId ||
            parsed.manifest.version.code != artifactVersionCode
        ) {
            return null
        }
        return parsed
    }

    private fun readDescriptor(
        descriptor: ParcelFileDescriptor?,
        maxBytes: Int,
    ): ByteArray {
        val value = descriptor
            ?: throw IOException("Provider descriptor is missing")

        value.use {
            FileInputStream(it.fileDescriptor).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0

                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    total += read
                    if (total > maxBytes) {
                        throw DescriptorTooLargeException()
                    }
                    output.write(buffer, 0, read)
                }

                return output.toByteArray()
            }
        }
    }

    private fun failure(code: ProviderRuntimeFailureCode): String =
        ProviderRuntimeProtocol.encodeResponse(
            ProviderRuntimeInvocationResponse.failure(code),
        )

    private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
        runCatching { descriptor?.close() }
    }
}

private fun ProviderScriptFailure.toFailureCode(): ProviderRuntimeFailureCode = when (this) {
    ProviderScriptFailure.TIMEOUT -> ProviderRuntimeFailureCode.TIMEOUT
    ProviderScriptFailure.SCRIPT_ERROR -> ProviderRuntimeFailureCode.SCRIPT_ERROR
    ProviderScriptFailure.HOST_ERROR -> ProviderRuntimeFailureCode.HOST_ERROR
}

private fun ProviderPackageFailure.toFailureCode(): ProviderRuntimeFailureCode = when (this) {
    ProviderPackageFailure.UNDECLARED_CAPABILITY ->
        ProviderRuntimeFailureCode.MALFORMED_REQUEST
    ProviderPackageFailure.MISSING_CAPABILITY_EXPORT ->
        ProviderRuntimeFailureCode.PACKAGE_INVALID
    ProviderPackageFailure.MODULE_ERROR ->
        ProviderRuntimeFailureCode.SCRIPT_ERROR
    ProviderPackageFailure.MALFORMED_INPUT ->
        ProviderRuntimeFailureCode.MALFORMED_REQUEST
    ProviderPackageFailure.MALFORMED_RESULT ->
        ProviderRuntimeFailureCode.MALFORMED_RESULT
    ProviderPackageFailure.TIMEOUT ->
        ProviderRuntimeFailureCode.TIMEOUT
    ProviderPackageFailure.HOST_ERROR ->
        ProviderRuntimeFailureCode.HOST_ERROR
}

private fun IProviderHostBridge?.toHostServices(
    allowedModules: Set<ProviderHostModule>,
): ProviderHostServices {
    if (this == null) return ProviderHostServices()
    val bridge = this

    return ProviderHostServices(
        http = if (ProviderHostModule.HTTP in allowedModules) {
            object : ProviderHttpHostService {
                override suspend fun request(request: ProviderHttpRequest): ProviderHttpResponse {
                    val encoded = ProviderHttpProtocol.encodeRequest(request)
                    val response = bridge.httpRequest(
                        encoded.boundedHostArg(
                            ProviderHttpProtocol.MAX_REQUEST_JSON_CHARS,
                            "HTTP request",
                        ),
                    ).orEmpty()
                    return ProviderHttpProtocol.decodeResponse(response)
                }

                override suspend fun getText(url: String): String =
                    bridge.httpGet(url.boundedHostArg(MAX_HOST_URL_CHARS, "HTTP URL")).orEmpty()

                override suspend fun getResource(url: String): ProviderResourceHandle =
                    ProviderResourceHandle(
                        bridge.httpGetResource(
                            url.boundedHostArg(MAX_HOST_URL_CHARS, "HTTP URL"),
                        ).orEmpty(),
                    )
            }
        } else {
            null
        },
        p2p = if (ProviderHostModule.P2P in allowedModules) {
            ProviderP2pHostService { request: ProviderP2pAcquireRequest ->
                val encoded = ProviderP2pProtocol.encodeRequest(request)
                val response = bridge.p2pAcquire(
                    encoded.boundedHostArg(
                        ProviderP2pProtocol.MAX_REQUEST_JSON_CHARS,
                        "P2P request",
                    ),
                ).orEmpty()
                ProviderP2pProtocol.decodeResponse(response)
            }
        } else {
            null
        },
        dom = if (ProviderHostModule.DOM in allowedModules) {
            object : ProviderDomHostService {
                override suspend fun selectText(
                    resourceHandle: ProviderResourceHandle,
                    cssSelector: String,
                ): String = bridge.domSelectText(
                    resourceHandle.value.boundedHostArg(MAX_HOST_HANDLE_CHARS, "resource handle"),
                    cssSelector.boundedHostArg(MAX_HOST_SELECTOR_CHARS, "DOM selector"),
                ).orEmpty()
            }
        } else {
            null
        },
        browser = if (ProviderHostModule.BROWSER in allowedModules) {
            object : ProviderBrowserHostService {
                override suspend fun readText(
                    url: String,
                    cssSelector: String,
                ): String = bridge.browserReadText(
                    url.boundedHostArg(MAX_HOST_URL_CHARS, "browser URL"),
                    cssSelector.boundedHostArg(MAX_HOST_SELECTOR_CHARS, "browser selector"),
                ).orEmpty()
            }
        } else {
            null
        },
        storage = if (ProviderHostModule.STORAGE in allowedModules) {
            object : ProviderStorageHostService {
                override suspend fun get(key: String): String? =
                    bridge.storageGet(key.boundedHostArg(MAX_HOST_KEY_CHARS, "storage key"))

                override suspend fun set(key: String, value: String) {
                    bridge.storageSet(
                        key.boundedHostArg(MAX_HOST_KEY_CHARS, "storage key"),
                        value.boundedHostArg(MAX_HOST_VALUE_CHARS, "storage value"),
                    )
                }

                override suspend fun remove(key: String) {
                    bridge.storageRemove(key.boundedHostArg(MAX_HOST_KEY_CHARS, "storage key"))
                }
            }
        } else {
            null
        },
        secrets = if (ProviderHostModule.SECRETS in allowedModules) {
            object : ProviderSecretsHostService {
                override suspend fun get(key: String): String? =
                    bridge.secretGet(key.boundedHostArg(MAX_HOST_KEY_CHARS, "secret key"))
            }
        } else {
            null
        },
        binary = if (ProviderHostModule.BINARY in allowedModules) {
            object : ProviderBinaryHostService {
                override suspend fun fetch(url: String): ProviderResourceHandle =
                    ProviderResourceHandle(
                        bridge.binaryFetch(
                            url.boundedHostArg(MAX_HOST_URL_CHARS, "binary URL"),
                        ).orEmpty(),
                    )

                override suspend fun zipEntry(
                    resourceHandle: ProviderResourceHandle,
                    entryName: String,
                ): ProviderResourceHandle =
                    ProviderResourceHandle(
                        bridge.binaryZipEntry(
                            resourceHandle.value.boundedHostArg(MAX_HOST_HANDLE_CHARS, "resource handle"),
                            entryName.boundedHostArg(MAX_HOST_ENTRY_NAME_CHARS, "archive entry"),
                        ).orEmpty(),
                    )

                override suspend fun promote(
                    resourceHandle: ProviderResourceHandle,
                    format: ProviderManagedResourceFormat,
                ): String =
                    bridge.binaryPromote(
                        resourceHandle.value.boundedHostArg(MAX_HOST_HANDLE_CHARS, "resource handle"),
                        format.name,
                    ).orEmpty()
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
                        bridge.cryptoAesCbcDecrypt(
                            resourceHandle.value.boundedHostArg(MAX_HOST_HANDLE_CHARS, "resource handle"),
                            keyHex.boundedHostArg(MAX_HOST_HEX_CHARS, "crypto key"),
                            ivHex.boundedHostArg(MAX_HOST_HEX_CHARS, "crypto IV"),
                        ).orEmpty(),
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
                        bridge.imageCrop(
                            resourceHandle.value.boundedHostArg(MAX_HOST_HANDLE_CHARS, "resource handle"),
                            x,
                            y,
                            width,
                            height,
                        ).orEmpty(),
                    )

                override suspend fun pixel(
                    resourceHandle: ProviderResourceHandle,
                    x: Int,
                    y: Int,
                ): String = bridge.imagePixel(
                    resourceHandle.value.boundedHostArg(MAX_HOST_HANDLE_CHARS, "resource handle"),
                    x,
                    y,
                ).orEmpty()
            }
        } else {
            null
        },
        log = if (ProviderHostModule.LOG in allowedModules) {
            object : ProviderLogHostService {
                override suspend fun info(message: String) {
                    bridge.logInfo(message.boundedHostArg(MAX_HOST_LOG_CHARS, "log message"))
                }
            }
        } else {
            null
        },
    )
}

private fun String.boundedHostArg(
    maxChars: Int,
    label: String,
): String {
    if (length > maxChars) {
        throw tachiyomi.core.provider.runtime.ProviderHostServiceException(
            "Provider $label exceeds the IPC size limit",
        )
    }
    return this
}

private const val MAX_HOST_URL_CHARS = 8 * 1024
private const val MAX_HOST_SELECTOR_CHARS = 2 * 1024
private const val MAX_HOST_KEY_CHARS = 128
private const val MAX_HOST_VALUE_CHARS = 64 * 1024
private const val MAX_HOST_LOG_CHARS = 1024
private const val MAX_HOST_ENTRY_NAME_CHARS = 4 * 1024
private const val MAX_HOST_HANDLE_CHARS = 256
private const val MAX_HOST_HEX_CHARS = 128

private class DescriptorTooLargeException : IOException()
