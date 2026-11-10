package dev.learnime.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*

class KeyboardSettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("keyboard", MODE_PRIVATE) }
    private lateinit var ui: MaterialUi
    private lateinit var content: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = MaterialUi(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(32), ui.dp(20), ui.dp(32)) }
        setContentView(ScrollView(this).apply { addView(content); setBackgroundColor(ui.surface) })
        title("键盘设置")
        choice("中文布局", "chineseMode", listOf("九键" to "CHINESE_NINE_KEY", "26键" to "CHINESE_QWERTY"), "CHINESE_QWERTY")
        toggle("翻译栏", "translation", true)
        title("中文更正和建议")
        toggle("拼音错键纠正", "autoCorrection", true)
        val groups = listOf("an / ang", "en / eng", "in / ing", "c / ch", "s / sh", "z / zh", "l / n", "f / h")
        groups.forEachIndexed { index, label ->
            content.addView(Switch(this).apply {
                text = "模糊音 $label"; isChecked = prefs.getInt("fuzzyGroups", 0) and (1 shl index) != 0
                setOnCheckedChangeListener { _, value ->
                    val previous = prefs.getInt("fuzzyGroups", 0)
                    prefs.edit().putInt("fuzzyGroups", if (value) previous or (1 shl index) else previous and (1 shl index).inv()).apply()
                }
            }, LinearLayout.LayoutParams(-1, ui.dp(48)))
        }
        toggle("学习选词和短语", "learning", true)
        toggle("后续字词联想", "prediction", true)
        toggle("中文候选建议英文／数字原文", "mixed", true)
        toggle("在输入框显示组合文字", "inline", false)
        toggle("繁体中文输出", "traditional", false)
        button("个人词典：添加、编辑、删除") { dictionary() }
        button("短语缩写：添加、编辑、删除") { shortcuts() }
        button("清除个人词典和学习记录") {
            MaterialAlertDialogBuilder(this).setTitle("清除所有学习数据？")
                .setMessage("将删除个人词典和选词历史。").setNegativeButton("取消", null)
                .setPositiveButton("清除") { _, _ -> scope.launch {
                    val ok = PinyinEngine.get(this@KeyboardSettingsActivity).clearLearning()
                    toast(if (ok) "已清除" else "清除失败")
                } }.show()
        }
        title("英文和标点")
        toggle("英文词候选与拼写纠正", "englishSuggestions", true)
        toggle("英文空格自动更正", "englishCorrection", false)
        toggle("句首自动大写", "autoCap", true)
        toggle("双击空格插入句号", "doubleSpace", false)
        title("布局和反馈")
        choice("单手模式", "oneHand", listOf("关闭" to "off", "靠左" to "left", "靠右" to "right"), "off")
        number("按键高度", "keyHeight", 54, 40, 68)
        number("按键字号", "keyFont", 22, 16, 28)
        number("候选字号", "candidateFont", 20, 16, 28)
        toggle("数字行", "numberRow", false)
        toggle("按键声音", "sound", false)
        toggle("按键振动", "vibration", false)
        number("振动时长（毫秒）", "vibrationMs", 15, 5, 40)
        number("长按延迟（毫秒）", "longPress", 400, 250, 700)
        toggle("按键弹出预览", "preview", false)
        toggle("按键边框", "keyBorder", false)
        choice("主题", "theme", listOf("跟随系统" to "system", "浅色" to "light", "深色" to "dark"), "system")
        title("工具和隐私")
        toggle("保存剪贴板文字历史", "clipboardHistory", true)
        button("清除剪贴板历史") { ClipboardHistory(this).clear(); toast("已清除") }
        button("离线语音权限") {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            else toast("麦克风权限已开启")
        }
        title("手写")
        choice("手写速度", "handwritingSpeed", listOf("快" to "fast", "正常" to "normal", "慢" to "slow"), "normal")
        number("手写笔画粗细", "handwritingThickness", 3, 1, 6)
        button("开源组件和数据许可") {
            MaterialAlertDialogBuilder(this).setTitle("开源许可").setMessage(
                assets.open("extras/NOTICE.txt").bufferedReader().use { it.readText() }
            ).setPositiveButton("关闭", null).show()
        }
        content.addView(ui.text("输入、词典、剪贴板与语音识别在本机处理。密码及禁止个性化学习的输入框不采集剪贴板、不学习，也不提供语音与手写。", 13f, ui.muted))
    }
    private fun shortcuts() {
        val data = runCatching { org.json.JSONObject(prefs.getString("shortcuts", "{}")) }.getOrDefault(org.json.JSONObject())
        val keys = data.keys().asSequence().toList().sorted()
        MaterialAlertDialogBuilder(this).setTitle("短语缩写")
            .setItems((listOf("＋ 添加缩写") + keys.map { "$it → ${data.getString(it)}" }).toTypedArray()) { _, index ->
                val oldKey = keys.getOrNull(index - 1)
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), 0, ui.dp(20), 0) }
                val key = EditText(this).apply {
                    hint = "缩写（小写字母或九键数字）"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setText(oldKey.orEmpty())
                }
                val value = EditText(this).apply { hint = "展开后的短语"; setText(oldKey?.let { data.getString(it) }.orEmpty()) }
                box.addView(key); box.addView(value)
                val dialog = MaterialAlertDialogBuilder(this).setTitle("短语缩写").setView(box)
                    .setNegativeButton("取消", null).setPositiveButton("保存", null)
                    .apply { if (oldKey != null) setNeutralButton("删除") { _, _ ->
                        data.remove(oldKey); prefs.edit().putString("shortcuts", data.toString()).apply(); shortcuts()
                    } }.create()
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val abbreviation = key.text.toString().trim()
                        if (!dev.learnime.shared.KeyboardPolicy.validShortcut(abbreviation, value.text.toString())) { key.error = "使用小写字母或 2–9 数字，填写有效短语"; return@setOnClickListener }
                        if (value.text.isBlank() || value.text.length > 1000) { value.error = "填写 1–1000 字短语"; return@setOnClickListener }
                        oldKey?.let { data.remove(it) }; data.put(abbreviation, value.text.toString())
                        prefs.edit().putString("shortcuts", data.toString()).apply(); dialog.dismiss(); shortcuts()
                    }
                }
                dialog.show()
            }.setNegativeButton("关闭", null).show()
    }
    private fun title(value: String) { content.addView(ui.text(value, 22f, ui.primary, true).apply { setPadding(0, ui.dp(20), 0, ui.dp(12)) }) }
    private fun toggle(label: String, key: String, default: Boolean) {
        content.addView(Switch(this).apply {
            text = label; isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }, LinearLayout.LayoutParams(-1, ui.dp(52)))
    }
    private fun button(label: String, action: () -> Unit) {
        content.addView(ui.button(label, ui.low, ui.onSurface, action = action), LinearLayout.LayoutParams(-1, ui.dp(48)).apply { bottomMargin = ui.dp(8) })
    }
    private fun choice(label: String, key: String, choices: List<Pair<String, String>>, default: String) {
        button("$label：${choices.firstOrNull { it.second == prefs.getString(key, default) }?.first}") {
            MaterialAlertDialogBuilder(this).setTitle(label).setSingleChoiceItems(choices.map { it.first }.toTypedArray(),
                choices.indexOfFirst { it.second == prefs.getString(key, default) }) { dialog, index ->
                prefs.edit().putString(key, choices[index].second).apply(); dialog.dismiss(); recreate()
            }.show()
        }
    }
    private fun number(label: String, key: String, default: Int, min: Int, max: Int) {
        val text = ui.text("$label：${prefs.getInt(key, default)}", 16f)
        content.addView(text)
        content.addView(SeekBar(this).apply {
            this.min = min; this.max = max; progress = prefs.getInt(key, default)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) { prefs.edit().putInt(key, value).apply(); text.text = "$label：$value" }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
    }
    private fun dictionary() = scope.launch {
        val raw = PinyinEngine.get(this@KeyboardSettingsActivity).userWords()
        // libime text format: word pinyin cost, separated by whitespace.
        val entries = raw.lineSequence().map { it.trim().split(Regex("\\s+")) }.filter { it.size >= 2 }.toList()
        MaterialAlertDialogBuilder(this@KeyboardSettingsActivity).setTitle("个人词典（${entries.size}）")
            .setItems((listOf("＋ 添加词语") + entries.map { "${it[0]}  ${it[1]}" }).toTypedArray()) { _, index ->
                editWord(entries.getOrNull(index - 1))
            }.setNegativeButton("关闭", null).show()
    }
    private fun editWord(entry: List<String>?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), 0, ui.dp(20), 0) }
        val word = EditText(this).apply { hint = "词语／短语"; setText(entry?.get(0).orEmpty()) }
        val spelling = EditText(this).apply {
            hint = "全拼，用 ' 分隔音节，如 ni'hao"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(entry?.get(1).orEmpty())
        }
        box.addView(word); box.addView(spelling)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (entry == null) "添加词语" else "编辑词语").setView(box)
            .setNegativeButton("取消", null).setPositiveButton("保存", null)
            .apply { if (entry != null) setNeutralButton("删除") { _, _ -> scope.launch {
                PinyinEngine.get(this@KeyboardSettingsActivity).userWord(entry[1], entry[0], true); dictionary()
            } } }.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                scope.launch {
                    val engine = PinyinEngine.get(this@KeyboardSettingsActivity)
                    val ok = engine.userWord(spelling.text.toString().trim(), word.text.toString().trim())
                    if (ok) {
                        if (entry != null && (entry[0] != word.text.toString().trim() || entry[1] != spelling.text.toString().trim()))
                            engine.userWord(entry[1], entry[0], true)
                        dialog.dismiss(); dictionary()
                    } else spelling.error = "请填写词语及有效全拼，音节数需对应汉字"
                }
            }
        }
        dialog.show()
    }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
