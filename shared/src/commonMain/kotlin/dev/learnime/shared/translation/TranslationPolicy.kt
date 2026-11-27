package dev.learnime.shared

/** Model identifiers from Android and iOS map to the same capabilities. */
object TranslationModels {
    fun canonical(model: String): String = when (model.lowercase()) {
        "opus", "opus_tiny", "opus-mt-tiny-zho-eng" -> "opus"
        else -> "m2m100"
    }
    fun supports(model: String, direction: TranslationDirection): Boolean =
        canonical(model) == "m2m100" || direction == TranslationDirection.CHINESE_TO_ENGLISH
    fun required(model: String, direction: TranslationDirection, allowSwitch: Boolean): String =
        if (supports(model, direction) || !allowSwitch) canonical(model) else "m2m100"
    fun direction(context: String, model: String): TranslationDirection =
        if (supports(model, TranslationDirection.ENGLISH_TO_CHINESE) &&
            TranslationText.accepts(TranslationText.sentence(context), TranslationDirection.ENGLISH_TO_CHINESE))
            TranslationDirection.ENGLISH_TO_CHINESE else TranslationDirection.CHINESE_TO_ENGLISH
}

data class TranslationRequest(
    val revision: Long, val source: String, val direction: TranslationDirection,
    val model: String, val delayMillis: Long, val enabled: Boolean,
)

/** Dedupe includes direction, model and enabled state. Only the current request may publish a result. */
class TranslationPolicy {
    private var revision = 0L
    private var current: TranslationRequest? = null

    fun update(context: String, enabled: Boolean, direction: TranslationDirection,
        model: String, allowModelSwitch: Boolean): TranslationRequest? {
        val selected = TranslationModels.required(model, direction, allowModelSwitch)
        val source = if (enabled && TranslationModels.supports(selected, direction))
            TranslationText.sentence(context).takeIf { TranslationText.accepts(it, direction) }.orEmpty() else ""
        val previous = current
        if (previous?.source == source && previous.direction == direction && previous.model == selected &&
            previous.enabled == enabled) return null
        return TranslationRequest(++revision, source, direction, selected,
            if (source.lastOrNull() in listOf('。', '！', '？', '!', '?', '.')) 150L else 700L, enabled).also { current = it }
    }

    fun isCurrent(request: TranslationRequest): Boolean = current == request && revision == request.revision
    fun reset() { revision++; current = null }
}
