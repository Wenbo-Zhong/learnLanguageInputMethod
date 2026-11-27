package dev.learnime.shared

import kotlin.test.*

class CandidatePipelineTest {
    private class Backend : CandidateBackend {
        val searches = mutableListOf<(CandidateResult) -> Unit>()
        val predictions = mutableListOf<(List<String>) -> Unit>()
        val words = mutableListOf<(List<String>) -> Unit>()
        var cancellations = 0
        override fun pinyin(request: CandidateRequest, completion: (CandidateResult) -> Unit) { searches += completion }
        override fun predict(context: String, completion: (List<String>) -> Unit) { predictions += completion }
        override fun english(input: String, nine: Boolean, completion: (List<String>) -> Unit) { words += completion }
        override fun cancel() { cancellations++ }
    }
    private val state = KeyboardInputState()
    private val flow = KeyboardInputFlow(state)
    private val backend = Backend()
    private var renders = 0
    private val pipeline = CandidatePipeline(flow, backend) { renders++ }
    private fun type(value: String = "a") { value.forEach { flow.type(it.toString(), InputOptions()) } }
    private fun refresh(mixed: Boolean = true, shortcut: String = "") =
        pipeline.refresh(true, true, mixed, "学习", shortcut)
    private val result = CandidateResult(listOf(PinyinCandidate("学习", "", 1)))

    @Test fun pinyinReadinessDoesNotWaitForSupplementalDictionary() {
        type(); refresh()
        var ready: Boolean? = null
        pipeline.whenReady { ready = it }
        assertTrue(pipeline.loading); assertNull(ready)
        backend.searches.single()(result)
        assertFalse(pipeline.loading); assertEquals(true, ready)
        assertEquals(result.candidates, state.session.candidates)
        assertTrue(state.englishChoices.isEmpty())
        backend.words.single()(listOf("a", "alpha", "able", "about", "another"))
        assertEquals(listOf("alpha", "able", "about"), state.englishChoices)
    }
    @Test fun dictionaryAndPinyinMayCompleteInEitherOrder() {
        type(); refresh(shortcut = "自定义")
        backend.words.single()(listOf("a", "alpha", "alpha"))
        assertEquals(listOf("自定义", "alpha"), state.englishChoices)
        assertTrue(pipeline.loading)
        backend.searches.single()(result)
        assertEquals(result.candidates, state.session.candidates)
        assertTrue(renders >= 3)
    }
    @Test fun invalidationReleasesWaiterFalseAndRejectsAllLateResults() {
        type(); refresh()
        var ready: Boolean? = null; pipeline.whenReady { ready = it }
        pipeline.invalidate()
        assertEquals(false, ready)
        backend.searches.single()(result); backend.words.single()(listOf("a", "obsolete"))
        assertTrue(state.session.candidates.isEmpty()); assertTrue(state.englishChoices.isEmpty())
        assertFalse(pipeline.loading); assertEquals("", pipeline.error)
    }
    @Test fun returningToSameEncodingCannotReviveOldCallbacks() {
        type(); refresh()
        flow.type("b", InputOptions()); flow.backspace(); refresh()
        backend.searches.first()(result); backend.words.first()(listOf("a", "obsolete"))
        assertTrue(pipeline.loading); assertTrue(state.englishChoices.isEmpty())
        backend.searches.last()(result)
        assertFalse(pipeline.loading)
    }
    @Test fun failureClearsLoadingAndResumesBoundaryWithoutCandidate() {
        type(); refresh()
        var ready: Boolean? = null; pipeline.whenReady { ready = it }
        backend.searches.single()(CandidateResult(emptyList(), "词库加载失败"))
        assertEquals(true, ready); assertFalse(pipeline.loading)
        assertEquals("词库加载失败", pipeline.error)
        val boundary = flow.boundary(CompositionBoundary.ENTER, "", "", 0, "", InputOptions())
        assertTrue(boundary.effects.any { it.kind == InputEffectKind.PRESERVE })
        assertEquals("a", state.session.composition)
    }
    @Test fun duplicateNativeCompletionDoesNotReleaseNewWaitersOrChangeError() {
        type(); refresh()
        var calls = 0; pipeline.whenReady { calls++ }
        backend.searches.single()(result)
        backend.searches.single()(CandidateResult(emptyList(), "duplicate"))
        assertEquals(1, calls); assertEquals("", pipeline.error)
    }
    @Test fun aReentrantWaiterCannotConfirmAnotherWaiterFromOldInput() {
        type(); refresh()
        val values = mutableListOf<Boolean>()
        pipeline.whenReady { values += it; flow.type("b", InputOptions()); refresh() }
        pipeline.whenReady { values += it }
        backend.searches.first()(result)
        assertEquals(listOf(true, false), values)
        assertTrue(pipeline.loading)
    }
    @Test fun refreshRemovesOldNativeCandidatesBeforeNewSearch() {
        type(); refresh(); backend.searches.last()(result)
        assertTrue(state.session.candidates.isNotEmpty())
        refresh()
        assertTrue(state.session.candidates.isEmpty()); assertTrue(pipeline.loading)
    }
    @Test fun shortcutsWorkWithoutMixedAndSensitiveInputSuppressesBoth() {
        type(); refresh(false, "自定义")
        assertEquals(listOf("自定义"), state.englishChoices); assertTrue(backend.words.isEmpty())
        pipeline.refresh(false, true, true, "", "不应出现")
        assertTrue(state.englishChoices.isEmpty()); assertTrue(backend.words.isEmpty())
    }
    @Test fun englishPublicationIsBoundToWordAndGeneration() {
        flow.changeMode(KeyboardMode.ENGLISH); flow.type("a", InputOptions())
        pipeline.refreshEnglish("a")
        flow.type("b", InputOptions()); pipeline.refreshEnglish("ab")
        backend.words.first()(listOf("obsolete"))
        assertTrue(state.englishChoices.isEmpty())
        backend.words.last()(listOf("ab", "about", "ab"))
        assertEquals(listOf("ab", "about"), state.englishChoices)
    }
    @Test fun predictionsCannotPublishAfterNewTypingOrEditorReset() {
        refresh(); type()
        backend.predictions.first()(listOf("obsolete")); assertTrue(state.predictions.isEmpty())
        flow.reset(); refresh(); pipeline.invalidate()
        backend.predictions.last()(listOf("obsolete")); assertTrue(state.predictions.isEmpty())
    }
    @Test fun noLookupAndEnglishLookupDoNotHoldBoundary() {
        pipeline.refresh(false, false, false, "", "")
        var ready = false; pipeline.whenReady { ready = it }; assertTrue(ready)
        flow.changeMode(KeyboardMode.ENGLISH); flow.type("a", InputOptions()); pipeline.refreshEnglish("a")
        ready = false; pipeline.whenReady { ready = it }; assertTrue(ready)
        assertFalse(pipeline.loading)
    }
}
