package dev.learnime.shared

enum class HandwritingPhase { EMPTY, DRAWING, RECOGNIZING, READY, MARKED }

/** Recognition results and editor marking have separate lifetimes. No timers or stroke buffers live here. */
class HandwritingSession {
    var revision: Long = 0; private set
    var phase: HandwritingPhase = HandwritingPhase.EMPTY; private set
    var candidate: String = ""; private set
    var marked: String = ""; private set
    val hasText: Boolean get() = candidate.isNotEmpty() || marked.isNotEmpty()

    /** A new stroke confirms only a word already marked after the user's pause. */
    fun beginStroke(): String {
        val previous = marked
        clear()
        phase = HandwritingPhase.DRAWING
        return previous
    }

    fun beginRecognition(hasStrokes: Boolean): Long {
        clear()
        phase = if (hasStrokes) HandwritingPhase.RECOGNIZING else HandwritingPhase.EMPTY
        return revision
    }

    fun isCurrent(request: Long): Boolean = request == revision

    fun accept(request: Long, text: String): Boolean {
        if (!isCurrent(request)) return false
        candidate = text
        phase = if (text.isEmpty()) HandwritingPhase.DRAWING else HandwritingPhase.READY
        return true
    }

    fun mark(request: Long): Boolean {
        if (!isCurrent(request) || candidate.isEmpty()) return false
        marked = candidate
        phase = HandwritingPhase.MARKED
        return true
    }

    fun finish(): String {
        val text = candidate.ifEmpty { marked }
        clear()
        return text
    }

    /** Rebuilding a panel cancels callbacks without discarding an already recognized word. */
    fun invalidateRequests() { revision++ }

    fun clear() {
        revision++
        candidate = ""; marked = ""; phase = HandwritingPhase.EMPTY
    }
}
