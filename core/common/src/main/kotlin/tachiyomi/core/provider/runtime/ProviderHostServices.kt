package tachiyomi.core.provider.runtime

interface ProviderHttpHostService {
    suspend fun request(request: ProviderHttpRequest): ProviderHttpResponse =
        throw ProviderHostServiceException("Provider HTTP request API is unavailable")

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
    suspend fun query(url: String, cssSelector: String, attribute: String?): String =
        throw ProviderHostServiceException("Provider browser query is unavailable")

    suspend fun readText(
        url: String,
        cssSelector: String,
    ): String
}

/**
 * Future MangaALL-only chapter-list continuation, never ordinary HTTP.
 * The Host grants this interface only to an explicitly authorized provider
 * manifest; it is not part of the generic storage or network capabilities.
 */
interface ProviderMangaAllChapterSnapshotHostService {
    suspend fun start(workId: String, language: String): String

    suspend fun next(workId: String, language: String, cursor: String): String
}

interface ProviderStorageHostService {
    suspend fun get(key: String): String?

    suspend fun set(key: String, value: String)

    suspend fun remove(key: String)
}

interface ProviderSecretsHostService {
    suspend fun get(key: String): String?
}

enum class ProviderManagedResourceFormat {
    CBZ,
    ZIP,
}

interface ProviderBinaryHostService {
    suspend fun inspect(resourceHandle: ProviderResourceHandle, operation: String, argument: String): String =
        throw ProviderHostServiceException("Provider binary inspection is unavailable")

    suspend fun transform(resourceHandle: ProviderResourceHandle, operation: String): ProviderResourceHandle =
        throw ProviderHostServiceException("Provider binary transform is unavailable")

    suspend fun fetch(url: String): ProviderResourceHandle

    suspend fun zipEntry(
        resourceHandle: ProviderResourceHandle,
        entryName: String,
    ): ProviderResourceHandle

    suspend fun promote(
        resourceHandle: ProviderResourceHandle,
        format: ProviderManagedResourceFormat,
    ): String
}

interface ProviderCryptoHostService {
    suspend fun aesCbcDecrypt(
        resourceHandle: ProviderResourceHandle,
        keyHex: String,
        ivHex: String,
    ): ProviderResourceHandle
}

interface ProviderImageHostService {
    suspend fun packPages(handlesJson: String): ProviderResourceHandle =
        throw ProviderHostServiceException("Provider image archive creation is unavailable")

    suspend fun compose(resourceHandle: ProviderResourceHandle, layout: String): ProviderResourceHandle =
        throw ProviderHostServiceException("Provider image composition is unavailable")

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
    val p2p: ProviderP2pHostService? = null,
    val dom: ProviderDomHostService? = null,
    val browser: ProviderBrowserHostService? = null,
    val storage: ProviderStorageHostService? = null,
    val mangaAllChapterSnapshot: ProviderMangaAllChapterSnapshotHostService? = null,
    val secrets: ProviderSecretsHostService? = null,
    val binary: ProviderBinaryHostService? = null,
    val crypto: ProviderCryptoHostService? = null,
    val image: ProviderImageHostService? = null,
    val log: ProviderLogHostService? = null,
)
