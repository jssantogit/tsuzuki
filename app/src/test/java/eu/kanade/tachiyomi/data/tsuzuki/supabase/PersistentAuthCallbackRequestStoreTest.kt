package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files

class PersistentAuthCallbackRequestStoreTest {

    @Test
    fun `matching callback is accepted once across a process restart`() {
        val directory = Files.createTempDirectory("tsuzuki-callback").toFile()
        val first = PersistentAuthCallbackRequestStore(
            directory = directory,
            nowEpochMillis = { 1_000L },
            nonceSource = { "secure-signup-nonce" },
        )
        val nonce = first.start("signup")
        nonce shouldBe "secure-signup-nonce"

        val restored = PersistentAuthCallbackRequestStore(
            directory = directory,
            nowEpochMillis = { 2_000L },
        )
        restored.consume("signup", nonce) shouldBe true
        restored.consume("signup", nonce) shouldBe false
    }

    @Test
    fun `wrong type or nonce cannot consume original request`() {
        val directory = Files.createTempDirectory("tsuzuki-callback-type").toFile()
        val store = PersistentAuthCallbackRequestStore(
            directory = directory,
            nowEpochMillis = { 10L },
            nonceSource = { "nonce-123456789012345678901234" },
        )
        store.start("recovery")

        store.consume("signup", "nonce-123456789012345678901234") shouldBe false
        store.consume("recovery", "unexpected") shouldBe false
        store.consume("recovery", "nonce-123456789012345678901234") shouldBe true
    }

    @Test
    fun `expired and unrequested callbacks fail closed`() {
        val directory = Files.createTempDirectory("tsuzuki-callback-expired").toFile()
        var current = 1_000L
        val store = PersistentAuthCallbackRequestStore(
            directory = directory,
            nowEpochMillis = { current },
            nonceSource = { "nonce-123456789012345678901234" },
        )
        store.start("signup")
        store.consume("email_change", "nonce-123456789012345678901234") shouldBe false

        current += PersistentAuthCallbackRequestStore.LIFETIME_MILLIS + 1
        store.consume("signup", "nonce-123456789012345678901234") shouldBe false
    }

    @Test
    fun `accidental nonce reuse is rejected rather than permitting two callbacks`() {
        val directory = Files.createTempDirectory("tsuzuki-nonce-collision").toFile()
        val nonce = "same-nonce-value-12345678901234"
        val store = PersistentAuthCallbackRequestStore(
            directory = directory,
            nowEpochMillis = { 1_000L },
            nonceSource = { nonce },
        )

        store.start("signup") shouldBe nonce
        runCatching { store.start("recovery") }.isFailure shouldBe true
        store.consume("signup", nonce) shouldBe true
        store.consume("recovery", nonce) shouldBe false
    }

    @Test
    fun `cancelled and malformed requests never authorize callbacks`() {
        val directory = Files.createTempDirectory("tsuzuki-callback-cancel").toFile()
        val store = PersistentAuthCallbackRequestStore(
            directory = directory,
            nowEpochMillis = { 1_000L },
            nonceSource = { "nonce-123456789012345678901234" },
        )
        store.start("signup")
        store.cancel("signup", "nonce-123456789012345678901234")
        store.consume("signup", "nonce-123456789012345678901234") shouldBe false
        directory.resolve(PersistentAuthCallbackRequestStore.FILE_NAME).writeText("bad format")
        store.consume("signup", "nonce-123456789012345678901234") shouldBe false
    }
}
