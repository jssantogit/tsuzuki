package eu.kanade.tachiyomi.ui.tsuzuki.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalTitleSourceBindingLaunchTest {

    @Test
    fun `requested binding flow opens once for the same canonical title`() {
        shouldAutoOpenSourceBindingFlow(
            canonicalTitleId = "canonical-title-1",
            requested = true,
            alreadyOpened = false,
        ) shouldBe true

        shouldAutoOpenSourceBindingFlow(
            canonicalTitleId = "canonical-title-1",
            requested = true,
            alreadyOpened = true,
        ) shouldBe false
    }

    @Test
    fun `explicit Reader edition recovery opens picker only after title is loaded and only once`() {
        shouldAutoOpenProviderEditionPicker("title-1", true, false, true) shouldBe true
        shouldAutoOpenProviderEditionPicker("title-1", true, false, false) shouldBe false
        shouldAutoOpenProviderEditionPicker("title-1", true, true, true) shouldBe false
        shouldAutoOpenProviderEditionPicker("title-1", false, false, true) shouldBe false
        shouldAutoOpenProviderEditionPicker("  ", true, false, true) shouldBe false
    }

    @Test
    fun `binding flow does not open when request or canonical title is invalid`() {
        shouldAutoOpenSourceBindingFlow(
            canonicalTitleId = "canonical-title-1",
            requested = false,
            alreadyOpened = false,
        ) shouldBe false

        shouldAutoOpenSourceBindingFlow(
            canonicalTitleId = " \t ",
            requested = true,
            alreadyOpened = false,
        ) shouldBe false
    }
}
