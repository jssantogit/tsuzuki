package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceRef
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProviderManagedFileStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `promotion returns opaque token and resolves only for owning provider and format`() {
        val store = store()
        val token = store.promote(
            providerId = "org.example.reader",
            bytes = zip("page-1.jpg", "chapter"),
            format = ProviderManagedResourceFormat.CBZ,
        )

        token.startsWith("managed:") shouldBe true
        token.contains(tempDir.toString()) shouldBe false

        store.resolve(
            providerId = ProviderId("org.example.reader"),
            resource = ProviderManagedResourceRef(token),
            format = ProviderManagedFileFormat.CBZ,
        ) shouldBe "managed-uri:$token.cbz"

        store.resolve(
            providerId = ProviderId("org.example.other"),
            resource = ProviderManagedResourceRef(token),
            format = ProviderManagedFileFormat.CBZ,
        ) shouldBe null

        store.resolve(
            providerId = ProviderId("org.example.reader"),
            resource = ProviderManagedResourceRef(token),
            format = ProviderManagedFileFormat.ZIP,
        ) shouldBe null
    }

    @Test
    fun `host downloaded archive can be adopted without exposing its filesystem path`() {
        val store = store(
            maxFileBytes = 4L * 1024L * 1024L,
            maxTotalBytesPerProvider = 8L * 1024L * 1024L,
        )
        val source = tempDir.resolve("p2p-selected.cbz").toFile().apply {
            writeBytes(zip("page-1.jpg", "torrent chapter"))
        }

        val token = store.adoptFile(
            providerId = "org.example.p2p",
            source = source,
            format = ProviderManagedResourceFormat.CBZ,
        )

        source.exists() shouldBe false
        token.startsWith("managed:") shouldBe true
        token.contains("p2p-selected") shouldBe false
        store.resolve(
            providerId = ProviderId("org.example.p2p"),
            resource = ProviderManagedResourceRef(token),
            format = ProviderManagedFileFormat.CBZ,
        ) shouldBe "managed-uri:$token.cbz"
    }

    @Test
    fun `failed host archive adoption leaves source available for engine cleanup`() {
        val store = store(
            maxFileBytes = 1024L,
        )
        val source = tempDir.resolve("invalid.cbz").toFile().apply {
            writeText("not-a-zip")
        }

        runCatching {
            store.adoptFile(
                providerId = "org.example.p2p",
                source = source,
                format = ProviderManagedResourceFormat.CBZ,
            )
        }.isFailure shouldBe true

        source.isFile shouldBe true
    }

    @Test
    fun `expired promoted resource is deleted and no longer resolves`() {
        var now = 1_000L
        val store = store(
            clock = { now },
            ttlMs = 100L,
        )
        val token = store.promote(
            providerId = "org.example.reader",
            bytes = zip("page-1.jpg", "chapter"),
            format = ProviderManagedResourceFormat.ZIP,
        )

        now = 1_101L

        store.resolve(
            providerId = ProviderId("org.example.reader"),
            resource = ProviderManagedResourceRef(token),
            format = ProviderManagedFileFormat.ZIP,
        ) shouldBe null
        tempDir.toFile().walkTopDown().filter { it.isFile }.toList() shouldBe emptyList()
    }

    @Test
    fun `clear all removes managed provider files without exposing raw paths`() {
        val store = store()
        val first = store.promote(
            providerId = "org.example.reader",
            bytes = zip("page-1.jpg", "first"),
            format = ProviderManagedResourceFormat.CBZ,
        )
        val second = store.promote(
            providerId = "org.example.other",
            bytes = zip("page-2.jpg", "second"),
            format = ProviderManagedResourceFormat.ZIP,
        )

        store.clearAll()
        store.clearAll()

        store.resolve(
            providerId = ProviderId("org.example.reader"),
            resource = ProviderManagedResourceRef(first),
            format = ProviderManagedFileFormat.CBZ,
        ) shouldBe null
        store.resolve(
            providerId = ProviderId("org.example.other"),
            resource = ProviderManagedResourceRef(second),
            format = ProviderManagedFileFormat.ZIP,
        ) shouldBe null
        tempDir.toFile().walkTopDown().filter { it.isFile }.toList() shouldBe emptyList()
    }

    @Test
    fun `promotion rejects non archive bytes and bounded archive expansion`() {
        val store = store(
            maxFileBytes = 1024,
            maxArchiveEntryBytes = 4,
            maxArchiveUncompressedBytes = 4,
        )

        runCatching {
            store.promote(
                providerId = "org.example.reader",
                bytes = "not-a-zip".encodeToByteArray(),
                format = ProviderManagedResourceFormat.CBZ,
            )
        }.isFailure shouldBe true

        runCatching {
            store.promote(
                providerId = "org.example.reader",
                bytes = zip("page.bin", "12345"),
                format = ProviderManagedResourceFormat.ZIP,
            )
        }.isFailure shouldBe true

        tempDir.toFile().walkTopDown().filter { it.isFile }.toList() shouldBe emptyList()
    }

    @Test
    fun `promotion quotas fail closed without deleting existing managed files`() {
        val store = store(
            maxFileBytes = 1024,
            maxFilesPerProvider = 1,
            maxTotalBytesPerProvider = 2048,
        )
        val first = store.promote(
            providerId = "org.example.reader",
            bytes = zip("page-1.jpg", "first"),
            format = ProviderManagedResourceFormat.CBZ,
        )

        runCatching {
            store.promote(
                providerId = "org.example.reader",
                bytes = zip("page-2.jpg", "second"),
                format = ProviderManagedResourceFormat.CBZ,
            )
        }.isFailure shouldBe true
        runCatching {
            store.promote(
                providerId = "org.example.other",
                bytes = ByteArray(1025),
                format = ProviderManagedResourceFormat.ZIP,
            )
        }.isFailure shouldBe true

        store.resolve(
            providerId = ProviderId("org.example.reader"),
            resource = ProviderManagedResourceRef(first),
            format = ProviderManagedFileFormat.CBZ,
        ) shouldBe "managed-uri:$first.cbz"
    }

    private fun store(
        clock: () -> Long = { 1_000L },
        ttlMs: Long = 10_000L,
        maxFileBytes: Long = 1024L,
        maxFilesPerProvider: Int = 8,
        maxTotalBytesPerProvider: Long = 4096,
        maxArchiveEntries: Int = 32,
        maxArchiveEntryBytes: Int = 1024,
        maxArchiveUncompressedBytes: Long = 4096,
    ) = ProviderManagedFileStore(
        root = tempDir.toFile(),
        uriFactory = { file ->
            val token = file.nameWithoutExtension
            "managed-uri:managed:$token.${file.extension}"
        },
        clock = clock,
        ttlMs = ttlMs,
        maxFileBytes = maxFileBytes,
        maxFilesPerProvider = maxFilesPerProvider,
        maxTotalBytesPerProvider = maxTotalBytesPerProvider,
        maxArchiveEntries = maxArchiveEntries,
        maxArchiveEntryBytes = maxArchiveEntryBytes,
        maxArchiveUncompressedBytes = maxArchiveUncompressedBytes,
    )

    private fun zip(path: String, value: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(value.encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
