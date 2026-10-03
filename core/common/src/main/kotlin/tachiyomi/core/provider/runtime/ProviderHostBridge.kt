package tachiyomi.core.provider.runtime

interface ProviderHostBridge {
    suspend fun httpGet(url: String): String

    suspend fun browserReadText(
        url: String,
        cssSelector: String,
    ): String

    suspend fun binaryFetch(url: String): String

    suspend fun binaryZipEntry(
        resourceHandle: String,
        entryName: String,
    ): String

    suspend fun binaryAesCbcDecrypt(
        resourceHandle: String,
        keyHex: String,
        ivHex: String,
    ): String

    suspend fun binaryImageCrop(
        resourceHandle: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): String

    suspend fun binaryImagePixel(
        resourceHandle: String,
        x: Int,
        y: Int,
    ): String
}
