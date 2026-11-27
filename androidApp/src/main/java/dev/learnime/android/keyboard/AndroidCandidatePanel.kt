package dev.learnime.android

import android.graphics.Color
import android.widget.LinearLayout
import dev.learnime.shared.PinyinCandidate

/** Candidate strip and expanded grid share labels and selection callbacks. */
internal object AndroidCandidatePanel {
    fun render(ui: MaterialUi, strip: LinearLayout, expanded: LinearLayout,
        values: List<PinyinCandidate>, nineKey: Boolean, isExpanded: Boolean, font: Float,
        output: (String) -> String, choose: (Int, PinyinCandidate) -> Unit, forget: (PinyinCandidate) -> Unit) {
        fun dp(value: Int) = (value * ui.context.resources.displayMetrics.density + 0.5f).toInt()
        var line: LinearLayout? = null
        values.forEachIndexed { index, candidate ->
            val detail = candidate.correction.isNotEmpty() && !nineKey
            val text = output(candidate.text)
            val label = if (detail) "$text\n${candidate.correction} ${candidate.spelling}".trim() else text
            if (index < 12) strip.addView(ui.button(label, if (index == 0) ui.primaryContainer else Color.TRANSPARENT,
                if (index == 0) ui.onPrimaryContainer else ui.onSurface, 16, if (detail) 12f else font) {
                choose(index, candidate)
            }.apply {
                maxLines = if (detail) 2 else 1
                setPadding(dp(12), 0, dp(12), 0)
                setOnLongClickListener { forget(candidate); true }
            }, LinearLayout.LayoutParams(-2, -1).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            if (isExpanded) {
                if (index % 3 == 0) {
                    line = LinearLayout(ui.context)
                    expanded.addView(line, LinearLayout.LayoutParams(-1, dp(44)))
                }
                line?.addView(ui.button(label, Color.TRANSPARENT, ui.onSurface, 12,
                    if (detail) 12f else 14f) { choose(index, candidate) }.apply {
                    maxLines = if (detail) 2 else 1
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
    }
}
