package eu.kanade.tachiyomi.provider.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The isolated process bridge must expose where a chapter-page invocation stalls. */
enum class ProviderRuntimeBindingPhase {
    BIND_STARTED,
    BIND_CONNECTED,
    BIND_REUSED,
    BIND_INVALIDATED,
    INVOKE_STARTED,
    INVOKE_COMPLETED,
}

class ProviderRuntimeClient(
    context: Context,
    private val hostInvocationFactory: ProviderHostInvocationFactory,
    private val bindingObserver: (ProviderRuntimeBindingPhase) -> Unit = {},
) {

    private val context = context.applicationContext

    // This client is App-scoped. Rebinding an isolated Android service for every
    // chapter page races its teardown on physical devices. Retain only the
    // Binder connection briefly: each invocation still has fresh QuickJS,
    // permissions, HTTP broker, resource owner and cancellation identity.
    private val bindingMutex = Mutex()
    private val bindingLock = Any()
    private var cachedBinding: RuntimeLease? = null

    private fun reportBinding(phase: ProviderRuntimeBindingPhase) {
        runCatching { bindingObserver(phase) }
    }

    private fun borrowLiveBinding(): RuntimeLease? = synchronized(bindingLock) {
        val existing = cachedBinding ?: return@synchronized null
        if (existing.bound.remote.asBinder().isBinderAlive) {
            existing.expiration?.cancel(false)
            existing.expiration = null
            existing.users += 1
            return@synchronized existing
        }
        cachedBinding = null
        existing.expiration?.cancel(false)
        existing.expiration = null
        if (existing.users == 0) existing.bound.close()
        null
    }

    private suspend fun acquireRuntime(): RuntimeLease {
        borrowLiveBinding()?.let {
            reportBinding(ProviderRuntimeBindingPhase.BIND_REUSED)
            return it
        }

        // Serialize cold connects. Parallel invocations borrow one live Binder
        // without making competing isolated-process bindService requests.
        bindingMutex.lock()
        try {
            borrowLiveBinding()?.let {
                reportBinding(ProviderRuntimeBindingPhase.BIND_REUSED)
                return it
            }
            reportBinding(ProviderRuntimeBindingPhase.BIND_STARTED)
            val bound = bindRuntime()
            reportBinding(ProviderRuntimeBindingPhase.BIND_CONNECTED)
            return RuntimeLease(bound, users = 1).also { lease ->
                synchronized(bindingLock) { cachedBinding = lease }
            }
        } finally {
            bindingMutex.unlock()
        }
    }

    private fun releaseRuntime(lease: RuntimeLease) {
        synchronized(bindingLock) {
            check(lease.users > 0) { "Provider runtime binding lease underflow" }
            lease.users -= 1
            if (lease.users != 0) return
            if (cachedBinding !== lease) {
                lease.bound.close()
                return
            }
            lease.expiration?.cancel(false)
            lease.expiration = BINDING_IDLE_EXECUTOR.schedule(
                {
                    synchronized(bindingLock) {
                        if (cachedBinding === lease && lease.users == 0) {
                            cachedBinding = null
                            lease.bound.close()
                            reportBinding(ProviderRuntimeBindingPhase.BIND_INVALIDATED)
                        }
                    }
                },
                BINDING_IDLE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private class RuntimeLease(
        val bound: BoundRuntime,
        var users: Int,
        var expiration: ScheduledFuture<*>? = null,
    )

    suspend fun invoke(
        request: ProviderRuntimeInvocationRequest,
        source: ByteArray,
        hostPolicy: ProviderHostInvocationPolicy,
    ): ProviderRuntimeInvocationResponse {
        validateInvocation(request, source, hostPolicy)

        return withRuntimeFile(
            bytes = source,
            suffix = ".js",
            invocationId = request.invocationId,
        ) { remote, descriptor ->
            val hostInvocation = hostInvocationFactory.create(
                policy = hostPolicy,
                invocationTimeoutMs = request.limits.wallClockTimeoutMs,
            )
            try {
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
            } finally {
                hostInvocation.close()
            }
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

        return withRuntimeFile(
            bytes = packageBytes,
            suffix = ".tsz",
            invocationId = request.invocationId,
        ) { remote, descriptor ->
            val hostInvocation = hostInvocationFactory.create(
                policy = hostPolicy,
                invocationTimeoutMs = request.limits.wallClockTimeoutMs,
            )
            try {
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
            } finally {
                hostInvocation.close()
            }
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

            val lease = acquireRuntime()
            try {
                val descriptor = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
                reportBinding(ProviderRuntimeBindingPhase.INVOKE_STARTED)
                return descriptor.use {
                    block(lease.bound.remote, it)
                }.also {
                    reportBinding(ProviderRuntimeBindingPhase.INVOKE_COMPLETED)
                }
            } finally {
                runCatching { lease.bound.remote.cancel(invocationId) }
                releaseRuntime(lease)
            }
        } finally {
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
        private const val BINDING_IDLE_TIMEOUT_MS = 8_000L
        private val BINDING_IDLE_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "tsuzuki-provider-runtime-binding-idle").apply { isDaemon = true }
        }

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
