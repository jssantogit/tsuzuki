package tachiyomi.core.provider.runtime

/**
 * Future-only strict JSON grammar boundary for the dedicated MangaALL snapshot
 * Host service. JsonElement's permissive primitive reader is insufficient:
 * unquoted tokens such as "not-json" may parse despite isLenient=false.
 *
 * This lexically validates bounded JSON before the typed Host page is exposed
 * to QuickJs. It is not a generic parser, network decoder or code10 change.
 */
internal object MangaAllFutureStrictJson {
    fun accepts(raw: String): Boolean = Grammar(raw).accepts()

    private class Grammar(private val source: String) {
        private var pos = 0

        fun accepts(): Boolean {
            skipSpace()
            if (!value(0)) return false
            skipSpace()
            return pos == source.length
        }

        private fun value(depth: Int): Boolean {
            if (depth > 32) return false
            skipSpace()
            if (pos >= source.length) return false
            return when (source[pos]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> numberValue()
                else -> false
            }
        }

        private fun objectValue(depth: Int): Boolean {
            pos++
            skipSpace()
            if (consume('}')) return true
            while (true) {
                if (!stringValue()) return false
                skipSpace()
                if (!consume(':') || !value(depth)) return false
                skipSpace()
                if (consume('}')) return true
                if (!consume(',')) return false
                skipSpace()
            }
        }

        private fun arrayValue(depth: Int): Boolean {
            pos++
            skipSpace()
            if (consume(']')) return true
            while (true) {
                if (!value(depth)) return false
                skipSpace()
                if (consume(']')) return true
                if (!consume(',')) return false
                skipSpace()
            }
        }

        private fun stringValue(): Boolean {
            if (!consume('"')) return false
            while (pos < source.length) {
                when (val char = source[pos++]) {
                    '"' -> return true
                    '\\' -> {
                        if (pos >= source.length) return false
                        val escaped = source[pos++]
                        if (escaped == 'u') {
                            repeat(4) {
                                if (pos >= source.length || !source[pos++].isHexDigit()) return false
                            }
                        } else if (escaped !in "\"\\/bfnrt") {
                            return false
                        }
                    }
                    else -> if (char.code < 32) return false
                }
            }
            return false
        }

        private fun numberValue(): Boolean {
            consume('-')
            if (consume('0')) {
                if (pos < source.length && source[pos] in '0'..'9') return false
            } else {
                if (pos >= source.length || source[pos] !in '1'..'9') return false
                pos++
                digits()
            }
            if (consume('.')) {
                if (!digits()) return false
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-')
                if (!digits()) return false
            }
            return true
        }

        private fun digits(): Boolean {
            val start = pos
            while (pos < source.length && source[pos] in '0'..'9') pos++
            return pos > start
        }

        private fun literal(text: String): Boolean {
            if (!source.startsWith(text, pos)) return false
            pos += text.length
            return true
        }

        private fun consume(char: Char): Boolean {
            if (pos >= source.length || source[pos] != char) return false
            pos++
            return true
        }

        private fun skipSpace() {
            while (pos < source.length && source[pos] in " \n\r\t") pos++
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
