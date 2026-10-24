package tachiyomi.domain.tsuzuki.library.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VerifiedExternalLibraryOwnerTest {
    @Test
    fun `distinct remote accounts and providers never share the same owner key`() {
        val accountA = VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", "account-A")
        val accountB = VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", "account-B")
        val otherProvider = VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("kitsu", "account-A")
        (accountA != accountB) shouldBe true
        (accountA != otherProvider) shouldBe true
        accountA.ownerKey.length shouldBe 64
        accountA.ownerKey.contains("account-A") shouldBe false
        accountA shouldBe VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", "account-A")
    }

    @Test
    fun `blank ambiguous and oversized remote identities fail closed`() {
        listOf("", " ", "\n", " \t ").forEach { subject ->
            shouldThrow<IllegalArgumentException> {
                VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", subject)
            }
        }
        shouldThrow<IllegalArgumentException> {
            VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", "a".repeat(513))
        }
        shouldThrow<IllegalArgumentException> {
            VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("", "account-A")
        }
    }
}
