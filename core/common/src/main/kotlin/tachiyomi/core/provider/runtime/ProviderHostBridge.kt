package tachiyomi.core.provider.runtime

interface ProviderHostBridge {
    suspend fun httpGet(url: String): String

    suspend fun browserReadText(
        url: String,
        cssSelector: String,
    ): String

    suspend fun binaryFetch(url: String): String =
        throw UnsupportedOperationException("Binary host service is unavailable")

    suspend fun binaryZipEntry(
        resourceHandle: String,
        entryName: String,
    ): String = throw UnsupportedOperationException("Binary host service is unavailable")

    suspend fun binaryAesCbcDecrypt(
        resourceHandle: String,
        keyHex: String,
        ivHex: String,
    ): String = throw UnsupportedOperationException("Binary host service is unavailable")

    suspend fun binaryImageCrop(
        resourceHandle: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): String = throw UnsupportedOperationException("Binary host service is unavailable")

    suspend fun binaryImagePixel(
        resourceHandle: String,
        x: Int,
        y: Int,
    ): String = throw UnsupportedOperationException("Binary host service is unavailable")
}
