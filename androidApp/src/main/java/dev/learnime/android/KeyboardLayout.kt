package dev.learnime.android

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.roundToInt

/** Android view geometry. Factories supply the platform skin and input callbacks. */
internal object AndroidKeyboardLayout {
    fun nine(
        parent: LinearLayout, spellings: LinearLayout, rows: List<List<String>>, height: Int,
        key: (Int, Int, String) -> View, side: List<View>, bottom: List<View>, left: View, right: View
    ) {
        fun dp(value: Int) = (value * parent.resources.displayMetrics.density).roundToInt()
        fun line() = LinearLayout(parent.context).apply { isBaselineAligned = false }
        fun column() = line().apply { orientation = LinearLayout.VERTICAL }
        fun params(width: Int, weight: Float = 0f) =
            LinearLayout.LayoutParams(width, height - dp(4), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        val body = line()
        body.addView(ScrollView(parent.context).apply {
            isVerticalScrollBarEnabled = false; addView(spellings)
        }, LinearLayout.LayoutParams(dp(52), height * 3))
        val center = column()
        rows.forEachIndexed { row, labels ->
            val line = line()
            labels.forEachIndexed { col, label -> line.addView(key(row, col, label), params(0, 1f)) }
            center.addView(line, LinearLayout.LayoutParams(-1, height))
        }
        body.addView(center, LinearLayout.LayoutParams(0, -2, 1f))
        val sidebar = column()
        side.forEach { sidebar.addView(it, params(-1)) }
        body.addView(sidebar, LinearLayout.LayoutParams(dp(52), -2))
        parent.addView(body)
        val footer = line()
        footer.addView(left, params(dp(48)))
        val footerCenter = line()
        bottom.forEachIndexed { index, view -> footerCenter.addView(view, params(0, if (index == 2) 3.5f else 1f)) }
        footer.addView(footerCenter, LinearLayout.LayoutParams(0, height, 1f))
        footer.addView(right, params(dp(48)))
        parent.addView(footer, LinearLayout.LayoutParams(-1, height))
    }

    fun row(parent: LinearLayout, labels: List<String>, height: Int, key: (String) -> View) {
        fun dp(value: Int) = (value * parent.resources.displayMetrics.density).roundToInt()
        val row = LinearLayout(parent.context).apply {
            isBaselineAligned = false
            if (labels.size == 9 && labels.first() != "⇧" && labels.first() != "分词")
                setPadding(dp(17), 0, dp(17), 0)
        }
        labels.forEach { label ->
            val weight = when {
                label == "空格" -> 4f
                label in listOf("⇧", "分词", "⌫") && labels.size == 9 -> 1.4f
                else -> 1f
            }
            row.addView(key(label).apply { if (label.isEmpty()) visibility = View.INVISIBLE },
                LinearLayout.LayoutParams(0, height - dp(4), weight).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, height))
    }
}
