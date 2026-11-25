package eu.kanade.tachiyomi.provider.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.provider.runtime.ProviderHostServiceException
import tachiyomi.core.provider.runtime.ProviderImageHostService
import tachiyomi.core.provider.runtime.ProviderResourceHandle
import tachiyomi.core.provider.runtime.ProviderResourceKind
import tachiyomi.core.provider.runtime.ProviderResourceOwner
import tachiyomi.core.provider.runtime.ProviderResourceStore
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    /** Pack previously transformed PNG resources into the existing managed CBZ delivery path. */
    override suspend fun packPages(handlesJson: String): ProviderResourceHandle {
        require(handlesJson.length <= 32 * 1024 && handlesJson.encodeToByteArray().size <= 32 * 1024)
        val handles = JSONArray(handlesJson)
        require(handles.length() in 1..64)
        val output = ByteArrayOutputStream()
        val seen = mutableSetOf<String>()
        var totalBytes = 0L
        ZipOutputStream(object : java.io.OutputStream() {
            override fun write(value: Int) {
                require(output.size() < 16 * 1024 * 1024)
                output.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                require(output.size().toLong() + count <= 16L * 1024L * 1024L)
                output.write(bytes, offset, count)
            }
        }).use { zip ->
            for (index in 0 until handles.length()) {
                val handle = handles.get(index) as? String
                    ?: throw ProviderHostServiceException("Invalid image handle")
                require(handle.length <= 256 && seen.add(handle))
                // Only Host-created transformed image resources may be packed.
                val bytes = resources.read(owner, ProviderResourceHandle(handle), ProviderResourceKind.IMAGE)
                totalBytes += bytes.size
                require(totalBytes <= 16L * 1024L * 1024L)
                validateEncodedImageBounds(bytes)
                require(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE))
                zip.putNextEntry(ZipEntry(String.format(Locale.US, "%05d.png", index + 1)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return resources.put(owner, ProviderResourceKind.ARCHIVE, output.toByteArray())
    }

    /**
     * Compose non-overlapping rectangles from one authorized source resource.
     * No external URLs, no script callbacks and no arbitrary drawing commands.
     */
    override suspend fun compose(resourceHandle: ProviderResourceHandle, layout: String): ProviderResourceHandle {
        require(layout.length <= 32 * 1024 && layout.encodeToByteArray().size <= 32 * 1024)
        val plan = JSONObject(layout)
        require(plan.keys().asSequence().toSet() == setOf("width", "height", "tiles"))
        fun integer(objectValue: JSONObject, key: String): Int {
            val value = objectValue.get(key)
            require(value is Int)
            return value
        }
        val width = integer(plan, "width")
        val height = integer(plan, "height")
        validatePixelCount(width, height)
        // A separate combined working-set budget covers both decoded bitmaps.
        val bytes = resources.read(owner, resourceHandle)
        validateEncodedImageBounds(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth.toLong() * bounds.outHeight + width.toLong() * height <= 16L * 1024L * 1024L)
        val tiles = plan.get("tiles") as? JSONArray
            ?: throw ProviderHostServiceException("Provider image tiles are invalid")
        require(tiles.length() in 1..256)
        val rectangles = mutableListOf<Pair<Rect, Rect>>()
        var covered = 0L
        for (index in 0 until tiles.length()) {
            val tile = tiles.getJSONObject(index)
            require(tile.keys().asSequence().toSet() == setOf("sx", "sy", "dx", "dy", "width", "height"))
            val w = integer(tile, "width")
            val h = integer(tile, "height")
            require(w > 0 && h > 0)
            fun rect(x: Int, y: Int, limitW: Int, limitH: Int): Rect {
                require(x >= 0 && y >= 0 && x.toLong() + w <= limitW && y.toLong() + h <= limitH)
                return Rect(x, y, x + w, y + h)
            }
            val source = rect(integer(tile, "sx"), integer(tile, "sy"), bounds.outWidth, bounds.outHeight)
            val destination = rect(integer(tile, "dx"), integer(tile, "dy"), width, height)
            require(rectangles.none { Rect.intersects(it.second, destination) })
            covered += w.toLong() * h
            rectangles += source to destination
        }
        require(covered == width.toLong() * height)
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw ProviderHostServiceException("Provider image resource could not be decoded")
        try {
            val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(target)
                for ((from, to) in rectangles) canvas.drawBitmap(source, from, to, null)
                val output = ByteArrayOutputStream()
                val bounded = object : java.io.OutputStream() {
                    override fun write(value: Int) {
                        require(output.size() < 16 * 1024 * 1024)
                        output.write(value)
                    }

                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        require(output.size().toLong() + count <= 16L * 1024L * 1024L)
                        output.write(bytes, offset, count)
                    }
                }
                require(target.compress(Bitmap.CompressFormat.PNG, 100, bounded))
                return resources.put(owner, ProviderResourceKind.IMAGE, output.toByteArray())
            } finally {
                target.recycle()
            }
        } finally {
            source.recycle()
        }
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
            if (x < 0 || y < 0 || x.toLong() + width > source.width || y.toLong() + height > source.height) {
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

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    private fun validatePixelCount(width: Int, height: Int) {
        val pixels = width.toLong() * height.toLong()
        if (width !in 1..maxDimension || height !in 1..maxDimension || pixels <= 0L || pixels > maxPixels) {
            throw ProviderHostServiceException("Provider image pixel count exceeds the configured limit")
        }
    }
}
