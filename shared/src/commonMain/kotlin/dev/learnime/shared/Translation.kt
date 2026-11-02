package dev.learnime.shared

import kotlinx.coroutines.*

/** Implementations must serialize access to native inference, and support cancellation. */
interface TranslationEngine {
    suspend fun translate(source: String): String
    suspend fun translate(source: String, direction: TranslationDirection): String {
        require(direction == TranslationDirection.CHINESE_TO_ENGLISH) { "此模型不支持英文译中文" }
        return translate(source)
    }
    fun cancel()
}

enum class TranslationDirection { CHINESE_TO_ENGLISH, ENGLISH_TO_CHINESE }

data class TranslationState(
    val source: String = "",
    val english: String = "",
    val status: String = "输入中文，停顿后显示英文",
    val busy: Boolean = false,
)

/** Pure functions are exported to Swift, which owns extension lifecycle and scheduling. */
object TranslationText {
    fun sentence(context: String): String {
        val text = context.takeLast(600).trim()
        if (text.isEmpty()) return ""
        val body = text.trimEnd('。', '！', '？', '!', '?', '.', '\n', '\r')
        val boundary = body.indices.lastOrNull { i ->
            body[i] in "。！？!?\n\r" ||
                (body[i] == '.' && body.getOrNull(i + 1)?.isWhitespace() == true)
        } ?: -1
        return text.substring(boundary + 1).trim().takeLast(160)
    }

    fun accepts(source: String, direction: TranslationDirection): Boolean {
        val chinese = source.any { it in '\u3400'..'\u9fff' || it in '\uf900'..'\ufaff' }
        return when (direction) {
            TranslationDirection.CHINESE_TO_ENGLISH -> chinese
            TranslationDirection.ENGLISH_TO_CHINESE -> !chinese && source.any { it in 'a'..'z' || it in 'A'..'Z' }
        }
    }
}

/** Latest request wins. A native cancellation signal prevents a backlog while typing. */
class TranslationController(
    private val engine: TranslationEngine,
    private val scope: CoroutineScope,
    private val onState: (TranslationState) -> Unit,
) {
    private var job: Job? = null
    private var revision = 0L
    private var lastSource = ""
    private var lastDirection = TranslationDirection.CHINESE_TO_ENGLISH

    fun update(context: String, enabled: Boolean = true,
        direction: TranslationDirection = TranslationDirection.CHINESE_TO_ENGLISH) {
        val source = if (enabled) TranslationText.sentence(context).takeIf {
            TranslationText.accepts(it, direction)
        }.orEmpty() else ""
        if (source == lastSource && direction == lastDirection) return
        lastSource = source
        lastDirection = direction
        val request = ++revision
        job?.cancel()
        engine.cancel()
        if (source.isBlank()) { onState(TranslationState()); return }
        onState(TranslationState(source = source, status = "等待停顿…"))
        job = scope.launch {
            delay(if (source.last() in "。！？!?.") 150L else 700L)
            onState(TranslationState(source = source, status = "正在本地翻译…", busy = true))
            try {
                val english = engine.translate(source, direction).trim()
                ensureActive()
                if (request == revision) onState(TranslationState(source, english,
                    if (english.isBlank()) "模型没有返回译文" else "本地翻译 · 仅供学习"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request == revision) onState(TranslationState(source = source, status = e.message ?: "翻译失败"))
            }
        }
    }

    fun reset() {
        revision++; job?.cancel(); engine.cancel(); lastSource = ""
        onState(TranslationState())
    }
}
