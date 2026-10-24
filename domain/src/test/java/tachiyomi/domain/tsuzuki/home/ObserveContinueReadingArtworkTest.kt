package tachiyomi.domain.tsuzuki.home

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.home.interactor.ObserveContinueReadingArtwork
import tachiyomi.domain.tsuzuki.home.interactor.ObserveHomeContinueReading
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.source.interactor.ResolveCanonicalSourceManga

class ObserveContinueReadingArtworkTest {
    @Test
    fun `empty reading stays empty without source resolution`() = runTest {
        val source = mockk<ObserveHomeContinueReading> {
            every { subscribe(5) } returns MutableStateFlow(emptyList())
        }
        val artwork = mockk<TitleArtworkRepository> {
            every { observeAll() } returns MutableStateFlow(emptyList())
        }
        val resolver = mockk<ResolveCanonicalSourceManga>()
        ObserveContinueReadingArtwork(source, artwork, resolver).subscribe(5).first() shouldBe emptyList()
        coVerify(exactly = 0) { resolver.execute(any(), any()) }
    }

    @Test
    fun `reading preserves order checkpoints and canonical artwork when local source is unavailable`() = runTest {
        val items = (5 downTo 1).map {
            HomeContinueReadingItem("t$it", "Title $it", "c$it", "$it", it.toLong(), it.toLong())
        }
        val source = mockk<ObserveHomeContinueReading> {
            every { subscribe(5) } returns MutableStateFlow(items)
        }
        val artwork = mockk<TitleArtworkRepository> {
            every { observeAll() } returns MutableStateFlow(
                listOf(TitleArtworkObservation("t5", "kitsu", "https://example.org/cover.jpg", updatedAt = 1L)),
            )
        }
        val resolver = mockk<ResolveCanonicalSourceManga> {
            coEvery { execute(any(), false) } throws IllegalStateException("Missing local mapping")
        }
        val result = ObserveContinueReadingArtwork(source, artwork, resolver).subscribe(5).first()
        result.map { it.canonicalTitleId } shouldBe items.map { it.canonicalTitleId }
        result.map { it.lastPageRead } shouldBe items.map { it.lastPageRead }
        result.first().coverUrl shouldBe "https://example.org/cover.jpg"
        result.all { it.sourceCover == null } shouldBe true
        coVerify(exactly = 5) { resolver.execute(any(), false) }
    }
}
