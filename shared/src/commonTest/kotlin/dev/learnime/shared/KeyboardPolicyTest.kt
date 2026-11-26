package dev.learnime.shared

import kotlin.test.*

class KeyboardPolicyTest {
    @Test fun englishCorrectionPreservesCaseAndAcceptsOnlySingleEdit() {
        val dictionary = EnglishLexicon("hello 100\nhelp 30\nworld 90\nword 80\nwifi 20\n")
        assertEquals("Hello", dictionary.correction("Helo"))
        assertEquals("WORLD", dictionary.correction("WROLD"))
        assertEquals("hello", dictionary.correction("hello"))
        assertEquals("hxxlo", dictionary.correction("hxxlo"))
        assertEquals(listOf("word"), dictionary.nineSuggestions("9673"))
        assertTrue(dictionary.nineSuggestions("10").isEmpty())
    }

    @Test fun phraseConversionOverridesCharacterConversionAndKeepsOtherText() {
        val converter = TraditionalText("发\t發\n型\t型\n", "发型\t髮型\n")
        assertEquals("髮型發 A😀", converter.convert("发型发 A😀"))
    }

    @Test fun clipboardExpiryAndCapacityPreservePinnedEntries() {
        val now = 4_000_000L
        val entries = listOf(
            ClipboardRecord("expired", now - 3_600_000L, false),
            ClipboardRecord("recent", now - 3_599_999L, false),
            ClipboardRecord("pinned", 0, true)
        )
        assertEquals(listOf("pinned", "recent"), ClipboardPolicy.retained(entries, now).map { it.text })
        assertEquals(1, ClipboardPolicy.added(entries, "pinned", now).count { it.text == "pinned" })
        assertTrue(ClipboardPolicy.added(entries, "pinned", now).first().pinned)
        val full = (1..40).map { ClipboardRecord("pin$it", 0, true) }
        assertEquals(full, ClipboardPolicy.added(full, "new", now))
        assertFalse(ClipboardPolicy.accepts(" ".repeat(10)))
        assertFalse(ClipboardPolicy.accepts("x".repeat(10_001)))
    }

    @Test fun markedTextAndDoubleSpaceHonorFieldAndTimeBoundaries() {
        assertEquals("", KeyboardPolicy.markedText("hello", "你", "ni", true, true))
        assertEquals("你", KeyboardPolicy.markedText("hello", "你", "ni", true, false))
        assertEquals("hello", KeyboardPolicy.markedText("hello", "", "ni", false, false))
        assertEquals("", KeyboardPolicy.markedText("", "", "ni", false, false))
        assertTrue(KeyboardPolicy.doubleSpace("hello ", 399))
        assertFalse(KeyboardPolicy.doubleSpace("hello ", 400))
        assertFalse(KeyboardPolicy.doubleSpace("hello ", -1))
        assertFalse(KeyboardPolicy.doubleSpace("! ", 100))
        assertEquals("已提交", KeyboardPolicy.committedContext("已提交ni", "ni"))
        assertEquals("新输入框", KeyboardPolicy.committedContext("新输入框", "ni"))
    }

}
