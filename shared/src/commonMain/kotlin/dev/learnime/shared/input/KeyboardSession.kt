package dev.learnime.shared

enum class KeyboardMode { CHINESE_QWERTY, CHINESE_NINE_KEY, ENGLISH }

data class PinyinSyllable(val spelling: String, val end: Int, val partial: Boolean = false)
data class PinyinCandidate(val text: String, val spelling: String, val consumed: Int,
    val token: Long = 0, val nativeIndex: Int = -1, val path: String = "",
    val syllables: List<PinyinSyllable> = emptyList(), val correction: String = "",
    val alternatives: List<PinyinSyllable> = emptyList())

object PinyinResults {
    fun parse(serialized: String): List<PinyinCandidate> {
        val alternatives = serialized.lineSequence().firstOrNull { it.startsWith("@syllables\t") }
            ?.substringAfter('\t')?.split(',')?.mapNotNull {
                val pair = it.split(':')
                val end = pair.getOrNull(1)?.trimEnd('*')?.toIntOrNull() ?: return@mapNotNull null
                if (pair.size != 2 || pair[0].isEmpty() || pair[0].any { c -> c !in 'a'..'z' } || end <= 0) return@mapNotNull null
                PinyinSyllable(pair[0], end, pair[1].endsWith('*'))
            }.orEmpty()
        return serialized.lineSequence().mapNotNull { line ->
        val fields = line.split('\t')
        val consumed = fields.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
        if (fields.size !in listOf(3, 6, 8) || fields[0].isBlank() || consumed <= 0) return@mapNotNull null
        val syllables = fields.getOrNull(6)?.split(',')?.mapNotNull {
            val pair = it.split(':')
            val end = pair.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (pair.size != 2 || pair[0].isEmpty() || pair[0].any { c -> c !in 'a'..'z' }) return@mapNotNull null
            PinyinSyllable(pair[0], end)
        }.orEmpty()
        if (fields.size == 8 && (syllables.isEmpty() || syllables.last().end != consumed ||
            syllables.zipWithNext().any { (a, b) -> a.end >= b.end } || syllables.first().end <= 0)) return@mapNotNull null
        PinyinCandidate(fields[0], fields[1], consumed, fields.getOrNull(3)?.toLongOrNull() ?: 0,
            fields.getOrNull(4)?.toIntOrNull() ?: -1, fields.getOrNull(5).orEmpty(), syllables,
            fields.getOrNull(7)?.takeUnless { it == "-" }.orEmpty(), alternatives)
    }.toList()
    }
}

/** Only returned text is committed. Adapters may show temporary editor preedit, excluded from translation. */
class KeyboardSession {
    var mode: KeyboardMode = KeyboardMode.CHINESE_QWERTY; private set
    var composition: String = ""; private set
    var candidates: List<PinyinCandidate> = emptyList(); private set
    var uppercase: Boolean = false; private set
    var symbols: Boolean = false; private set
    var cursor: Int = 0; private set
    var revision: Long = 0; private set
    var lockedSyllables: List<PinyinSyllable> = emptyList(); private set
    var autoCorrection: Boolean = true; private set
    val lockSpecification: String get() = lockedSyllables.joinToString(",") { "${it.spelling}:${it.end}${if (it.partial) "*" else ""}" }
    val nextSyllables: List<PinyinSyllable> get() {
        val full = lockedSyllables.takeWhile { !it.partial }
        val prefix = lockedSyllables.lastOrNull()?.takeIf { it.partial }
        val options = candidates.firstOrNull()?.alternatives.orEmpty()
        return (if (mode == KeyboardMode.CHINESE_NINE_KEY && options.isNotEmpty()) options else candidates.filter {
            it.syllables.take(full.size) == full
        }.mapNotNull { it.syllables.getOrNull(full.size) }.distinct()).filter {
            prefix == null || it.spelling.startsWith(prefix.spelling)
        }
    }
    fun lockNext(syllable: PinyinSyllable): Boolean {
        val full = lockedSyllables.takeWhile { !it.partial }
        if (syllable !in nextSyllables || syllable.end <= (full.lastOrNull()?.end ?: 0)) return false
        lockedSyllables = full + syllable; edited(); return true
    }
    fun unlockFrom(index: Int) {
        if (index !in lockedSyllables.indices) return
        lockedSyllables = lockedSyllables.take(index); edited()
    }
    fun setAutoCorrection(enabled: Boolean) {
        if (autoCorrection == enabled) return
        autoCorrection = enabled; lockedSyllables = emptyList(); edited()
    }
    private fun invalidateLocksAt(position: Int) {
        lockedSyllables = lockedSyllables.takeWhile { it.end <= position }
    }
    val needsSegmentation: Boolean get() = composition.length > 40
    private fun edited() { revision++; candidates = emptyList() }

    fun changeMode(value: KeyboardMode) {
        clearComposition(); mode = value; symbols = false; uppercase = false
    }
    fun clearComposition() { composition = ""; cursor = 0; lockedSyllables = emptyList(); edited() }
    fun clearCandidates() { candidates = emptyList() }
    fun reset() { clearComposition(); symbols = false; uppercase = false }
    fun toggleShift() { uppercase = !uppercase }
    fun toggleSymbols() { symbols = !symbols }
    fun key(value: String): String {
        if (symbols) return literal(value)
        if (mode == KeyboardMode.ENGLISH) return if (uppercase) value.uppercase() else value
        val valid = value.length == 1 && (value[0] == '\'' ||
            if (mode == KeyboardMode.CHINESE_NINE_KEY) value[0] in '2'..'9' else value[0] in 'a'..'z')
        if (valid && !(value == "'" && (cursor == 0 || composition.getOrNull(cursor - 1) == '\'' || composition.getOrNull(cursor) == '\''))) {
            invalidateLocksAt(cursor)
            composition = composition.substring(0, cursor) + value + composition.substring(cursor)
            cursor++; edited()
        }
        return ""
    }
    fun applyCandidates(input: String, inputMode: KeyboardMode, result: List<PinyinCandidate>): Boolean =
        applyCandidates(input, inputMode, result, revision)
    fun applyCandidates(input: String, inputMode: KeyboardMode, result: List<PinyinCandidate>, inputRevision: Long): Boolean {
        if (input != composition || inputMode != mode || inputRevision != revision) return false
        candidates = result.filter {
            val count = minOf(it.syllables.size, lockedSyllables.size)
            it.consumed in 1..composition.length &&
                (lockedSyllables.isEmpty() || (count > 0 && (0 until count).all { index ->
                    val lock = lockedSyllables[index]; val actual = it.syllables[index]
                    if (lock.partial) actual.spelling.startsWith(lock.spelling) && actual.end >= lock.end
                    else actual == lock
                }))
        }
        return true
    }
    fun choose(index: Int): String {
        val candidate = candidates.getOrNull(index) ?: return ""
        val oldLength = composition.length
        composition = composition.drop(candidate.consumed).trimStart('\'')
        val removed = oldLength - composition.length
        lockedSyllables = lockedSyllables.filter { it.end > removed }.map { it.copy(end = it.end - removed) }
        cursor = (cursor - removed).coerceIn(0, composition.length)
        edited()
        return candidate.text
    }
    fun backspace(): Boolean {
        if (composition.isEmpty()) return false
        if (cursor == 0) return true
        invalidateLocksAt(cursor - 1)
        composition = composition.removeRange(cursor - 1, cursor); cursor--; edited(); return true
    }
    fun moveCursor(position: Int) { cursor = position.coerceIn(0, composition.length) }
    fun space(): String = when {
        composition.isEmpty() -> " "
        candidates.isNotEmpty() -> choose(0)
        else -> ""
    }
    fun enter(): String = if (composition.isEmpty()) "\n" else space()
    fun takeRaw(): String { val value = composition; clearComposition(); return value }
    fun literal(value: String): String {
        if (composition.isEmpty()) return value
        if (candidates.firstOrNull()?.consumed != composition.length || candidates.first().correction.isNotEmpty()) return ""
        return choose(0) + value
    }
}
