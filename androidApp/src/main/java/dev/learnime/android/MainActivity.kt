package dev.learnime.android

import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var ui: MaterialUi
    private lateinit var status: TextView
    private lateinit var setupStatus: TextView
    private lateinit var enableButton: MaterialButton
    private lateinit var chooseButton: MaterialButton
    private var prepareJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        ui = MaterialUi(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        window.navigationBarColor = ui.surface
        val page = column(24)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ui.iconButton(R.drawable.ic_keyboard, "对照英语", ui.primaryContainer, ui.onPrimaryContainer) {}.apply {
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        header.addView(ui.text("对照英语", 20f, medium = true).apply { setPadding(ui.dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ui.text("离线 · 私密", 12f, ui.onSecondaryContainer, true).apply {
            background = ui.rounded(ui.secondaryContainer, 20)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        })
        page.addView(header)
        page.addView(ui.text("让每次输入，\n都成为练习。", 32f, medium = true).apply {
            setLineSpacing(ui.dp(4).toFloat(), 1f)
        }, spaced(top = 28))
        page.addView(ui.text("中文和英文，双向练习。\n停顿片刻，键盘就会呈现翻译对照。", 16f, ui.muted).apply {
            setLineSpacing(ui.dp(5).toFloat(), 1f)
        }, spaced(top = 12))

        val preview = column(20)
        preview.addView(ui.text("中 → EN    /    对照示例", 12f, ui.onPrimaryContainer, true))
        preview.addView(ui.text("今天也要保持好奇心。", 17f, ui.onPrimaryContainer), spaced(top = 16))
        preview.addView(ui.text("Stay curious today, too.", 22f, ui.onPrimaryContainer, true), spaced(top = 8))
        page.addView(ui.card(ui.primaryContainer).apply { addView(preview) }, spaced(top = 24))

        val setup = column(20)
        setup.addView(ui.text("开始使用", 20f, medium = true))
        setupStatus = ui.text("", 14f, ui.muted)
        setup.addView(setupStatus, spaced(top = 8))
        enableButton = ui.button("1   启用学习键盘", ui.primary, ui.onPrimary, 28, 15f) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        setup.addView(enableButton, spaced(top = 20, height = ui.dp(52)))
        chooseButton = ui.button("2   设为当前键盘", ui.secondaryContainer, ui.onSecondaryContainer, 28, 15f) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        setup.addView(chooseButton, spaced(top = 10, height = ui.dp(52)))
        status = ui.text("正在准备离线翻译…", 12f, ui.muted)
        setup.addView(status, spaced(top = 16))
        page.addView(ui.card().apply { addView(setup) }, spaced(top = 16))

        val modelSettings = column(20)
        modelSettings.addView(ui.text("翻译模型", 20f, medium = true))
        val modelDescription = ui.text("", 13f, ui.muted)
        val models = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val selectedModel = TranslationModel.selected(this)
        fun description(model: TranslationModel) {
            modelDescription.text = when (model) {
                TranslationModel.M2M100 -> "中文 ⇄ 英文 · M2M100 418M\n模型较大，首次加载和翻译需要更久。"
                TranslationModel.OPUS_TINY -> "中文 → 英文 · OPUS-MT tiny\n英文译中文使用 M2M100。"
            }
        }
        TranslationModel.entries.forEach { model ->
            models.addView(ui.button(model.label, ui.secondaryContainer, ui.onSecondaryContainer, 24, 12f) {}.apply {
                id = if (model == TranslationModel.M2M100) R.id.model_m2m else R.id.model_opus
                isCheckable = true
                backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ui.primaryContainer, ui.high))
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ui.onPrimaryContainer, ui.muted)))
                text = if (model == selectedModel) "✓ ${model.label}" else model.label
            }, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        }
        models.check(if (selectedModel == TranslationModel.M2M100) R.id.model_m2m else R.id.model_opus)
        models.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                val model = if (id == R.id.model_m2m) TranslationModel.M2M100 else TranslationModel.OPUS_TINY
                TranslationModel.select(this, model)
                TranslationModel.entries.forEach { option ->
                    models.findViewById<MaterialButton>(if (option == TranslationModel.M2M100) R.id.model_m2m else R.id.model_opus)
                        .text = if (option == model) "✓ ${option.label}" else option.label
                }
                description(model)
                prepareModel(model)
            }
        }
        description(selectedModel)
        modelSettings.addView(models, spaced(top = 16))
        modelSettings.addView(modelDescription, spaced(top = 12))
        page.addView(ui.card().apply { addView(modelSettings) }, spaced(top = 16))

        page.addView(ui.text("试着输入一句", 20f, medium = true), spaced(top = 28))
        page.addView(ui.text("输入中文或英文，在键盘上查看翻译对照。", 14f, ui.muted), spaced(top = 8))
        val input = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "今天想说些什么？"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(ui.dp(16).toFloat(), ui.dp(16).toFloat(), ui.dp(16).toFloat(), ui.dp(16).toFloat())
            addView(TextInputEditText(context).apply {
                id = R.id.practice_input
                textSize = 17f
                minLines = 3
                maxLines = 5
                gravity = Gravity.TOP or Gravity.START
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        page.addView(input, spaced(top = 16))
        page.addView(ui.text("翻译在本机完成，输入内容留在你的设备上。", 12f, ui.muted).apply {
            gravity = Gravity.CENTER
        }, spaced(top = 20))
        val scroll = ScrollView(this).apply {
            id = R.id.practice_scroll
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(ui.surface)
            addView(page, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(ui.surface)
            addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        prepareModel(selectedModel)
    }

    private fun prepareModel(model: TranslationModel) {
        prepareJob?.cancel()
        status.text = "正在准备 ${model.label}…"
        status.setTextColor(ui.muted)
        prepareJob = scope.launch {
            try {
                ModelStore.prepareSelected(applicationContext, model)
                status.text = "${model.label} 文件已就绪"
                status.setTextColor(ui.primary)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status.text = "翻译准备失败，请重新打开应用重试"
                status.setTextColor(ui.error)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::setupStatus.isInitialized) return
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') == packageName
        setupStatus.text = when {
            selected -> "已准备好，打开输入框就能开始练习。"
            enabled -> "键盘已启用，再将它设为当前键盘。"
            else -> "两步设置，开始你的日常语言练习。"
        }
        enableButton.text = if (enabled) "✓   键盘已启用" else "1   启用学习键盘"
        chooseButton.text = if (selected) "✓   当前正在使用" else "2   设为当前键盘"
    }

    private fun column(padding: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(padding), ui.dp(padding), ui.dp(padding), ui.dp(padding))
    }

    private fun spaced(top: Int = 0, height: Int = -2) =
        LinearLayout.LayoutParams(-1, height).apply { topMargin = ui.dp(top) }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
