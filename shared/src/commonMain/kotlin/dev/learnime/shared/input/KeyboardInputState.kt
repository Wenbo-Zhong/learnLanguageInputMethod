package dev.learnime.shared

data class SupplementalRequest(val candidate: CandidateRequest, val english: String, val revision: Long)

/** Owns English preedit and supplemental candidate identity; adapters schedule native work. */
class KeyboardInputState {
    val session = KeyboardSession()
    val coordinator = CandidateCoordinator()
    private var revision = 0L
    var englishWord: String = ""
        set(value) {
            if (field == value) return
            field = value
            revision++
            englishChoices = emptyList()
            predictions = emptyList()
        }
    var englishChoices: List<String> = emptyList()
    var predictions: List<String> = emptyList()

    fun appendEnglish(value: String) {
        englishWord += if (session.uppercase) value.uppercase() else value
    }
    fun backspaceEnglish(): Boolean {
        if (englishWord.isEmpty()) return false
        englishWord = englishWord.dropLast(1)
        return true
    }
    fun snapshot(): SupplementalRequest = SupplementalRequest(coordinator.snapshot(session), englishWord, revision)
    fun isCurrent(request: SupplementalRequest): Boolean =
        request.revision == revision && request.english == englishWord && coordinator.isCurrent(request.candidate, session)
    fun publishChoices(request: SupplementalRequest, words: List<String>): Boolean {
        if (!isCurrent(request)) return false
        englishChoices = words.distinct()
        return true
    }
    fun publishPredictions(request: SupplementalRequest, words: List<String>): Boolean {
        if (!isCurrent(request) || englishWord.isNotEmpty() || session.composition.isNotEmpty()) return false
        predictions = words.distinct()
        return true
    }
    fun canChoose(request: SupplementalRequest, word: String, prediction: Boolean): Boolean =
        isCurrent(request) && word in (if (prediction) predictions else englishChoices) &&
            (!prediction || (englishWord.isEmpty() && session.composition.isEmpty()))
}
