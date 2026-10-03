package eu.kanade.tachiyomi.provider.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import java.io.File
import java.io.FileOutputStream
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

        val boundRuntime = bindRuntime()
        val hostInvocation = hostInvocationFactory.create(hostPolicy)
        val sourceFile = File.createTempFile(
            ".provider-source-",
            ".js",
            context.cacheDir,
        )

        try {
            FileOutputStream(sourceFile).use { output ->
                output.write(source)
                output.flush()
                output.fd.sync()
            }

            val sourceFd = ParcelFileDescriptor.open(
                sourceFile,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            return sourceFd.use { descriptor ->
                invokeRemote(
                    remote = boundRuntime.remote,
                    request = request,
                    sourceFd = descriptor,
                    hostBridge = hostInvocation.bridge,
                )
            }
        } finally {
            hostInvocation.close()
            boundRuntime.close()
            sourceFile.delete()
        }
    }

    private fun validateInvocation(
        request: ProviderRuntimeInvocationRequest,
        source: ByteArray,
        hostPolicy: ProviderHostInvocationPolicy,
    ) {
        require(request.providerId == hostPolicy.providerId) {
            "Provider runtime request and Host Service policy must use the same Provider ID"
        }
        require(request.invocationId == hostPolicy.invocationId) {
            "Provider runtime request and Host Service policy must use the same invocation ID"
        }
        require(source.size <= ProviderRuntimeProtocol.MAX_SOURCE_BYTES) {
            "Provider JavaScript source exceeds the runtime source-size limit"
        }
    }

    private suspend fun invokeRemote(
        remote: IProviderRuntimeService,
        request: ProviderRuntimeInvocationRequest,
        sourceFd: ParcelFileDescriptor,
        hostBridge: IProviderHostBridge,
    ): ProviderRuntimeInvocationResponse =
        suspendCancellableCoroutine { continuation ->
            val future: Future<*> = BINDER_EXECUTOR.submit {
                try {
                    val raw = remote.invoke(
                        ProviderRuntimeProtocol.encodeRequest(request),
                        sourceFd,
                        hostBridge,
                    )
                    if (continuation.isActive) {
                        continuation.resume(
                            ProviderRuntimeProtocol.decodeResponse(raw),
                        )
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }

            continuation.invokeOnCancellation {
                runCatching { remote.cancel(request.invocationId) }
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
