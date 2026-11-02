package dev.learnime.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationTest {
    @Test fun extractsLastSentenceWithoutLosingFinalPunctuation() {
        assertEquals("我想早点回家。", TranslationText.sentence("第一句。 我想早点回家。"))
        assertEquals("明天见", TranslationText.sentence("今天忙！明天见"))
        assertEquals("", TranslationText.sentence("   "))
        assertEquals(160, TranslationText.sentence("中".repeat(1000)).length)
    }
    @Test fun typingIsDebouncedAndOnlyNewestSentenceRuns() = runTest {
        val inputs = mutableListOf<String>()
        val engine = object : TranslationEngine {
            override suspend fun translate(prompt: String): String { inputs += prompt; return "Hello" }
            override fun cancel() {}
        }
        var state = TranslationState()
        val controller = TranslationController(engine, this) { state = it }
        controller.update("你"); advanceTimeBy(300)
        controller.update("你好"); advanceTimeBy(699); runCurrent()
        assertTrue(inputs.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(1, inputs.size); assertEquals("你好", state.source); assertEquals("Hello", state.english)
        controller.reset(); assertEquals("", state.english)
    }
    @Test fun cancellationAndResetSuppressStaleInference() = runTest {
        val engine = object : TranslationEngine {
            override suspend fun translate(prompt: String): String = withContext(NonCancellable) { delay(1000); "old result" }
            override fun cancel() {}
        }
        var state = TranslationState()
        val controller = TranslationController(engine, this) { state = it }
        controller.update("旧句子"); advanceTimeBy(700); runCurrent()
        controller.reset(); advanceUntilIdle()
        assertEquals(TranslationState(), state)
    }
}
