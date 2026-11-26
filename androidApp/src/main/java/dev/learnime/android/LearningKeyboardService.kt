package dev.learnime.android

import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.Intent
import android.content.ClipDescription
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import dev.learnime.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LearningKeyboardService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = KeyboardSession()
    private lateinit var source: TextView
    private lateinit var english: TextView
    private lateinit var status: TextView
    private lateinit var preedit: TextView
    private lateinit var candidates: LinearLayout
    private lateinit var candidateExpand: MaterialButton
    private lateinit var keys: LinearLayout
    private lateinit var translationCard: View
    private lateinit var translationDirection: TextView
    private lateinit var expanded: LinearLayout
    private lateinit var expandedScroll: ScrollView
    private lateinit var spellings: LinearLayout
    private var nineSpellings: LinearLayout? = null
    private var preeditOffsets: List<Int> = emptyList()
    private var expandedCandidates = false
    private var predictions: List<String> = emptyList()
    private var inputScene = "text"
    private var signedNumber = false
    private var decimalNumber = false
    private lateinit var ui: MaterialUi
    private lateinit var engine: LocalEngine
    private lateinit var pinyin: PinyinEngine
    private lateinit var controller: TranslationController
    private var candidateJob: Job? = null
    private var editActionJob: Job? = null
    private var editorEpoch = 0L
    private val candidateCoordinator = CandidateCoordinator()
    private var sensitive = false
    private var languagePopup: PopupWindow? = null
    private val direction get() = if (session.mode == KeyboardMode.ENGLISH)
        TranslationDirection.ENGLISH_TO_CHINESE else TranslationDirection.CHINESE_TO_ENGLISH
    private var supportedLanguage = true
    private val translationAllowed get() = !sensitive && inputScene == "text" && supportedLanguage && preferences.getBoolean("translation", true)
    private val translationHint get() = if (session.mode == KeyboardMode.ENGLISH)
        "输入英文，看看中文怎么说" else "输入中文，看看英文怎么说"
    private var expectedSelection: Int? = null
    private var selectionStart = -1
    private var selectionEnd = -1
    private val preferences by lazy { getSharedPreferences("keyboard", MODE_PRIVATE) }
    private val modelPreferences by lazy { getSharedPreferences("translation", MODE_PRIVATE) }
    private val modelListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "model" && ::controller.isInitialized) {
            controller.reset()
            if (isInputViewShown) updateTranslation()
        }
    }
    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val keyHeight get() = if (landscape) 28.dp else preferences.getInt("keyHeight", 54).dp
    private var englishWord = ""
    private var englishChoices = emptyList<String>()
    private var dictionary: EnglishDictionary? = null
    private var converter: TraditionalConverter? = null
    private val extrasMutex = Mutex()
    private var capsLock = false
    private var lastShift = 0L
    private var lastSpace = 0L
    private var composing = false
    private var composingStart = -1
    private var lastCommit = ""
    private var undoSelection = -1
    private lateinit var keyboardRoot: LinearLayout
    private lateinit var keyboardBody: LinearLayout
    private var handwritingMode = false
    private val handwriting by lazy {
        AndroidHandwritingInput(this, scope, preferences, { editorEpoch }, { handwritingMode },
            ::outputText, ::commit, ::syncComposing, ::queueEdit, ::trace)
    }
    private val handwritingWord get() = handwriting.word
    private val handwritingCandidate get() = handwriting.candidate
    private val voice by lazy {
        AndroidVoiceInput(this, scope, { editorEpoch }, { !sensitive }, ::openSettings) { text ->
            queueEdit { closeTools(); commit(outputText(text)) }
        }
    }
    private val panels by lazy {
        AndroidKeyboardPanels(this, preferences, voice, ::row, ::literal, ::editCommand)
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }
    private val keyboardListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (::keys.isInitialized && isInputViewShown) {
            if (key == "theme") { setInputView(onCreateInputView()); return@OnSharedPreferenceChangeListener }
            if (key == "englishSuggestions" && englishWord.isNotEmpty()) queueEdit { finishEnglish(); renderCandidates() }
            applyLayout(); session.setAutoCorrection(preferences.getBoolean("autoCorrection", true))
            renderKeys(); requestCandidates(); updateTranslation()
        }
    }
    private suspend fun englishDictionary(): EnglishDictionary = withContext(Dispatchers.Default) {
        extrasMutex.withLock { dictionary ?: EnglishDictionary(this@LearningKeyboardService).also { dictionary = it } }
    }
    private suspend fun traditionalConverter(): TraditionalConverter = withContext(Dispatchers.Default) {
        extrasMutex.withLock { converter ?: TraditionalConverter(this@LearningKeyboardService).also { converter = it } }
    }
    private suspend fun outputText(text: String): String {
        if (!preferences.getBoolean("traditional", false) || sensitive) return text
        return withContext(Dispatchers.Default) {
            val value = traditionalConverter()
            value.convert(text)
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = LocalEngine(this)
        pinyin = PinyinEngine.get(this)
        controller = TranslationController(engine, scope) { state ->
            if (::source.isInitialized) {
                source.text = state.source
                source.visibility = if (state.source.isEmpty() || landscape || !translationAllowed) View.GONE else View.VISIBLE
                english.text = state.english.ifEmpty { translationHint }
                english.setTextColor(if (state.english.isEmpty()) ui.muted else ui.primary)
                status.text = if (state.english.isEmpty()) {
                    if (state.source.isEmpty()) "离线对照" else state.status
                } else "${engine.backendLabel} · 本地翻译"
            }
        }
        modelPreferences.registerOnSharedPreferenceChangeListener(modelListener)
        preferences.registerOnSharedPreferenceChangeListener(keyboardListener)
        scope.launch { runCatching { englishDictionary() } }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
    }

    override fun onCreateInputView(): View {
        val config = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or when (preferences.getString("theme", "system")) {
                "light" -> Configuration.UI_MODE_NIGHT_NO
                "dark" -> Configuration.UI_MODE_NIGHT_YES
                else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            }
        }
        val themedContext = DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(createConfigurationContext(config), R.style.Theme_LearnIME))
        ui = MaterialUi(themedContext)
        val padding = 5.dp
        val root = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(ui.container)
        }
        keyboardBody = root
        keyboardRoot = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(ui.container)
            addView(root, LinearLayout.LayoutParams(-1, -2))
        }
        applyLayout()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(padding + bars.left, padding, padding + bars.right, padding + bars.bottom)
            insets
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                ViewCompat.requestApplyInsets(view)
                window?.window?.let { imeWindow ->
                    imeWindow.navigationBarColor = ui.container
                    WindowInsetsControllerCompat(imeWindow, view).isAppearanceLightNavigationBars =
                        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
                }
            }
            override fun onViewDetachedFromWindow(view: View) {}
        })
        val translation = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, if (landscape) 4.dp else 8.dp, 12.dp, if (landscape) 4.dp else 8.dp)
        }
        val translationHeader = LinearLayout(themedContext).apply { gravity = Gravity.CENTER_VERTICAL }
        translationDirection = ui.text("中 → EN", 11f, ui.primary, true)
        translationHeader.addView(translationDirection, LinearLayout.LayoutParams(-2, -2))
        status = ui.text("离线对照", 11f, ui.muted).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(8.dp, 0, 0, 0)
        }
        translationHeader.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        translation.addView(translationHeader)
        translationHeader.visibility = if (landscape) View.GONE else View.VISIBLE
        source = ui.text("", 13f, ui.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            visibility = View.GONE
        }
        translation.addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 5.dp })
        english = ui.text(translationHint, 17f, ui.muted, true).apply {
            maxLines = if (landscape) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
            minHeight = 22.dp
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        translation.addView(english, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (landscape) 0 else 5.dp })
        translationCard = ui.card(ui.low, 16).apply {
            id = R.id.keyboard_translation_card
            addView(translation)
            visibility = if (translationAllowed) View.VISIBLE else View.GONE
        }
        root.addView(translationCard,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(3.dp, 0, 3.dp, 4.dp) })
        preedit = ui.text("", 14f, ui.primary, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(12.dp, 2.dp, 12.dp, 0)
            visibility = View.INVISIBLE
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val offset = layout?.getOffsetForHorizontal(0, event.x - totalPaddingLeft) ?: 0
                    session.moveCursor(preeditOffsets.getOrElse(offset) { session.composition.length })
                    renderCandidates(); performClick()
                }
                true
            }
        }
        root.addView(preedit, LinearLayout.LayoutParams(-1, if (landscape) 16.dp else 22.dp))
        candidates = LinearLayout(themedContext).apply { gravity = Gravity.CENTER_VERTICAL }
        val candidateBar = LinearLayout(themedContext)
        candidateBar.addView(HorizontalScrollView(themedContext).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(candidates)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        candidateExpand = ui.button("展开", ui.high, ui.onSurface, 12, 12f) {
            expandedCandidates = !expandedCandidates; renderCandidates()
        }
        candidateBar.addView(candidateExpand, LinearLayout.LayoutParams(48.dp, -1))
        root.addView(candidateBar, LinearLayout.LayoutParams(-1, if (landscape) 28.dp else 44.dp))
        spellings = LinearLayout(themedContext)
        root.addView(HorizontalScrollView(themedContext).apply {
            isHorizontalScrollBarEnabled = false; addView(spellings)
        }, LinearLayout.LayoutParams(-1, if (landscape) 22.dp else 34.dp))
        expanded = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }
        expandedScroll = ScrollView(themedContext).apply { addView(expanded); visibility = View.GONE }
        root.addView(expandedScroll, LinearLayout.LayoutParams(-1, keyHeight * 4 + if (landscape) 24.dp else 36.dp))
        keys = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keys)
        renderKeys(); renderCandidates(); updateTranslation()
        return keyboardRoot
    }

    private fun button(label: String, action: () -> Unit): MaterialButton {
        val isEnter = label == enterLabel()
        val function = isEnter || label in listOf("⇧", "分词", "⌫", "123", "返回", "空格")
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
            if (label.length == 1) (if (landscape) minOf(preferences.getInt("keyFont", 22), 18)
                else preferences.getInt("keyFont", 22)).toFloat() else 14f, press)
        result.cornerRadius = 10.dp
        if (icon == null && label.length > 1 && label != "空格")
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                result, 10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        if (preferences.getBoolean("keyBorder", false)) {
            result.strokeWidth = 1.dp; result.strokeColor = android.content.res.ColorStateList.valueOf(ui.outline)
        }
        if (label == "空格") result.text = if (session.mode == KeyboardMode.ENGLISH) "English" else "空格"
        if (label == "⇧") result.isSelected = session.uppercase
        if (label == "⌫") {
            var repeated = false
            var downX = 0f
            var removed = 0
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
            result.text = if (handwritingMode) "手写" else when (session.mode) {
                KeyboardMode.CHINESE_NINE_KEY -> "中文 · 九键"
                KeyboardMode.CHINESE_QWERTY -> "中文 · 26键"
                KeyboardMode.ENGLISH -> "English"
            }
            result.contentDescription = "空格，长按选择语言"
            result.id = R.id.keyboard_space_key
            result.setOnLongClickListener { showKeyboardOptions(result); true }
            var lastX = 0f
            var dragging = false
            val step = 18.dp.toFloat()
            result.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastX = event.x; dragging = false }
                    MotionEvent.ACTION_MOVE -> {
                        val steps = ((event.x - lastX) / step).toInt()
                        if (steps != 0) {
                            dragging = true; lastX += steps * step; moveCursor(steps); result.isPressed = false
                        }
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
                val popup = PopupWindow(content, -2, keyHeight, false).apply {
                    inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                    isOutsideTouchable = true
                    setBackgroundDrawable(ui.rounded(ui.high, 12)); elevation = 8.dp.toFloat()
                }
                choices.forEach { choice -> content.addView(ui.button(choice, ui.high, ui.onSurface) {
                    literal(choice); popup.dismiss()
                }, LinearLayout.LayoutParams(54.dp, -1)) }
                popup.showAsDropDown(result, 0, -keyHeight * 2)
                true
            }
            var held = false
            val hold = Runnable { result.cancelLongPress(); held = result.performLongClick() }
            result.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        held = false
                        result.postDelayed(hold, preferences.getInt("longPress", 400).toLong())
                        if (preferences.getBoolean("preview", false)) showKeyPreview(result, label)
                    }
                    MotionEvent.ACTION_UP -> {
                        result.removeCallbacks(hold)
                        if (held) { result.isPressed = false; return@setOnTouchListener true }
                    }
                    MotionEvent.ACTION_CANCEL -> result.removeCallbacks(hold)
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    result.isPressed = true
                    return@setOnTouchListener true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP && !held) {
                    result.isPressed = false; result.performClick(); return@setOnTouchListener true
                }
                false
            }
        }
        return result
    }
    private fun row(parent: LinearLayout, labels: List<String>, height: Int = keyHeight, action: (String) -> Unit) {
        AndroidKeyboardLayout.row(parent, labels, height) { label ->
            if (label == "🌐") languageKey() else button(label) { action(label) }
        }
    }
    private fun chineseMode(): KeyboardMode {
        val saved = preferences.getString("chineseMode", preferences.getString("mode", null))
        return if (saved == KeyboardMode.CHINESE_NINE_KEY.name) KeyboardMode.CHINESE_NINE_KEY
            else KeyboardMode.CHINESE_QWERTY
    }

    private fun changeKeyboardMode(mode: KeyboardMode) = queueEdit {
        if (!resolveHandwriting()) return@queueEdit
        handwritingMode = false
        finishEnglish()
        // Finish pending composition with the same native selection checks as a space.
        if (session.composition.isNotEmpty()) {
            resolveNow(CompositionBoundary.SPACE) { session.space() }
            if (session.composition.isNotEmpty()) return@queueEdit
        }
        invalidateCandidates(); predictions = emptyList(); expandedCandidates = false
        controller.reset(); session.changeMode(mode)
        preferences.edit().putString("mode", mode.name).apply {
            if (mode != KeyboardMode.ENGLISH) putString("chineseMode", mode.name)
        }.apply()
        renderKeys(); renderCandidates(); updateTranslation()
        autoCapitalization()
    }

    private fun languageKey(): View = ui.iconButton(R.drawable.ic_globe, "切换中文和英文") {
        changeKeyboardMode(if (session.mode == KeyboardMode.ENGLISH) chineseMode() else KeyboardMode.ENGLISH)
    }.apply {
        id = R.id.keyboard_language_key
        setOnLongClickListener { showKeyboardOptions(this); true }
    }
    private fun renderKeys() {
        if (!::keys.isInitialized) return
        keys.removeAllViews()
        nineSpellings = null
        if (inputScene == "number" || inputScene == "phone") {
            val extra = if (inputScene == "phone") listOf("+", "*", "#") else
                listOfNotNull(if (signedNumber) "-" else null, if (decimalNumber) "." else null)
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"),
                listOf(extra.firstOrNull() ?: "", "0", "⌫")).forEach { labels ->
                row(keys, labels) { if (it == "⌫") backspace() else if (it.isNotEmpty()) commit(it) }
            }
            row(keys, extra.drop(1) + enterLabel()) { if (it == enterLabel()) enter() else commit(it) }
            return
        }
        addTools(keys)
        if (handwritingMode && !sensitive && inputScene == "text") {
            handwritingPanel(keys)
            return
        }
        if (preferences.getBoolean("numberRow", false) || sensitive) row(keys, "1234567890".map { it.toString() }, if (landscape) 24.dp else 36.dp) { literal(it) }
        when {
            session.symbols -> KeyboardKeymap.symbolRows(session.mode == KeyboardMode.ENGLISH).forEach { letters ->
                row(keys, letters.map { it.toString() }) { literal(it) }
            }
            session.mode == KeyboardMode.CHINESE_NINE_KEY -> {
                renderNineKeys()
                return
            }
            else -> {
                KeyboardKeymap.letterRows().forEach { letters ->
                    row(keys, letters.map { if (session.mode == KeyboardMode.ENGLISH && session.uppercase) it.uppercase() else it.toString() }) { type(it.lowercase()) }
                }
                row(keys, listOf(if (session.mode == KeyboardMode.ENGLISH) "⇧" else "分词") +
                    "zxcvbnm".map { if (session.mode == KeyboardMode.ENGLISH && session.uppercase) it.uppercase() else it.toString() } + "⌫") { key ->
                    when (key) { "⇧" -> queueEdit { shift(); renderKeys() }; "分词" -> type("'"); "⌫" -> backspace(); else -> type(key.lowercase()) }
                }
            }
        }
        val englishMode = session.mode == KeyboardMode.ENGLISH
        val bottomDelete = session.symbols
        row(keys, listOf(if (session.symbols) "返回" else "123", "🌐", if (englishMode) "," else "，", "空格",
            if (bottomDelete) "⌫" else if (englishMode) "." else "。", enterLabel())) { key ->
            when (key) {
                "123", "返回" -> queueEdit { session.toggleSymbols(); renderKeys() }
                "空格" -> space()
                "⌫" -> backspace()
                enterLabel() -> enter()
                else -> literal(key)
            }
        }
        if (inputScene == "email" || inputScene == "url") {
            row(keys, if (inputScene == "email") listOf("@", ".", "_", "-", ".com") else listOf("/", ".", ":", "-", ".com"),
                if (landscape) 28.dp else 36.dp) { literal(it) }
        }
        val tools = LinearLayout(ui.context).apply { gravity = Gravity.CENTER_VERTICAL }
        tools.addView(ui.iconButton(R.drawable.ic_paste, "粘贴") {
            if (!sensitive) {
                val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                clip?.getItemAt(0)?.coerceToText(this)?.let { value -> literal(value.toString()) }
            }
        }.apply { isEnabled = !sensitive; alpha = if (sensitive) 0.38f else 1f }, LinearLayout.LayoutParams(44.dp, -1))
        tools.addView(ui.text("长按空格选择语言", 11f, ui.muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(ui.button(if (englishMode) "'" else "原样", Color.TRANSPARENT, ui.muted, 16, 12f) {
            if (englishMode) literal("'") else queueEdit { commit(session.takeRaw()); requestCandidates() }
        }, LinearLayout.LayoutParams(48.dp, -1))
        if (bottomDelete) tools.addView(ui.button(if (englishMode) "." else "。", Color.TRANSPARENT, ui.muted, 16, 16f) {
            literal(if (englishMode) "." else "。")
        }, LinearLayout.LayoutParams(36.dp, -1))
        keys.addView(tools, LinearLayout.LayoutParams(-1, if (landscape) 20.dp else 36.dp))
    }
    private fun showKeyboardOptions(anchor: View) {
        languagePopup?.dismiss()
        val content = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }
        val popup = PopupWindow(content, 210.dp, -2, false).apply {
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            isOutsideTouchable = true
            setBackgroundDrawable(ui.rounded(ui.high, 12)); elevation = 8.dp.toFloat()
        }
        languagePopup = popup
        content.addView(ui.text("选择语言和中文布局", 14f, ui.onSurface, true).apply {
            setPadding(12.dp, 10.dp, 12.dp, 8.dp)
        }, LinearLayout.LayoutParams(-1, -2))
        listOf("中文 · 九键" to KeyboardMode.CHINESE_NINE_KEY, "中文 · 26键" to KeyboardMode.CHINESE_QWERTY,
            "English" to KeyboardMode.ENGLISH).forEach { (label, mode) ->
            val selected = if (mode == KeyboardMode.ENGLISH) session.mode == mode else chineseMode() == mode
            content.addView(ui.button(if (selected) "● $label" else label, ui.high, ui.onSurface, 12, 14f) {
                popup.dismiss()
                changeKeyboardMode(mode)
            }.apply { contentDescription = label; isSelected = session.mode == mode }, LinearLayout.LayoutParams(-1, 44.dp))
        }
        content.addView(ui.button(if (session.autoCorrection) "关闭自动纠错" else "开启自动纠错", ui.high, ui.onSurface, 12, 14f) {
            popup.dismiss()
            queueEdit {
                session.setAutoCorrection(!session.autoCorrection)
                preferences.edit().putBoolean("autoCorrection", session.autoCorrection).apply()
                requestCandidates()
            }
        }, LinearLayout.LayoutParams(-1, 44.dp))
        content.addView(ui.button("其他输入法", ui.high, ui.onSurface, 12, 14f) {
            popup.dismiss()
            queueEdit {
                session.clearComposition(); invalidateCandidates(); renderCandidates()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
            }
        }, LinearLayout.LayoutParams(-1, 44.dp))
        content.addView(ui.button("键盘设置", ui.high, ui.onSurface, 12, 14f) {
            popup.dismiss(); openSettings()
        }, LinearLayout.LayoutParams(-1, 44.dp))
        val location = IntArray(2); anchor.getLocationOnScreen(location)
        content.measure(View.MeasureSpec.makeMeasureSpec(210.dp, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT,
            location[0].coerceIn(0, (resources.displayMetrics.widthPixels - 210.dp).coerceAtLeast(0)),
            (location[1] - content.measuredHeight).coerceAtLeast(0))
    }
    private fun renderNineKeys() {
        val height = if (landscape) 38.dp else (preferences.getInt("keyHeight", 54) - 2).dp
        nineSpellings = LinearLayout(ui.context).apply { orientation = LinearLayout.VERTICAL }
        val side = listOf("⌫", "清除", "123").map { label ->
            button(label) {
                when (label) {
                    "⌫" -> backspace()
                    "清除" -> queueEdit { session.clearComposition(); requestCandidates() }
                    else -> queueEdit { session.toggleSymbols(); renderKeys(); renderCandidates() }
                }
            }
        }
        AndroidKeyboardLayout.nine(keys, nineSpellings!!, KeyboardKeymap.nineRows(), height,
            key = { rowIndex, column, label ->
                val digit = rowIndex * 3 + column + 1
                ui.button(label, ui.lowest, ui.onSurface, 8, 21f) {
                    feedback(label)
                    type(KeyboardKeymap.nineDigit(rowIndex, column))
                }.apply {
                    contentDescription = if (digit == 1) "拼音分词" else "$digit $label"
                    setOnLongClickListener { literal(digit.toString()); true }
                }
            }, side = side, bottom = listOf(button("，") { literal("，") }, languageKey(),
                button("空格") { space() }, button("。") { literal("。") }),
            left = button("符") { queueEdit { session.toggleSymbols(); renderKeys(); renderCandidates() } },
            right = button(enterLabel()) { enter() })
        if (inputScene == "email" || inputScene == "url")
            row(keys, if (inputScene == "email") listOf("@", ".", "_", "-", ".com") else listOf("/", ".", ":", "-", ".com"),
                if (landscape) 28.dp else 36.dp) { literal(it) }
        val tools = LinearLayout(ui.context).apply { isBaselineAligned = false }
        tools.addView(ui.button("原样", Color.TRANSPARENT, ui.muted, 12, 12f) {
            queueEdit { commit(session.takeRaw()); requestCandidates() }
        }, LinearLayout.LayoutParams(52.dp, 30.dp))
        tools.addView(ui.text("长按空格选择语言 · 左侧选择音节", 11f, ui.muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, 30.dp, 1f))
        tools.addView(ui.iconButton(R.drawable.ic_paste, "粘贴") {
            if (!sensitive) (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                ?.getItemAt(0)?.coerceToText(this)?.let { literal(it.toString()) }
        }.apply { isEnabled = !sensitive }, LinearLayout.LayoutParams(44.dp, 30.dp))
        keys.addView(tools)
        renderNineSpellings()
    }
    private fun renderNineSpellings() {
        val column = nineSpellings ?: return
        column.removeAllViews()
        session.lockedSyllables.forEachIndexed { index, syllable ->
            column.addView(ui.button("${syllable.spelling} ×", ui.primaryContainer, ui.onPrimaryContainer, 8, 14f) {
                queueEdit { session.unlockFrom(index); requestCandidates() }
            }, LinearLayout.LayoutParams(-1, 38.dp))
        }
        session.nextSyllables.forEach { syllable ->
            column.addView(ui.button(syllable.spelling, Color.TRANSPARENT, ui.onSurface, 8, 18f) {
                queueEdit { if (session.lockNext(syllable)) requestCandidates() }
            }.apply { contentDescription = if (syllable.partial) "限定首字母 ${syllable.spelling}" else "锁定音节 ${syllable.spelling}" },
                LinearLayout.LayoutParams(-1, 38.dp))
        }
        if (session.composition.isEmpty()) listOf("，", "。", "？", "！").forEach { punctuation ->
            column.addView(ui.button(punctuation, Color.TRANSPARENT, ui.muted, 8, 18f) { literal(punctuation) },
                LinearLayout.LayoutParams(-1, 38.dp))
        }
    }
    private fun renderCandidates() {
        if (!::preedit.isInitialized) return
        preedit.visibility = if (handwritingMode) View.GONE else View.INVISIBLE
        (candidates.parent?.parent as? View)?.visibility = if (handwritingMode) View.GONE else View.VISIBLE
        (spellings.parent as? View)?.visibility = if (handwritingMode) View.GONE else View.VISIBLE
        if (handwritingMode) {
            expandedScroll.visibility = View.GONE; keys.visibility = View.VISIBLE
            return
        }
        val nine = session.mode == KeyboardMode.CHINESE_NINE_KEY
        val offsets = mutableListOf(0)
        val display = StringBuilder()
        var rawEnd = 0
        if (nine) session.candidates.firstOrNull()?.syllables?.forEach { syllable ->
            if (display.isNotEmpty()) { display.append(' '); offsets.add(rawEnd) }
            syllable.spelling.forEachIndexed { index, c ->
                display.append(c)
                offsets.add(rawEnd + ((syllable.end - rawEnd) * (index + 1) / syllable.spelling.length))
            }
            rawEnd = syllable.end
        }
        session.composition.drop(rawEnd).forEach { c -> display.append(c); offsets.add(++rawEnd) }
        val displayCursor = offsets.indexOfLast { it <= session.cursor }.coerceAtLeast(0)
        offsets.add(displayCursor + 1, session.cursor)
        preeditOffsets = offsets
        preedit.text = display.substring(0, displayCursor) + "│" + display.substring(displayCursor) +
            if (session.needsSegmentation) " · 长拼音，请分段选词" else ""
        session.lockedSyllables.lastOrNull()?.let { locked ->
            preedit.text = SpannableString(preedit.text).apply {
                val end = offsets.indexOfLast { it <= locked.end }.coerceAtLeast(0)
                setSpan(android.text.style.UnderlineSpan(), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val numeric = inputScene in listOf("number", "phone")
        preedit.visibility = when {
            numeric -> View.GONE
            session.composition.isEmpty() -> View.INVISIBLE
            else -> View.VISIBLE
        }
        ((candidates.parent as View).parent as View).visibility = if (numeric) View.GONE else View.VISIBLE
        candidates.removeAllViews()
        if (session.mode == KeyboardMode.ENGLISH && englishWord.isNotEmpty()) {
            preedit.text = "$englishWord│"; preedit.visibility = View.VISIBLE
        }
        spellings.removeAllViews()
        (spellings.parent as View).visibility = when {
            session.mode == KeyboardMode.ENGLISH || nine || numeric -> View.GONE
            else -> View.VISIBLE
        }
        if (session.mode != KeyboardMode.ENGLISH && !nine && !numeric) {
            spellings.addView(ui.button(if (session.autoCorrection) "纠错开" else "纠错关",
                if (session.autoCorrection) ui.secondaryContainer else Color.TRANSPARENT, ui.onSurface, 12, 12f) {
                queueEdit {
                    session.setAutoCorrection(!session.autoCorrection)
                    preferences.edit().putBoolean("autoCorrection", session.autoCorrection).apply()
                    requestCandidates()
                }
            }, LinearLayout.LayoutParams(-2, -1))
            session.lockedSyllables.forEachIndexed { index, syllable ->
                spellings.addView(ui.button("✓ ${syllable.spelling} ×", ui.primaryContainer, ui.onPrimaryContainer, 12, 12f) {
                    queueEdit { session.unlockFrom(index); requestCandidates() }
                }, LinearLayout.LayoutParams(-2, -1))
            }
            if (session.nextSyllables.isNotEmpty()) {
                spellings.addView(ui.text("第${session.lockedSyllables.size + 1}音节", 12f, ui.muted).apply {
                    gravity = Gravity.CENTER; setPadding(8.dp, 0, 8.dp, 0)
                }, LinearLayout.LayoutParams(-2, -1))
            }
            session.nextSyllables.forEach { syllable ->
                val begin = session.lockedSyllables.lastOrNull()?.end ?: 0
                val typed = session.composition.substring(begin, syllable.end).trim('\'')
                spellings.addView(ui.button("${syllable.spelling}（$typed）", Color.TRANSPARENT, ui.onSurface, 12, 12f) {
                    queueEdit { if (session.lockNext(syllable)) requestCandidates() }
                }, LinearLayout.LayoutParams(-2, -1))
            }
        }
        renderNineSpellings()
        expanded.removeAllViews()
        expandedScroll.visibility = if (expandedCandidates && session.candidates.isNotEmpty()) View.VISIBLE else View.GONE
        keys.visibility = if (expandedScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        candidateExpand.visibility = if (session.candidates.isNotEmpty()) View.VISIBLE else View.GONE
        candidateExpand.text = if (expandedCandidates) "收起" else "展开"
        AndroidCandidatePanel.render(ui, candidates, expanded, session.candidates, nine, expandedCandidates,
            preferences.getInt("candidateFont", 20).toFloat(),
            { converter?.takeIf { preferences.getBoolean("traditional", false) }?.convert(it) ?: it },
            ::chooseCandidate, ::forgetCandidate)
        if (session.composition.isNotEmpty() && preferences.getBoolean("mixed", true) && session.lockedSyllables.isEmpty()) {
            val raw = session.composition
            candidates.addView(ui.button(raw, ui.high, ui.onSurface, 12, 18f) {
                queueEdit { if (session.composition == raw) { commit(session.takeRaw()); requestCandidates() } }
            }.apply { contentDescription = "原文 $raw" }, LinearLayout.LayoutParams(-2, -1))
        }
        englishChoices.forEach { word ->
            val expectedWord = englishWord
            val expectedInput = session.composition
            val expectedRevision = session.revision
            candidates.addView(ui.button(word, ui.primaryContainer, ui.onPrimaryContainer, 12, 18f) {
                queueEdit {
                    if (englishWord != expectedWord || session.composition != expectedInput || session.revision != expectedRevision) return@queueEdit
                    if (englishWord.isNotEmpty()) finishEnglish(word)
                    else if (session.composition.isNotEmpty() && session.lockedSyllables.isEmpty()) {
                        session.clearComposition(); commit(word); requestCandidates()
                    }
                    renderCandidates()
                }
            }, LinearLayout.LayoutParams(-2, -1))
        }
        if (session.composition.isEmpty()) predictions.forEach { prediction ->
            candidates.addView(ui.button(prediction, Color.TRANSPARENT, ui.onSurface, 12, 20f) {
                resolve { if (session.composition.isEmpty()) prediction else "" }
            }, LinearLayout.LayoutParams(-2, -1))
        }
        if (session.candidates.isEmpty() && predictions.isEmpty() && englishChoices.isEmpty()) candidates.addView(ui.text("", 12f, ui.muted).apply {
            setPadding(12.dp, 0, 12.dp, 0)
            text = when {
                session.composition.isNotEmpty() && candidateJob?.isActive == true -> "正在生成候选…"
                session.composition.isNotEmpty() -> "暂无候选，可退格修改或点「原样」"
                session.mode == KeyboardMode.CHINESE_NINE_KEY -> "九键拼音"
                session.mode == KeyboardMode.CHINESE_QWERTY -> "全拼 / 简拼 · 空格选首词"
                else -> "English · ⇧ 切换大小写"
            }
        })
    }
    private fun invalidateCandidates() { candidateCoordinator.invalidate(); candidateJob?.cancel(); candidateJob = null }
    private fun requestCandidates() {
        invalidateCandidates()
        val request = candidateCoordinator.snapshot(session)
        val input = request.input
        val mode = request.mode
        val editRevision = request.revision
        val locks = request.locks
        val corrections = request.corrections
        syncComposing()
        trace("search length=${input.length} cursor=${session.cursor} revision=$editRevision locks=${session.lockedSyllables.size} correction=$corrections")
        predictions = emptyList()
        if (mode != KeyboardMode.ENGLISH) englishChoices = emptyList()
        if (input.isEmpty()) {
            expandedCandidates = false
            if (!sensitive && mode != KeyboardMode.ENGLISH && preferences.getBoolean("prediction", true)) candidateJob = scope.launch {
                val history = currentInputConnection?.getTextBeforeCursor(16, 0)?.toString().orEmpty()
                val result = pinyin.predict(history)
                if (candidateCoordinator.isCurrent(request, session)) {
                    predictions = result; candidateJob = null; renderCandidates()
                }
            }
            renderCandidates(); return
        }
        candidateJob = scope.launch {
            try {
                if (preferences.getBoolean("traditional", false) && converter == null)
                    traditionalConverter()
                val result = pinyin.search(input, mode == KeyboardMode.CHINESE_NINE_KEY, locks, corrections)
                val mixedWords = if (!sensitive && locks.isEmpty() && preferences.getBoolean("mixed", true) && input.length <= 20) {
                    val dictionary = englishDictionary()
                    withContext(Dispatchers.Default) {
                        if (mode == KeyboardMode.CHINESE_NINE_KEY) dictionary.nineSuggestions(input)
                        else dictionary.suggestions(input).drop(1).take(3)
                    }
                } else emptyList()
                val shortcut = if (!sensitive && locks.isEmpty()) runCatching {
                    org.json.JSONObject(preferences.getString("shortcuts", "{}")).optString(input)
                }.getOrDefault("") else ""
                if (candidateCoordinator.isCurrent(request, session)) {
                    englishChoices = (listOf(shortcut).filter { it.isNotEmpty() } + mixedWords).distinct()
                    if (session.applyCandidates(input, mode, result, editRevision)) { candidateJob = null; renderCandidates() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (candidateCoordinator.isCurrent(request, session)) { preedit.text = "$input · ${e.message}" } }
        }
        renderCandidates()
    }
    private fun type(value: String) = queueEdit {
        if (handwritingMode) {
            if (!resolveHandwriting()) return@queueEdit
            handwritingMode = false; renderKeys(); renderCandidates()
        }
        if (session.mode == KeyboardMode.ENGLISH && value.length == 1 && (value[0] in 'a'..'z' || value[0] in 'A'..'Z') &&
            !sensitive && inputScene == "text" && preferences.getBoolean("englishSuggestions", true)) {
            englishWord += if (session.uppercase) value.uppercase() else value
            syncComposing()
            if (!capsLock && session.uppercase) { session.toggleShift(); renderKeys() }
            val input = englishWord
            candidateJob?.cancel()
            candidateJob = scope.launch {
                val data = englishDictionary()
                val result = withContext(Dispatchers.Default) { data.suggestions(input) }
                if (englishWord == input) { englishChoices = result; renderCandidates() }
            }
            renderCandidates(); return@queueEdit
        }
        val before = session.composition
        val direct = session.key(value)
        if (direct.isNotEmpty()) {
            commit(direct)
            if (session.mode == KeyboardMode.ENGLISH && !capsLock && session.uppercase) { session.toggleShift(); renderKeys() }
        }
        if (session.composition != before) requestCandidates()
    }
    private fun queueEdit(action: suspend () -> Unit) {
        // Serialize user intent across candidate/JNI suspension; never drop the
        // letter following a pending space. Editor epochs prevent cross-field input.
        val previous = editActionJob
        val epoch = editorEpoch
        editActionJob = scope.launch {
            previous?.join()
            if (epoch == editorEpoch) action()
        }
    }
    private fun resolve(boundary: CompositionBoundary? = null, action: () -> String) =
        queueEdit { resolveNow(boundary, action) }
    private suspend fun resolveNow(boundary: CompositionBoundary?, action: () -> String) {
        val request = candidateCoordinator.snapshot(session)
        val pending = candidateJob
        if (request.input.isNotEmpty()) pending?.join()
        if (!candidateCoordinator.isCurrent(request, session)) return
        val candidate = session.candidates.firstOrNull()
        if (candidate != null && boundary != null &&
            candidateCoordinator.boundary(session, boundary) == BoundaryResolution.SELECT_FIRST) {
            if (!pinyin.select(candidate, !sensitive && preferences.getBoolean("learning", true))) { requestCandidates(); return }
            if (!candidateCoordinator.isCurrent(request, session)) return
        }
        val result = action()
        if (result.isEmpty()) {
            renderCandidates()
            if (session.composition.isNotEmpty())
                preedit.text = preedit.text.toString() + " · 请先选词、修改或点原样"
            return
        }
        commit(outputText(result)); requestCandidates()
    }
    private fun chooseCandidate(index: Int, expected: PinyinCandidate) {
        val candidate = session.candidates.getOrNull(index) ?: return
        if (candidate != expected) return
        val request = candidateCoordinator.snapshot(session)
        queueEdit {
            if (!candidateCoordinator.canChoose(request, session, candidate, index) ||
                !pinyin.select(candidate, !sensitive && preferences.getBoolean("learning", true)) ||
                !candidateCoordinator.canChoose(request, session, candidate, index)) return@queueEdit
            trace("choose token=${candidate.token} index=${candidate.nativeIndex} consumed=${candidate.consumed} learn=${!sensitive}")
            expandedCandidates = false
            commit(outputText(session.choose(index))); requestCandidates()
        }
    }
    private fun moveCursor(steps: Int) = queueEdit {
        finishEnglish()
        if (session.composition.isNotEmpty()) {
            session.moveCursor(session.cursor + steps); renderCandidates()
            trace("move composition cursor=${session.cursor} length=${session.composition.length}")
            return@queueEdit
        }
        val connection = currentInputConnection ?: return@queueEdit
        if (selectionStart < 0) return@queueEdit
        val extracted = connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (extracted == null) {
            val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(steps)) {
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            return@queueEdit
        }
        val text = extracted.text.toString()
        val anchor = if (steps < 0) minOf(selectionStart, selectionEnd) else maxOf(selectionStart, selectionEnd)
        var local = (anchor - extracted.startOffset).coerceIn(0, text.length)
        repeat(kotlin.math.abs(steps)) {
            if (steps < 0 && local > 0) local = text.offsetByCodePoints(local, -1)
            else if (steps > 0 && local < text.length) local = text.offsetByCodePoints(local, 1)
        }
        val position = extracted.startOffset + local
        selectionStart = position; selectionEnd = position
        connection.setSelection(position, position)
        trace("move host position=$position")
    }
    private fun enterLabel(): String {
        val info = currentInputEditorInfo ?: return "↵"
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "↵"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            EditorInfo.IME_ACTION_GO -> "前往"
            else -> "↵"
        }
    }
    private fun literal(value: String) {
        if (handwritingMode) {
            queueEdit { if (resolveHandwriting()) commit(value) }
            return
        }
        if (session.mode == KeyboardMode.ENGLISH) {
            queueEdit { finishEnglish(); commit(value); autoCapitalization(); renderKeys(); renderCandidates() }
            return
        }
        resolve(CompositionBoundary.LITERAL) { session.literal(value) }
    }
    private fun enter() = queueEdit {
        if (handwritingMode) {
            if (!resolveHandwriting()) return@queueEdit
        }
        finishEnglish()
        if (session.composition.isNotEmpty()) resolveNow(CompositionBoundary.ENTER) { session.enter() }
        else {
            val info = currentInputEditorInfo
            val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
            if ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && action !in listOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED))
                currentInputConnection?.performEditorAction(action)
            else commit("\n")
        }
    }
    private fun backspace() = queueEdit {
        if (handwritingMode) {
            if (handwritingWord.isNotEmpty()) {
                cancelComposing(); clearHandwriting()
                return@queueEdit
            }
            if (handwriting.hasStrokes) { handwriting.undo(); return@queueEdit }
        }
        if (englishWord.isNotEmpty()) {
            englishWord = englishWord.dropLast(1); englishChoices = emptyList(); syncComposing(); renderCandidates(); return@queueEdit
        }
        if (session.backspace()) { requestCandidates(); return@queueEdit }
        val selected = currentInputConnection?.getSelectedText(0)
        if (!selected.isNullOrEmpty()) currentInputConnection?.commitText("", 1)
        else currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
        updateTranslation()
    }
    private fun commit(text: String) {
        if (text.isEmpty()) return
        val start = if (composing) composingStart else minOf(selectionStart, selectionEnd)
        expectedSelection = if (start >= 0) start + text.length else null
        currentInputConnection?.commitText(text, 1)
        composing = false; composingStart = -1
        lastCommit = if (sensitive) "" else text; undoSelection = expectedSelection ?: -1
        expectedSelection?.let { selectionStart = it; selectionEnd = it }
        updateTranslation()
    }
    private fun updateTranslation() {
        if (::translationCard.isInitialized) {
            translationCard.visibility = if (translationAllowed) View.VISIBLE else View.GONE
            translationDirection.text = if (direction == TranslationDirection.ENGLISH_TO_CHINESE) "EN → 中" else "中 → EN"
        }
        if (!translationAllowed) { controller.reset(); return }
        val before = currentInputConnection?.getTextBeforeCursor(600, 0)?.toString().orEmpty()
        val raw = handwritingWord.ifEmpty { if (englishWord.isNotEmpty()) englishWord else session.composition }
        controller.update(if (composing && before.endsWith(raw)) before.dropLast(raw.length) else before,
            direction = direction, model = TranslationModel.selected(this).name)
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        languagePopup?.dismiss(); languagePopup = null; closeTools()
        clearHandwriting()
        handwritingMode = false; handwriting.reset()
        cancelComposing()
        englishWord = ""; englishChoices = emptyList(); capsLock = false
        lastCommit = ""; undoSelection = -1; lastSpace = 0L; lastShift = 0L
        editorEpoch++; editActionJob?.cancel(); editActionJob = null
        invalidateCandidates(); session.reset(); controller.reset(); expectedSelection = null
        selectionStart = attribute?.initialSelStart ?: -1; selectionEnd = attribute?.initialSelEnd ?: -1
        val type = attribute?.inputType ?: 0
        val hintedLanguage = attribute?.hintLocales?.takeUnless { it.isEmpty }?.get(0)?.language
        supportedLanguage = hintedLanguage == null || hintedLanguage in listOf("en", "zh")
        val inputClass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = (inputClass == InputType.TYPE_CLASS_TEXT && variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val number = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE
        inputScene = when {
            inputClass == InputType.TYPE_CLASS_PHONE -> "phone"
            inputClass == InputType.TYPE_CLASS_NUMBER -> "number"
            inputClass == InputType.TYPE_CLASS_TEXT && variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> "email"
            inputClass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> "url"
            password -> "password"
            inputClass == InputType.TYPE_CLASS_TEXT -> "text"
            else -> "other"
        }
        signedNumber = type and InputType.TYPE_NUMBER_FLAG_SIGNED != 0
        decimalNumber = type and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
        predictions = emptyList(); expandedCandidates = false
        session.setAutoCorrection(preferences.getBoolean("autoCorrection", true))
        sensitive = password || number || ((attribute?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        val saved = runCatching { KeyboardMode.valueOf(preferences.getString("mode", KeyboardMode.CHINESE_QWERTY.name)!!) }.getOrDefault(KeyboardMode.CHINESE_QWERTY)
        val englishHint = hintedLanguage == "en"
        session.changeMode(if (inputScene != "text" || englishHint) KeyboardMode.ENGLISH
            else if (saved == KeyboardMode.ENGLISH) saved else chineseMode())
        trace("editor scene=$inputScene sensitive=$sensitive action=${enterLabel()}")
        autoCapitalization()
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting); applyLayout(); captureClipboard(); renderKeys(); renderCandidates(); updateTranslation()
    }
    override fun onUpdateSelection(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candidatesStart, candidatesEnd)
        val ownCommit = expectedSelection == newStart && newStart == newEnd
        expectedSelection = null; selectionStart = newStart; selectionEnd = newEnd
        if (!ownCommit && composing && candidatesStart >= 0 && newStart == candidatesEnd && newStart == newEnd) {
            composingStart = candidatesStart
        } else if (!ownCommit && (oldStart != newStart || oldEnd != newEnd) &&
            (session.composition.isNotEmpty() || englishWord.isNotEmpty() || handwritingWord.isNotEmpty() ||
                handwritingCandidate.isNotEmpty() || (handwritingMode && handwriting.hasStrokes))) {
            if ((englishWord.isNotEmpty() || handwritingWord.isNotEmpty()) && composing) {
                // The editor has already moved its selection. Finalize the visible
                // word in place; replacing composing text here would delete it or
                // move the cursor back to the end of the word.
                currentInputConnection?.finishComposingText()
                composing = false; composingStart = -1
            } else cancelComposing()
            clearHandwriting()
            englishWord = ""; englishChoices = emptyList()
            session.clearComposition(); invalidateCandidates(); renderCandidates()
        }
        updateTranslation()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown || event.isCtrlPressed || event.isAltPressed) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> { backspace(); return true }
            KeyEvent.KEYCODE_SPACE -> { space(); return true }
            KeyEvent.KEYCODE_ENTER -> { enter(); return true }
            KeyEvent.KEYCODE_ESCAPE -> {
                queueEdit {
                    cancelComposing(); englishWord = ""; englishChoices = emptyList()
                    clearHandwriting()
                    session.clearComposition(); invalidateCandidates(); renderCandidates()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(1); return true }
        }
        val ch = event.unicodeChar.toChar()
        if (ch.isLetter() && ch.code < 128 || ch in '2'..'9' && session.mode == KeyboardMode.CHINESE_NINE_KEY || ch == '\'') {
            type(if (session.mode == KeyboardMode.ENGLISH) ch.toString() else ch.lowercase())
            return true
        }
        if (ch in ' '..'~' && ch != '\u0000') { literal(ch.toString()); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onFinishInputView(finishingInput: Boolean) {
        languagePopup?.dismiss(); languagePopup = null
        if (englishWord.isNotEmpty()) commit(englishWord)
        finishHandwriting()
        closeTools(); cancelComposing(); englishWord = ""; englishChoices = emptyList()
        editorEpoch++; editActionJob?.cancel(); editActionJob = null
        session.clearComposition(); invalidateCandidates(); controller.reset()
        scope.launch { engine.close() }; super.onFinishInputView(finishingInput)
    }
    override fun onDestroy() {
        modelPreferences.unregisterOnSharedPreferenceChangeListener(modelListener)
        preferences.unregisterOnSharedPreferenceChangeListener(keyboardListener)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        closeTools()
        handwriting.close()
        controller.reset(); scope.cancel()
        CoroutineScope(Dispatchers.Default).launch { engine.close() }
        super.onDestroy()
    }
    private fun syncComposing() {
        val raw = KeyboardPolicy.markedText(englishWord, handwritingWord, session.composition,
            preferences.getBoolean("inline", false), sensitive)
        if (raw.isNotEmpty()) {
            if (!composing) composingStart = minOf(selectionStart, selectionEnd)
            currentInputConnection?.setComposingText(raw, 1); composing = true
            if (composingStart >= 0) expectedSelection = composingStart + raw.length
        } else if (composing) cancelComposing()
    }
    private fun cancelComposing() {
        if (composing) {
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
        }
        composing = false; composingStart = -1
    }
    private suspend fun finishEnglish(value: String = englishWord) {
        if (englishWord.isEmpty()) return
        candidateJob?.cancel(); candidateJob = null
        englishWord = ""; englishChoices = emptyList()
        commit(value)
    }
    private fun shift() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastShift < 350) {
            capsLock = true
            if (!session.uppercase) session.toggleShift()
        } else { capsLock = false; session.toggleShift() }
        lastShift = now
    }
    private fun autoCapitalization() {
        if (session.mode != KeyboardMode.ENGLISH || capsLock || sensitive || inputScene != "text" || !preferences.getBoolean("autoCap", true)) return
        val before = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        val capital = KeyboardPolicy.capitalize(before)
        if (session.uppercase != capital) session.toggleShift()
    }
    private fun space() = queueEdit {
        if (handwritingMode) {
            if (!resolveHandwriting()) return@queueEdit
            commit(" ")
            return@queueEdit
        }
        if (session.mode == KeyboardMode.ENGLISH) {
            val raw = englishWord
            val result = if (raw.isNotEmpty() && preferences.getBoolean("englishCorrection", false)) withContext(Dispatchers.Default) {
                englishDictionary().correction(raw)
            } else raw
            finishEnglish(result)
            val now = android.os.SystemClock.uptimeMillis()
            val previous = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString().orEmpty()
            if (preferences.getBoolean("doubleSpace", false) && KeyboardPolicy.doubleSpace(previous, now - lastSpace)) {
                currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0); commit(". ")
            } else commit(" ")
            lastSpace = now; autoCapitalization(); renderKeys(); renderCandidates()
        } else if (session.composition.isEmpty() && preferences.getBoolean("doubleSpace", false)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (KeyboardPolicy.doubleSpace(currentInputConnection?.getTextBeforeCursor(3, 0)?.toString().orEmpty(), now - lastSpace)) {
                currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0); commit("。")
            } else commit(" ")
            lastSpace = now
        } else resolveNow(CompositionBoundary.SPACE) { session.space() }
    }
    private fun applyLayout() {
        if (!::keyboardRoot.isInitialized) return
        val hand = preferences.getString("oneHand", "off")
        keyboardRoot.gravity = if (hand == "right") Gravity.RIGHT else Gravity.LEFT
        keyboardBody.layoutParams = LinearLayout.LayoutParams(if (hand == "off" || landscape) -1 else
            (resources.displayMetrics.widthPixels * 0.82f).toInt(), -2)
    }
    private fun feedback(label: String) {
        if (preferences.getBoolean("sound", false))
            (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 0.3f)
        if (preferences.getBoolean("vibration", false)) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(preferences.getInt("vibrationMs", 15).toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    private fun showKeyPreview(anchor: View, label: String) {
        val text = ui.text(label, 28f, ui.onPrimaryContainer, true).apply { gravity = Gravity.CENTER; setBackground(ui.rounded(ui.primaryContainer, 12)) }
        val popup = PopupWindow(text, 52.dp, 56.dp, false).apply { inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED }
        popup.showAsDropDown(anchor, (anchor.width - 52.dp) / 2, -anchor.height - 56.dp)
        text.postDelayed({ popup.dismiss() }, 180)
    }
    private fun openSettings() {
        startActivity(Intent(this, KeyboardSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun captureClipboard() {
        if (!isInputViewShown || sensitive || !preferences.getBoolean("clipboardHistory", true)) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) return
        val text = clip.getItemAt(0).text ?: return
        ClipboardHistory(this).add(text.toString())
    }
    private fun addTools(parent: LinearLayout) {
        val labels = listOf("设置", "剪贴板", "编辑", "符号", "表情", if (handwritingMode) "键盘" else "手写", "语音")
        val scroll = HorizontalScrollView(ui.context).apply { isHorizontalScrollBarEnabled = false }
        val bar = LinearLayout(ui.context)
        labels.forEach { label ->
            bar.addView(ui.button(label, Color.TRANSPARENT, ui.muted, 10, 12f) {
                if (label == "设置") openSettings() else if (!sensitive) showTool(label)
            }.apply { isEnabled = label == "设置" || !sensitive }, LinearLayout.LayoutParams(54.dp, if (landscape) 24.dp else 32.dp))
        }
        scroll.addView(bar); parent.addView(scroll)
    }
    private fun showTool(name: String) = queueEdit {
        finishEnglish()
        if (!resolveHandwriting()) return@queueEdit
        if (session.composition.isNotEmpty()) {
            resolveNow(CompositionBoundary.SPACE) { session.space() }
            if (session.composition.isNotEmpty()) return@queueEdit
        }
        closeTools()
        if (name == "手写" || name == "键盘") {
            handwritingMode = name == "手写"
            renderKeys(); renderCandidates()
            return@queueEdit
        }
        panels.show(name, keys, ui)
    }
    private fun closeTools() { panels.close() }
    private fun editCommand(label: String) {
        when (label) {
            "全选", "复制", "剪切", "粘贴" -> queueEdit {
                val action = when (label) { "全选" -> android.R.id.selectAll; "复制" -> android.R.id.copy; "剪切" -> android.R.id.cut; else -> android.R.id.paste }
                currentInputConnection?.performContextMenuAction(action)
            }
            "←" -> moveCursor(-1)
            "→" -> moveCursor(1)
            "按词删除" -> queueEdit {
                val text = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString().orEmpty()
                val count = KeyboardPolicy.deleteWord(text)
                currentInputConnection?.deleteSurroundingTextInCodePoints(count.codePointCount(0, count.length), 0); updateTranslation()
            }
            "撤销" -> queueEdit {
                val before = currentInputConnection?.getTextBeforeCursor(lastCommit.length, 0)?.toString()
                if (lastCommit.isNotEmpty() && selectionStart == selectionEnd && selectionStart == undoSelection && before == lastCommit) {
                    currentInputConnection?.deleteSurroundingTextInCodePoints(lastCommit.codePointCount(0, lastCommit.length), 0)
                    lastCommit = ""; updateTranslation()
                }
            }
        }
    }
    private fun forgetCandidate(candidate: PinyinCandidate) {
        if (sensitive || candidate.spelling.isEmpty()) return
        queueEdit {
            val spelling = candidate.spelling.trim().replace(' ', '\'')
            if (pinyin.userWord(spelling, candidate.text, true)) {
                requestCandidates(); Toast.makeText(this@LearningKeyboardService, "已移除该词的个人学习记录", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun finishHandwriting() {
        handwriting.finish()
    }
    private fun clearHandwriting() {
        handwriting.clear()
    }
    private suspend fun resolveHandwriting(): Boolean {
        return !handwritingMode || handwriting.resolve()
    }
    private fun handwritingPanel(panel: LinearLayout) {
        handwriting.render(panel, ui, if (landscape) 100.dp else keyHeight * 4)
        row(panel, listOf("键盘", "撤销", "清空", "空格", "⌫", enterLabel()), if (landscape) 32.dp else 44.dp) { label ->
            when (label) {
                "键盘" -> showTool("键盘")
                "撤销" -> {
                    if (handwritingWord.isNotEmpty()) {
                        cancelComposing(); clearHandwriting()
                    } else handwriting.undo()
                }
                "清空" -> {
                    cancelComposing(); clearHandwriting()
                }
                "空格" -> space()
                "⌫" -> backspace()
                else -> enter()
            }
        }
    }
    private val Int.dp get() = (this * resources.displayMetrics.density + 0.5f).toInt()
    private fun trace(message: String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            android.util.Log.d("LEARNIME_EDIT_TRACE", message)
    }
}
