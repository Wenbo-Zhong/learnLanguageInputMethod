package dev.learnime.shared

data class InputPreedit(
    val text: String, val cursorText: String, val offsets: List<Int>, val lockedEnd: Int,
)
data class InputSyllableChoice(val syllable: PinyinSyllable, val typed: String)

/** Maps displayed spelling back to raw encoding; fonts, underlining and touch APIs stay native. */
internal object InputPreeditBuilder {
    fun build(session: KeyboardSession, english: String): InputPreedit {
        if (english.isNotEmpty()) return InputPreedit(english, "$english│", emptyList(), 0)
        val raw = session.composition
        val offsets = mutableListOf(0)
        val display = StringBuilder()
        var rawEnd = 0
        if (session.mode == KeyboardMode.CHINESE_NINE_KEY) {
            session.candidates.firstOrNull()?.syllables.orEmpty().forEach { syllable ->
                if (syllable.end <= rawEnd || syllable.end > raw.length || syllable.spelling.isEmpty()) return@forEach
                if (display.isNotEmpty()) { display.append(' '); offsets += rawEnd }
                syllable.spelling.forEachIndexed { index, c ->
                    display.append(c)
                    offsets += rawEnd + ((syllable.end - rawEnd) * (index + 1) / syllable.spelling.length)
                }
                rawEnd = syllable.end
            }
        }
        raw.drop(rawEnd).forEach { display.append(it); offsets += ++rawEnd }
        val displayCursor = offsets.indexOfLast { it <= session.cursor }.coerceAtLeast(0)
        offsets.add(displayCursor + 1, session.cursor)
        val lockedEnd = session.lockedSyllables.lastOrNull()?.let { locked ->
            offsets.indexOfLast { it <= locked.end }.coerceAtLeast(0)
        } ?: 0
        val text = when {
            raw.isEmpty() -> ""
            session.mode == KeyboardMode.CHINESE_NINE_KEY -> session.candidates.firstOrNull()?.spelling ?: raw
            else -> raw.substring(0, session.cursor) + "│" + raw.substring(session.cursor)
        }
        return InputPreedit(text, display.substring(0, displayCursor) + "│" + display.substring(displayCursor),
            offsets, lockedEnd)
    }
    fun choices(session: KeyboardSession): List<InputSyllableChoice> {
        val begin = session.lockedSyllables.lastOrNull()?.end ?: 0
        return session.nextSyllables.mapNotNull { syllable ->
            if (begin !in 0..session.composition.length || syllable.end !in begin..session.composition.length) null
            else InputSyllableChoice(syllable, session.composition.substring(begin, syllable.end))
        }
    }
}
