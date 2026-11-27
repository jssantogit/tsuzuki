package tachiyomi.domain.tsuzuki.identity

import java.text.Normalizer

private val combiningMarks = Regex("""\p{M}+""")

/** Compatibility normalization with diacritic folding restricted to Latin script. */
internal fun foldLatinIdentityDiacritics(value: String): String {
    val compatible = Normalizer.normalize(value, Normalizer.Form.NFKC)
    return buildString {
        for (char in compatible) {
            if (Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN) {
                append(Normalizer.normalize(char.toString(), Normalizer.Form.NFD).replace(combiningMarks, ""))
            } else {
                append(char)
            }
        }
    }
}
