package eu.kanade.tachiyomi.ui.tsuzuki.detail

import eu.kanade.presentation.tsuzuki.content.ContentProviderLabels
import eu.kanade.presentation.tsuzuki.content.emptySelectorMessage
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalDetailsProviderCopyTest {

    @Test
    fun `Details source recovery uses supplied Provider terminology for each real empty reason`() {
        val empty = ContentSelectorScreenState.Empty("work", "chapter")
        val labels = labels()

        emptySelectorMessage(empty.copy(noEnabledAddon = true), labels) shouldBe labels.noEnabled
        emptySelectorMessage(empty.copy(confirmationRequired = true), labels) shouldBe labels.confirmationRequired
        emptySelectorMessage(empty.copy(timedOut = true), labels) shouldBe labels.timedOut
        emptySelectorMessage(empty.copy(discoveryAttempted = true), labels) shouldBe labels.notFound
        emptySelectorMessage(empty, labels) shouldBe null
    }

    @Test
    fun `Details terminology override does not change legacy Reader defaults or recovery precedence`() {
        val empty = ContentSelectorScreenState.Empty("work", "chapter", noEnabledAddon = true)

        emptySelectorMessage(empty)?.contains("Add-on") shouldBe true
        emptySelectorMessage(empty.copy(confirmationRequired = true), labels()) shouldBe labels().noEnabled
    }

    private fun labels() = ContentProviderLabels(
        settings = "Manage Providers",
        choose = "Choose a Provider",
        searching = { count -> "Searching $count Providers" },
        failed = { count -> "$count Providers unavailable" },
        noEnabled = "Enable a Provider",
        confirmationRequired = "Choose a Provider to confirm the edition",
        timedOut = "Choose a Provider to continue searching",
        notFound = "Choose another Provider",
        bindingTitle = "Link a reading Provider",
        bindingEmpty = "No enabled Providers",
        bindingChoice = "Choose an installed Provider",
        error = "Could not load reading options",
    )
}
