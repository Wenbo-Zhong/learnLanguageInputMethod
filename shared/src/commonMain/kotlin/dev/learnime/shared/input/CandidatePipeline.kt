package dev.learnime.shared

data class CandidateResult(val candidates: List<PinyinCandidate>, val error: String = "")

/** Native adapters do IO off-thread and deliver every completion on the editor thread. */
interface CandidateBackend {
    fun pinyin(request: CandidateRequest, completion: (CandidateResult) -> Unit)
    fun predict(context: String, completion: (List<String>) -> Unit)
    fun english(input: String, nine: Boolean, completion: (List<String>) -> Unit)
    fun cancel()
}

/** Shared request lifecycle, result merging and readiness; owns no native jobs or editor objects. */
class CandidatePipeline(
    private val flow: KeyboardInputFlow, private val backend: CandidateBackend,
    private val changed: () -> Unit,
) {
    private val state get() = flow.state
    var loading: Boolean = false; private set
    var error: String = ""; private set
    private var pending: SupplementalRequest? = null
    private val waiters = mutableListOf<(Boolean) -> Unit>()

    fun invalidate() {
        state.coordinator.invalidate()
        backend.cancel()
        pending = null; loading = false; error = ""
        finishWaiters(null)
    }

    fun refresh(wordInput: Boolean, prediction: Boolean, mixed: Boolean, context: String, shortcut: String) {
        invalidate()
        val lookup = flow.beginCandidates(wordInput, prediction, mixed)
        val request = lookup.request
        if (lookup.kind == CandidateLookupKind.PREDICTION) {
            backend.predict(context) { words ->
                if (state.publishPredictions(request, words)) changed()
            }
        } else if (lookup.kind == CandidateLookupKind.PINYIN) {
            pending = request; loading = true
            changed()
            backend.pinyin(request.candidate) { result ->
                if (pending != request || !state.isCurrent(request)) return@pinyin
                pending = null; loading = false; error = result.error
                if (error.isEmpty()) flow.publishCandidates(lookup, result.candidates)
                changed()
                finishWaiters(request)
            }
            val prefix = if (lookup.shortcuts && shortcut.isNotEmpty()) listOf(shortcut) else emptyList()
            if (lookup.mixed && state.isCurrent(request)) {
                backend.english(request.candidate.input, request.candidate.mode == KeyboardMode.CHINESE_NINE_KEY) { words ->
                    val mixedWords = if (request.candidate.mode == KeyboardMode.CHINESE_NINE_KEY) words
                        else words.drop(1).take(3)
                    if (state.publishChoices(request, prefix + mixedWords)) changed()
                }
            } else if (state.publishChoices(request, prefix)) changed()
        }
        if (!loading) changed()
    }

    fun refreshEnglish(input: String) {
        invalidate()
        val request = state.snapshot()
        backend.english(input, false) { words ->
            if (state.publishChoices(request, words)) changed()
        }
    }

    fun whenReady(completion: (Boolean) -> Unit) {
        if (!loading) completion(true) else waiters += completion
    }
    private fun finishWaiters(request: SupplementalRequest?) {
        val ready = waiters.toList()
        waiters.clear()
        ready.forEach { it(request != null && state.isCurrent(request)) }
    }
}
