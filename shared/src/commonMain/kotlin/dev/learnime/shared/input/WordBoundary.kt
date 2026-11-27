package dev.learnime.shared

/** Supplementary Unicode classification uses the host's Unicode tables, not its regex engine. */
internal expect fun supplementaryWordCharacter(codePoint: Int): Boolean

internal object WordBoundary {
    private data class Unit(val start: Int, var end: Int, var word: Boolean, val space: Boolean,
                            var regionalCount: Int = 0, var joinNext: Boolean = false)
    private val marks = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)
    private fun word(codePoint: Int): Boolean = if (codePoint > 0xffff) supplementaryWordCharacter(codePoint) else {
        val char = codePoint.toChar()
        char.isLetterOrDigit() || char.category in marks ||
            char.category == CharCategory.LETTER_NUMBER || char.category == CharCategory.OTHER_NUMBER
    }
    private fun extender(codePoint: Int): Boolean =
        (codePoint <= 0xffff && codePoint.toChar().category in marks) ||
            codePoint in 0x1f3fb..0x1f3ff || codePoint in 0xe0100..0xe01ef || codePoint in 0xe0020..0xe007f

    fun tail(text: String): String {
        if (text.isEmpty()) return ""
        val units = mutableListOf<Unit>()
        var index = 0
        while (index < text.length) {
            val start = index
            val first = text[index++]
            val codePoint = if (first.isHighSurrogate() && index < text.length && text[index].isLowSurrogate())
                0x10000 + ((first.code - 0xd800) shl 10) + text[index++].code - 0xdc00 else first.code
            val previous = units.lastOrNull()
            val regional = codePoint in 0x1f1e6..0x1f1ff
            when {
                previous != null && extender(codePoint) -> {
                    previous.end = index
                    if (codePoint == 0x20e3) previous.word = false // A keycap is an emoji cluster.
                }
                previous != null && codePoint == 0x200d -> {
                    previous.end = index; previous.joinNext = true
                }
                previous?.joinNext == true -> {
                    previous.end = index; previous.joinNext = false
                }
                regional && previous?.regionalCount == 1 -> {
                    previous.end = index; previous.regionalCount = 2
                }
                else -> units += Unit(start, index, word(codePoint),
                    codePoint <= 0xffff && codePoint.toChar().isWhitespace(), if (regional) 1 else 0)
            }
        }
        var last = units.lastIndex
        while (last >= 0 && units[last].space) last--
        if (last < 0) return text
        if (units[last].word) {
            var begin = last
            while (begin > 0 && units[begin - 1].word) begin--
            return text.substring(units[begin].start)
        }
        return if (last < units.lastIndex) text.substring(units[last + 1].start) else text.substring(units[last].start)
    }
}
