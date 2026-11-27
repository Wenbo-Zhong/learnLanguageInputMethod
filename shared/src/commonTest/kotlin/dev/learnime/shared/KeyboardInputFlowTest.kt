package dev.learnime.shared

import kotlin.test.*

class KeyboardInputFlowTest {
    private val state = KeyboardInputState()
    private val flow = KeyboardInputFlow(state)
    private val session get() = state.session
    private val options = InputOptions()
    private fun InputTransition.text() = effects.filter {
        it.kind in listOf(InputEffectKind.COMMIT, InputEffectKind.CONVERT_COMMIT)
    }.joinToString("") { it.text }
    private fun boundary(kind: CompositionBoundary, context: String = "", now: Long = 1000,
                         literal: String = "", corrected: String = state.englishWord,
                         settings: InputOptions = options) =
        flow.boundary(kind, literal, context, now, corrected, settings)
    private fun compose(value: String, consumed: Int = value.length, correction: String = "") {
        value.forEach { flow.type(it.toString(), options) }
        session.applyCandidates(value, session.mode, listOf(PinyinCandidate("学习", "xue xi", consumed, correction = correction)))
    }

    @Test fun englishTypingAndOneShotShiftAreShared() {
        flow.changeMode(KeyboardMode.ENGLISH); flow.shift(1000)
        val first = flow.type("a", options)
        assertEquals("A", state.englishWord)
        assertFalse(session.uppercase)
        assertTrue(first.effects.any { it.kind == InputEffectKind.SEARCH_ENGLISH && it.text == "A" })
        flow.type("b", options)
        assertEquals("Ab", state.englishWord)
    }
    @Test fun capsLockSurvivesTypingAndCapitalizationUntilSingleShift() {
        flow.changeMode(KeyboardMode.ENGLISH); flow.shift(1000); flow.shift(1200)
        flow.type("a", options); flow.type("b", options)
        flow.capitalize("lowercase ", options)
        assertEquals("AB", state.englishWord); assertTrue(session.uppercase)
        flow.shift(1800); assertFalse(session.uppercase)
    }
    @Test fun firstShiftNearClockOriginIsNotDoubleTap() {
        flow.changeMode(KeyboardMode.ENGLISH); flow.shift(50); flow.type("a", options)
        assertFalse(session.uppercase)
    }
    @Test fun suggestionsDisabledStillConsumesOneShotShift() {
        flow.changeMode(KeyboardMode.ENGLISH); flow.shift(1000)
        assertEquals("A", flow.type("a", options.copy(englishSuggestions = false)).text())
        assertFalse(session.uppercase); assertEquals("", state.englishWord)
    }
    @Test fun englishBackspaceCannotWriteOrUseObsoleteSuggestions() {
        flow.changeMode(KeyboardMode.ENGLISH); flow.type("a", options)
        val request = state.snapshot(); state.publishChoices(request, listOf("alpha"))
        assertTrue(flow.backspace().effects.any { it.kind == InputEffectKind.SYNC_MARKED })
        assertTrue(flow.chooseSupplemental(request, "alpha", false).effects.isEmpty())
        assertEquals("", state.englishWord)
    }
    @Test fun pinyinBackspaceThenHostBackspace() {
        flow.type("a", options)
        assertEquals(InputEffectKind.SEARCH_PINYIN, flow.backspace().effects.single().kind)
        assertEquals(InputEffectKind.DELETE_BACKWARD, flow.backspace().effects.single().kind)
    }
    @Test fun spaceSelectsWithoutAddingWhitespaceAndEnterAfterwardsUsesHost() {
        compose("xuexi")
        val selection = boundary(CompositionBoundary.SPACE).selection!!
        assertEquals("学习", flow.completeSelection(selection, true).text())
        val enter = boundary(CompositionBoundary.ENTER)
        assertTrue(enter.effects.any { it.kind == InputEffectKind.HOST_ENTER })
        assertEquals("", enter.text())
    }
    @Test fun enterWithCompositionSelectsWithoutHostAction() {
        compose("xuexi")
        val choice = boundary(CompositionBoundary.ENTER)
        assertNotNull(choice.selection); assertTrue(choice.effects.isEmpty())
        assertFalse(flow.completeSelection(choice.selection!!, true).effects.any { it.kind == InputEffectKind.HOST_ENTER })
    }
    @Test fun literalPreservesPartialAndCorrectedCandidate() {
        compose("xuexi", 3)
        assertNull(boundary(CompositionBoundary.LITERAL, literal = "。").selection)
        assertEquals("xuexi", session.composition)
        session.applyCandidates("xuexi", session.mode, listOf(PinyinCandidate("学习", "", 5, correction = "fix")))
        assertTrue(boundary(CompositionBoundary.LITERAL, literal = "。").effects.any { it.kind == InputEffectKind.PRESERVE })
        assertEquals("xuexi", session.composition)
    }
    @Test fun literalAppendsOnlyAfterNativeAcknowledgement() {
        compose("xuexi")
        val selection = boundary(CompositionBoundary.LITERAL, literal = "。").selection!!
        assertEquals("xuexi", session.composition)
        assertEquals("学习。", flow.completeSelection(selection, true).text())
        assertTrue(flow.completeSelection(selection, true).effects.isEmpty())
    }
    @Test fun failedAndStaleSelectionNeverCommits() {
        compose("xuexi")
        val selection = flow.select(0, "").selection!!
        assertEquals("", flow.completeSelection(selection, false).text())
        assertEquals("xuexi", session.composition)
        state.coordinator.invalidate()
        assertTrue(flow.completeSelection(selection, true).effects.isEmpty())
    }
    @Test fun modeChangeWaitsForFullAcknowledgedComposition() {
        compose("xuexi", 3)
        val first = flow.changeMode(KeyboardMode.ENGLISH).selection!!
        flow.completeSelection(first, true)
        assertEquals(KeyboardMode.CHINESE_QWERTY, session.mode); assertEquals("xi", session.composition)
        session.applyCandidates("xi", session.mode, listOf(PinyinCandidate("习", "", 2)))
        val second = flow.changeMode(KeyboardMode.ENGLISH).selection!!
        flow.completeSelection(second, true)
        assertEquals(KeyboardMode.ENGLISH, session.mode); assertEquals("", session.composition)
    }
    @Test fun modeChangeWithNoCandidatePreservesInput() {
        flow.type("a", options)
        assertTrue(flow.changeMode(KeyboardMode.ENGLISH).effects.any { it.kind == InputEffectKind.PRESERVE })
        assertEquals("a", session.composition); assertEquals(KeyboardMode.CHINESE_QWERTY, session.mode)
    }
    @Test fun englishCorrectionSpaceAndLiteralUseSameFinish() {
        flow.changeMode(KeyboardMode.ENGLISH)
        "teh".forEach { flow.type(it.toString(), options) }
        assertEquals("the ", boundary(CompositionBoundary.SPACE, "teh", corrected = "the",
            settings = options.copy(englishCorrection = true)).text())
        assertEquals("", state.englishWord)
        flow.type("a", options)
        assertEquals("a,", boundary(CompositionBoundary.LITERAL, literal = ",").text())
    }
    @Test fun doubleSpaceUsesLanguagePunctuationAndResetsOnTyping() {
        val settings = options.copy(doubleSpace = true)
        flow.changeMode(KeyboardMode.ENGLISH)
        assertEquals(" ", boundary(CompositionBoundary.SPACE, "word", 1000, settings = settings).text())
        val second = boundary(CompositionBoundary.SPACE, "word ", 1200, settings = settings)
        assertEquals(". ", second.text()); assertEquals(InputEffectKind.DELETE_BACKWARD, second.effects.first().kind)
        flow.type("a", options)
        assertEquals("a ", boundary(CompositionBoundary.SPACE, "word. a", 1250, settings = settings).text())
        flow.changeMode(KeyboardMode.CHINESE_QWERTY)
        boundary(CompositionBoundary.SPACE, "学习", 2000, settings = settings)
        assertEquals("。", boundary(CompositionBoundary.SPACE, "学习 ", 2200, settings = settings).text())
    }
    @Test fun resetInvalidatesNativeSelectionLookupAndShiftHistory() {
        compose("xuexi"); val selection = flow.select(0, "").selection!!
        val request = state.snapshot()
        flow.reset()
        assertTrue(flow.completeSelection(selection, true).effects.isEmpty())
        assertFalse(state.publishChoices(request, listOf("obsolete")))
        assertEquals("", session.composition)
    }
    @Test fun supplementalAndRawButtonsRejectSameTextFromAnOlderRevision() {
        flow.type("a", options); val request = state.snapshot()
        flow.type("b", options); flow.backspace()
        assertTrue(flow.takeRaw(request).effects.isEmpty())
        assertEquals("a", flow.takeRaw(state.snapshot()).text())
    }
    @Test fun mixedAndPredictionSelectionsClearOnlyCurrentComposition() {
        flow.type("a", options); val mixed = state.snapshot()
        state.publishChoices(mixed, listOf("alpha"))
        assertEquals("alpha", flow.chooseSupplemental(mixed, "alpha", false).text())
        assertEquals("", session.composition)
        val prediction = state.snapshot(); state.publishPredictions(prediction, listOf("学习"))
        flow.type("n", options)
        assertTrue(flow.chooseSupplemental(prediction, "学习", true).effects.isEmpty())
        assertEquals("n", session.composition)
    }
    @Test fun lookupUsesOnePolicyForMixedShortcutsAndPredictions() {
        assertEquals(CandidateLookupKind.PREDICTION, flow.beginCandidates(true, true, true).kind)
        assertEquals(CandidateLookupKind.NONE, flow.beginCandidates(false, true, true).kind)
        flow.type("a", options)
        val search = flow.beginCandidates(true, true, false)
        assertEquals(CandidateLookupKind.PINYIN, search.kind)
        assertTrue(search.shortcuts); assertFalse(search.mixed)
        val sensitive = flow.beginCandidates(false, true, true)
        assertFalse(sensitive.shortcuts); assertFalse(sensitive.mixed)
    }
    @Test fun lookupPublicationRejectsStaleRevisionAndGeneration() {
        flow.type("a", options)
        val old = flow.beginCandidates(true, true, true)
        val next = flow.beginCandidates(true, true, true)
        val result = listOf(PinyinCandidate("学习", "", 1))
        assertFalse(flow.publishCandidates(old, result))
        assertTrue(flow.publishCandidates(next, result))
        flow.type("b", options)
        assertFalse(flow.publishCandidates(next, result))
    }
    @Test fun presentationIncludesSnapshotAndHidesPredictionsDuringEnglishPreedit() {
        flow.changeMode(KeyboardMode.ENGLISH)
        val request = state.snapshot()
        state.publishPredictions(request, listOf("Learning"))
        flow.type("a", options)
        val model = flow.presentation(true)
        assertEquals("a", model.preedit); assertTrue(model.predictions.isEmpty())
        assertFalse(model.showRaw); assertTrue(state.isCurrent(model.request))
    }
    @Test fun longCompositionDisablesMixedLookupButKeepsShortcuts() {
        repeat(21) { flow.type("a", options) }
        val lookup = flow.beginCandidates(true, true, true)
        assertFalse(lookup.mixed); assertTrue(lookup.shortcuts)
        assertEquals("a".repeat(21) + "│", flow.presentation(true).preedit)
    }
    @Test fun toolOpeningWaitsForNativeAcknowledgementAndPreservesPartialInput() {
        compose("xuexi", 3)
        val selection = flow.prepareTool("剪贴板").selection!!
        assertFalse(flow.completeSelection(selection, true).effects.any { it.kind == InputEffectKind.OPEN_TOOL })
        assertEquals("xi", session.composition)
        session.applyCandidates("xi", session.mode, listOf(PinyinCandidate("习", "", 2)))
        val finish = flow.prepareTool("剪贴板").selection!!
        assertTrue(flow.completeSelection(finish, true).effects.any {
            it.kind == InputEffectKind.OPEN_TOOL && it.text == "剪贴板"
        })
    }
    @Test fun toolOpeningWithoutCandidatesPreservesComposition() {
        flow.type("a", options)
        assertEquals(InputEffectKind.PRESERVE, flow.prepareTool("符号").effects.single().kind)
        assertEquals("a", session.composition)
    }
    @Test fun clearingInputInvalidatesEnglishChoicesAndNativeCandidates() {
        flow.changeMode(KeyboardMode.ENGLISH); flow.type("a", options)
        val request = state.snapshot(); state.publishChoices(request, listOf("alpha"))
        flow.clearComposition()
        assertEquals("", state.englishWord); assertTrue(state.englishChoices.isEmpty())
        assertFalse(state.isCurrent(request))
    }
    @Test fun ordinaryTypingUpdatesCandidatesWithoutRebuildingNativeKeys() {
        assertFalse(flow.type("a", options).effects.any { it.kind == InputEffectKind.RENDER_KEYS })
        flow.clearComposition()
        flow.changeMode(KeyboardMode.ENGLISH)
        assertFalse(flow.type("a", options).effects.any { it.kind == InputEffectKind.RENDER_KEYS })
        flow.shift(1000)
        assertTrue(flow.type("a", options).effects.any { it.kind == InputEffectKind.RENDER_KEYS })
    }
    @Test fun capitalizationRequestsRedrawOnlyWhenCaseChanges() {
        flow.changeMode(KeyboardMode.ENGLISH)
        assertTrue(flow.capitalize("", options))
        assertFalse(flow.capitalize("", options))
        assertTrue(flow.capitalize("word ", options))
        assertFalse(flow.capitalize("word ", options))
    }
}
