package dev.learnime.android

import android.content.Context

internal enum class TranslationModel(val label: String) {
    M2M100("M2M100 418M"),
    OPUS_TINY("OPUS-MT tiny");

    companion object {
        fun selected(context: Context): TranslationModel =
            entries.firstOrNull { it.name == context.getSharedPreferences("translation", Context.MODE_PRIVATE)
                .getString("model", null) } ?: M2M100

        fun select(context: Context, model: TranslationModel) {
            context.getSharedPreferences("translation", Context.MODE_PRIVATE).edit().putString("model", model.name).apply()
        }
    }
}
