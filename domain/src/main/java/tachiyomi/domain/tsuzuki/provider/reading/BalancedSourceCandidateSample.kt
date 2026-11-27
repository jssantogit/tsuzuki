package tachiyomi.domain.tsuzuki.provider.reading

/**
 * Bounded source-balanced diagnostic selection, independent of the number
 * or name of sources. Each registered source receives a slot before any
 * receives a second. Input order is retained within each source; the
 * returned records keep the original global index and display order.
 *
 * The caller supplies the trusted namespace extractor. Unknown sources are
 * excluded, and the result is for diagnostics only, not selection authority.
 */
internal fun <T> balancedSourceCandidateSample(
    candidates: List<T>,
    maxTotal: Int,
    maxPerSource: Int,
    sourceId: (T) -> String?,
): List<IndexedValue<T>> {
    require(maxTotal >= 0 && maxPerSource >= 0)
    if (maxTotal == 0 || maxPerSource == 0) return emptyList()

    val groups = linkedMapOf<String, MutableList<IndexedValue<T>>>()
    candidates.forEachIndexed { index, candidate ->
        val source = sourceId(candidate) ?: return@forEachIndexed
        val group = groups.getOrPut(source) { mutableListOf() }
        if (group.size < maxPerSource) group += IndexedValue(index, candidate)
    }
    val selected = mutableListOf<IndexedValue<T>>()
    val maxDepth = groups.values.maxOfOrNull { it.size } ?: 0
    for (depth in 0 until maxDepth) {
        for (group in groups.values) {
            val candidate = group.getOrNull(depth) ?: continue
            selected += candidate
            if (selected.size == maxTotal) return selected.sortedBy { it.index }
        }
    }
    return selected.sortedBy { it.index }
}
