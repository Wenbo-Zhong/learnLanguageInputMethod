package dev.learnime.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream

internal data class TranslationModelFiles(val model: File, val sourceSpm: File, val targetSpm: File)
internal data class M2mModelAssets(
    val encoderAsset: String, val decoderAsset: String, val spm: File, val mapping: File,
    val decoderRuntime: String,
)

internal object ModelStore {
    private const val DIR = "opus-mt-tiny-zho-eng"
    private val names = listOf("model.tflite", "source.spm", "target.spm")
    private val mutex = Mutex()
    private const val M2M_DIR = "m2m100-418m-int8"
    private val m2mNames = listOf("sentencepiece.bpe.model", "tokenizer-map.bin")
    private val m2mGraphs = listOf("onnx/encoder_model_quantized.onnx", "onnx/decoder_model_optimized.onnx")

    fun ready(context: Context): TranslationModelFiles? {
        val dir = File(context.noBackupFilesDir, DIR)
        val files = names.map { File(dir, it) }
        return files.takeIf { it.all { file -> file.isFile && file.length() > 0 } }?.let { TranslationModelFiles(it[0], it[1], it[2]) }
    }

    suspend fun prepare(context: Context): TranslationModelFiles = withContext(Dispatchers.IO) {
        mutex.withLock {
            ready(context)?.let { return@withLock it }
            val dir = File(context.noBackupFilesDir, DIR)
            check(dir.exists() || dir.mkdirs()) { "无法创建模型目录" }
            for (name in names) {
                val target = File(dir, name)
                val temporary = File(dir, "$name.tmp")
                try {
                    context.assets.open("models/$DIR/$name").use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temporary.length() > 0) { "内置模型文件不完整：$name" }
                    check(temporary.renameTo(target)) { "无法准备模型文件：$name" }
                } finally { temporary.delete() }
            }
            requireNotNull(ready(context)) { "模型准备失败" }
        }
    }

    suspend fun prepareSelected(context: Context, model: TranslationModel = TranslationModel.selected(context)) {
        when (model) {
            TranslationModel.OPUS_TINY -> prepare(context)
            TranslationModel.M2M100 -> prepareM2m(context)
        }
    }

    suspend fun prepareM2m(context: Context): M2mModelAssets = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = File(context.noBackupFilesDir, M2M_DIR)
            val identity = context.assets.open("models/$M2M_DIR/model-identity.txt").bufferedReader().use { it.readText().trim() }
            check(identity.matches(Regex("m2m-bundled-v1:[0-9a-f]{64}"))) { "M2M100 内置模型版本无效" }
            val runtime = context.assets.open("models/$M2M_DIR/optimized-decoder-runtime.txt")
                .bufferedReader().use { it.readText().trim() }
            // openFd also verifies that these graph assets are stored uncompressed for mmap.
            m2mGraphs.forEach { name ->
                context.assets.openFd("models/$M2M_DIR/$name").use { check(it.length > 0) { "M2M100 模型不完整" } }
            }
            val prepared = File(dir, "prepared-source-id.txt")
            val sameSource = prepared.isFile && prepared.readText().trim() == identity
            val files = m2mNames.map { File(dir, it) }
            for ((index, name) in m2mNames.withIndex()) {
                ensureActive()
                val target = files[index]
                val descriptor = context.assets.openFd("models/$M2M_DIR/$name")
                val size = descriptor.use { it.length }
                if (sameSource && target.isFile && target.length() == size) continue
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建 M2M100 模型目录" }
                val temporary = File(target.parentFile, "${target.name}.tmp")
                try {
                    context.assets.open("models/$M2M_DIR/$name").use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    check(temporary.length() == size) { "M2M100 文件不完整：$name" }
                    check(temporary.renameTo(target)) { "无法准备 M2M100 文件：$name" }
                } finally { temporary.delete() }
            }
            val preparedTemporary = File(dir, "${prepared.name}.tmp")
            try {
                ensureActive()
                FileOutputStream(preparedTemporary).use {
                    it.write(identity.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                check(preparedTemporary.renameTo(prepared)) { "无法提交 M2M100 模型版本" }
            } finally { preparedTemporary.delete() }
            removeLegacyGraphCopies(context, dir)
            M2mModelAssets("models/$M2M_DIR/${m2mGraphs[0]}", "models/$M2M_DIR/${m2mGraphs[1]}",
                files[0], files[1], runtime)
        }
    }

    private fun removeLegacyGraphCopies(context: Context, dir: File) {
        var removedBytes = 0L
        val graphDir = File(dir, "onnx")
        for (name in listOf("encoder_model_quantized.onnx", "decoder_model_merged_quantized.onnx",
            "decoder_model_merged_hoisted.onnx")) {
            for (suffix in listOf("", ".tmp")) {
                val file = File(graphDir, name + suffix)
                val bytes = file.length()
                if (file.delete()) removedBytes += bytes
            }
        }
        val legacyCache = File(context.noBackupFilesDir, "m2m100-decoder-cache")
        val cacheName = Regex("decoder-fp32-[0-9a-f]{64}\\.onnx(?:\\.ready)?(?:\\.tmp)?")
        legacyCache.listFiles()?.filter { cacheName.matches(it.name) }?.forEach { file ->
            val bytes = file.length()
            if (file.delete()) removedBytes += bytes
        }
        if (legacyCache.listFiles()?.isEmpty() == true) legacyCache.delete()
        if (graphDir.listFiles()?.isEmpty() == true) graphDir.delete()
        if (removedBytes > 0) Log.i("LEARNIME_M2M_ASSET", "removed_legacy_bytes=$removedBytes")
    }
}
