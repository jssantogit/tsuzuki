package eu.kanade.tachiyomi.provider.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import tachiyomi.core.provider.runtime.ProviderHostServiceException
import tachiyomi.core.provider.runtime.ProviderImageHostService
import tachiyomi.core.provider.runtime.ProviderResourceHandle
import tachiyomi.core.provider.runtime.ProviderResourceKind
import tachiyomi.core.provider.runtime.ProviderResourceOwner
import tachiyomi.core.provider.runtime.ProviderResourceStore
import java.io.ByteArrayOutputStream
import java.util.Locale

class AndroidProviderImageHostService(
    private val owner: ProviderResourceOwner,
    private val resources: ProviderResourceStore,
    private val maxDimension: Int = 16_384,
    private val maxPixels: Long = 64L * 1024L * 1024L,
) : ProviderImageHostService {

    init {
        require(maxDimension > 0) { "Provider image dimension limit must be positive" }
        require(maxPixels > 0L) { "Provider image pixel limit must be positive" }
    }

    override suspend fun crop(
        resourceHandle: ProviderResourceHandle,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): ProviderResourceHandle {
        if (width <= 0 || height <= 0) {
            throw ProviderHostServiceException("Provider image crop dimensions must be positive")
        }
        validatePixelCount(width, height)

        val bytes = resources.read(owner, resourceHandle)
        validateEncodedImageBounds(bytes)
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw ProviderHostServiceException("Provider image resource could not be decoded")

        try {
            if (x < 0 || y < 0 || x + width > source.width || y + height > source.height) {
                throw ProviderHostServiceException("Provider image crop is outside source bounds")
            }
            val cropped = Bitmap.createBitmap(source, x, y, width, height)
            try {
                val encoded = ByteArrayOutputStream().use { output ->
                    if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw ProviderHostServiceException("Provider cropped image could not be encoded")
                    }
                    output.toByteArray()
                }
                return resources.put(owner, ProviderResourceKind.IMAGE, encoded)
            } finally {
                if (cropped !== source) {
                    cropped.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    override suspend fun pixel(
        resourceHandle: ProviderResourceHandle,
        x: Int,
        y: Int,
    ): String {
        val bytes = resources.read(owner, resourceHandle)
        validateEncodedImageBounds(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw ProviderHostServiceException("Provider image resource could not be decoded")

        try {
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) {
                throw ProviderHostServiceException("Provider image pixel is outside source bounds")
            }
            return String.format(Locale.US, "%08X", bitmap.getPixel(x, y))
        } finally {
            bitmap.recycle()
        }
    }

    private fun validateEncodedImageBounds(bytes: ByteArray) {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw ProviderHostServiceException("Provider image bounds could not be decoded")
        }
        if (options.outWidth > maxDimension || options.outHeight > maxDimension) {
            throw ProviderHostServiceException("Provider image dimensions exceed the configured limit")
        }
        validatePixelCount(options.outWidth, options.outHeight)
    }

    private fun validatePixelCount(width: Int, height: Int) {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= 0L || pixels > maxPixels) {
            throw ProviderHostServiceException("Provider image pixel count exceeds the configured limit")
        }
    }
}
