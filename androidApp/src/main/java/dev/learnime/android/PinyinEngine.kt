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
    external fun create(path: ByteArray): Long
    external fun createWithUser(path: ByteArray, userPath: ByteArray): Long
    external fun search(handle: Long, input: ByteArray, nine: Boolean): ByteArray
    external fun searchAdvanced(handle: Long, input: ByteArray, nine: Boolean, locks: ByteArray, corrections: Boolean): ByteArray
    external fun select(handle: Long, token: Long, index: Int, path: ByteArray, learn: Boolean): ByteArray
    external fun predict(handle: Long, history: ByteArray): ByteArray
    external fun learningError(handle: Long): ByteArray
    external fun destroy(handle: Long)
    external fun configure(handle: Long, groups: Int)
    external fun userWords(handle: Long): ByteArray
    external fun userWord(handle: Long, spelling: ByteArray, word: ByteArray, remove: Boolean): Boolean
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
                handle = NativePinyin.createWithUser(File(data, "sc.dict").absolutePath.toByteArray(Charsets.UTF_8),
                    File(app.noBackupFilesDir, "libime-user-v1.dat").absolutePath.toByteArray(Charsets.UTF_8))
                check(handle != 0L) { "拼音引擎加载失败" }
                Log.i("LEARNIME_LIBIME_TRACE", "engine=libime data=20260907 loaded")
            }
            ensureActive()
            val groups = app.getSharedPreferences("keyboard", Context.MODE_PRIVATE).getInt("fuzzyGroups", 0)
            if (configuredGroups != groups) {
                NativePinyin.configure(handle, groups); configuredGroups = groups
            }
            val result = NativePinyin.searchAdvanced(handle, input.toByteArray(Charsets.UTF_8), nine,
                locks.toByteArray(Charsets.UTF_8), corrections).toString(Charsets.UTF_8)
            ensureActive()
            PinyinResults.parse(result)
        }
    }
    suspend fun select(candidate: PinyinCandidate, learn: Boolean): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (handle == 0L) return@withLock false
            val selected = NativePinyin.select(handle, candidate.token, candidate.nativeIndex,
                candidate.path.toByteArray(Charsets.UTF_8), learn).toString(Charsets.UTF_8)
            val error = NativePinyin.learningError(handle).toString(Charsets.UTF_8)
            if (learn && error.isNotEmpty()) Log.w("LEARNIME_LIBIME_TRACE", "learning pending: $error")
            selected == candidate.text
        }
    }
    suspend fun predict(history: String): List<String> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (handle == 0L) emptyList() else NativePinyin.predict(handle, history.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)
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
        return withContext(Dispatchers.Default) { mutex.withLock {
            NativePinyin.userWord(handle, spelling.toByteArray(Charsets.UTF_8), word.toByteArray(Charsets.UTF_8), remove)
        } }
    }
    suspend fun clearLearning(): Boolean {
        search("", false)
        return withContext(Dispatchers.Default) { mutex.withLock { NativePinyin.clearLearning(handle) } }
    }
}
