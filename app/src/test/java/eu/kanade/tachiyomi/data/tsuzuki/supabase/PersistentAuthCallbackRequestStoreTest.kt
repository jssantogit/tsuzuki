package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

class PersistentAuthCallbackRequestStoreTest {

    private val verifier = "private-verifier-123456789012345678901234567890123"

    @Test
    fun `PKCE verifier survives process restart and is consumed only once`() {
        val directory = Files.createTempDirectory("tsuzuki-callback").toFile()
        val first = store(directory, now = { 1_000L })
        val request = first.start("signup")

        request.nonce shouldBe "secure-callback-nonce-1234567890"
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        request.codeChallenge shouldBe expectedChallenge
        request.codeChallenge shouldBe request.codeChallenge // deterministic S256

        val reopened = store(directory, now = { 2_000L })
        reopened.consume(request.nonce, "signup") shouldBe verifier
        reopened.consume(request.nonce, "signup") shouldBe null
    }

    @Test
    fun `wrong request type or nonce cannot consume a PKCE verifier`() {
        val directory = Files.createTempDirectory("tsuzuki-callback-type").toFile()
        val store = store(directory)
        val request = store.start("recovery")

        store.consume(request.nonce, "signup") shouldBe null
        store.consume("unexpected-nonce-1234567890", "recovery") shouldBe null
        store.consume(request.nonce, "recovery") shouldBe verifier
        store.consume(request.nonce, "recovery") shouldBe null
    }

    @Test
    fun `type may be absent in GoTrue code-only redirect`() {
        val store = store(Files.createTempDirectory("tsuzuki-code-only").toFile())
        val request = store.start("signup")
        store.consume(request.nonce) shouldBe verifier
    }

    @Test
    fun `expired callback and unsupported type are rejected`() {
        var clock = 1_000L
        val store = store(Files.createTempDirectory("tsuzuki-expiry").toFile(), { clock })
        val request = store.start("signup")

        store.consume(request.nonce, "email_change") shouldBe null
        clock += PersistentAuthCallbackRequestStore.LIFETIME_MILLIS + 1
        store.consume(request.nonce, "signup") shouldBe null
    }

    @Test
    fun `nonce collision cannot bind a second verifier`() {
        val store = store(Files.createTempDirectory("tsuzuki-collision").toFile())
        val request = store.start("signup")
        runCatching { store.start("recovery") }.isFailure shouldBe true
        store.consume(request.nonce, "signup") shouldBe verifier
        store.consume(request.nonce, "recovery") shouldBe null
    }

    @Test
    fun `damaged ledger cannot be rebound to an arbitrary user`() {
        val directory = Files.createTempDirectory("tsuzuki-corrupt").toFile()
        directory.resolve(PersistentAuthCallbackRequestStore.FILE_NAME).writeText("{bad")
        val store = store(directory)
        runCatching { store.start("signup") }.isFailure shouldBe true
        store.consume("secure-callback-nonce-1234567890", "signup") shouldBe null
    }

    @Test
    fun `short state and verifier are rejected`() {
        val dir = Files.createTempDirectory("tsuzuki-short").toFile()
        val shortNonce = PersistentAuthCallbackRequestStore(dir, nonceSource = { "tiny" })
        runCatching { shortNonce.start("signup") }.isFailure shouldBe true
        val shortVerifier = PersistentAuthCallbackRequestStore(
            dir,
            nonceSource = { "secure-callback-nonce-1234567890" },
            verifierSource = { "tiny" },
        )
        runCatching { shortVerifier.start("signup") }.isFailure shouldBe true
    }

    @Test
    fun `cancelled requests cannot authorize a future callback`() {
        val store = store(Files.createTempDirectory("tsuzuki-cancel").toFile())
        val request = store.start("recovery")
        store.cancel("recovery", request.nonce)
        store.consume(request.nonce, "recovery") shouldBe null
    }

    private fun store(
        directory: java.io.File,
        now: () -> Long = { 1_000L },
    ) = PersistentAuthCallbackRequestStore(
        directory = directory,
        nowEpochMillis = now,
        nonceSource = { "secure-callback-nonce-1234567890" },
        verifierSource = { verifier },
    )
}
