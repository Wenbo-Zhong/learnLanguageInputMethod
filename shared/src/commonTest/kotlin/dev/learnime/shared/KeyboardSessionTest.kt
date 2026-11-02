package dev.learnime.shared

import kotlin.test.*

class KeyboardSessionTest {
    @Test fun punctuationPreservesUnconvertedSuffix() {
        val session = KeyboardSession()
        "nihaov".forEach { session.key(it.toString()) }
        session.applyCandidates("nihaov", KeyboardMode.CHINESE_QWERTY,
            listOf(PinyinCandidate("\u4f60\u597d", "nihao", 5)))
        assertEquals("", session.literal(","))
        assertEquals("nihaov", session.composition)
        assertEquals("\u4f60\u597d", session.space())
        assertEquals("v", session.composition)
        assertEquals("", session.space())
        assertEquals("v", session.takeRaw())
    }
    @Test fun longCompositionNeverDropsKeys() {
        val session = KeyboardSession()
        val input = "nihao".repeat(12)
        input.forEach { session.key(it.toString()) }
        assertEquals(input, session.composition)
        assertEquals(input, session.takeRaw())
    }
    @Test fun pinyinStaysUncommittedUntilCandidateSelected() {
        val session = KeyboardSession()
        "nihao".forEach { assertEquals("", session.key(it.toString())) }
        assertEquals("nihao", session.composition)
        assertTrue(session.applyCandidates("nihao", KeyboardMode.CHINESE_QWERTY,
            listOf(PinyinCandidate("你好", "nihao", 5), PinyinCandidate("你", "ni", 2))))
        assertEquals("你", session.choose(1))
        assertEquals("hao", session.composition)
        assertTrue(session.candidates.isEmpty())
    }
    @Test fun nineKeyPartialChoicePreservesRemainingKeys() {
        val session = KeyboardSession()
        session.changeMode(KeyboardMode.CHINESE_NINE_KEY)
        "64426".forEach { session.key(it.toString()) }
        session.applyCandidates("64426", KeyboardMode.CHINESE_NINE_KEY, listOf(PinyinCandidate("你", "ni", 2)))
        assertEquals("你", session.choose(0))
        assertEquals("426", session.composition)
        assertTrue(session.backspace()); assertEquals("42", session.composition)
    }
    @Test fun oldResultsAndMalformedCandidatesCannotCommit() {
        val session = KeyboardSession()
        session.key("n"); session.key("i")
        assertFalse(session.applyCandidates("n", KeyboardMode.CHINESE_QWERTY, listOf(PinyinCandidate("你", "n", 1))))
        session.applyCandidates("ni", KeyboardMode.CHINESE_QWERTY,
            listOf(PinyinCandidate("坏", "ni", 3), PinyinCandidate("坏", "ni", 0)))
        assertEquals("", session.choose(0)); assertEquals("ni", session.composition)
        session.changeMode(KeyboardMode.ENGLISH)
        assertFalse(session.applyCandidates("ni", KeyboardMode.CHINESE_QWERTY, listOf(PinyinCandidate("你", "ni", 2))))
        assertEquals("a", session.key("a"))
        session.toggleShift(); assertEquals("A", session.key("a"))
    }
    @Test fun spaceEnterPunctuationAndSymbolsResolveComposition() {
        val session = KeyboardSession()
        "nihao".forEach { session.key(it.toString()) }
        session.applyCandidates("nihao", KeyboardMode.CHINESE_QWERTY, listOf(PinyinCandidate("你好", "nihao", 5)))
        assertEquals("你好。", session.literal("。")); assertEquals("", session.composition)
        assertEquals(" ", session.space()); assertEquals("\n", session.enter())
        session.toggleSymbols(); assertEquals("9", session.key("9"))
        session.reset(); assertFalse(session.symbols)
        session.key("n"); assertEquals("n", session.takeRaw()); assertFalse(session.backspace())
    }
    @Test fun nativeTsvParsingAndInputBoundaries() {
        val list = PinyinResults.parse("你好\tni hao\t5\ninvalid\n坏\tx\t0\n坏\tx\t-1\n")
        assertEquals(listOf(PinyinCandidate("你好", "ni hao", 5)), list)
        val session = KeyboardSession()
        session.key("'"); assertEquals("", session.composition)
        repeat(100) { session.key("n") }
        assertEquals(100, session.composition.length)
        assertTrue(session.needsSegmentation)
    }
}
