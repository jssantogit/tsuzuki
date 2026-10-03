package tachiyomi.core.provider.runtime

interface ProviderHttpHostService {
    suspend fun getText(url: String): String

    suspend fun getResource(url: String): ProviderResourceHandle
}

interface ProviderDomHostService {
    suspend fun selectText(
        resourceHandle: ProviderResourceHandle,
        cssSelector: String,
    ): String
}

interface ProviderBrowserHostService {
    suspend fun readText(
        url: String,
        cssSelector: String,
    ): String
}

interface ProviderStorageHostService {
    suspend fun get(key: String): String?

    suspend fun set(key: String, value: String)

    suspend fun remove(key: String)
}

interface ProviderSecretsHostService {
    suspend fun get(key: String): String?
}

interface ProviderBinaryHostService {
    suspend fun fetch(url: String): ProviderResourceHandle

    suspend fun zipEntry(
        resourceHandle: ProviderResourceHandle,
        entryName: String,
    ): ProviderResourceHandle
}

interface ProviderCryptoHostService {
    suspend fun aesCbcDecrypt(
        resourceHandle: ProviderResourceHandle,
        keyHex: String,
        ivHex: String,
    ): ProviderResourceHandle
}

interface ProviderImageHostService {
    suspend fun crop(
        resourceHandle: ProviderResourceHandle,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): ProviderResourceHandle

    suspend fun pixel(
        resourceHandle: ProviderResourceHandle,
        x: Int,
        y: Int,
    ): String
}

interface ProviderLogHostService {
    suspend fun info(message: String)
}

data class ProviderHostServices(
    val http: ProviderHttpHostService? = null,
    val dom: ProviderDomHostService? = null,
    val browser: ProviderBrowserHostService? = null,
    val storage: ProviderStorageHostService? = null,
    val secrets: ProviderSecretsHostService? = null,
    val binary: ProviderBinaryHostService? = null,
    val crypto: ProviderCryptoHostService? = null,
    val image: ProviderImageHostService? = null,
    val log: ProviderLogHostService? = null,
)
