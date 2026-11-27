package dev.learnime.android

import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.google.android.material.button.MaterialButton
import dev.learnime.shared.KeyboardMode
import dev.learnime.shared.KeyboardSession

/** Material styling and native gestures; editing decisions are supplied by the service. */
internal class AndroidKeyFactory(
    private val preferences: SharedPreferences, private val session: KeyboardSession,
    private val skin: () -> MaterialUi, private val landscape: () -> Boolean,
    private val keyHeight: () -> Int, private val handwriting: () -> Boolean,
    private val enterLabel: () -> String, private val feedback: (String) -> Unit,
    private val backspace: () -> Unit, private val moveCursor: (Int) -> Unit,
    private val literal: (String) -> Unit, private val options: (View) -> Unit,
    private val preview: (View, String) -> Unit,
) {
    private val ui get() = skin()
    private val Int.dp get() = (this * ui.context.resources.displayMetrics.density + 0.5f).toInt()
    fun button(label: String, action: () -> Unit): MaterialButton {
        val function = label == enterLabel() || label in listOf("⇧", "分词", "⌫", "123", "返回", "空格")
        val fill = when (label) {
            enterLabel() -> ui.primary
            "空格" -> ui.secondaryContainer
            "⇧" -> if (session.uppercase) ui.primaryContainer else ui.high
            else -> if (function) ui.high else ui.lowest
        }
        val ink = when (label) {
            enterLabel() -> ui.onPrimary
            "空格" -> ui.onSecondaryContainer
            "⇧" -> if (session.uppercase) ui.onPrimaryContainer else ui.onSurface
            else -> ui.onSurface
        }
        val icon = when (label) {
            "⇧" -> R.drawable.ic_shift
            "⌫" -> R.drawable.ic_backspace
            "↵" -> R.drawable.ic_enter
            else -> null
        }
        val press = { feedback(label); action() }
        val result = if (icon != null) ui.iconButton(icon, when (label) {
            "⇧" -> if (session.uppercase) "切换小写" else "切换大写"
            "⌫" -> "退格"
            else -> "回车"
        }, fill, ink, press) else ui.button(label, fill, ink, 10,
            if (label.length == 1) (if (landscape()) minOf(preferences.getInt("keyFont", 22), 18)
                else preferences.getInt("keyFont", 22)).toFloat() else 14f, press)
        result.cornerRadius = 10.dp
        if (icon == null && label.length > 1 && label != "空格")
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                result, 10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        if (preferences.getBoolean("keyBorder", false)) {
            result.strokeWidth = 1.dp; result.strokeColor = android.content.res.ColorStateList.valueOf(ui.outline)
        }
        if (label == "⇧") result.isSelected = session.uppercase
        if (label == "⌫") {
            var repeated = false; var downX = 0f; var removed = 0
            val repeat = object : Runnable {
                override fun run() { repeated = true; backspace(); result.postDelayed(this, 65) }
            }
            result.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { repeated = false; downX = event.x; removed = 0; result.postDelayed(repeat, preferences.getInt("longPress", 400).toLong()) }
                    MotionEvent.ACTION_MOVE -> {
                        val count = ((downX - event.x) / 24.dp).toInt().coerceAtLeast(0)
                        if (count > removed) {
                            result.removeCallbacks(repeat); repeated = true
                            repeat(count - removed) { backspace() }; removed = count
                        }
                    }
                    MotionEvent.ACTION_UP -> { result.removeCallbacks(repeat); if (repeated) { result.isPressed = false; return@setOnTouchListener true } }
                    MotionEvent.ACTION_CANCEL -> { result.removeCallbacks(repeat); result.isPressed = false }
                }
                false
            }
        } else if (label == "空格") {
            result.text = if (handwriting()) "手写" else when (session.mode) {
                KeyboardMode.CHINESE_NINE_KEY -> "中文 · 九键"
                KeyboardMode.CHINESE_QWERTY -> "中文 · 26键"
                KeyboardMode.ENGLISH -> "English"
            }
            result.contentDescription = "空格，长按选择语言"; result.id = R.id.keyboard_space_key
            result.setOnLongClickListener { options(result); true }
            var lastX = 0f; var dragging = false; val step = 18.dp.toFloat()
            result.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastX = event.x; dragging = false }
                    MotionEvent.ACTION_MOVE -> {
                        val steps = ((event.x - lastX) / step).toInt()
                        if (steps != 0) { dragging = true; lastX += steps * step; moveCursor(steps); result.isPressed = false }
                    }
                    MotionEvent.ACTION_UP -> if (dragging) return@setOnTouchListener true
                    MotionEvent.ACTION_CANCEL -> { dragging = false; result.isPressed = false }
                }
                false
            }
        } else if (label.length == 1 && label[0].isLetter()) {
            val digit = "qwertyuiop".indexOf(label.lowercase())
            result.setOnLongClickListener {
                val choices = listOf(label.uppercase()) + if (digit >= 0) listOf(((digit + 1) % 10).toString()) else emptyList()
                val content = LinearLayout(ui.context)
                val popup = PopupWindow(content, -2, keyHeight(), false).apply {
                    inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED; isOutsideTouchable = true
                    setBackgroundDrawable(ui.rounded(ui.high, 12)); elevation = 8.dp.toFloat()
                }
                choices.forEach { choice -> content.addView(ui.button(choice, ui.high, ui.onSurface) {
                    literal(choice); popup.dismiss()
                }, LinearLayout.LayoutParams(54.dp, -1)) }
                popup.showAsDropDown(result, 0, -keyHeight() * 2); true
            }
            var held = false
            val hold = Runnable { result.cancelLongPress(); held = result.performLongClick() }
            result.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        held = false; result.postDelayed(hold, preferences.getInt("longPress", 400).toLong())
                        if (preferences.getBoolean("preview", false)) preview(result, label)
                    }
                    MotionEvent.ACTION_UP -> { result.removeCallbacks(hold); if (held) { result.isPressed = false; return@setOnTouchListener true } }
                    MotionEvent.ACTION_CANCEL -> result.removeCallbacks(hold)
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { result.isPressed = true; return@setOnTouchListener true }
                if (event.actionMasked == MotionEvent.ACTION_UP && !held) {
                    result.isPressed = false; result.performClick(); return@setOnTouchListener true
                }
                false
            }
        }
        return result
    }
}
