package dev.learnime.shared

enum class KeyboardEditCommand { DELETE_WORD, UNDO }
data class EditorSnapshot(val context: String, val position: Long = -1, val selectionCollapsed: Boolean = true)

/** Native hosts report successful commits; shared state decides whether they can be undone. */
internal class KeyboardEditorState {
    private var text = ""
    private var anchor = EditorSnapshot("")
    val committedLength get() = text.length

    fun reset() { text = ""; anchor = EditorSnapshot("") }
    fun record(text: String, snapshot: EditorSnapshot, remember: Boolean) {
        reset()
        if (!remember || text.isEmpty() || !snapshot.selectionCollapsed) return
        this.text = text; anchor = snapshot
    }
    fun edit(command: KeyboardEditCommand, snapshot: EditorSnapshot): InputTransition {
        if (command == KeyboardEditCommand.DELETE_WORD) {
            reset()
            return if (!snapshot.selectionCollapsed)
                InputTransition(listOf(InputEffect(InputEffectKind.DELETE_BACKWARD)))
            else delete(KeyboardPolicy.deleteWord(snapshot.context))
        }
        val valid = text.isNotEmpty() && snapshot.selectionCollapsed && snapshot.context.endsWith(text) &&
            if (anchor.position >= 0) snapshot.position == anchor.position else snapshot.context == anchor.context
        if (!valid) { reset(); return InputTransition() }
        val result = delete(text)
        reset()
        return result
    }
    private fun delete(text: String): InputTransition =
        if (text.isEmpty()) InputTransition() else InputTransition(listOf(InputEffect(InputEffectKind.DELETE_TEXT, text)))
}
