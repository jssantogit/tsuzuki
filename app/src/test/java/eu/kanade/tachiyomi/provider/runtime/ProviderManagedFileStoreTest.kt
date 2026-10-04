package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceRef
import java.nio.file.Path

class ProviderManagedFileStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `promotion returns opaque token and resolves only for owning provider and format`() {
        val store = store()
        val token = store.promote(
            providerId = "org.example.reader",
            bytes = "chapter".encodeToByteArray(),
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
    fun `expired promoted resource is deleted and no longer resolves`() {
        var now = 1_000L
        val store = store(
            clock = { now },
            ttlMs = 100L,
        )
        val token = store.promote(
            providerId = "org.example.reader",
            bytes = "chapter".encodeToByteArray(),
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
    fun `promotion quotas fail closed without deleting existing managed files`() {
        val store = store(
            maxFileBytes = 4,
            maxFilesPerProvider = 1,
            maxTotalBytesPerProvider = 4,
        )
        val first = store.promote(
            providerId = "org.example.reader",
            bytes = byteArrayOf(1, 2, 3, 4),
            format = ProviderManagedResourceFormat.CBZ,
        )

        runCatching {
            store.promote(
                providerId = "org.example.reader",
                bytes = byteArrayOf(1),
                format = ProviderManagedResourceFormat.CBZ,
            )
        }.isFailure shouldBe true
        runCatching {
            store.promote(
                providerId = "org.example.other",
                bytes = byteArrayOf(1, 2, 3, 4, 5),
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
        maxFileBytes: Int = 16,
        maxFilesPerProvider: Int = 8,
        maxTotalBytesPerProvider: Long = 64,
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
    )
}
