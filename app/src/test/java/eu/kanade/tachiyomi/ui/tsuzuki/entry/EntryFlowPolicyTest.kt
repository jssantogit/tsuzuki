package eu.kanade.tachiyomi.ui.tsuzuki.entry

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState

class EntryFlowPolicyTest {

    @Test
    fun `first install choice completes welcome before opening its destination`() {
        EntryChoice.entries.forEach { choice ->
            var completed = false
            var destination: EntryDestination? = null

            EntryFlowPolicy.choose(
                choice = choice,
                markComplete = { completed = true },
                navigate = {
                    completed shouldBe true
                    destination = it
                },
            )

            completed shouldBe true
            destination shouldBe choice.destination
        }
    }

    @Test
    fun `guest is a completed first install choice that opens home`() {
        var completed = false
        var destination: EntryDestination? = null

        EntryFlowPolicy.choose(
            choice = EntryChoice.GUEST,
            markComplete = { completed = true },
            navigate = { destination = it },
        )

        completed shouldBe true
        destination shouldBe EntryDestination.HOME
    }

    @Test
    fun `welcome visibility depends on first install completion rather than auth state`() {
        val completed = true
        var accountState: AccountState = AccountState.Authenticated(
            tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount("user-1", "reader@example.com"),
        )

        EntryFlowPolicy.shouldShowWelcome(firstInstallComplete = completed) shouldBe false
        accountState = AccountState.LoggedOut
        EntryFlowPolicy.shouldShowWelcome(firstInstallComplete = completed) shouldBe false
        accountState shouldBe AccountState.LoggedOut
    }
}
