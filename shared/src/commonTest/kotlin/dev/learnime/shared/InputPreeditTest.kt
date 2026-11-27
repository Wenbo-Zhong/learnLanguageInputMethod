package dev.learnime.shared

import kotlin.test.*

class InputPreeditTest {
    private val state = KeyboardInputState()
    private val flow = KeyboardInputFlow(state)
    private val session get() = state.session
    private val syllables = listOf(PinyinSyllable("xue", 3), PinyinSyllable("xi", 5))
    private val candidate = PinyinCandidate("学习", "xue xi", 5, syllables = syllables)
    private fun compose(nine: Boolean = false) {
        session.changeMode(if (nine) KeyboardMode.CHINESE_NINE_KEY else KeyboardMode.CHINESE_QWERTY)
        (if (nine) "98394" else "xuexi").forEach { session.key(it.toString()) }
        publish()
    }
    private fun publish() { session.applyCandidates(session.composition, session.mode, listOf(candidate)) }

    @Test fun qwertyCursorOffsetsMapEveryDisplayBoundary() {
        compose(); session.moveCursor(2)
        val model = flow.presentation(true).preeditModel
        assertEquals("xu│exi", model.cursorText)
        assertEquals(listOf(0, 1, 2, 2, 3, 4, 5), model.offsets)
        assertEquals(model.cursorText, model.text)
    }
    @Test fun nineKeySpellingSpacingAndOffsetsFollowNativeCandidates() {
        compose(true); session.moveCursor(3)
        val model = flow.presentation(true).preeditModel
        assertEquals("xue xi", model.text)
        assertEquals("xue │xi", model.cursorText)
        assertEquals(listOf(0, 1, 2, 3, 3, 3, 4, 5), model.offsets)
    }
    @Test fun nineKeyKeepsUnconsumedEncodingVisible() {
        compose(true)
        session.applyCandidates("98394", session.mode, listOf(candidate.copy(consumed = 3, spelling = "xue", syllables = syllables.take(1))))
        assertEquals("xue94│", flow.presentation(true).preeditModel.cursorText)
    }
    @Test fun englishPreeditHasNoPinyinTouchOffsets() {
        flow.changeMode(KeyboardMode.ENGLISH); flow.type("a", InputOptions())
        val model = flow.presentation(true).preeditModel
        assertEquals("a", model.text); assertEquals("a│", model.cursorText)
        assertTrue(model.offsets.isEmpty())
    }
    @Test fun syllableChoicesContainRawSliceAfterLockedPrefix() {
        compose(true)
        val request = state.snapshot()
        flow.lockSyllable(request, syllables.first()); publish()
        val model = flow.presentation(true)
        assertEquals(syllables.take(1), model.lockedSyllables)
        assertEquals(listOf(InputSyllableChoice(syllables.last(), "94")), model.syllableChoices)
        assertTrue(model.preeditModel.lockedEnd > 0)
    }
    @Test fun queuedSyllableLockRejectsTypingAndReturningToSameEncoding() {
        compose()
        val request = state.snapshot()
        session.key("a"); session.backspace(); publish()
        assertTrue(flow.lockSyllable(request, syllables.first()).effects.isEmpty())
        assertTrue(session.lockedSyllables.isEmpty())
    }
    @Test fun queuedUnlockRejectsNewLookupGeneration() {
        compose(); flow.lockSyllable(state.snapshot(), syllables.first()); publish()
        val request = state.snapshot(); state.coordinator.invalidate()
        assertTrue(flow.unlockSyllable(request, 0).effects.isEmpty())
        assertEquals(syllables.take(1), session.lockedSyllables)
        assertTrue(flow.unlockSyllable(state.snapshot(), 0).effects.isNotEmpty())
        assertTrue(session.lockedSyllables.isEmpty())
    }
    @Test fun invalidSyllableOrUnlockIndexCannotMutateInput() {
        compose(); val request = state.snapshot()
        assertTrue(flow.lockSyllable(request, PinyinSyllable("other", 3)).effects.isEmpty())
        assertTrue(flow.unlockSyllable(request, -1).effects.isEmpty())
        assertTrue(flow.unlockSyllable(request, 1).effects.isEmpty())
        assertEquals("xuexi", session.composition)
    }
    @Test fun preeditTapIsBoundToItsRenderedRevision() {
        compose(); val request = state.snapshot()
        session.key("a")
        assertTrue(flow.movePreedit(request, 0).effects.isEmpty())
        assertEquals(6, session.cursor)
        flow.movePreedit(state.snapshot(), 0); assertEquals(0, session.cursor)
    }
    @Test fun correctionChangeInvalidatesLocksAndOldRequests() {
        compose(); flow.lockSyllable(state.snapshot(), syllables.first()); publish()
        val request = state.snapshot()
        assertEquals(InputEffectKind.SEARCH_PINYIN, flow.setCorrection(false).effects.single().kind)
        assertTrue(session.lockedSyllables.isEmpty())
        assertFalse(state.isCurrent(request))
        assertTrue(flow.setCorrection(false).effects.isEmpty())
    }
    @Test fun symbolToggleOnlyUpdatesSharedLayoutStateAndRendering() {
        val transition = flow.toggleSymbols()
        assertTrue(session.symbols)
        assertEquals(listOf(InputEffectKind.RENDER_KEYS, InputEffectKind.RENDER), transition.effects.map { it.kind })
        flow.toggleSymbols(); assertFalse(session.symbols)
    }
}
