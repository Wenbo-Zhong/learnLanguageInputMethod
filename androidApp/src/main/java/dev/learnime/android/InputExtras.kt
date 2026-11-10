package dev.learnime.android

import android.content.Context
import dev.learnime.shared.EnglishLexicon
import dev.learnime.shared.TraditionalText
import dev.learnime.shared.ClipboardPolicy
import dev.learnime.shared.ClipboardRecord
import org.json.JSONArray
import org.json.JSONObject

/** Android only owns asset IO. Both platforms use the same KMP dictionary. */
internal class EnglishDictionary(context: Context) {
    private val dictionary = EnglishLexicon(context.assets.open("extras/english-frequency.txt").bufferedReader().use { it.readText() })
    fun suggestions(input: String) = dictionary.suggestions(input)
    fun correction(input: String) = dictionary.correction(input)
    fun nineSuggestions(input: String) = dictionary.nineSuggestions(input)
}
internal class TraditionalConverter(context: Context) {
    private val converter = TraditionalText(
        context.assets.open("extras/STCharacters.txt").bufferedReader().use { it.readText() },
        context.assets.open("extras/STPhrases.txt").bufferedReader().use { it.readText() })
    fun convert(text: String) = converter.convert(text)
}

internal data class ClipEntry(val text: String, val time: Long, val pinned: Boolean)
/** Android persistence/serialization; retention and ordering are shared. */
internal class ClipboardHistory(context: Context, storage: String = "clipboard") {
    private val prefs = context.getSharedPreferences(storage, Context.MODE_PRIVATE)
    private fun records() = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrDefault(JSONArray()).let { json ->
        (0 until json.length()).mapNotNull { index ->
            runCatching { json.getJSONObject(index).let { ClipboardRecord(it.getString("text"), it.getLong("time"), it.getBoolean("pinned")) } }.getOrNull()
        }
    }
    fun entries(): List<ClipEntry> {
        val values = ClipboardPolicy.retained(records(), System.currentTimeMillis())
        save(values)
        return values.map { ClipEntry(it.text, it.timestamp, it.pinned) }
    }
    fun add(text: String) { save(ClipboardPolicy.added(records(), text, System.currentTimeMillis())) }
    fun pin(entry: ClipEntry) { save(entries().map { ClipboardRecord(it.text, it.time, if (it.text == entry.text) !it.pinned else it.pinned) }) }
    fun delete(entry: ClipEntry) { save(entries().filter { it.text != entry.text }.map { ClipboardRecord(it.text, it.time, it.pinned) }) }
    fun clear() { prefs.edit().clear().apply() }
    private fun save(entries: List<ClipboardRecord>) {
        val json = JSONArray()
        entries.forEach { json.put(JSONObject().put("text", it.text).put("time", it.timestamp).put("pinned", it.pinned)) }
        prefs.edit().putString("entries", json.toString()).apply()
    }
}
