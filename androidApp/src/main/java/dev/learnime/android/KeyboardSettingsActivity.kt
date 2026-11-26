package dev.learnime.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import dev.learnime.shared.*

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
        choice("chineseMode")
        toggle("translation")
        title("中文更正和建议")
        toggle("autoCorrection")
        val groups = KeyboardSettings.fuzzyGroups
        groups.forEachIndexed { index, label ->
            content.addView(Switch(this).apply {
                text = "模糊音 $label"; isChecked = prefs.getInt("fuzzyGroups", 0) and (1 shl index) != 0
                setOnCheckedChangeListener { _, value ->
                    val previous = prefs.getInt("fuzzyGroups", 0)
                    prefs.edit().putInt("fuzzyGroups", if (value) previous or (1 shl index) else previous and (1 shl index).inv()).apply()
                }
            }, LinearLayout.LayoutParams(-1, ui.dp(48)))
        }
        listOf("learning", "prediction", "mixed", "inline", "traditional").forEach { toggle(it) }
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
        listOf("englishSuggestions", "englishCorrection", "autoCap", "doubleSpace").forEach { toggle(it) }
        title("布局和反馈")
        choice("oneHand")
        listOf("keyHeight", "keyFont", "candidateFont").forEach { number(it) }
        listOf("numberRow", "sound", "vibration").forEach { toggle(it) }
        number("vibrationMs"); number("longPress")
        toggle("preview"); toggle("keyBorder"); choice("theme")
        title("工具和隐私")
        toggle("clipboardHistory")
        button("清除剪贴板历史") { ClipboardHistory(this).clear(); toast("已清除") }
        button("离线语音权限") {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            else toast("麦克风权限已开启")
        }
        title("手写")
        choice("handwritingSpeed")
        number("handwritingThickness")
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
    private fun toggle(key: String) {
        val setting = KeyboardSettings.get(key, KeyboardPlatform.ANDROID)
        content.addView(Switch(this).apply {
            text = setting.title; isChecked = prefs.getBoolean(key, setting.booleanDefault)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }, LinearLayout.LayoutParams(-1, ui.dp(52)))
    }
    private fun button(label: String, action: () -> Unit) {
        content.addView(ui.button(label, ui.low, ui.onSurface, action = action), LinearLayout.LayoutParams(-1, ui.dp(48)).apply { bottomMargin = ui.dp(8) })
    }
    private fun choice(key: String) {
        val setting = KeyboardSettings.get(key, KeyboardPlatform.ANDROID)
        val label = setting.title
        val choices = setting.options.map { it.label to it.value }
        val default = setting.choiceDefault
        button("$label：${choices.firstOrNull { it.second == prefs.getString(key, default) }?.first}") {
            MaterialAlertDialogBuilder(this).setTitle(label).setSingleChoiceItems(choices.map { it.first }.toTypedArray(),
                choices.indexOfFirst { it.second == prefs.getString(key, default) }) { dialog, index ->
                prefs.edit().putString(key, choices[index].second).apply(); dialog.dismiss(); recreate()
            }.show()
        }
    }
    private fun number(key: String) {
        val setting = KeyboardSettings.get(key, KeyboardPlatform.ANDROID)
        val label = setting.title
        val value = setting.validateNumber(prefs.getInt(key, setting.numberDefault))
        val text = ui.text("$label：$value", 16f)
        content.addView(text)
        content.addView(SeekBar(this).apply {
            min = setting.minimum; max = setting.maximum; progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val checked = setting.validateNumber(value)
                        prefs.edit().putInt(key, checked).apply(); text.text = "$label：$checked"
                    }
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
