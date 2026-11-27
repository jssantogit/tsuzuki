package tachiyomi.domain.tsuzuki.provider.reading

import java.text.Normalizer

/**
 * Bounded, whole-name spelling comparison anchored in independently published
 * Hangul. No edit distance, initials, surname-only or Latin-to-Latin inference.
 * These are orthographic alternatives, not proof of an edition or chapter.
 */
internal object HangulCreatorRomanization {

    fun matchesUnique(verifiedNames: Set<String>, source: String): Boolean {
        val latin = source.trim().lowercase()
        if (latin.length !in 2..64 || !LATIN.matches(latin)) return false
        val words = latin.split(WORD_SEPARATOR).map { it.replace(HYPHENS, "") }
        val compact = words.joinToString("")
        val matches = verifiedNames.asSequence()
            .map { Normalizer.normalize(it, Normalizer.Form.NFC).trim() }
            .filter(NATIVE::matches)
            .map { it.split(SPACE) }
            .filter { parts -> parts.sumOf { it.length } in 2..4 }
            .filter { parts -> matchesCredit(parts, words, compact) }
            .map { it.joinToString("") }
            .distinct()
            .take(2)
            .count()
        return matches == 1
    }

    private fun matchesCredit(parts: List<String>, words: List<String>, compact: String): Boolean {
        if (matchesSequence(parts.joinToString(""), compact)) return true
        // Only an explicitly separated native surname permits moving it to
        // the end, and the source must explicitly separate both name groups.
        if (parts.size != 2 || parts[0].length !in 1..2 || words.size < 2) return false
        return (1 until words.size).any { boundary ->
            matchesSequence(parts[1], words.take(boundary).joinToString("")) &&
                matchesSequence(parts[0], words.drop(boundary).joinToString(""))
        }
    }

    private fun matchesSequence(native: String, latin: String): Boolean {
        var offsets = setOf(0)
        for (syllable in native) {
            val index = syllable - '가'
            val initial = index / 588
            val vowel = index / 28 % 21
            val final = index % 28
            val next = mutableSetOf<Int>()
            for (offset in offsets) {
                for (onset in INITIALS[initial].split('/')) {
                    for (medial in VOWELS[vowel].split('/')) {
                        for (coda in FINALS[final].split('/')) {
                            val spelling = onset + medial + coda
                            if (latin.startsWith(spelling, offset)) next.add(offset + spelling.length)
                        }
                    }
                }
            }
            if (next.isEmpty()) return false
            offsets = next
        }
        return latin.length in offsets
    }

    private val NATIVE = Regex("""[가-힣]+(?:\s+[가-힣]+)?""")
    private val LATIN = Regex("""[a-z]+(?:[-‐‑ ,]+[a-z]+)*""")
    private val SPACE = Regex("""\s+""")
    private val WORD_SEPARATOR = Regex("[ ,]+")
    private val HYPHENS = Regex("[-‐‑]")

    // Hangul syllable decomposition order (19 initials, 21 vowels, 28 finals).
    // Alternatives are bounded common spelling variants of individual
    // graphemes, not arbitrary sound substitutions or a creator dictionary.
    private val INITIALS = listOf(
        "g/k", "kk", "n", "d/t", "tt", "r/l", "m", "b/p", "pp", "s", "ss", "",
        "j/ch", "jj", "ch", "k", "t", "p", "h",
    )
    private val VOWELS = listOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe",
        "yo", "u/oo/woo", "wo", "we", "wi", "yu/you/u", "eu", "ui", "i/ee",
    )
    private val FINALS = listOf(
        "", "k/g", "kk", "ks", "n", "nj", "nh", "t/d", "l/r", "lk", "lm", "lb",
        "ls", "lt", "lp", "lh", "m", "p/b", "ps", "s/t", "ss/t", "ng", "j/t",
        "ch/t", "k", "t", "p", "h/t",
    )
}
