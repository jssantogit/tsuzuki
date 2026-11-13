package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId

class InspectProviderEditionChaptersTest {

    private val ref = ProviderBindingRef(
        providerId = ProviderId("app.tsuzuki.mangaall"),
        facetId = "pt-br",
        externalWorkId = "mangadex:example",
    )

    @Test
    fun `counts complete chapter inventory through bounded continuation without inventing canon`() = runBlocking {
        val requestedCursors = mutableListOf<String?>()
        val inspector = InspectProviderEditionChapters { cursor ->
            requestedCursors += cursor?.value
            ProviderCallResult.Success(
                when (cursor?.value) {
                    null -> ProviderPage(
                        items = listOf(
                            chapter("mangadex:ch-1", 1.0),
                            chapter("mangadex:ch-2", 2.0),
                        ),
                        nextCursor = ProviderCursor("2"),
                    )
                    "2" -> ProviderPage(
                        items = listOf(
                            chapter("mangadex:ch-2", 2.0),
                            chapter("mangadex:ch-3", 3.5),
                        ),
                        nextCursor = null,
                    )
                    else -> error("Unexpected cursor")
                },
            )
        }
        val inspection = inspector.execute()

        inspection.observedRecords shouldBe 3
        inspection.complete shouldBe true
        inspection.failed shouldBe false
        inspection.minChapterMilli shouldBe 1000L
        inspection.maxChapterMilli shouldBe 3500L
        requestedCursors shouldBe listOf(null, "2")
    }

    @Test
    fun `failed continuation remains partial and cannot masquerade as exact count`() = runBlocking {
        val inspection = InspectProviderEditionChapters { cursor ->
            if (cursor == null) {
                ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(chapter("mangadex:ch-1", 1.0)),
                        nextCursor = ProviderCursor("more"),
                    ),
                )
            } else {
                ProviderCallResult.Failure(
                    ProviderError(ProviderErrorCode.SOURCE_READ_ERROR, retryable = false),
                )
            }
        }.execute()
        inspection.observedRecords shouldBe 1
        inspection.complete shouldBe false
        inspection.failed shouldBe true
        inspection.minChapterMilli shouldBe 1000L
    }

    @Test
    fun `empty verified page is distinguishable from no response`() = runBlocking {
        val inspection = InspectProviderEditionChapters {
            ProviderCallResult.Success(ProviderPage(emptyList(), nextCursor = null))
        }.execute()
        inspection.observedRecords shouldBe 0
        inspection.complete shouldBe true
        inspection.failed shouldBe false
    }

    private fun chapter(id: String, number: Double) = ProviderChapterObservation(
        providerChapterId = id,
        rawLabel = "Chapter $number",
        rawNumber = number,
        language = "pt-br",
    )
}
