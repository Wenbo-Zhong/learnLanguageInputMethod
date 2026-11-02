package dev.learnime.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Frequency data and deletion lookup follow SymSpell's public dictionary workflow (MIT). */
internal class EnglishDictionary(context: Context) {
    private val frequencies = HashMap<String, Long>()
    private val sorted: List<String>
    init {
        context.assets.open("extras/english-frequency.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(' ')
                if (parts.size == 2 && parts[0].all { it in 'a'..'z' })
                    frequencies[parts[0]] = parts[1].toLongOrNull() ?: 0
            }
        }
        sorted = frequencies.keys.sorted()
    }
    fun suggestions(input: String): List<String> {
        val word = input.lowercase(Locale.ROOT)
        if (word.isEmpty() || word.length > 40) return listOf(input)
        val edits = HashSet<String>()
        if (word.length >= 3 && word !in frequencies) {
            for (i in word.indices) {
                edits += word.removeRange(i, i + 1)
                for (c in 'a'..'z') edits += word.substring(0, i) + c + word.substring(i + 1)
                if (i < word.lastIndex) edits += word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
            }
            for (i in 0..word.length) for (c in 'a'..'z') edits += word.substring(0, i) + c + word.substring(i)
        }
        val corrections = edits.filter { it in frequencies }.sortedByDescending { frequencies[it] }.take(3)
        var start = sorted.binarySearch(word).let { if (it < 0) -it - 1 else it }
        val completions = ArrayList<String>()
        while (start < sorted.size && sorted[start].startsWith(word)) { completions += sorted[start++]; if (completions.size == 5000) break }
        return (listOf(word) + corrections + completions.sortedByDescending { frequencies[it] }.take(5))
            .distinct().take(8).map { value -> when {
                input.all { it.isUpperCase() } -> value.uppercase(Locale.ROOT)
                input.first().isUpperCase() -> value.replaceFirstChar { it.uppercaseChar() }
                else -> value
            } }
    }
    fun correction(input: String): String {
        if (input.lowercase(Locale.ROOT) in frequencies) return input
        return suggestions(input).drop(1).firstOrNull {
            kotlin.math.abs(it.length - input.length) <= 1 && distance(input.lowercase(Locale.ROOT), it.lowercase(Locale.ROOT)) <= 1
        } ?: input
    }
    fun nineSuggestions(input: String): List<String> {
        if (input.isEmpty() || input.any { it !in '2'..'9' }) return emptyList()
        val groups = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")
        return frequencies.keys.asSequence().filter { word ->
            word.length == input.length && word.indices.all { index -> word[index] in groups[input[index] - '2'] }
        }.sortedByDescending { frequencies[it] }.take(4).toList()
    }
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}

/** OpenCC longest phrase matching, then per-character conversion. */
internal class TraditionalConverter(context: Context) {
    private val map = HashMap<String, String>()
    private var maxLength = 1
    init {
        for (name in listOf("STCharacters.txt", "STPhrases.txt")) context.assets.open("extras/$name").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 2) { map[parts[0]] = parts[1].substringBefore(' '); maxLength = maxOf(maxLength, parts[0].length) }
            }
        }
    }
    fun convert(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            var count = minOf(maxLength, text.length - index)
            while (count > 1 && text.substring(index, index + count) !in map) count--
            val key = text.substring(index, index + count); append(map[key] ?: key); index += count
        }
    }
}

internal data class ClipEntry(val text: String, val time: Long, val pinned: Boolean)
internal class ClipboardHistory(context: Context, storage: String = "clipboard") {
    private val prefs = context.getSharedPreferences(storage, Context.MODE_PRIVATE)
    fun entries(): List<ClipEntry> {
        val json = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrDefault(JSONArray())
        val result = (0 until json.length()).mapNotNull { index ->
            runCatching { val item = json.getJSONObject(index); ClipEntry(item.getString("text"), item.getLong("time"), item.getBoolean("pinned")) }.getOrNull()
        }.filter { it.pinned || System.currentTimeMillis() - it.time < 3_600_000 }
        save(result)
        return result.sortedWith(compareByDescending<ClipEntry> { it.pinned }.thenByDescending { it.time })
    }
    fun add(text: String) {
        if (text.isBlank() || text.length > 10_000) return
        val entries = entries()
        val existing = entries.firstOrNull { it.text == text }
        save((listOf(ClipEntry(text, System.currentTimeMillis(), existing?.pinned == true)) + entries.filter { it.text != text }).take(40))
    }
    fun pin(entry: ClipEntry) { save(entries().map { if (it.text == entry.text) it.copy(pinned = !it.pinned) else it }) }
    fun delete(entry: ClipEntry) { save(entries().filter { it.text != entry.text }) }
    fun clear() { prefs.edit().clear().apply() }
    private fun save(entries: List<ClipEntry>) {
        val json = JSONArray()
        entries.forEach { json.put(JSONObject().put("text", it.text).put("time", it.time).put("pinned", it.pinned)) }
        prefs.edit().putString("entries", json.toString()).apply()
    }
}
