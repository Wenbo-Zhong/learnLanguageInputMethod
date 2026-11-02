package dev.learnime.android

import android.content.Context
import dev.learnime.shared.PinyinCandidate
import dev.learnime.shared.PinyinResults
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import android.util.Log

internal object NativePinyin {
    init { System.loadLibrary("learnime") }
    external fun create(path: String): Long
    external fun createWithUser(path: String, userPath: String): Long
    external fun search(handle: Long, input: String, nine: Boolean): ByteArray
    external fun searchAdvanced(handle: Long, input: String, nine: Boolean, locks: String, corrections: Boolean): ByteArray
    external fun select(handle: Long, token: Long, index: Int, path: String, learn: Boolean): ByteArray
    external fun predict(handle: Long, history: String): ByteArray
    external fun destroy(handle: Long)
    external fun configure(handle: Long, groups: Int)
    external fun userWords(handle: Long): ByteArray
    external fun userWord(handle: Long, spelling: String, word: String, remove: Boolean): Boolean
    external fun clearLearning(handle: Long): Boolean
}

internal class PinyinEngine private constructor(context: Context) {
    companion object {
        @Volatile private var instance: PinyinEngine? = null
        fun get(context: Context): PinyinEngine = instance ?: synchronized(this) {
            instance ?: PinyinEngine(context).also { instance = it }
        }
    }
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var handle = 0L
    private var configuredGroups = -1
    suspend fun search(input: String, nine: Boolean, locks: String = "", corrections: Boolean = true): List<PinyinCandidate> = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureActive()
            if (handle == 0L) {
                val data = File(app.noBackupFilesDir, "libime-20260907").apply { mkdirs() }
                for (name in listOf("sc.dict", "sc.lm", "sc.lm.predict")) {
                    val file = File(data, name)
                    app.assets.openFd("libime/$name").use { asset ->
                        if (!file.isFile || file.length() != asset.length) {
                            val temp = File(data, "$name.tmp")
                            try {
                                asset.createInputStream().use { from -> temp.outputStream().use { from.copyTo(it) } }
                                check(temp.renameTo(file)) { "无法准备拼音词库" }
                            } finally { temp.delete() }
                        }
                    }
                }
                handle = NativePinyin.createWithUser(File(data, "sc.dict").absolutePath,
                    File(app.noBackupFilesDir, "libime-user-v1.dat").absolutePath)
                check(handle != 0L) { "拼音引擎加载失败" }
                Log.i("LEARNIME_LIBIME_TRACE", "engine=libime data=20260907 loaded")
            }
            ensureActive()
            val groups = app.getSharedPreferences("keyboard", Context.MODE_PRIVATE).getInt("fuzzyGroups", 0)
            if (configuredGroups != groups) {
                NativePinyin.configure(handle, groups); configuredGroups = groups
            }
            val result = NativePinyin.searchAdvanced(handle, input, nine, locks, corrections).toString(Charsets.UTF_8)
            ensureActive()
            PinyinResults.parse(result)
        }
    }
    suspend fun select(candidate: PinyinCandidate, learn: Boolean): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            handle != 0L && NativePinyin.select(handle, candidate.token, candidate.nativeIndex, candidate.path, learn)
                .toString(Charsets.UTF_8) == candidate.text
        }
    }
    suspend fun predict(history: String): List<String> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (handle == 0L) emptyList() else NativePinyin.predict(handle, history).toString(Charsets.UTF_8)
                .lineSequence().filter { it.isNotBlank() }.toList()
        }
    }
    suspend fun close() = withContext(Dispatchers.Default) {
        mutex.withLock { if (handle != 0L) { NativePinyin.destroy(handle); handle = 0L; configuredGroups = -1 } }
    }
    suspend fun userWords(): String {
        search("", false)
        return withContext(Dispatchers.Default) { mutex.withLock {
            NativePinyin.userWords(handle).toString(Charsets.UTF_8)
        } }
    }
    suspend fun userWord(spelling: String, word: String, remove: Boolean = false): Boolean {
        search("", false)
        return withContext(Dispatchers.Default) { mutex.withLock { NativePinyin.userWord(handle, spelling, word, remove) } }
    }
    suspend fun clearLearning(): Boolean {
        search("", false)
        return withContext(Dispatchers.Default) { mutex.withLock { NativePinyin.clearLearning(handle) } }
    }
}
