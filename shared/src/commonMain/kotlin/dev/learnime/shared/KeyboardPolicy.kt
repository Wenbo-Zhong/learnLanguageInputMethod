package dev.learnime.shared

/** Pure rules; OS input connections, clocks and persistence stay in adapters. */
object KeyboardPolicy {
    fun capitalize(context: String): Boolean = context.isEmpty() || context.endsWith('\n') ||
        context.trimEnd().lastOrNull() in listOf('.', '!', '?', '。', '！', '？')
    fun doubleSpace(context: String, elapsedMs: Long): Boolean = elapsedMs in 0..399 &&
        context.endsWith(' ') && context.dropLast(1).lastOrNull()?.isLetterOrDigit() == true
    fun markedText(english: String, handwriting: String, pinyin: String, inline: Boolean, sensitive: Boolean): String =
        if (sensitive) "" else handwriting.ifEmpty { english.ifEmpty { if (inline) pinyin else "" } }
    fun committedContext(context: String, marked: String): String =
        if (marked.isNotEmpty() && context.endsWith(marked)) context.dropLast(marked.length) else context
    fun validShortcut(key: String, phrase: String): Boolean = phrase.isNotBlank() && phrase.length <= 1000 &&
        key.matches(Regex("(?:[a-z]{1,20}|[2-9]{1,20})"))
    fun handwritingDelay(speed: String): Long = when (speed) { "fast" -> 600L; "slow" -> 1600L; else -> 1000L }
    fun deleteWord(context: String): String =
        Regex("(?:\\s+|[\\p{L}\\p{N}]+\\s*|[^\\p{L}\\p{N}\\s])$").find(context)?.value.orEmpty()
}

data class ClipboardRecord(val text: String, val timestamp: Long, val pinned: Boolean)
object ClipboardPolicy {
    fun accepts(text: String): Boolean = text.isNotBlank() && text.length <= 10_000
    fun retained(entries: List<ClipboardRecord>, now: Long): List<ClipboardRecord> =
        entries.filter { it.pinned || now-it.timestamp in 0 until 3_600_000 }
            .sortedWith(compareByDescending<ClipboardRecord> { it.pinned }.thenByDescending { it.timestamp })
    fun added(entries: List<ClipboardRecord>, text: String, now: Long): List<ClipboardRecord> {
        val current = retained(entries, now)
        if (!accepts(text)) return current
        return retained(listOf(ClipboardRecord(text, now, current.firstOrNull { it.text == text }?.pinned == true)) +
            current.filter { it.text != text }, now).take(40)
    }
}

/** Content catalog shared by both platforms; panel presentation is platform-owned. */
object KeyboardSymbols {
    fun categories(emoji: Boolean): List<String> =
        if (emoji) listOf("最近","开心","难过","手势","生活") else listOf("最近","中文","英文","数学","常用")
    fun values(emoji: Boolean, category: String, recent: String): List<String> = (when (category) {
        "最近" -> recent
        "开心" -> "😀 😃 😄 😁 😆 😂 🤣 😊 😍 🥰 😘"
        "难过" -> "🥲 😔 😢 😭 😞 😣 😡 🤔 🙄 😴"
        "手势" -> "👍 👎 👏 🙌 🤝 🙏 💪 👌 ✌️ 🤞"
        "生活" -> "❤️ 💔 🎉 🎂 🎁 🌹 🌞 🌙 🔥 🍎 ☕ 🚗 🏠"
        "中文" -> "， 。 ？ ！ ： ； 、 … — · 「 」 『 』 《 》 【 】 （ ）"
        "英文" -> ", . ? ! : ; ' \" @ # $ % & * ( ) [ ] { } / \\ _ - + ="
        "数学" -> "＋ − × ÷ ＝ ≠ ≈ ≤ ≥ ± ∞ √ π ² ³ ％ ℃ °"
        "常用" -> "© ® ™ → ← ↑ ↓ ★ ☆ ✓ ✕ ● ○ ※ ¥ € £"
        else -> ""
    }).split(' ').filter(String::isNotEmpty)
    fun keywords(): List<String> = listOf("笑","哭","爱","庆祝")
    fun keywordValues(keyword: String): List<String> = (when (keyword) {
        "笑" -> "😀 😃 😄 😁 😆 😂 🤣 😊"
        "哭" -> "🥲 😢 😭"
        "爱" -> "❤️ 😍 🥰 😘"
        "庆祝" -> "🎉 🎂 🎁 👏"
        else -> ""
    }).split(' ').filter(String::isNotEmpty)
    fun remember(value: String, previous: String): String =
        (listOf(value) + previous.split(' ').filter { it.isNotEmpty() && it != value }).take(30).joinToString(" ")
}

/** Logical key arrangement, independent of points, dp, colors and UI widgets. */
object KeyboardKeymap {
    fun letterRows(): List<String> = listOf("qwertyuiop", "asdfghjkl")
    fun nineRows(): List<List<String>> = listOf(listOf("分词","ABC","DEF"),listOf("GHI","JKL","MNO"),listOf("PQRS","TUV","WXYZ"))
    fun symbolRows(english: Boolean): List<String> = if (english)
        listOf("1234567890", "@#$%&*()-+", ".,?!:;\"'/_")
        else listOf("1234567890", "@#￥%&*()-+", "，。？！：；“”《》")
    fun nineDigit(row: Int, column: Int): String = if (row == 0 && column == 0) "'" else (row*3+column+1).toString()
}
