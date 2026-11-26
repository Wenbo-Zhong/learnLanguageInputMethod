package dev.learnime.android

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import dev.learnime.shared.HandwritingSession
import dev.learnime.shared.KeyboardPolicy
import kotlinx.coroutines.*

/** Android stroke presentation and scheduling around the shared handwriting state. */
internal class AndroidHandwritingInput(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: SharedPreferences,
    private val editorEpoch: () -> Long,
    private val active: () -> Boolean,
    private val output: suspend (String) -> String,
    private val commit: (String) -> Unit,
    private val syncMarked: () -> Unit,
    private val queueEdit: (suspend () -> Unit) -> Unit,
    private val trace: (String) -> Unit,
) {
    private val state = HandwritingSession()
    private val engineDelegate = lazy { HandwritingEngine(context) }
    private val engine by engineDelegate
    private var recognition: Job? = null
    private var marking: Job? = null
    private var board: HandwritingView? = null
    private var choices: LinearLayout? = null
    val word get() = state.marked
    val candidate get() = state.candidate
    val hasStrokes get() = board?.hasStrokes == true
    fun undo() { board?.undo() }

    private fun cancelTasks() {
        recognition?.cancel(); recognition = null
        marking?.cancel(); marking = null
    }
    fun finish() {
        val text = state.finish()
        cancelTasks()
        if (text.isNotEmpty()) commit(text)
        board?.clear(); choices?.removeAllViews()
    }
    fun clear() { state.clear(); cancelTasks(); board?.clear(); choices?.removeAllViews() }
    fun reset() { clear(); board = null; choices = null }
    fun close() {
        reset()
        if (engineDelegate.isInitialized()) CoroutineScope(Dispatchers.Default).launch { engine.close() }
    }

    suspend fun resolve(): Boolean {
        val epoch = editorEpoch()
        val request = state.revision
        val currentBoard = board
        currentBoard?.isEnabled = false
        try {
            // A delimiter waits for recognition, independently of the marking timer.
            recognition?.join()
            if (epoch != editorEpoch() || !state.isCurrent(request)) return false
            if (!state.hasText && currentBoard?.hasStrokes == true) return false
            finish()
            return true
        } finally { currentBoard?.isEnabled = true }
    }

    fun render(panel: LinearLayout, ui: MaterialUi, height: Int) {
        cancelTasks(); state.invalidateRequests()
        val epoch = editorEpoch()
        fun valid(request: Long) = state.isCurrent(request) && epoch == editorEpoch() && active()
        val strip = LinearLayout(ui.context).apply { gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
        choices = strip
        panel.addView(HorizontalScrollView(ui.context).apply { addView(strip) }, LinearLayout.LayoutParams(-1, 42.dp))
        strip.addView(ui.text("写一个字 · 停笔后继续写下一个", 13f, ui.muted))
        val drawing = HandwritingView(ui.context, ui.onSurface,
            preferences.getInt("handwritingThickness", 3).toFloat(),
            started = {
                cancelTasks()
                val previous = state.beginStroke()
                if (previous.isNotEmpty()) { commit(previous); board?.clear() }
                strip.removeAllViews()
            }) { points ->
            val request = state.beginRecognition(points.isNotEmpty())
            cancelTasks(); strip.removeAllViews()
            if (points.isNotEmpty()) recognition = scope.launch {
                delay(120)
                try {
                    val words = engine.recognize(points)
                    val text = words.firstOrNull()?.let { output(it) }.orEmpty()
                    if (!valid(request)) return@launch
                    state.accept(request, text)
                    words.forEach { word ->
                        strip.addView(ui.button(word, if (word == words.firstOrNull()) ui.primaryContainer else Color.TRANSPARENT,
                            ui.onSurface, 10, 24f) {
                            queueEdit {
                                if (valid(request)) {
                                    marking?.cancel()
                                    val selected = output(word)
                                    if (valid(request)) { state.accept(request, selected); finish() }
                                }
                            }
                        }, LinearLayout.LayoutParams(56.dp, -1))
                    }
                    if (words.isNotEmpty()) {
                        marking = scope.launch {
                            delay(KeyboardPolicy.handwritingDelay(preferences.getString("handwritingSpeed", "normal") ?: "normal"))
                            if (!valid(request) || !state.mark(request)) return@launch
                            syncMarked(); board?.clear()
                            trace("handwriting ready revision=$request candidates=${words.size}")
                        }
                    } else strip.addView(ui.text("未识别到文字，请继续书写或清空", 12f, ui.muted))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (valid(request)) strip.addView(ui.text(e.message ?: "手写识别失败", 12f, ui.error)) }
            }
        }
        board = drawing
        panel.addView(drawing.apply {
            background = ui.rounded(ui.lowest, 12); contentDescription = "中文手写画板"
        }, LinearLayout.LayoutParams(-1, height))
    }

    private val Int.dp get() = (this * context.resources.displayMetrics.density + 0.5f).toInt()
}
