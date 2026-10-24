package eu.kanade.presentation.tsuzuki.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalEditorialFactsTest {

    @Test
    fun `editorial genres retain every real genre instead of the three-item hero excerpt`() {
        editorialGenres(listOf("Action", "Adventure", "Comedy", "Fantasy", "Drama")) shouldBe
            listOf("Action", "Adventure", "Comedy", "Fantasy", "Drama")
    }

    @Test
    fun `editorial genres omit empty or duplicate genres without mutating input order`() {
        editorialGenres(listOf("Action", " ", "Drama", "Action", "", "Fantasy")) shouldBe
            listOf("Action", "Drama", "Fantasy")
        editorialGenres(emptyList()) shouldBe emptyList()
    }
}
