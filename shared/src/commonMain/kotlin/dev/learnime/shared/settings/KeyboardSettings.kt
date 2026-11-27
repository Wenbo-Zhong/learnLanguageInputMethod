package dev.learnime.shared

enum class KeyboardPlatform { ANDROID, IOS }
enum class SettingType { BOOLEAN, NUMBER, CHOICE }
data class SettingOption(val value: String, val label: String)
data class KeyboardSetting(
    val key: String, val title: String, val type: SettingType,
    val booleanDefault: Boolean = false, val numberDefault: Int = 0, val choiceDefault: String = "",
    val minimum: Int = 0, val maximum: Int = 0, val options: List<SettingOption> = emptyList(),
) {
    fun validateNumber(value: Int): Int {
        require(type == SettingType.NUMBER)
        return value.coerceIn(minimum, maximum)
    }
    fun validateChoice(value: String): String {
        require(type == SettingType.CHOICE)
        return options.firstOrNull { it.value == value }?.value ?: choiceDefault
    }
}

/** Persistent identifiers and values stay compatible with existing installations. */
object KeyboardSettings {
    val fuzzyGroups: List<String> = listOf("an / ang", "en / eng", "in / ing", "c / ch", "s / sh", "z / zh", "l / n", "f / h")
    private fun toggle(key: String, title: String, enabled: Boolean = false) =
        KeyboardSetting(key, title, SettingType.BOOLEAN, booleanDefault = enabled)
    private fun number(key: String, title: String, default: Int, min: Int, max: Int) =
        KeyboardSetting(key, title, SettingType.NUMBER, numberDefault = default, minimum = min, maximum = max)
    private fun choice(key: String, title: String, default: String, vararg choices: Pair<String, String>) =
        KeyboardSetting(key, title, SettingType.CHOICE, choiceDefault = default,
            options = choices.map { SettingOption(it.first, it.second) })

    private val android by lazy { definitions(KeyboardPlatform.ANDROID) }
    private val ios by lazy { definitions(KeyboardPlatform.IOS) }
    fun all(platform: KeyboardPlatform): List<KeyboardSetting> =
        if (platform == KeyboardPlatform.ANDROID) android else ios

    private fun definitions(platform: KeyboardPlatform): List<KeyboardSetting> = listOf(
        choice("chineseMode", "中文布局", "CHINESE_QWERTY", "CHINESE_NINE_KEY" to "九键", "CHINESE_QWERTY" to "26键"),
        toggle("translation", "翻译栏", true),
        toggle("autoCorrection", "拼音错键纠正", true),
        toggle("learning", "学习选词和短语", true),
        toggle("prediction", "后续字词联想", true),
        toggle("mixed", "中文候选建议英文／数字原文", true),
        toggle("inline", "在输入框显示组合文字"),
        toggle("traditional", "繁体中文输出"),
        toggle("englishSuggestions", "英文词候选与拼写纠正", true),
        toggle("englishCorrection", "英文空格自动更正"),
        toggle("autoCap", "句首自动大写", true),
        toggle("doubleSpace", "双击空格插入句号"),
        choice("oneHand", "单手模式", "off", "off" to "关闭", "left" to "靠左", "right" to "靠右"),
        number("keyHeight", "按键高度", if (platform == KeyboardPlatform.ANDROID) 54 else 44, 40, 68),
        number("keyFont", "按键字号", 22, 16, 28),
        number("candidateFont", "候选字号", 20, 16, 28),
        toggle("numberRow", "数字行"),
        toggle("sound", "按键声音"),
        toggle("vibration", "按键振动"),
        number("longPress", "长按延迟（毫秒）", 400, 250, 700),
        toggle("preview", "按键弹出预览"),
        toggle("keyBorder", "按键边框"),
        choice("theme", "主题", "system", "system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
        choice("handwritingSpeed", "手写速度", "normal", "fast" to "快", "normal" to "正常", "slow" to "慢"),
        number("handwritingThickness", "手写笔画粗细", 3, 1, 6),
    ) + if (platform == KeyboardPlatform.ANDROID) listOf(
        toggle("clipboardHistory", "保存剪贴板文字历史", true),
        number("vibrationMs", "振动时长（毫秒）", 15, 5, 40),
    ) else listOf(
        choice("translationModel", "翻译模型", "m2m100", "m2m100" to "M2M100 · 中英互译", "opus" to "OPUS-MT · 中文译英文"),
        toggle("gpuTranslation", "Metal GPU 翻译（实验）"),
    )

    fun get(key: String, platform: KeyboardPlatform): KeyboardSetting =
        all(platform).firstOrNull { it.key == key } ?: error("Unknown keyboard setting: $key")
}
