package dev.learnime.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationDirectionTest {
    @Test fun englishSentenceKeepsWordPunctuationAndDecimals() {
        assertEquals("Good morning.", TranslationText.sentence("Hello. Good morning."))
        assertEquals("It costs 3.14 dollars.", TranslationText.sentence("It costs 3.14 dollars."))
        assertEquals("hello", TranslationText.sentence("hello"))
        assertFalse(TranslationText.accepts("123.45", TranslationDirection.ENGLISH_TO_CHINESE))
        assertFalse(TranslationText.accepts("こんにちは", TranslationDirection.ENGLISH_TO_CHINESE))
        assertFalse(TranslationText.accepts("你好", TranslationDirection.ENGLISH_TO_CHINESE))
        assertTrue(TranslationText.accepts("I'm going home.", TranslationDirection.ENGLISH_TO_CHINESE))
    }

    @Test fun reverseDirectionReachesEngineAndChangingLanguageClearsOldResult() = runTest {
        val requests = mutableListOf<Pair<String, TranslationDirection>>()
        val engine = object : TranslationEngine {
            override suspend fun translate(source: String): String = error("Direction was lost")
            override suspend fun translate(source: String, direction: TranslationDirection): String {
                requests += source to direction
                return "你好"
            }
            override fun cancel() {}
        }
        var state = TranslationState()
        val controller = TranslationController(engine, this) { state = it }
        controller.update("hello", direction = TranslationDirection.ENGLISH_TO_CHINESE)
        advanceUntilIdle()
        assertEquals(listOf("hello" to TranslationDirection.ENGLISH_TO_CHINESE), requests)
        assertEquals("你好", state.english)
        controller.update("hello", direction = TranslationDirection.CHINESE_TO_ENGLISH)
        assertEquals("", state.english)
        advanceUntilIdle()
        assertEquals(1, requests.size)
    }

    @Test fun languageChangeSuppressesInFlightReverseTranslation() = runTest {
        val engine = object : TranslationEngine {
            override suspend fun translate(source: String): String = error("Direction was lost")
            override suspend fun translate(source: String, direction: TranslationDirection): String =
                withContext(NonCancellable) { delay(1000); "旧译文" }
            override fun cancel() {}
        }
        var state = TranslationState()
        val controller = TranslationController(engine, this) { state = it }
        controller.update("hello", direction = TranslationDirection.ENGLISH_TO_CHINESE)
        advanceTimeBy(700); runCurrent()
        controller.update("hello", direction = TranslationDirection.CHINESE_TO_ENGLISH)
        advanceUntilIdle()
        assertEquals("", state.source)
        assertEquals("", state.english)
    }

    @Test fun disabledContextNeverReachesEngine() = runTest {
        val engine = object : TranslationEngine {
            override suspend fun translate(source: String): String = error("Private content reached model")
            override fun cancel() {}
        }
        val controller = TranslationController(engine, this) {}
        controller.update("secret password", enabled = false, direction = TranslationDirection.ENGLISH_TO_CHINESE)
        advanceUntilIdle()
    }
}
