package dev.learnime.shared

import kotlin.test.*

class KeyboardEditorStateTest {
    private val state = KeyboardInputState()
    private val flow = KeyboardInputFlow(state)
    private fun undo(context: String, position: Long = -1, collapsed: Boolean = true) =
        flow.edit(KeyboardEditCommand.UNDO, EditorSnapshot(context, position, collapsed))

    @Test fun positionedEditorUndoRequiresSameSelectionAndSuffix() {
        flow.recordCommit("学习", EditorSnapshot("", 12), true)
        val transition = undo("继续学习", 12)
        assertEquals("学习", transition.effects.single().text)
        assertEquals(InputEffectKind.DELETE_TEXT, transition.effects.single().kind)
        assertTrue(undo("继续学习", 12).effects.isEmpty())
    }
    @Test fun positionedEditorRejectsDifferentPositionEvenWithSameText() {
        flow.recordCommit("学习", EditorSnapshot("", 12), true)
        assertTrue(undo("学习", 14).effects.isEmpty())
    }
    @Test fun contextOnlyEditorRejectsDifferentSurroundingText() {
        flow.recordCommit("学习", EditorSnapshot("继续学习"), true)
        assertTrue(undo("开始学习").effects.isEmpty())
    }
    @Test fun contextOnlyEditorUndoAcceptsSameSnapshot() {
        flow.recordCommit("学习", EditorSnapshot("继续学习"), true)
        assertEquals("学习", undo("继续学习").effects.single().text)
    }
    @Test fun selectedTextCannotBeDeletedByUndo() {
        flow.recordCommit("学习", EditorSnapshot("", 12), true)
        assertTrue(undo("学习", 12, false).effects.isEmpty())
    }
    @Test fun sensitiveCommitAndResetForgetHistory() {
        flow.recordCommit("学习", EditorSnapshot("学习"), true)
        flow.recordCommit("private", EditorSnapshot("private"), false)
        assertEquals(0, flow.committedLength)
        flow.recordCommit("学习", EditorSnapshot("学习"), true)
        flow.reset(); assertTrue(undo("学习").effects.isEmpty())
    }
    @Test fun wordDeletionUsesSharedUnicodeTailWithoutNativeCounts() {
        val transition = flow.edit(KeyboardEditCommand.DELETE_WORD, EditorSnapshot("learn world "))
        assertEquals("world ", transition.effects.single().text)
        assertEquals(InputEffectKind.DELETE_TEXT, transition.effects.single().kind)
        assertEquals("。", flow.edit(KeyboardEditCommand.DELETE_WORD, EditorSnapshot("学习。")).effects.single().text)
    }
    @Test fun wordDeletionWithSelectionUsesHostSelectionDelete() {
        val transition = flow.edit(KeyboardEditCommand.DELETE_WORD, EditorSnapshot("before", selectionCollapsed = false))
        assertEquals(InputEffectKind.DELETE_BACKWARD, transition.effects.single().kind)
    }
    @Test fun wordDeletionClearsPendingEncodingInsteadOfDeletingHostText() {
        flow.type("a", InputOptions())
        val transition = flow.edit(KeyboardEditCommand.DELETE_WORD, EditorSnapshot("host"))
        assertEquals(InputEffectKind.SEARCH_PINYIN, transition.effects.single().kind)
        assertEquals("", state.session.composition)
    }
    @Test fun deletionAndBackspaceInvalidateUndoHistory() {
        flow.recordCommit("word", EditorSnapshot("word"), true)
        flow.backspace()
        assertTrue(undo("word").effects.isEmpty())
        flow.recordCommit("word", EditorSnapshot("word"), true)
        flow.edit(KeyboardEditCommand.DELETE_WORD, EditorSnapshot("word"))
        assertTrue(undo("word").effects.isEmpty())
    }
    @Test fun cursorMovesWithinCompositionBeforeMovingHost() {
        flow.type("a", InputOptions()); flow.type("b", InputOptions())
        assertEquals(InputEffectKind.RENDER, flow.moveCursor(-1).effects.single().kind)
        assertEquals(1, state.session.cursor)
        flow.clearComposition()
        val move = flow.moveCursor(-1).effects.single()
        assertEquals(InputEffectKind.MOVE_CURSOR, move.kind); assertEquals(-1, move.amount)
    }
    @Test fun englishCursorMoveFinishesWordBeforeHostMovement() {
        flow.changeMode(KeyboardMode.ENGLISH); flow.type("a", InputOptions())
        val transition = flow.moveCursor(-1)
        assertEquals(listOf(InputEffectKind.INVALIDATE, InputEffectKind.COMMIT, InputEffectKind.MOVE_CURSOR),
            transition.effects.map { it.kind })
        assertEquals("a", transition.effects[1].text); assertEquals("", state.englishWord)
        assertTrue(flow.moveCursor(0).effects.isEmpty())
    }
    @Test fun supplementaryCharactersRetainOriginalTextForNativeDeletion() {
        flow.recordCommit("😀", EditorSnapshot("😀"), true)
        assertEquals(2, flow.committedLength)
        assertEquals("😀", undo("😀").effects.single().text)
    }
    @Test fun wordDeletionKeepsCombiningMarksAndEmojiSequencesWhole() {
        for ((source, tail) in listOf(
            "learn café" to "café", "learn cafe\u0301" to "cafe\u0301",
            "text 👨‍👩‍👧" to "👨‍👩‍👧", "text 👍🏽" to "👍🏽",
            "text 🇨🇳" to "🇨🇳", "text 1️⃣" to "1️⃣", "hello。  " to "  ",
            "text 𐐀𐐁" to "𐐀𐐁", "text 𝟙𝟚" to "𝟙𝟚"
        )) assertEquals(tail, KeyboardPolicy.deleteWord(source), source)
    }
    @Test fun wordDeletionHandlesEmptyAndWhitespaceOnlyContexts() {
        assertEquals("", KeyboardPolicy.deleteWord(""))
        assertEquals(" \n\t", KeyboardPolicy.deleteWord(" \n\t"))
        assertEquals("word\u00a0", KeyboardPolicy.deleteWord("word\u00a0"))
    }
}
