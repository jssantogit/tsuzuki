package eu.kanade.presentation.tsuzuki.collections

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FilterOptionGuidanceTest {

    private val providerOptions = listOf(
        "Ação" to "action",
        "Romance" to "romance",
        "Aventura" to "adventure",
        "Comédia romântica" to "romcom",
    )

    @Test
    fun `local static filter search ignores case and accents but preserves provider values`() {
        matchingFilterOptions(providerOptions, "AcAo") shouldContainExactly listOf("Ação" to "action")
        matchingFilterOptions(providerOptions, "roma") shouldContainExactly listOf(
            "Romance" to "romance",
            "Comédia romântica" to "romcom",
        )
    }

    @Test
    fun `blank query shows all real options and unknown query does not fabricate choices`() {
        matchingFilterOptions(providerOptions, " ") shouldContainExactly providerOptions
        matchingFilterOptions(providerOptions, "no-such-option") shouldBe emptyList()
    }

    @Test
    fun `preview is grounded in the current provider option labels`() {
        optionPreviewLabels(providerOptions, 3) shouldContainExactly listOf("Ação", "Romance", "Aventura")
        optionPreviewLabels(emptyList(), 3) shouldBe emptyList()
    }
}
