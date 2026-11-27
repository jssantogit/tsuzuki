package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId

class MangaAllNativeChapterCoverageTest {
    @Test
    fun `native coverage projection applies identical guards to ten registered adapters`() {
        for (size in listOf(3, 5, 10)) {
            val contracts = (1..size).map { index ->
                NativeSourceCoverageContract(
                    sourceId = "source-$index",
                    versions = setOf(11L),
                    languages = setOf("en"),
                    workKey = Regex("[0-9]+"),
                    chapterKey = Regex("release:[0-9]+"),
                    workUrl = { id, url -> url == "https://source-$index.example/work/$id" },
                )
            }
            val policy = NativeChapterCoveragePolicy(contracts)
            for (index in 1..size) {
                val source = "source-$index"
                val binding = ProviderBindingRef(ProviderId("app.tsuzuki.mangaall"), "en", "$source:42")
                val candidate = ProviderWorkCandidate(
                    externalWorkId = binding.externalWorkId,
                    title = "Work",
                    url = "https://$source.example/work/42",
                    authors = listOf("Writer"),
                    detailOutcome = ProviderWorkDetailOutcome.VERIFIED_NO_ALIASES,
                )
                val row = ProviderChapterObservation(
                    providerChapterId = "$source:release:7",
                    rawLabel = "Ch. 1",
                    rawNumber = 1.0,
                    language = "en",
                )
                val page = ProviderPage(listOf(row), null)
                policy.project(11L, binding, candidate, page)?.coverageObservedCount shouldBe 1
                policy.project(10L, binding, candidate, page) shouldBe null
                policy.project(11L, binding, candidate.copy(url = "https://other.example/work/42"), page) shouldBe null
                policy.project(11L, binding, candidate.copy(authors = emptyList()), page) shouldBe null
                policy.project(11L, binding, candidate, ProviderPage(listOf(row, row), null)) shouldBe null
                policy.project(
                    11L,
                    binding,
                    candidate,
                    ProviderPage(listOf(row.copy(providerChapterId = "source-${index % size + 1}:release:7")), null),
                ) shouldBe null
                policy.project(11L, binding, candidate, ProviderPage(listOf(row.copy(language = null)), null)) shouldBe
                    null
            }
        }
    }
}
