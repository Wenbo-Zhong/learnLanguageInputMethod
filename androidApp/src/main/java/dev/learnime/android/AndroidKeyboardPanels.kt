package dev.learnime.android

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.learnime.shared.KeyboardSymbols

/** Tool presentation dispatches editor actions without owning an input connection. */
internal class AndroidKeyboardPanels(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val voice: AndroidVoiceInput,
    private val row: (LinearLayout, List<String>, Int, (String) -> Unit) -> Unit,
    private val literal: (String) -> Unit,
    private val edit: (String) -> Unit,
) {
    private var popup: PopupWindow? = null

    fun show(name: String, anchor: View, ui: MaterialUi) {
        val panel = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL; setPadding(8.dp, 8.dp, 8.dp, 8.dp) }
        val header = LinearLayout(ui.context)
        header.addView(ui.text(name, 18f, ui.primary, true), LinearLayout.LayoutParams(0, 36.dp, 1f))
        header.addView(ui.button("关闭", ui.high, ui.onSurface, 10, 12f) { close() }, LinearLayout.LayoutParams(52.dp, 36.dp))
        panel.addView(header)
        val window = PopupWindow(panel, anchor.width.coerceAtLeast(280.dp), -2, false).apply {
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED; isOutsideTouchable = true
            setBackgroundDrawable(ui.rounded(ui.high, 16)); elevation = 12.dp.toFloat()
            setOnDismissListener { voice.stop() }
        }
        popup = window
        when (name) {
            "剪贴板" -> clipboard(panel, ui)
            "编辑" -> {
                row(panel, listOf("全选", "复制", "剪切", "粘贴"), 44.dp, edit)
                row(panel, listOf("←", "→", "按词删除", "撤销"), 44.dp, edit)
            }
            "符号", "表情" -> symbols(panel, ui, name == "表情")
            "语音" -> voice.render(panel, ui)
        }
        val location = IntArray(2); anchor.getLocationOnScreen(location)
        panel.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        window.height = panel.measuredHeight
        window.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, location[0],
            minOf(location[1], context.resources.displayMetrics.heightPixels - panel.measuredHeight - 32.dp).coerceAtLeast(0))
    }

    fun close() { popup?.dismiss(); popup = null; voice.stop() }

    private fun clipboard(panel: LinearLayout, ui: MaterialUi) {
        val history = ClipboardHistory(context)
        val body = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ui.text("未固定文字保留 1 小时 · 长按固定或删除", 12f, ui.muted))
        panel.addView(ScrollView(ui.context).apply { addView(body) }, LinearLayout.LayoutParams(-1, 200.dp))
        fun refresh() {
            body.removeAllViews()
            history.entries().forEach { entry ->
                body.addView(ui.button((if (entry.pinned) "📌 " else "") + entry.text, ui.low, ui.onSurface, 10, 14f) {
                    literal(entry.text); close()
                }.apply {
                    setOnLongClickListener {
                        val actions = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }
                        val menu = PopupWindow(actions, 150.dp, -2, false).apply {
                            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED; isOutsideTouchable = true
                            setBackgroundDrawable(ui.rounded(ui.high, 12))
                        }
                        actions.addView(ui.button(if (entry.pinned) "取消固定" else "固定", ui.high, ui.onSurface) { history.pin(entry); menu.dismiss(); refresh() })
                        actions.addView(ui.button("删除", ui.high, ui.error) { history.delete(entry); menu.dismiss(); refresh() })
                        menu.showAsDropDown(this); true
                    }
                }, LinearLayout.LayoutParams(-1, 44.dp))
            }
            if (body.childCount == 0) body.addView(ui.text("暂无剪贴板历史", 16f, ui.muted))
        }
        refresh()
    }

    private fun symbols(panel: LinearLayout, ui: MaterialUi, emoji: Boolean) {
        val groups = KeyboardSymbols.categories(emoji)
        val groupScroll = HorizontalScrollView(ui.context)
        val tabs = LinearLayout(ui.context); groupScroll.addView(tabs); panel.addView(groupScroll)
        val body = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(ui.context).apply { addView(body) }, LinearLayout.LayoutParams(-1, 160.dp))
        val recentKey = if (emoji) "recentEmoji" else "recentSymbols"
        fun use(value: String) {
            literal(value)
            preferences.edit().putString(recentKey,
                KeyboardSymbols.remember(value, preferences.getString(recentKey, "").orEmpty())).apply()
        }
        fun render(category: String) {
            body.removeAllViews()
            val values = KeyboardSymbols.values(emoji, category, preferences.getString(recentKey, "").orEmpty())
            values.chunked(6).forEach { line -> row(body, line, 44.dp) { use(it) } }
            if (values.isEmpty()) body.addView(ui.text("暂无最近使用", 14f, ui.muted))
        }
        groups.forEach { label -> tabs.addView(ui.button(label, ui.high, ui.onSurface, 10, 13f) { render(label) }, LinearLayout.LayoutParams(60.dp, 36.dp)) }
        if (emoji) {
            val categories = LinearLayout(ui.context)
            panel.addView(categories, 1)
            panel.addView(ui.text("按关键词筛选", 12f, ui.muted), 1)
            KeyboardSymbols.keywords().forEach { keyword ->
                categories.addView(ui.button(keyword, ui.high, ui.onSurface, 10, 12f) {
                    val matches = KeyboardSymbols.keywordValues(keyword)
                    body.removeAllViews(); matches.chunked(6).forEach { line -> row(body, line, 44.dp) { use(it) } }
                }, LinearLayout.LayoutParams(0, 34.dp, 1f))
            }
        }
        render(if (emoji) "开心" else "中文")
    }

    private val Int.dp get() = (this * context.resources.displayMetrics.density + 0.5f).toInt()
}
