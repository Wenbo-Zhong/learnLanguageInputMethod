package dev.learnime.shared

import kotlin.test.*

class KeyboardInputStateTest {
    @Test fun editingAndReturningToSameWordRejectsOldLookupAndButton() {
        val state = KeyboardInputState()
        state.session.changeMode(KeyboardMode.ENGLISH)
        state.appendEnglish("a")
        val old = state.snapshot()
        assertTrue(state.publishChoices(old, listOf("alpha")))
        assertTrue(state.canChoose(old, "alpha", false))
        state.appendEnglish("b")
        assertTrue(state.englishChoices.isEmpty())
        state.backspaceEnglish()
        assertEquals("a", state.englishWord)
        assertFalse(state.publishChoices(old, listOf("obsolete")))
        assertFalse(state.canChoose(old, "alpha", false))
    }

    @Test fun chineseEditsInvalidateMixedCandidatesEvenWhenRawInputReturns() {
        val state = KeyboardInputState()
        state.session.key("a")
        val request = state.snapshot()
        state.publishChoices(request, listOf("alpha"))
        state.session.key("b"); state.session.backspace()
        assertFalse(state.canChoose(request, "alpha", false))
        assertFalse(state.publishChoices(request, listOf("obsolete")))
    }

    @Test fun predictionsRejectNewTypingModeAndEditorGeneration() {
        val state = KeyboardInputState()
        val request = state.snapshot()
        assertTrue(state.publishPredictions(request, listOf("学习")))
        assertTrue(state.canChoose(request, "学习", true))
        state.session.key("n")
        assertFalse(state.canChoose(request, "学习", true))
        state.session.clearComposition()
        assertFalse(state.publishPredictions(request, listOf("旧")))
        val next = state.snapshot()
        state.coordinator.invalidate()
        assertFalse(state.publishPredictions(next, listOf("旧输入框")))
        val mode = state.snapshot()
        state.session.changeMode(KeyboardMode.ENGLISH)
        assertFalse(state.isCurrent(mode))
    }

    @Test fun uppercaseAndSupplementalPublicationShareOneState() {
        val state = KeyboardInputState()
        state.session.changeMode(KeyboardMode.ENGLISH)
        state.session.toggleShift()
        state.appendEnglish("a")
        assertEquals("A", state.englishWord)
        val current = state.snapshot()
        assertTrue(state.publishChoices(current, listOf("Alpha", "Alpha")))
        assertEquals(listOf("Alpha"), state.englishChoices)
        assertFalse(state.canChoose(current, "Other", false))
        assertFalse(state.publishPredictions(current, listOf("Learn")))
    }

    @Test fun modelContractsReserveEosAndCheckVocabularyIdentity() {
        assertNotNull(OpusModelSpec.sourceError(0))
        assertNull(OpusModelSpec.sourceError(255))
        assertNotNull(OpusModelSpec.sourceError(256))
        assertNotNull(OpusModelSpec.sourceError(602))
        assertEquals(8, OpusModelSpec.cacheNames.distinct().size)
        assertTrue(M2mModelSpec.matchesVocabulary(128000, 128112, 128004, 128102, 128022))
        assertFalse(M2mModelSpec.matchesVocabulary(128000, 128112, 128004, 128022, 128102))
    }
}
