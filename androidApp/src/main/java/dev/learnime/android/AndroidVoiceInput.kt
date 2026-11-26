package dev.learnime.android

import android.content.Context
import android.content.pm.PackageManager
import android.widget.EditText
import android.widget.LinearLayout
import kotlinx.coroutines.*

/** Owns recording preparation, callbacks and native resources for one keyboard editor. */
internal class AndroidVoiceInput(
    private val context: Context,
    private val scope: CoroutineScope,
    private val editorEpoch: () -> Long,
    private val allowed: () -> Boolean,
    private val openSettings: () -> Unit,
    private val commit: (String) -> Unit,
) {
    private var job: Job? = null
    private var service: org.vosk.android.SpeechService? = null
    private var model: org.vosk.Model? = null
    private var recognizer: org.vosk.Recognizer? = null
    private var generation = 0

    fun render(panel: LinearLayout, ui: MaterialUi) {
        val result = EditText(ui.context).apply {
            hint = "识别结果可修改后上屏"; minLines = 2; maxLines = 3
            showSoftInputOnFocus = false
        }
        val state = ui.text("中文离线识别 · 录音不会保存", 12f, ui.muted)
        panel.addView(state); panel.addView(result, LinearLayout.LayoutParams(-1, 90.dp))
        val buttons = LinearLayout(ui.context)
        panel.addView(buttons, LinearLayout.LayoutParams(-1, 44.dp))
        val start = ui.button("开始", ui.primaryContainer, ui.onPrimaryContainer, 10, 14f) {}
        buttons.addView(start, LinearLayout.LayoutParams(0, -1, 1f))
        buttons.addView(ui.button("停止", ui.high, ui.onSurface, 10, 14f) {
            stop(); state.text = "已停止，可编辑并上屏"
        }, LinearLayout.LayoutParams(0, -1, 1f))
        buttons.addView(ui.button("上屏", ui.primary, ui.onPrimary, 10, 14f) {
            result.text.toString().takeIf { it.isNotBlank() }?.let(commit)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        start.setOnClickListener {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                state.text = "请在键盘设置中开启麦克风权限"; openSettings(); return@setOnClickListener
            }
            stop()
            val request = generation
            val epoch = editorEpoch()
            fun valid() = request == generation && epoch == editorEpoch() && allowed()
            state.text = "正在准备离线模型…"
            job = scope.launch {
                var loadedModel: org.vosk.Model? = null
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        val directory = java.io.File(context.noBackupFilesDir, "vosk-small-cn-0.22")
                        suspend fun copy(path: String, target: java.io.File) {
                            currentCoroutineContext().ensureActive()
                            val children = context.assets.list(path).orEmpty()
                            if (children.isEmpty()) {
                                target.parentFile?.mkdirs()
                                context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
                            } else { target.mkdirs(); children.forEach { copy("$path/$it", java.io.File(target, it)) } }
                        }
                        if (!java.io.File(directory, ".ready").exists()) {
                            copy("extras/vosk-cn", directory)
                            java.io.File(directory, ".ready").writeText("0.22")
                        }
                        org.vosk.Model(directory.absolutePath).also { loadedModel = it }
                    }
                    ensureActive()
                    if (!valid()) return@launch
                    model = loaded; loadedModel = null
                    val recordingRecognizer = org.vosk.Recognizer(loaded, 16000f).also { recognizer = it }
                    val recording = org.vosk.android.SpeechService(recordingRecognizer, 16000f).also { service = it }
                    var committed = ""
                    fun extract(json: String, field: String): String =
                        runCatching { org.json.JSONObject(json).optString(field).replace(" ", "") }.getOrDefault("")
                    recording.startListening(object : org.vosk.android.RecognitionListener {
                        override fun onPartialResult(hypothesis: String) {
                            if (valid()) result.setText(committed + extract(hypothesis, "partial"))
                        }
                        override fun onResult(hypothesis: String) {
                            if (valid()) { committed += extract(hypothesis, "text"); result.setText(committed) }
                        }
                        override fun onFinalResult(hypothesis: String) {
                            if (valid()) { committed += extract(hypothesis, "text"); result.setText(committed); state.text = "识别完成，可修改后上屏" }
                        }
                        override fun onError(exception: Exception) { if (valid()) state.text = "识别失败：${exception.message}" }
                        override fun onTimeout() { if (valid()) state.text = "录音已结束" }
                    }, 60000)
                    state.text = "正在听… 最长 60 秒"; result.setText("")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (valid()) { state.text = "无法开始录音：${e.message}"; stop() } }
                finally { loadedModel?.close() }
            }
        }
    }

    fun stop() {
        generation++; job?.cancel(); job = null
        service?.cancel(); service?.shutdown(); service = null
        recognizer?.close(); recognizer = null
        model?.close(); model = null
    }

    private val Int.dp get() = (this * context.resources.displayMetrics.density + 0.5f).toInt()
}
