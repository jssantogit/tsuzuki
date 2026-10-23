package eu.kanade.tachiyomi.ui.tsuzuki.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SupabaseCallbackCodePolicyTest {

    private val nonce = "secure-callback-nonce-1234567890"
    private val safe = mapOf("tsz_state" to nonce, "code" to "auth-code-one-time")

    @Test
    fun `code only callback allows absent flow type`() {
        val parsed = SupabaseCallbackCodePolicy.parse(safe, null)
        parsed.state shouldBe nonce
        parsed.code shouldBe "auth-code-one-time"
        parsed.typeHint shouldBe null
    }

    @Test
    fun `an explicit matching signup or recovery type is allowed`() {
        SupabaseCallbackCodePolicy.parse(safe + ("type" to "signup"), null)
            .typeHint shouldBe "signup"
        SupabaseCallbackCodePolicy.parse(safe + ("type" to "recovery"), null)
            .typeHint shouldBe "recovery"
    }

    @Test
    fun `implicit bearer token fragments and token hashes are forbidden`() {
        runCatching {
            SupabaseCallbackCodePolicy.parse(
                safe,
                "access_token=attacker&refresh_token=attacker",
            )
        }.isFailure shouldBe true
        runCatching {
            SupabaseCallbackCodePolicy.parse(
                safe + ("token_hash" to "attacker"),
                null,
            )
        }.isFailure shouldBe true
        runCatching {
            SupabaseCallbackCodePolicy.parse(
                safe + ("access_token" to "attacker"),
                null,
            )
        }.isFailure shouldBe true
        runCatching {
            SupabaseCallbackCodePolicy.parse(
                safe + ("refresh_token" to "attacker"),
                null,
            )
        }.isFailure shouldBe true
    }

    @Test
    fun `missing state and unsupported callback types are rejected`() {
        runCatching {
            SupabaseCallbackCodePolicy.parse(mapOf("code" to "c"), null)
        }.isFailure shouldBe true
        runCatching {
            SupabaseCallbackCodePolicy.parse(safe + ("type" to "email_change"), null)
        }.isFailure shouldBe true
    }

    @Test
    fun `empty authorization code is rejected`() {
        runCatching {
            SupabaseCallbackCodePolicy.parse(safe + ("code" to ""), null)
        }.isFailure shouldBe true
    }
}
