package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.provider.packageformat.ProviderScriptManifest
import tachiyomi.core.provider.runtime.BoundedProviderLogHostService
import tachiyomi.core.provider.runtime.DefaultProviderBinaryTransformHostService
import tachiyomi.core.provider.runtime.DefaultProviderCryptoHostService
import tachiyomi.core.provider.runtime.DefaultProviderDomHostService
import tachiyomi.core.provider.runtime.DefaultProviderHttpHostService
import tachiyomi.core.provider.runtime.FileProviderStorageHostService
import tachiyomi.core.provider.runtime.ProviderHostModule
import tachiyomi.core.provider.runtime.ProviderHostServices
import tachiyomi.core.provider.runtime.ProviderHttpDiagnostic
import tachiyomi.core.provider.runtime.ProviderHttpProtocol
import tachiyomi.core.provider.runtime.ProviderHttpSessionStore
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
import tachiyomi.core.provider.runtime.ProviderP2pAcquireResponse
import tachiyomi.core.provider.runtime.ProviderP2pHostService
import tachiyomi.core.provider.runtime.ProviderP2pProtocol
import tachiyomi.core.provider.runtime.ProviderResourceHandle
import tachiyomi.core.provider.runtime.ProviderResourceOwner
import tachiyomi.core.provider.runtime.ProviderResourceStore
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ScopedProviderSecretsHostService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

data class ProviderHostInvocationPolicy(
    val providerId: String,
    val invocationId: String,
    val networkOrigins: Set<String> = emptySet(),
    val browserOrigins: Set<String> = emptySet(),
    val allowLocalNetwork: Boolean = false,
    val storageEnabled: Boolean = false,
    val allowedSecrets: Set<String> = emptySet(),
    val directP2pEnabled: Boolean = false,
    val maxHostOperations: Int = 256,
) {
    init {
        require(PROVIDER_ID.matches(providerId)) { "Provider ID is invalid" }
        require(INVOCATION_ID.matches(invocationId)) { "Provider invocation ID is invalid" }
        require(maxHostOperations in 1..4096) {
            "Provider Host Service operation budget is outside supported bounds"
        }
    }

    fun allowedHostModules(): Set<ProviderHostModule> = buildSet {
        add(ProviderHostModule.DOM)
        add(ProviderHostModule.BINARY)
        add(ProviderHostModule.CRYPTO)
        add(ProviderHostModule.IMAGE)
        add(ProviderHostModule.LOG)

        if (networkOrigins.isNotEmpty()) add(ProviderHostModule.HTTP)
        if (directP2pEnabled) add(ProviderHostModule.P2P)
        if (browserOrigins.isNotEmpty()) add(ProviderHostModule.BROWSER)
        if (storageEnabled) add(ProviderHostModule.STORAGE)
        if (allowedSecrets.isNotEmpty()) add(ProviderHostModule.SECRETS)
    }

    companion object {
        fun fromManifest(
            manifest: ProviderScriptManifest,
            invocationId: String,
        ): ProviderHostInvocationPolicy =
            ProviderHostInvocationPolicy(
                providerId = manifest.id,
                invocationId = invocationId,
                networkOrigins = manifest.permissions.network?.origins.orEmpty(),
                browserOrigins = manifest.permissions.browser?.origins.orEmpty(),
                allowLocalNetwork = manifest.permissions.network?.localNetwork == true,
                storageEnabled = manifest.permissions.storage.enabled,
                allowedSecrets = manifest.permissions.secrets,
            )

        private val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val INVOCATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

class ProviderHostInvocationFactory(
    context: Context,
    private val storageRoot: File = File(context.filesDir, "provider-storage"),
    private val secretResolver: suspend (providerId: String, key: String) -> String? = { _, _ -> null },
    private val logSink: (providerId: String, message: String) -> Unit = { _, _ -> },
    private val httpSessions: ProviderHttpSessionStore = ProviderHttpSessionStore(),
    private val p2pServiceFactory: (String) -> ProviderP2pHostService? = { null },
    val managedFiles: ProviderManagedFileStore = ProviderManagedFileStore(context.applicationContext),
) {

    private val context = context.applicationContext

    fun create(
        policy: ProviderHostInvocationPolicy,
        invocationTimeoutMs: Long = ProviderRuntimeLimitsDto().wallClockTimeoutMs,
    ): ProviderHostInvocation {
        val owner = ProviderResourceOwner(
            providerId = policy.providerId,
            invocationId = policy.invocationId,
        )
        val resources = ProviderResourceStore()

        val http = policy.networkOrigins
            .takeIf { it.isNotEmpty() }
            ?.let { origins ->
                DefaultProviderHttpHostService(
                    owner = owner,
                    resources = resources,
                    policy = ProviderNetworkPolicy(
                        allowedOrigins = origins,
                        allowLocalNetwork = policy.allowLocalNetwork,
                    ),
                    cookieJar = httpSessions.cookieJar(policy.providerId),
                    invocationTimeoutMs = invocationTimeoutMs,
                    diagnosticSink = { diagnostic ->
                        logSink(policy.providerId, diagnostic.toRuntimeLogMessage())
                    },
                )
            }

        val browser = policy.browserOrigins
            .takeIf { it.isNotEmpty() }
            ?.let { origins ->
                AndroidProviderBrowserHostService(
                    context = context,
                    policy = ProviderNetworkPolicy(
                        allowedOrigins = origins,
                        allowLocalNetwork = policy.allowLocalNetwork,
                    ),
                    providerProfileName = providerProfileName(policy.providerId),
                )
            }

        val services = ProviderHostServices(
            http = http,
            p2p = if (policy.directP2pEnabled) {
                p2pServiceFactory(policy.providerId)
            } else {
                null
            },
            dom = DefaultProviderDomHostService(owner, resources),
            browser = browser,
            storage = if (policy.storageEnabled) {
                FileProviderStorageHostService(
                    root = storageRoot,
                    providerId = policy.providerId,
                )
            } else {
                null
            },
            secrets = policy.allowedSecrets
                .takeIf { it.isNotEmpty() }
                ?.let { keys ->
                    ScopedProviderSecretsHostService(
                        providerId = policy.providerId,
                        allowedKeys = keys,
                        resolver = secretResolver,
                    )
                },
            binary = DefaultProviderBinaryTransformHostService(
                owner = owner,
                resources = resources,
                fetcher = { url ->
                    val service = http
                        ?: throw SecurityException("Provider HTTP permission is required for binary fetch")
                    service.getBinaryBytes(url)
                },
                promoter = { bytes, format ->
                    managedFiles.promote(
                        providerId = policy.providerId,
                        bytes = bytes,
                        format = format,
                    )
                },
            ),
            crypto = DefaultProviderCryptoHostService(owner, resources),
            image = AndroidProviderImageHostService(owner, resources),
            log = BoundedProviderLogHostService { message ->
                logSink(policy.providerId, message)
            },
        )

        return ProviderHostInvocation(
            owner = owner,
            resources = resources,
            closeables = listOfNotNull<AutoCloseable>(http, browser),
            bridge = ProviderHostBridgeAdapter(
                providerId = policy.providerId,
                services = services,
                operationBudget = ProviderHostOperationBudget(policy.maxHostOperations),
            ),
        )
    }

    private fun providerProfileName(providerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return "tsuzuki-provider-${digest.take(24)}"
    }
}

private fun ProviderHttpDiagnostic.toRuntimeLogMessage(): String =
    buildString {
        append("host_http phase=")
        append(phase.name)
        append(" host=")
        append(host)
        append(" elapsedMs=")
        append(elapsedMs)
        append(" timeoutMs=")
        append(timeoutMs)
        append(" status=")
        append(statusCode ?: "none")
        append(" failure=")
        append(failureFamily?.name ?: "none")
    }

class ProviderHostInvocation internal constructor(
    private val owner: ProviderResourceOwner,
    private val resources: ProviderResourceStore,
    private val closeables: List<AutoCloseable>,
    val bridge: IProviderHostBridge,
) : AutoCloseable {

    override fun close() {
        closeables.forEach { closeable ->
            runCatching { closeable.close() }
        }
        resources.releaseInvocation(owner)
    }
}

private class ProviderHostBridgeAdapter(
    private val providerId: String,
    private val services: ProviderHostServices,
    private val operationBudget: ProviderHostOperationBudget,
) : IProviderHostBridge.Stub() {

    override fun httpGet(url: String?): String =
        runBlocking { requireService(services.http, "http").getText(url.orEmpty()) }

    override fun httpGetResource(url: String?): String =
        runBlocking { requireService(services.http, "http").getResource(url.orEmpty()).value }

    override fun httpRequest(requestJson: String?): String =
        runBlocking {
            ProviderHttpProtocol.encodeResponse(
                requireService(services.http, "http").request(
                    ProviderHttpProtocol.decodeRequest(requestJson.orEmpty()),
                ),
            )
        }

    override fun p2pAcquire(requestJson: String?): String =
        runBlocking {
            val request = ProviderP2pProtocol.decodeRequest(requestJson.orEmpty())
            val response = requireService(services.p2p, "p2p").acquire(request)
            when (response) {
                is ProviderP2pAcquireResponse.Ready ->
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.HOST_RESPONSE,
                        operationId = request.operationId,
                        providerId = providerId,
                        codes = mapOf(
                            "status" to "READY",
                            "format" to response.format.name,
                        ),
                    )
                is ProviderP2pAcquireResponse.Failure ->
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.HOST_RESPONSE,
                        operationId = request.operationId,
                        providerId = providerId,
                        codes = mapOf(
                            "status" to "FAILED",
                            "failure" to response.reason.name,
                        ),
                        priority = LogPriority.WARN,
                    )
                is ProviderP2pAcquireResponse.Pending -> Unit
            }
            ProviderP2pProtocol.encodeResponse(response)
        }

    override fun domSelectText(resourceHandle: String?, cssSelector: String?): String =
        runBlocking {
            requireService(services.dom, "dom").selectText(
                ProviderResourceHandle(resourceHandle.orEmpty()),
                cssSelector.orEmpty(),
            )
        }

    override fun browserReadText(url: String?, cssSelector: String?): String =
        runBlocking {
            requireService(services.browser, "browser").readText(
                url.orEmpty(),
                cssSelector.orEmpty(),
            )
        }

    override fun storageGet(key: String?): String? =
        runBlocking { requireService(services.storage, "storage").get(key.orEmpty()) }

    override fun storageSet(key: String?, value: String?) {
        runBlocking {
            requireService(services.storage, "storage").set(
                key.orEmpty(),
                value.orEmpty(),
            )
        }
    }

    override fun storageRemove(key: String?) {
        runBlocking { requireService(services.storage, "storage").remove(key.orEmpty()) }
    }

    override fun secretGet(key: String?): String? =
        runBlocking { requireService(services.secrets, "secrets").get(key.orEmpty()) }

    override fun binaryFetch(url: String?): String =
        runBlocking { requireService(services.binary, "binary").fetch(url.orEmpty()).value }

    override fun binaryZipEntry(resourceHandle: String?, entryName: String?): String =
        runBlocking {
            requireService(services.binary, "binary").zipEntry(
                ProviderResourceHandle(resourceHandle.orEmpty()),
                entryName.orEmpty(),
            ).value
        }

    override fun binaryPromote(resourceHandle: String?, format: String?): String =
        runBlocking {
            requireService(services.binary, "binary").promote(
                resourceHandle = ProviderResourceHandle(resourceHandle.orEmpty()),
                format = ProviderManagedResourceFormat.valueOf(format.orEmpty().uppercase()),
            )
        }

    override fun cryptoAesCbcDecrypt(
        resourceHandle: String?,
        keyHex: String?,
        ivHex: String?,
    ): String =
        runBlocking {
            requireService(services.crypto, "crypto").aesCbcDecrypt(
                ProviderResourceHandle(resourceHandle.orEmpty()),
                keyHex.orEmpty(),
                ivHex.orEmpty(),
            ).value
        }

    override fun imageCrop(
        resourceHandle: String?,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): String =
        runBlocking {
            requireService(services.image, "image").crop(
                ProviderResourceHandle(resourceHandle.orEmpty()),
                x,
                y,
                width,
                height,
            ).value
        }

    override fun imagePixel(resourceHandle: String?, x: Int, y: Int): String =
        runBlocking {
            requireService(services.image, "image").pixel(
                ProviderResourceHandle(resourceHandle.orEmpty()),
                x,
                y,
            )
        }

    override fun logInfo(message: String?) {
        runBlocking { requireService(services.log, "log").info(message.orEmpty()) }
    }

    private fun <T : Any> requireService(
        service: T?,
        name: String,
    ): T {
        operationBudget.consume()
        return service ?: throw SecurityException("Provider Host Service '$name' is not permitted")
    }
}

private class ProviderHostOperationBudget(
    maxOperations: Int,
) {
    private val remaining = AtomicInteger(maxOperations)

    fun consume() {
        val previous = remaining.getAndDecrement()
        if (previous <= 0) {
            remaining.incrementAndGet()
            throw IllegalStateException("Provider Host Service operation budget exhausted")
        }
    }
}
