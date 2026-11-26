package dev.learnime.android

import android.content.Context
import dev.learnime.shared.TranslationEngine
import dev.learnime.shared.TranslationDirection
import dev.learnime.shared.TranslationModels
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only one model is resident; the other implementation and its files remain available. */
internal class LocalEngine(context: Context) : TranslationEngine {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val opus = OpusEngine(app)
    private val m2m = M2mEngine(app)
    @Volatile private var active: TranslationModel? = null

    val backendLabel: String get() = (active ?: TranslationModel.selected(app)).label

    override suspend fun translate(source: String): String = translate(source, TranslationDirection.CHINESE_TO_ENGLISH)

    override suspend fun translate(source: String, direction: TranslationDirection): String = mutex.withLock {
        // OPUS asset is zho-eng only; reverse translation uses the explicitly capable multilingual model.
        val selected = if (TranslationModels.required(TranslationModel.selected(app).name, direction, true) == "opus")
            TranslationModel.OPUS_TINY else TranslationModel.M2M100
        if (selected != active) {
            when (active) {
                TranslationModel.OPUS_TINY -> opus.close()
                TranslationModel.M2M100 -> m2m.close()
                null -> Unit
            }
            active = selected
        }
        when (selected) {
            TranslationModel.OPUS_TINY -> opus.translate(source)
            TranslationModel.M2M100 -> m2m.translate(source, direction)
        }
    }

    override fun cancel() { opus.cancel(); m2m.cancel() }

    suspend fun close() {
        cancel()
        mutex.withLock {
            opus.close()
            m2m.close()
            active = null
        }
    }
}
