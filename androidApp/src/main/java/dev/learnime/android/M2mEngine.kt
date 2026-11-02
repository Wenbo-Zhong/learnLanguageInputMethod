package dev.learnime.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.learnime.shared.TranslationEngine
import dev.learnime.shared.TranslationDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.ln

/** M2M100 418M INT8, bidirectional Chinese/English with native ONNX attention caches. */
internal class M2mEngine(context: Context) : TranslationEngine {
    private val app = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val mutex = Mutex()
    private val revision = AtomicLong()
    private val runLock = Any()
    private var activeRun: OrtSession.RunOptions? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer = 0L
    private var forward = IntArray(0)
    private var outputPieces = emptyArray<ByteArray>()
    private var modelVocabularySize = 0
    private var vocabularySize = 0
    private var chineseId = 0
    private var englishId = 0

    override suspend fun translate(source: String): String = translate(source, TranslationDirection.CHINESE_TO_ENGLISH)

    override suspend fun translate(source: String, direction: TranslationDirection): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val request = revision.get()
            val started = SystemClock.elapsedRealtime()
            try {
                ensureLoaded()
                checkCurrent(request)
                val pieces = NativeSentencePiece.encodeUtf8(tokenizer, source.toByteArray(Charsets.UTF_8))
                require(pieces.size <= MAX_INPUT_TOKENS - 2) { "句子太长，请分句翻译" }
                val ids = LongArray(pieces.size + 2).apply {
                    this[0] = (if (direction == TranslationDirection.ENGLISH_TO_CHINESE) englishId else chineseId).toLong()
                    pieces.forEachIndexed { i, id -> this[i + 1] = forward[id].toLong() }
                    this[lastIndex] = EOS.toLong()
                }
                val run = OrtSession.RunOptions()
                synchronized(runLock) { activeRun = run }
                try {
                    checkCurrent(request)
                    val targetId = if (direction == TranslationDirection.ENGLISH_TO_CHINESE) chineseId else englishId
                    val translated = generate(ids, targetId, run, request)
                    Log.i(TAG, "complete input_tokens=${ids.size} elapsed_ms=${SystemClock.elapsedRealtime() - started} direction=$direction")
                    translated
                } finally {
                    synchronized(runLock) { activeRun = null; run.close() }
                }
            } catch (e: Exception) {
                if (revision.get() != request) {
                    Log.i(TAG, "cancelled elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                    throw CancellationException("翻译已取消", e)
                }
                throw e
            }
        }
    }

    override fun cancel() {
        revision.incrementAndGet()
        synchronized(runLock) { activeRun?.setTerminate(true) }
    }

    private suspend fun checkCurrent(request: Long) {
        coroutineContext.ensureActive()
        if (revision.get() != request) throw CancellationException("翻译已取消")
    }

    private suspend fun generate(ids: LongArray, targetId: Int, run: OrtSession.RunOptions, request: Long): String {
        val shape = longArrayOf(1, ids.size.toLong())
        longTensor(ids, shape).use { input ->
            longTensor(LongArray(ids.size) { 1L }, shape).use { mask ->
                encoder!!.run(mapOf("input_ids" to input, "attention_mask" to mask), run).use { encoded ->
                    checkCurrent(request)
                    val hidden = encoded.tensor("last_hidden_state")
                    OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, 16, 0, 64)).use { empty ->
                        // The uncached first step computes the cross-attention keys/values.
                        val first = step(EOS, false, hidden, mask, empty, null, null, run)
                        val live = mutableSetOf<OrtSession.Result>()
                        try {
                            // Match config.json: num_beams=5, length_penalty=1, early_stopping=true.
                            // Each child's state contains its parent's tokens, before its final token.
                            var beams = listOf(Beam(0.0, listOf(targetId), first))
                            val finished = mutableListOf<Completion>()
                            for (position in 0 until MAX_OUTPUT_TOKENS) {
                                val candidates = ArrayList<Beam>()
                                for (beam in beams) {
                                    checkCurrent(request)
                                    val next = step(beam.tokens.last(), true, hidden, mask, empty, first, beam.state, run)
                                    live += next
                                    for ((token, probability) in topTokens(next.tensor("logits"))) {
                                        candidates += Beam(beam.score + probability, beam.tokens + token, next)
                                    }
                                }
                                candidates.sortByDescending { it.score }
                                val nextBeams = ArrayList<Beam>(BEAM_WIDTH)
                                for ((rank, candidate) in candidates.withIndex()) {
                                    if (candidate.tokens.last() == EOS) {
                                        if (rank < BEAM_WIDTH) {
                                            finished += Completion(
                                                // Decoder prompt is EOS; generated length includes forced target BOS.
                                                candidate.score / candidate.tokens.size,
                                                candidate.tokens.drop(1).dropLast(1),
                                            )
                                        }
                                    } else {
                                        nextBeams += candidate
                                    }
                                    if (nextBeams.size == BEAM_WIDTH) break
                                }
                                finished.sortByDescending { it.score }
                                if (finished.size > BEAM_WIDTH) finished.subList(BEAM_WIDTH, finished.size).clear()
                                if (finished.size == BEAM_WIDTH || nextBeams.isEmpty()) {
                                    checkCurrent(request)
                                    val output = finished.firstOrNull()?.tokens
                                        ?: error("翻译未能生成完整结果，请重试")
                                    Log.i(TAG, "decoded output_tokens=${output.size} beams=$BEAM_WIDTH steps=${position + 1}")
                                    val pieces = output.filter { it > 3 }.map { outputPieces[it] }.toTypedArray()
                                    val decoded = NativeSentencePiece.decodePieces(tokenizer, pieces)
                                        ?: error("M2M100 译文解码失败")
                                    return decoded.toString(Charsets.UTF_8).trim()
                                }
                                val retained = nextBeams.map { it.state }.toSet()
                                val obsolete = live.filter { it !in retained }
                                obsolete.forEach { it.close(); live.remove(it) }
                                beams = nextBeams
                            }
                            Log.w(TAG, "generation_limit input_tokens=${ids.size} beams=$BEAM_WIDTH steps=$MAX_OUTPUT_TOKENS completed=${finished.size}")
                            error("翻译未能生成完整结果，请重试")
                        } finally {
                            live.forEach { it.close() }
                            first.close()
                        }
                    }
                }
            }
        }
    }

    private fun step(
        token: Int, cached: Boolean, hidden: OnnxTensor, mask: OnnxTensor, empty: OnnxTensor,
        cross: OrtSession.Result?, self: OrtSession.Result?, run: OrtSession.RunOptions,
    ): OrtSession.Result = longTensor(longArrayOf(token.toLong()), longArrayOf(1, 1)).use { input ->
        val flag = ByteBuffer.allocateDirect(1).put(if (cached) 1.toByte() else 0.toByte()).apply { rewind() }
        OnnxTensor.createTensor(environment, flag, longArrayOf(1), OnnxJavaType.BOOL).use { branch ->
            val inputs = mutableMapOf("input_ids" to input, "encoder_hidden_states" to hidden,
                "encoder_attention_mask" to mask, "use_cache_branch" to branch)
            for (layer in 0 until LAYERS) {
                for (kind in listOf("key", "value")) {
                    inputs["past_key_values.$layer.encoder.$kind"] = cross?.tensor("present.$layer.encoder.$kind") ?: empty
                    inputs["past_key_values.$layer.decoder.$kind"] = self?.tensor("present.$layer.decoder.$kind") ?: empty
                }
            }
            decoder!!.run(inputs, run)
        }
    }

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(values.size * Long.SIZE_BYTES).order(ByteOrder.nativeOrder()).asLongBuffer()
        buffer.put(values).rewind()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    private fun OrtSession.Result.tensor(name: String) = get(name).orElseThrow {
        IllegalStateException("M2M100 输出缺失：$name")
    } as OnnxTensor

    private data class Beam(val score: Double, val tokens: List<Int>, val state: OrtSession.Result)
    private data class Completion(val score: Double, val tokens: List<Int>)

    private fun topTokens(tensor: OnnxTensor): List<Pair<Int, Double>> {
        val values = tensor.floatBuffer
        check(values.remaining() == modelVocabularySize) { "M2M100 logits 形状不匹配" }
        var maximum = Double.NEGATIVE_INFINITY
        for (i in 0 until modelVocabularySize) maximum = maxOf(maximum, values.get(i).toDouble())
        check(maximum.isFinite()) { "M2M100 logits 数值异常" }
        var total = 0.0
        for (i in 0 until modelVocabularySize) total += exp(values.get(i).toDouble() - maximum)
        val normalizer = maximum + ln(total)
        check(normalizer.isFinite()) { "M2M100 logits 数值异常" }
        // Normalize before suppression, matching standard log_softmax + logits processors.
        val best = ArrayList<Pair<Int, Double>>(BEAM_WIDTH * 2)
        for (i in EOS until vocabularySize) {
            val score = values.get(i).toDouble() - normalizer
            if (best.size == BEAM_WIDTH * 2 && score <= best.last().second) continue
            val position = best.indexOfFirst { score > it.second }.let { if (it < 0) best.size else it }
            best.add(position, i to score)
            if (best.size > BEAM_WIDTH * 2) best.removeAt(best.lastIndex)
        }
        return best
    }

    private suspend fun ensureLoaded() {
        if (encoder != null && decoder != null) return
        val started = SystemClock.elapsedRealtime()
        val files = ModelStore.prepareM2m(app)
        var loadedEncoder: OrtSession? = null
        var loadedDecoder: OrtSession? = null
        var loadedTokenizer = 0L
        try {
            DataInputStream(files.mapping.inputStream().buffered()).use { input ->
                check(input.readInt() == 0x4D324D32) { "M2M100 词表映射格式错误" }
                val spSize = input.readInt()
                val modelSize = input.readInt()
                vocabularySize = input.readInt()
                chineseId = input.readInt()
                englishId = input.readInt()
                check(spSize == 128000 && modelSize == 128112 && vocabularySize == 128004 &&
                    chineseId == 128102 && englishId == 128022) { "M2M100 词表不匹配" }
                forward = IntArray(spSize) { input.readInt() }
                modelVocabularySize = modelSize
                outputPieces = Array(vocabularySize) {
                    val length = input.readInt()
                    check(length in 0..1024) { "M2M100 词表片段长度错误" }
                    ByteArray(length).also { input.readFully(it) }
                }
                check(input.read() == -1) { "M2M100 映射文件不完整" }
            }
            loadedTokenizer = NativeSentencePiece.create(files.spm.absolutePath)
            check(loadedTokenizer != 0L) { "M2M100 SentencePiece 加载失败" }
            check(files.decoderRuntime == environment.version) { "内置优化解码器与推理库版本不匹配，请重新生成模型" }
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4)
                options.setInterOpNumThreads(1)
                options.setMemoryPatternOptimization(false)
                options.setCPUArenaAllocator(false)
                // ALL's graph fusions duplicate large initializers in the merged decoder.
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                loadedEncoder = createAssetSession(files.encoderAsset, options)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                loadedDecoder = createAssetSession(files.decoderAsset, options)
            }
            encoder = loadedEncoder
            decoder = loadedDecoder
            tokenizer = loadedTokenizer
            Log.i(TAG, "loaded backend=ONNX_CPU_APK_PROJECTION elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        } catch (e: Exception) {
            loadedDecoder?.close()
            loadedEncoder?.close()
            if (loadedTokenizer != 0L) NativeSentencePiece.destroy(loadedTokenizer)
            forward = IntArray(0); outputPieces = emptyArray()
            throw e
        }
    }

    private fun createAssetSession(asset: String, options: OrtSession.SessionOptions): OrtSession {
        val started = SystemClock.elapsedRealtime()
        return app.assets.openFd(asset).use { descriptor ->
            MappedModelAsset.openSession(environment, descriptor.parcelFileDescriptor.fd,
                descriptor.startOffset, descriptor.length, options).also {
                Log.i("LEARNIME_M2M_ASSET", "loaded asset=$asset bytes=${descriptor.length} elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        cancel()
        mutex.withLock {
            decoder?.close(); decoder = null
            encoder?.close(); encoder = null
            if (tokenizer != 0L) NativeSentencePiece.destroy(tokenizer)
            tokenizer = 0L
            forward = IntArray(0); outputPieces = emptyArray()
            Log.i(TAG, "unloaded")
        }
    }

    private companion object {
        const val TAG = "LEARNIME_M2M_TRACE"
        const val EOS = 2
        const val LAYERS = 12
        const val BEAM_WIDTH = 5
        const val MAX_INPUT_TOKENS = 256
        const val MAX_OUTPUT_TOKENS = 128
    }
}
