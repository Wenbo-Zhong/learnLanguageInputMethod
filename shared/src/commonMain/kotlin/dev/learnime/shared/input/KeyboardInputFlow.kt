package dev.learnime.shared

enum class InputEffectKind {
    COMMIT, CONVERT_COMMIT, DELETE_BACKWARD, HOST_ENTER, SYNC_MARKED,
    SEARCH_PINYIN, SEARCH_ENGLISH, INVALIDATE, RENDER, RENDER_KEYS, CAPITALIZE, PRESERVE, OPEN_TOOL,
    MOVE_CURSOR, DELETE_TEXT
}
data class InputEffect(val kind: InputEffectKind, val text: String = "", val amount: Int = 0)
data class InputSelection(
    val request: CandidateRequest, val candidate: PinyinCandidate, val index: Int,
    val suffix: String = "", val modeAfter: KeyboardMode? = null, val toolAfter: String? = null,
)
data class InputTransition(
    val effects: List<InputEffect> = emptyList(), val selection: InputSelection? = null,
)
data class InputOptions(
    val wordInput: Boolean = true, val englishSuggestions: Boolean = true,
    val englishCorrection: Boolean = false, val doubleSpace: Boolean = false,
    val autoCap: Boolean = true,
)
enum class CandidateLookupKind { NONE, PINYIN, PREDICTION }
data class CandidateLookup(
    val request: SupplementalRequest, val kind: CandidateLookupKind,
    val mixed: Boolean, val shortcuts: Boolean,
)
data class CandidatePresentation(
    val request: SupplementalRequest, val preedit: String, val raw: String,
    val pinyin: List<PinyinCandidate>, val english: List<String>, val predictions: List<String>,
    val showRaw: Boolean,
    val preeditModel: InputPreedit, val lockedSyllables: List<PinyinSyllable>,
    val syllableChoices: List<InputSyllableChoice>,
)

/** One input algorithm for both hosts. Native calls and asynchronous scheduling are effects. */
class KeyboardInputFlow(val state: KeyboardInputState) {
    private val session get() = state.session
    private val editor = KeyboardEditorState()
    val committedLength get() = editor.committedLength
    private var capsLock = false
    private var lastShift: Long? = null
    private var lastSpace: Long? = null
    private fun effect(kind: InputEffectKind, text: String = "") = InputEffect(kind, text)
    private fun transition(vararg effects: InputEffect) = InputTransition(effects.toList())

    fun beginCandidates(wordInput: Boolean, prediction: Boolean, mixed: Boolean): CandidateLookup {
        state.coordinator.invalidate()
        session.clearCandidates()
        state.predictions = emptyList()
        if (session.mode != KeyboardMode.ENGLISH) state.englishChoices = emptyList()
        val kind = when {
            session.composition.isNotEmpty() -> CandidateLookupKind.PINYIN
            wordInput && prediction && session.mode != KeyboardMode.ENGLISH -> CandidateLookupKind.PREDICTION
            else -> CandidateLookupKind.NONE
        }
        val supplemental = wordInput && session.lockedSyllables.isEmpty() && kind == CandidateLookupKind.PINYIN
        return CandidateLookup(state.snapshot(), kind,
            supplemental && mixed && session.composition.length <= 20, supplemental)
    }

    fun publishCandidates(lookup: CandidateLookup, candidates: List<PinyinCandidate>): Boolean {
        if (!state.isCurrent(lookup.request) || lookup.kind != CandidateLookupKind.PINYIN) return false
        val request = lookup.request.candidate
        return session.applyCandidates(request.input, request.mode, candidates, request.revision)
    }

    fun presentation(mixed: Boolean): CandidatePresentation {
        val raw = session.composition
        val preedit = InputPreeditBuilder.build(session, state.englishWord)
        return CandidatePresentation(state.snapshot(), preedit.text, raw, session.candidates,
            state.englishChoices, if (raw.isEmpty() && state.englishWord.isEmpty()) state.predictions else emptyList(),
            mixed && raw.isNotEmpty() && session.lockedSyllables.isEmpty(), preedit,
            session.lockedSyllables, InputPreeditBuilder.choices(session))
    }

    fun reset() {
        editor.reset()
        capsLock = false; lastShift = null; lastSpace = null
        state.englishWord = ""; state.englishChoices = emptyList(); state.predictions = emptyList()
        state.coordinator.invalidate(); session.reset()
    }

    fun type(value: String, options: InputOptions): InputTransition {
        lastSpace = null
        val before = session.composition
        val english = session.mode == KeyboardMode.ENGLISH && !session.symbols &&
            options.wordInput && options.englishSuggestions &&
            value.length == 1 && (value[0] in 'a'..'z' || value[0] in 'A'..'Z')
        val effects = mutableListOf<InputEffect>()
        if (english) {
            state.appendEnglish(value)
            effects += effect(InputEffectKind.SYNC_MARKED)
            effects += effect(InputEffectKind.SEARCH_ENGLISH, state.englishWord)
        } else {
            val direct = session.key(value)
            if (direct.isNotEmpty()) effects += effect(InputEffectKind.COMMIT, direct)
            if (before != session.composition) effects += effect(InputEffectKind.SEARCH_PINYIN)
        }
        if (session.mode == KeyboardMode.ENGLISH && !capsLock && session.uppercase) {
            session.toggleShift(); effects += effect(InputEffectKind.RENDER_KEYS)
        }
        effects += effect(InputEffectKind.RENDER)
        return InputTransition(effects)
    }

    fun backspace(): InputTransition {
        lastSpace = null
        editor.reset()
        return when {
            state.backspaceEnglish() -> transition(effect(InputEffectKind.SYNC_MARKED), effect(InputEffectKind.RENDER))
            session.backspace() -> transition(effect(InputEffectKind.SEARCH_PINYIN))
            else -> transition(effect(InputEffectKind.DELETE_BACKWARD))
        }
    }

    fun finishEnglish(value: String): InputTransition {
        if (state.englishWord.isEmpty()) return InputTransition()
        state.englishWord = ""
        state.coordinator.invalidate()
        return transition(effect(InputEffectKind.INVALIDATE), effect(InputEffectKind.COMMIT, value))
    }

    fun shift(nowMs: Long): InputTransition {
        val previous = lastShift
        if (previous != null && nowMs - previous in 0..349) {
            capsLock = true
            if (!session.uppercase) session.toggleShift()
        } else { capsLock = false; session.toggleShift() }
        lastShift = nowMs
        return transition(effect(InputEffectKind.RENDER_KEYS), effect(InputEffectKind.RENDER))
    }

    fun capitalize(context: String, options: InputOptions): Boolean {
        if (session.mode != KeyboardMode.ENGLISH || capsLock || !options.wordInput || !options.autoCap) return false
        val uppercase = KeyboardPolicy.capitalize(context)
        if (session.uppercase == uppercase) return false
        session.toggleShift()
        return true
    }

    /** Called after candidate lookup has completed; selection is acknowledged separately by the host. */
    fun boundary(boundary: CompositionBoundary, literal: String, context: String, nowMs: Long,
                 correctedEnglish: String, options: InputOptions): InputTransition {
        val resolution = state.coordinator.boundary(session, boundary)
        if (resolution == BoundaryResolution.PRESERVE)
            return transition(effect(InputEffectKind.RENDER), effect(InputEffectKind.PRESERVE))
        if (resolution == BoundaryResolution.SELECT_FIRST) {
            lastSpace = null
            return select(0, if (boundary == CompositionBoundary.LITERAL) literal else "")
        }
        val word = state.englishWord
        val text = if (boundary == CompositionBoundary.SPACE && options.englishCorrection)
            correctedEnglish.ifEmpty { word } else word
        val effects = finishEnglish(text).effects.toMutableList()
        when (boundary) {
            CompositionBoundary.ENTER -> {
                lastSpace = null; effects += effect(InputEffectKind.HOST_ENTER)
            }
            CompositionBoundary.LITERAL -> {
                lastSpace = null; effects += effect(InputEffectKind.COMMIT, literal)
            }
            CompositionBoundary.SPACE -> {
                // The supplied host context still contains the marked English word, if any.
                val before = KeyboardPolicy.committedContext(context, word) + text
                val previous = lastSpace
                if (options.doubleSpace && previous != null && KeyboardPolicy.doubleSpace(before, nowMs - previous)) {
                    effects += effect(InputEffectKind.DELETE_BACKWARD)
                    effects += effect(InputEffectKind.COMMIT, if (session.mode == KeyboardMode.ENGLISH) ". " else "。")
                } else effects += effect(InputEffectKind.COMMIT, " ")
                lastSpace = nowMs
            }
        }
        effects += effect(InputEffectKind.CAPITALIZE)
        effects += effect(InputEffectKind.RENDER)
        return InputTransition(effects)
    }

    fun select(index: Int, suffix: String): InputTransition {
        val candidate = session.candidates.getOrNull(index) ?: return InputTransition()
        return InputTransition(selection = InputSelection(state.coordinator.snapshot(session), candidate, index, suffix))
    }
    fun canSelect(selection: InputSelection): Boolean =
        state.coordinator.canChoose(selection.request, session, selection.candidate, selection.index)

    fun completeSelection(selection: InputSelection, accepted: Boolean): InputTransition {
        if (!canSelect(selection)) return InputTransition()
        if (!accepted) return transition(effect(InputEffectKind.SEARCH_PINYIN))
        val text = session.choose(selection.index)
        val effects = mutableListOf(effect(InputEffectKind.CONVERT_COMMIT, text + selection.suffix))
        if (selection.modeAfter != null && session.composition.isEmpty()) {
            session.changeMode(selection.modeAfter); lastSpace = null; lastShift = null; capsLock = false
            effects += effect(InputEffectKind.RENDER_KEYS)
        }
        effects += effect(InputEffectKind.SEARCH_PINYIN)
        effects += effect(InputEffectKind.CAPITALIZE)
        if (selection.toolAfter != null && session.composition.isEmpty())
            effects += effect(InputEffectKind.OPEN_TOOL, selection.toolAfter)
        effects += effect(InputEffectKind.RENDER)
        return InputTransition(effects)
    }

    fun changeMode(mode: KeyboardMode): InputTransition {
        if (session.composition.isNotEmpty()) {
            if (state.coordinator.boundary(session, CompositionBoundary.SPACE) == BoundaryResolution.PRESERVE)
                return transition(effect(InputEffectKind.PRESERVE))
            return InputTransition(selection = InputSelection(state.coordinator.snapshot(session),
                session.candidates.first(), 0, modeAfter = mode))
        }
        val effects = finishEnglish(state.englishWord).effects.toMutableList()
        session.changeMode(mode); state.coordinator.invalidate()
        capsLock = false; lastShift = null; lastSpace = null
        effects += effect(InputEffectKind.RENDER_KEYS)
        effects += effect(InputEffectKind.SEARCH_PINYIN)
        effects += effect(InputEffectKind.CAPITALIZE)
        effects += effect(InputEffectKind.RENDER)
        return InputTransition(effects)
    }

    fun chooseSupplemental(request: SupplementalRequest, word: String, prediction: Boolean): InputTransition {
        if (!state.canChoose(request, word, prediction)) return InputTransition()
        lastSpace = null
        val effects = if (state.englishWord.isNotEmpty()) finishEnglish(word).effects.toMutableList() else {
            session.clearComposition()
            mutableListOf(effect(InputEffectKind.CONVERT_COMMIT, word))
        }
        effects += effect(InputEffectKind.SEARCH_PINYIN)
        effects += effect(InputEffectKind.RENDER)
        return InputTransition(effects)
    }

    fun takeRaw(request: SupplementalRequest): InputTransition {
        if (!state.isCurrent(request) || session.composition.isEmpty()) return InputTransition()
        lastSpace = null
        return transition(effect(InputEffectKind.COMMIT, session.takeRaw()), effect(InputEffectKind.SEARCH_PINYIN))
    }

    fun clearComposition(): InputTransition {
        lastSpace = null; session.clearComposition(); state.englishWord = ""
        state.englishChoices = emptyList(); state.predictions = emptyList()
        return transition(effect(InputEffectKind.SEARCH_PINYIN))
    }

    fun recordCommit(text: String, snapshot: EditorSnapshot, remember: Boolean) {
        editor.record(text, snapshot, remember)
    }
    fun edit(command: KeyboardEditCommand, snapshot: EditorSnapshot): InputTransition {
        lastSpace = null
        if (state.englishWord.isNotEmpty() || session.composition.isNotEmpty()) {
            editor.reset()
            return clearComposition()
        }
        return editor.edit(command, snapshot)
    }
    fun moveCursor(steps: Int): InputTransition {
        if (steps == 0) return InputTransition()
        lastSpace = null
        val effects = finishEnglish(state.englishWord).effects.toMutableList()
        editor.reset()
        if (session.composition.isNotEmpty()) {
            session.moveCursor(session.cursor + steps)
            effects += effect(InputEffectKind.RENDER)
        } else effects += InputEffect(InputEffectKind.MOVE_CURSOR, amount = steps)
        return InputTransition(effects)
    }
    fun movePreedit(request: SupplementalRequest, position: Int): InputTransition {
        if (!state.isCurrent(request) || session.composition.isEmpty()) return InputTransition()
        session.moveCursor(position)
        return transition(effect(InputEffectKind.RENDER))
    }
    fun lockSyllable(request: SupplementalRequest, syllable: PinyinSyllable): InputTransition {
        if (!state.isCurrent(request) || !session.lockNext(syllable)) return InputTransition()
        return transition(effect(InputEffectKind.SEARCH_PINYIN))
    }
    fun unlockSyllable(request: SupplementalRequest, index: Int): InputTransition {
        if (!state.isCurrent(request) || index !in session.lockedSyllables.indices) return InputTransition()
        session.unlockFrom(index)
        return transition(effect(InputEffectKind.SEARCH_PINYIN))
    }
    fun setCorrection(enabled: Boolean): InputTransition {
        if (session.autoCorrection == enabled) return InputTransition()
        session.setAutoCorrection(enabled)
        return transition(effect(InputEffectKind.SEARCH_PINYIN))
    }
    fun toggleSymbols(): InputTransition {
        session.toggleSymbols()
        return transition(effect(InputEffectKind.RENDER_KEYS), effect(InputEffectKind.RENDER))
    }

    fun prepareTool(name: String): InputTransition {
        if (session.composition.isNotEmpty()) {
            if (state.coordinator.boundary(session, CompositionBoundary.SPACE) == BoundaryResolution.PRESERVE)
                return transition(effect(InputEffectKind.PRESERVE))
            return InputTransition(selection = InputSelection(state.coordinator.snapshot(session),
                session.candidates.first(), 0, toolAfter = name))
        }
        val effects = finishEnglish(state.englishWord).effects.toMutableList()
        lastSpace = null
        effects += effect(InputEffectKind.INVALIDATE)
        effects += effect(InputEffectKind.SYNC_MARKED)
        effects += effect(InputEffectKind.OPEN_TOOL, name)
        effects += effect(InputEffectKind.RENDER)
        return InputTransition(effects)
    }
}
