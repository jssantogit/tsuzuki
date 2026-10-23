package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files

class PersistentSupabaseAccountScopeGuardTest {

    @Test
    fun `first account is durably bound and a different account cannot reuse its sync state`() = runTest {
        val directory = Files.createTempDirectory("tsuzuki-sync-owner").toFile()
        val first = PersistentSupabaseAccountScopeGuard(directory) { false }

        first.permits("account-A") shouldBe true
        first.permits("account-B") shouldBe false

        val restored = PersistentSupabaseAccountScopeGuard(directory) { true }
        restored.permits("account-A") shouldBe true
        restored.permits("account-B") shouldBe false
    }

    @Test
    fun `unowned legacy cloud state fails closed instead of trusting currently signed in account`() = runTest {
        val directory = Files.createTempDirectory("tsuzuki-legacy-sync").toFile()
        val guard = PersistentSupabaseAccountScopeGuard(directory) { true }

        guard.permits("account-B") shouldBe false
        guard.permits("account-A") shouldBe false
        directory.resolve(PersistentSupabaseAccountScopeGuard.FILE_NAME).exists() shouldBe false
    }

    @Test
    fun `malformed or blank owner marker never authorizes a new account`() = runTest {
        val directory = Files.createTempDirectory("tsuzuki-invalid-sync-owner").toFile()
        directory.resolve(PersistentSupabaseAccountScopeGuard.FILE_NAME).writeText(" ")
        val guard = PersistentSupabaseAccountScopeGuard(directory) { false }

        guard.permits("account-A") shouldBe false
    }

    @Test
    fun `blank account identity is never bound`() = runTest {
        val directory = Files.createTempDirectory("tsuzuki-empty-account").toFile()
        val guard = PersistentSupabaseAccountScopeGuard(directory) { false }

        guard.permits(" ") shouldBe false
        directory.resolve(PersistentSupabaseAccountScopeGuard.FILE_NAME).exists() shouldBe false
    }
}
