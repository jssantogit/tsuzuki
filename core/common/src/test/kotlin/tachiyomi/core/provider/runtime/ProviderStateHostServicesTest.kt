package tachiyomi.core.provider.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProviderStateHostServicesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `provider storage survives runtime disposal and stays provider scoped`() = runBlocking {
        val first = FileProviderStorageHostService(
            root = tempDir.resolve("storage").toFile(),
            providerId = "org.example.a",
        )
        first.set("mirror", "one")

        val restarted = FileProviderStorageHostService(
            root = tempDir.resolve("storage").toFile(),
            providerId = "org.example.a",
        )
        restarted.get("mirror") shouldBe "one"

        val other = FileProviderStorageHostService(
            root = tempDir.resolve("storage").toFile(),
            providerId = "org.example.b",
        )
        other.get("mirror") shouldBe null
    }

    @Test
    fun `provider storage enforces key value entry and total quotas`() = runBlocking {
        val storage = FileProviderStorageHostService(
            root = tempDir.resolve("quota").toFile(),
            providerId = "org.example.a",
            maxEntries = 2,
            maxValueChars = 4,
            maxTotalChars = 6,
        )

        storage.set("a", "1234")
        (runCatching { storage.set("b", "12345") }.exceptionOrNull() is ProviderHostServiceException) shouldBe true
        (runCatching { storage.set("b", "123") }.exceptionOrNull() is ProviderHostServiceException) shouldBe true

        storage.set("b", "12")
        (runCatching { storage.set("c", "1") }.exceptionOrNull() is ProviderHostServiceException) shouldBe true
    }

    @Test
    fun `secret service exposes only declared keys for the same provider`() = runBlocking {
        var observedProvider: String? = null
        val secrets = ScopedProviderSecretsHostService(
            providerId = "org.example.reader",
            allowedKeys = setOf("session"),
            resolver = { providerId, key ->
                observedProvider = providerId
                if (key == "session") "token" else null
            },
        )

        secrets.get("session") shouldBe "token"
        observedProvider shouldBe "org.example.reader"
        shouldThrow<ProviderHostServiceException> {
            runBlocking { secrets.get("other") }
        }
    }

    @Test
    fun `provider logging is bounded and strips control characters`() = runBlocking {
        var logged = ""
        val service = BoundedProviderLogHostService(
            maxChars = 8,
            sink = { logged = it },
        )

        service.info("abc\u0000defghijk")

        logged shouldBe "abcdefgh"
    }
}
