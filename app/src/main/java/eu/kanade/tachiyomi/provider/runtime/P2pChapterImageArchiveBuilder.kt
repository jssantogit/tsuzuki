package eu.kanade.tachiyomi.provider.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A real file index and path from verified torrent metadata, never a directory index. */
internal data class TorrentVerifiedImageFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
)

/**
 * Packages only completed native image files from one chapter into a Host-owned CBZ.
 * Caller must verify all selected indices/paths/sizes against TorrentInfo and pieces
 * first; this component independently checks paths, content, bounds and the archive.
 */
internal class P2pChapterImageArchiveBuilder {

    fun build(
        downloadRoot: File,
        archiveRoot: File,
        images: List<TorrentVerifiedImageFile>,
    ): File? {
        if (images.isEmpty() || images.size > MAX_CHAPTER_PAGES ||
            images.map { it.index }.distinct().size != images.size ||
            images.map { it.path.lowercase() }.distinct().size != images.size
        ) {
            return null
        }
        val chapterPrefix = images.first().path.substringBeforeLast('/', missingDelimiterValue = "")
        if (chapterPrefix.isBlank() || images.any { it.path.substringBeforeLast('/') != chapterPrefix }) {
            return null
        }

        val downloadedRoot = runCatching { downloadRoot.canonicalFile }.getOrNull()
            ?.takeIf { it.isDirectory } ?: return null
        val downloadedPath = downloadedRoot.toPath()
        var totalBytes = 0L
        val verified = mutableListOf<Pair<TorrentVerifiedImageFile, File>>()
        for (image in images) {
            if (!safePath(image.path) || image.index < 0 || image.sizeBytes <= 0L ||
                image.sizeBytes > MAX_PAGE_BYTES || totalBytes > MAX_CHAPTER_BYTES - image.sizeBytes
            ) {
                return null
            }
            val extension = image.path.substringAfterLast('.', "").lowercase()
            if (extension !in ALLOWED_EXTENSIONS) return null
            val source = runCatching { File(downloadedRoot, image.path).canonicalFile }.getOrNull() ?: return null
            if (!source.toPath().startsWith(downloadedPath) || !source.isFile ||
                Files.isSymbolicLink(File(downloadedRoot, image.path).toPath()) ||
                source.length() != image.sizeBytes || !matchesImageSignature(source, extension)
            ) {
                return null
            }
            totalBytes += image.sizeBytes
            verified += image to source
        }

        val root = archiveRoot.takeIf { it.isDirectory || it.mkdirs() } ?: return null
        val part = runCatching { File.createTempFile("chapter-", ".cbz.part", root) }.getOrNull() ?: return null
        var committed = false
        try {
            ZipOutputStream(FileOutputStream(part).buffered()).use { output ->
                verified.forEachIndexed { pageIndex, (image, file) ->
                    val extension = image.path.substringAfterLast('.').lowercase()
                    output.putNextEntry(
                        ZipEntry((pageIndex + 1).toString().padStart(4, '0') + "." + extension),
                    )
                    var copied = 0L
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > image.sizeBytes) return null
                            output.write(buffer, 0, read)
                        }
                    }
                    if (copied != image.sizeBytes) return null
                    output.closeEntry()
                }
            }
            if (verified.any { (image, file) -> file.length() != image.sizeBytes }) return null

            ZipFile(part).use { zip ->
                if (zip.size() != verified.size) return null
            }
            val destination = File(root, "chapter-" + UUID.randomUUID() + ".cbz")
            if (!part.renameTo(destination)) return null
            committed = true
            return destination
        } catch (_: Exception) {
            return null
        } finally {
            if (!committed) part.delete()
        }
    }

    private fun safePath(path: String): Boolean =
        path.isNotBlank() && path.length <= 4096 &&
            !path.startsWith('/') && !path.startsWith('\\') &&
            '\\' !in path && ':' !in path && '\u0000' !in path &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun matchesImageSignature(file: File, extension: String): Boolean {
        val header = ByteArray(12)
        val count = runCatching { file.inputStream().use { it.read(header) } }.getOrNull() ?: return false
        return when (extension) {
            "jpg", "jpeg" -> count >= 3 && header[0] == 0xff.toByte() &&
                header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
            "png" -> count >= 8 && header.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            )
            "webp" -> count >= 12 && header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                header.copyOfRange(8, 12).decodeToString() == "WEBP"
            "gif" -> count >= 6 && header.copyOfRange(0, 6).decodeToString() in setOf("GIF87a", "GIF89a")
            else -> false
        }
    }

    private companion object {
        const val MAX_CHAPTER_PAGES = 400
        const val MAX_PAGE_BYTES = 32L * 1024L * 1024L
        const val MAX_CHAPTER_BYTES = 256L * 1024L * 1024L
        val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
    }
}
