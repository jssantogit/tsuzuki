package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BalancedSourceCandidateSampleTest {
    private data class Candidate(val source: String?, val id: Int)

    private fun sample(candidates: List<Candidate>, total: Int = 20, perSource: Int = 6) =
        balancedSourceCandidateSample(candidates, total, perSource, Candidate::source)

    @Test
    fun `blocked ordering cannot hide registered sources within the global budget`() {
        for (size in listOf(3, 5, 10)) {
            val sources = (1..size).map { "source-$it" }
            for (order in listOf(sources, sources.reversed())) {
                val candidates = order.flatMap { source -> List(30) { Candidate(source, it) } }
                val result = sample(candidates)
                val counts = result.groupingBy { it.value.source }.eachCount()
                counts.keys shouldBe sources.toSet()
                result.size shouldBe minOf(20, size * 6)
                counts.values.all { it in 1..6 } shouldBe true
                (counts.values.max() - counts.values.min() <= 1) shouldBe true
                result.map { it.index } shouldBe result.map { it.index }.sorted()
                result.all { candidates[it.index] == it.value } shouldBe true
                counts.forEach { (source, count) ->
                    result.filter { it.value.source == source }.map { it.value.id } shouldBe (0 until count).toList()
                }
            }
        }
    }

    @Test
    fun `sparse sources and unknown namespaces never consume another source allocation`() {
        val candidates = listOf(Candidate(null, 0), Candidate("a", 0)) +
            List(20) { Candidate("b", it) } + listOf(Candidate("c", 0), Candidate(null, 1))
        val result = sample(candidates, total = 8, perSource = 6)
        result.groupingBy { it.value.source }.eachCount() shouldBe mapOf("a" to 1, "b" to 6, "c" to 1)
        sample(candidates, total = 2).map { it.value.source } shouldBe listOf("a", "b")
        sample(candidates, total = 0) shouldBe emptyList()
        sample(candidates, perSource = 0) shouldBe emptyList()
        sample(emptyList()) shouldBe emptyList()
        shouldThrow<IllegalArgumentException> { sample(candidates, total = -1) }
    }
}
