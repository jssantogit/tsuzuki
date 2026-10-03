package eu.kanade.tachiyomi.provider.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderPackageContractValidator
import tachiyomi.core.provider.runtime.ProviderPackageValidationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.core.provider.supplychain.VerifiedProviderArtifact
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProviderRuntimeClient(
    context: Context,
    private val hostInvocationFactory: ProviderHostInvocationFactory,
) {

    private val context = context.applicationContext

    suspend fun invoke(
        request: ProviderRuntimeInvocationRequest,
        source: ByteArray,
        hostPolicy: ProviderHostInvocationPolicy,
    ): ProviderRuntimeInvocationResponse {
        validateInvocation(request, source, hostPolicy)

        val hostInvocation = hostInvocationFactory.create(hostPolicy)
        return try {
            withRuntimeFile(
                bytes = source,
                suffix = ".js",
                invocationId = request.invocationId,
            ) { remote, descriptor ->
                callRemote(
                    remote = remote,
                    invocationId = request.invocationId,
                ) {
                    remote.invoke(
                        ProviderRuntimeProtocol.encodeRequest(request),
                        descriptor,
                        hostInvocation.bridge,
                    )
                }
            }
        } finally {
            hostInvocation.close()
        }
    }

    suspend fun invokePackage(
        request: ProviderRuntimeInvocationRequest,
        packageBytes: ByteArray,
        inputJson: String,
        hostPolicy: ProviderHostInvocationPolicy,
    ): ProviderRuntimeInvocationResponse {
        validateInvocation(request, packageBytes, hostPolicy, isPackage = true)
        require(inputJson.length <= ProviderRuntimeProtocol.MAX_INPUT_JSON_CHARS) {
            "Provider capability input exceeds the runtime input-size limit"
        }

        val hostInvocation = hostInvocationFactory.create(hostPolicy)
        return try {
            withRuntimeFile(
                bytes = packageBytes,
                suffix = ".tsz",
                invocationId = request.invocationId,
            ) { remote, descriptor ->
                callRemote(
                    remote = remote,
                    invocationId = request.invocationId,
                ) {
                    remote.invokePackage(
                        ProviderRuntimeProtocol.encodeRequest(request),
                        descriptor,
                        inputJson,
                        hostInvocation.bridge,
                    )
                }
            }
        } finally {
            hostInvocation.close()
        }
    }

    suspend fun validatePackage(
        request: ProviderPackageValidationRequest,
        packageBytes: ByteArray,
    ): ProviderRuntimeInvocationResponse {
        require(packageBytes.size <= ProviderRuntimeProtocol.MAX_PACKAGE_BYTES) {
            "Provider package exceeds the runtime package-size limit"
        }

        return withRuntimeFile(
            bytes = packageBytes,
            suffix = ".tsz",
            invocationId = request.invocationId,
        ) { remote, descriptor ->
            callRemote(
                remote = remote,
                invocationId = request.invocationId,
            ) {
                remote.validatePackage(
                    ProviderRuntimeProtocol.encodeValidationRequest(request),
                    descriptor,
                )
            }
        }
    }

    private fun validateInvocation(
        request: ProviderRuntimeInvocationRequest,
        bytes: ByteArray,
        hostPolicy: ProviderHostInvocationPolicy,
        isPackage: Boolean = false,
    ) {
        require(request.providerId == hostPolicy.providerId) {
            "Provider runtime request and Host Service policy must use the same Provider ID"
        }
        require(request.invocationId == hostPolicy.invocationId) {
            "Provider runtime request and Host Service policy must use the same invocation ID"
        }
        val maxBytes = if (isPackage) {
            ProviderRuntimeProtocol.MAX_PACKAGE_BYTES
        } else {
            ProviderRuntimeProtocol.MAX_SOURCE_BYTES
        }
        require(bytes.size <= maxBytes) {
            "Provider runtime payload exceeds the configured size limit"
        }
        val allowedModules = hostPolicy.allowedHostModules()
        require(request.hostModules.all { it in allowedModules }) {
            "Provider runtime request asks for a Host Service module outside its permission snapshot"
        }
    }

    private suspend fun <T> withRuntimeFile(
        bytes: ByteArray,
        suffix: String,
        invocationId: String,
        block: suspend (
            remote: IProviderRuntimeService,
            descriptor: ParcelFileDescriptor,
        ) -> T,
    ): T {
        val boundRuntime = bindRuntime()
        val file = File.createTempFile(
            ".provider-runtime-",
            suffix,
            context.cacheDir,
        )

        try {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }

            val descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            return descriptor.use {
                block(boundRuntime.remote, it)
            }
        } finally {
            runCatching { boundRuntime.remote.cancel(invocationId) }
            boundRuntime.close()
            file.delete()
        }
    }

    private suspend fun callRemote(
        remote: IProviderRuntimeService,
        invocationId: String,
        call: () -> String,
    ): ProviderRuntimeInvocationResponse =
        suspendCancellableCoroutine { continuation ->
            val future: Future<*> = BINDER_EXECUTOR.submit {
                try {
                    val raw = call()
                    if (continuation.isActive) {
                        continuation.resume(
                            ProviderRuntimeProtocol.decodeResponse(raw),
                        )
                    }
                } catch (_: DeadObjectException) {
                    if (continuation.isActive) {
                        continuation.resume(
                            ProviderRuntimeInvocationResponse.failure(
                                tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode.RUNTIME_DIED,
                            ),
                        )
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }

            continuation.invokeOnCancellation {
                runCatching { remote.cancel(invocationId) }
                future.cancel(true)
            }
        }

    private suspend fun bindRuntime(): BoundRuntime =
        suspendCancellableCoroutine { continuation ->
            val isBound = AtomicBoolean(false)
            val isClosed = AtomicBoolean(false)

            lateinit var connection: ServiceConnection
            fun unbindOnce() {
                if (isBound.get() && isClosed.compareAndSet(false, true)) {
                    runCatching { context.unbindService(connection) }
                }
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    val remote = IProviderRuntimeService.Stub.asInterface(service)
                    if (remote == null) {
                        unbindOnce()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Provider runtime connected without a Binder"),
                            )
                        }
                        return
                    }

                    if (continuation.isActive) {
                        continuation.resume(
                            BoundRuntime(
                                context = context,
                                connection = connection,
                                isClosed = isClosed,
                                remote = remote,
                            ),
                        )
                    } else {
                        unbindOnce()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit

                override fun onBindingDied(name: ComponentName?) {
                    unbindOnce()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Provider runtime binding died"),
                        )
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    unbindOnce()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Provider runtime returned a null binding"),
                        )
                    }
                }
            }

            val bound = try {
                context.bindService(
                    Intent(context, ProviderRuntimeService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (error: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
                return@suspendCancellableCoroutine
            }

            if (!bound) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Provider runtime service could not be bound"),
                    )
                }
                return@suspendCancellableCoroutine
            }
            isBound.set(true)

            if (!continuation.isActive) {
                unbindOnce()
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                unbindOnce()
            }
        }

    private class BoundRuntime(
        private val context: Context,
        private val connection: ServiceConnection,
        private val isClosed: AtomicBoolean,
        val remote: IProviderRuntimeService,
    ) : AutoCloseable {

        override fun close() {
            if (isClosed.compareAndSet(false, true)) {
                runCatching { context.unbindService(connection) }
            }
        }
    }

    private companion object {
        val BINDER_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "tsuzuki-provider-runtime-client").apply {
                isDaemon = true
            }
        }
    }
}

class IsolatedProviderPackageContractValidator(
    private val runtimeClient: ProviderRuntimeClient,
    private val limits: ProviderRuntimeLimitsDto = ProviderRuntimeLimitsDto(),
) : ProviderPackageContractValidator {

    override suspend fun validate(
        artifact: VerifiedProviderArtifact,
        providerPackage: ParsedProviderPackage,
    ): Boolean {
        val request = ProviderPackageValidationRequest(
            protocolVersion = ProviderRuntimeProtocol.VERSION,
            invocationId = "activation:${UUID.randomUUID()}",
            providerId = providerPackage.manifest.id,
            artifactVersionCode = providerPackage.manifest.version.code,
            limits = limits,
        )

        val response = runtimeClient.validatePackage(
            request = request,
            packageBytes = artifact.bytes,
        )
        return response.failure == null && response.value == "valid"
    }
}
