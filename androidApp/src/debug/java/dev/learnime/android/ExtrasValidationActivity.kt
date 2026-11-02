package dev.learnime.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.File

/** Debug-only integration checks using the same assets, JNI and persistence as the IME. */
class ExtrasValidationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val output = TextView(this).apply { textSize = 16f; setPadding(20, 80, 20, 0) }
        setContentView(output)
        scope.launch {
            val lines = ArrayList<String>()
            fun pass(value: String) { lines += "PASS $value"; output.text = lines.joinToString("\n"); android.util.Log.i("LEARNIME_EXTRAS_CHECK", lines.last()) }
            try {
                val english = withContext(Dispatchers.Default) { EnglishDictionary(this@ExtrasValidationActivity) }
                check("Hello" in english.suggestions("Helo")); pass("English Helo -> Hello")
                check("world" in english.suggestions("wrold")); pass("English adjacent swap")
                check("wifi" in english.nineSuggestions("9434")); pass("nine key 9434 -> wifi")
                val traditional = withContext(Dispatchers.Default) { TraditionalConverter(this@ExtrasValidationActivity) }
                check(traditional.convert("头发发展软件后台") == "頭髮發展軟件後臺"); pass("OpenCC character and phrase conversion")
                val clips = ClipboardHistory(this@ExtrasValidationActivity, "clipboard-validation")
                clips.clear(); clips.add("固定短语"); clips.pin(clips.entries().single())
                clips.add("临时文字"); check(clips.entries().first().pinned)
                val storage = getSharedPreferences("clipboard-validation", MODE_PRIVATE)
                val clipJson = org.json.JSONArray(storage.getString("entries", "[]"))
                for (i in 0 until clipJson.length()) clipJson.getJSONObject(i).put("time", 0L)
                storage.edit().putString("entries", clipJson.toString()).commit()
                check(clips.entries().size == 1 && clips.entries().single().text == "固定短语")
                clips.delete(clips.entries().single()); check(clips.entries().isEmpty()); clips.clear()
                pass("clipboard pin, expiry, delete in isolated storage")
                val engine = PinyinEngine.get(this@ExtrasValidationActivity)
                check(engine.userWord("kai'yuan'ci'ku", "开源词库"))
                check(engine.userWords().contains("开源词库 kai'yuan'ci'ku")); pass("user dictionary add/list")
                check(engine.search("kaiyuanciku", false).any { it.text == "开源词库" }); pass("user dictionary decoding")
                check(engine.userWord("kai'yuan'ci'ku", "开源词库", true))
                check(!engine.userWords().contains("开源词库")); pass("user dictionary remove")
                val model = HandwritingEngine(this@ExtrasValidationActivity)
                try {
                    val points = (0..30).flatMap { listOf(0, 200 + 20 * it, 500) }.toIntArray()
                    check(model.recognize(points).first() == "一"); pass("Zinnia real stroke -> 一")
                } finally { model.close() }
                // Verify actual speech runtime can open the bundled acoustic model.
                val directory = File(noBackupFilesDir, "vosk-validation")
                withContext(Dispatchers.IO) {
                    fun copy(path: String, target: File) {
                        val children = assets.list(path).orEmpty()
                        if (children.isEmpty()) { target.parentFile?.mkdirs(); assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } } }
                        else { target.mkdirs(); children.forEach { copy("$path/$it", File(target, it)) } }
                    }
                    copy("extras/vosk-cn", directory)
                    org.vosk.Model(directory.absolutePath).use { vosk ->
                        org.vosk.Recognizer(vosk, 16000f).use { recognizer ->
                            val fixture = File(filesDir, "voice-test.wav")
                            if (fixture.isFile) {
                                val bytes = fixture.readBytes()
                                var offset = 12
                                while (offset + 8 < bytes.size && String(bytes, offset, 4, Charsets.US_ASCII) != "data") {
                                    val count = java.nio.ByteBuffer.wrap(bytes, offset + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                                    offset += 8 + count + (count and 1)
                                }
                                check(offset + 8 < bytes.size)
                                offset += 8
                                val chunks = ArrayList<String>()
                                while (offset < bytes.size) {
                                    val size = minOf(8000, bytes.size - offset)
                                    if (recognizer.acceptWaveForm(bytes.copyOfRange(offset, offset + size), size))
                                        chunks += org.json.JSONObject(recognizer.result).optString("text")
                                    offset += size
                                }
                                chunks += org.json.JSONObject(recognizer.finalResult).optString("text")
                                val text = chunks.joinToString("").replace(" ", "")
                                check(text.contains("你好") && text.contains("明天见")) { "Speech fixture: $text" }
                                android.util.Log.i("LEARNIME_EXTRAS_CHECK", "speech_fixture=$text")
                            } else {
                                val audio = ShortArray(16000)
                                recognizer.acceptWaveForm(audio, audio.size)
                                check(org.json.JSONObject(recognizer.finalResult).has("text"))
                            }
                        }
                    }
                    directory.deleteRecursively()
                }
                pass("Vosk acoustic model load and PCM decode")
                pass("ALL INTEGRATION CHECKS")
            } catch (e: Exception) {
                lines += "FAIL ${e.stackTraceToString()}"
                output.text = lines.joinToString("\n")
                android.util.Log.e("LEARNIME_EXTRAS_CHECK", lines.last())
            }
            File(filesDir, "extras-validation.txt").writeText(lines.joinToString("\n"))
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
