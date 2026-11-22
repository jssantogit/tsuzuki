package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ProviderStateHostServicesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `provider storage survives runtime disposal and stays provider scoped`(): Unit = runBlocking {
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
    fun `provider storage enforces key value entry and total quotas`(): Unit = runBlocking {
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
    fun `future MangaALL shared storage retains concurrent writes across independent instances`() {
        val root = tempDir.resolve("shared-mangaall").toFile()
        val first = FileProviderStorageHostService(root, "app.tsuzuki.mangaall")
        val second = FileProviderStorageHostService(root, "app.tsuzuki.mangaall")
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val updates = (0 until 60).map { n ->
                pool.submit {
                    start.await()
                    runBlocking {
                        (if (n % 2 == 0) first else second).set("entry$n", "value$n")
                    }
                }
            }
            start.countDown()
            updates.forEach { it.get(20, TimeUnit.SECONDS) }
            runBlocking {
                for (n in 0 until 60) {
                    first.get("entry$n") shouldBe "value$n"
                    second.get("entry$n") shouldBe "value$n"
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `future MangaALL storage respects an OS lock held by another process`() {
        val root = tempDir.resolve("multiprocess").toFile()
        val storage = FileProviderStorageHostService(root, "app.tsuzuki.mangaall")
        runBlocking { storage.set("existing", "preserved") }
        val helper = tempDir.resolve("HoldMangaAllFileLock.java")
        Files.writeString(
            helper,
            """
            import java.io.RandomAccessFile;
            import java.nio.channels.FileLock;
            public class HoldMangaAllFileLock {
                public static void main(String[] args) throws Exception {
                    try (RandomAccessFile file = new RandomAccessFile(args[0], "rw");
                         FileLock lock = file.getChannel().lock()) {
                        System.out.println("LOCKED");
                        System.out.flush();
                        Thread.sleep(1400);
                    }
                }
            }
            """.trimIndent(),
        )
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val lockFile = root.resolve(".app.tsuzuki.mangaall.snapshot.lock")
        val child = ProcessBuilder(java, helper.toString(), lockFile.toString())
            .redirectErrorStream(true)
            .start()
        val pool = Executors.newSingleThreadExecutor()
        try {
            child.inputStream.bufferedReader().readLine() shouldBe "LOCKED"
            val started = CountDownLatch(1)
            val waiting = pool.submit {
                started.countDown()
                runBlocking { storage.set("later", "success") }
            }
            started.await(3, TimeUnit.SECONDS) shouldBe true
            (
                runCatching { waiting.get(150, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException
                ) shouldBe true
            child.waitFor(10, TimeUnit.SECONDS) shouldBe true
            child.exitValue() shouldBe 0
            waiting.get(10, TimeUnit.SECONDS)
            runBlocking {
                storage.get("existing") shouldBe "preserved"
                storage.get("later") shouldBe "success"
            }
        } finally {
            pool.shutdownNow()
            child.destroyForcibly()
        }
    }

    @Test
    fun `future MangaALL lock errors leave committed state intact`(): Unit = runBlocking {
        val root = tempDir.resolve("blocked-lock").toFile()
        val storage = FileProviderStorageHostService(root, "app.tsuzuki.mangaall")
        storage.set("existing", "preserved")
        val lockFile = root.resolve(".app.tsuzuki.mangaall.snapshot.lock").toPath()
        Files.delete(lockFile)
        Files.createDirectory(lockFile)
        try {
            (
                runCatching { storage.set("uncommitted", "bad") }
                    .exceptionOrNull() is ProviderHostServiceException
                ) shouldBe true
        } finally {
            Files.delete(lockFile)
        }
        storage.get("existing") shouldBe "preserved"
        storage.get("uncommitted") shouldBe null
    }

    @Test
    fun `queued future MangaALL callers share one timeout budget across JVM and OS locks`() {
        val root = tempDir.resolve("queued-lock").toFile()
        val a = FileProviderStorageHostService(root, "app.tsuzuki.mangaall")
        val b = FileProviderStorageHostService(root, "app.tsuzuki.mangaall")
        runBlocking { a.set("existing", "preserved") }
        val lockFile = root.resolve(".app.tsuzuki.mangaall.snapshot.lock")
        val pool = Executors.newFixedThreadPool(2)
        try {
            RandomAccessFile(lockFile, "rw").use { handle ->
                handle.channel.lock().use {
                    val entered = CountDownLatch(2)
                    val start = CountDownLatch(1)
                    val attempts = listOf(a, b).map { storage ->
                        pool.submit<Throwable?> {
                            entered.countDown()
                            start.await()
                            runCatching {
                                runBlocking { storage.set("uncommitted", "bad") }
                            }.exceptionOrNull()
                        }
                    }
                    entered.await(3, TimeUnit.SECONDS) shouldBe true
                    val began = System.nanoTime()
                    start.countDown()
                    // The former synchronized(monitor) implementation could
                    // serialize two separate 3s waits into 6s. Both operations
                    // must fail within ONE deadline even with OS contention.
                    attempts.forEach { attempt ->
                        (attempt.get(5, TimeUnit.SECONDS) is ProviderHostServiceException) shouldBe true
                    }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                    (elapsed < 5_000L) shouldBe true
                }
            }
            runBlocking {
                a.get("existing") shouldBe "preserved"
                b.get("uncommitted") shouldBe null
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `secret service exposes only declared keys for the same provider`(): Unit = runBlocking {
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
        (runCatching { secrets.get("other") }.exceptionOrNull() is ProviderHostServiceException) shouldBe true
    }

    @Test
    fun `secret service accepts the configured limit and rejects one character beyond it`(): Unit = runBlocking {
        val allowed = ScopedProviderSecretsHostService(
            providerId = "org.example.reader",
            allowedKeys = setOf("session"),
            maxValueChars = 4,
            resolver = { _, _ -> "1234" },
        )
        allowed.get("session") shouldBe "1234"

        val oversized = ScopedProviderSecretsHostService(
            providerId = "org.example.reader",
            allowedKeys = setOf("session"),
            maxValueChars = 4,
            resolver = { _, _ -> "12345" },
        )
        (runCatching { oversized.get("session") }.exceptionOrNull() is ProviderHostServiceException) shouldBe true
    }

    @Test
    fun `provider logging is bounded and strips control characters`(): Unit = runBlocking {
        var logged = ""
        val service = BoundedProviderLogHostService(
            maxChars = 8,
            sink = { logged = it },
        )

        service.info("abc\u0000defghijk")

        logged shouldBe "abcdefgh"
    }
}
