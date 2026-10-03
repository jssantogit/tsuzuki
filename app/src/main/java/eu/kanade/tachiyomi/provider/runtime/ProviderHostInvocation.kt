package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import kotlinx.coroutines.runBlocking
import tachiyomi.core.provider.runtime.BoundedProviderLogHostService
import tachiyomi.core.provider.runtime.DefaultProviderBinaryTransformHostService
import tachiyomi.core.provider.runtime.DefaultProviderCryptoHostService
import tachiyomi.core.provider.runtime.DefaultProviderDomHostService
import tachiyomi.core.provider.runtime.DefaultProviderHttpHostService
import tachiyomi.core.provider.runtime.FileProviderStorageHostService
import tachiyomi.core.provider.runtime.ProviderHostModule
import tachiyomi.core.provider.runtime.ProviderHostServices
import tachiyomi.core.provider.runtime.ProviderHttpSessionStore
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
import tachiyomi.core.provider.runtime.ProviderResourceHandle
import tachiyomi.core.provider.runtime.ProviderResourceOwner
import tachiyomi.core.provider.runtime.ProviderResourceStore
import tachiyomi.core.provider.runtime.ScopedProviderSecretsHostService
import java.io.File
import java.security.MessageDigest

data class ProviderHostInvocationPolicy(
    val providerId: String,
    val invocationId: String,
    val networkOrigins: Set<String> = emptySet(),
    val browserOrigins: Set<String> = emptySet(),
    val allowLocalNetwork: Boolean = false,
    val storageEnabled: Boolean = false,
    val allowedSecrets: Set<String> = emptySet(),
) {
    init {
        require(PROVIDER_ID.matches(providerId)) { "Provider ID is invalid" }
        require(INVOCATION_ID.matches(invocationId)) { "Provider invocation ID is invalid" }
    }

    fun allowedHostModules(): Set<ProviderHostModule> = buildSet {
        add(ProviderHostModule.DOM)
        add(ProviderHostModule.BINARY)
        add(ProviderHostModule.CRYPTO)
        add(ProviderHostModule.IMAGE)
        add(ProviderHostModule.LOG)

        if (networkOrigins.isNotEmpty()) add(ProviderHostModule.HTTP)
        if (browserOrigins.isNotEmpty()) add(ProviderHostModule.BROWSER)
        if (storageEnabled) add(ProviderHostModule.STORAGE)
        if (allowedSecrets.isNotEmpty()) add(ProviderHostModule.SECRETS)
    }

    private companion object {
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val INVOCATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

class ProviderHostInvocationFactory(
    context: Context,
    private val storageRoot: File = File(context.filesDir, "provider-storage"),
    private val secretResolver: suspend (providerId: String, key: String) -> String? = { _, _ -> null },
    private val logSink: (providerId: String, message: String) -> Unit = { _, _ -> },
    private val httpSessions: ProviderHttpSessionStore = ProviderHttpSessionStore(),
) {

    private val context = context.applicationContext

    fun create(policy: ProviderHostInvocationPolicy): ProviderHostInvocation {
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
                )
            }

        val services = ProviderHostServices(
            http = http,
            dom = DefaultProviderDomHostService(owner, resources),
            browser = policy.browserOrigins
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
                },
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
            bridge = ProviderHostBridgeAdapter(services),
        )
    }

    private fun providerProfileName(providerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return "tsuzuki-provider-${digest.take(24)}"
    }
}

class ProviderHostInvocation internal constructor(
    private val owner: ProviderResourceOwner,
    private val resources: ProviderResourceStore,
    val bridge: IProviderHostBridge,
) : AutoCloseable {

    override fun close() {
        resources.releaseInvocation(owner)
    }
}

private class ProviderHostBridgeAdapter(
    private val services: ProviderHostServices,
) : IProviderHostBridge.Stub() {

    override fun httpGet(url: String?): String =
        runBlocking { requireService(services.http, "http").getText(url.orEmpty()) }

    override fun httpGetResource(url: String?): String =
        runBlocking { requireService(services.http, "http").getResource(url.orEmpty()).value }

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
    ): T = service ?: throw SecurityException("Provider Host Service '$name' is not permitted")
}
