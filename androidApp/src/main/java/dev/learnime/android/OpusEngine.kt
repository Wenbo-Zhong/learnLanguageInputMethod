package dev.learnime.android

import android.content.Context
import dev.learnime.shared.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong

internal object NativeSentencePiece {
    init { System.loadLibrary("learnime") }
    external fun create(path: String): Long
    external fun encode(handle: Long, text: String): IntArray
    external fun decode(handle: Long, ids: IntArray): String
    external fun destroy(handle: Long)
    external fun encodeUtf8(handle: Long, text: ByteArray): IntArray
    external fun decodePieces(handle: Long, pieces: Array<ByteArray>): ByteArray?
}

/** Retained LiteRT runner for OPUS-MT tiny's encode/prefill/decode signatures. */
internal class OpusEngine(context: Context) : TranslationEngine {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val cancelRevision = AtomicLong()
    private var interpreter: Interpreter? = null
    private var tokenizer = 0L

    val backendLabel: String = "LiteRT CPU"

    override suspend fun translate(source: String): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val request = cancelRevision.get()
            ensureLoaded()
            val encoded = NativeSentencePiece.encode(tokenizer, source).take(MAX_TOKENS - 1).toMutableList().apply { add(EOS) }
            require(encoded.isNotEmpty()) { "请输入中文句子" }
            val ids = IntArray(MAX_TOKENS) { PAD }.also { out -> encoded.forEachIndexed { i, id -> out[i] = id } }
            // This model's pad_mask is added to attention logits, not a 0/1 keep mask.
            val mask = FloatArray(MAX_TOKENS) { i -> if (i < encoded.size) 0f else MASKED_ATTENTION }
            val positions = IntArray(MAX_TOKENS) { it }
            val hidden = Array(1) { Array(MAX_TOKENS) { FloatArray(HIDDEN) } }
            interpreter!!.runSignature(
                mapOf("input_ids" to arrayOf(ids), "input_pos" to positions, "pad_mask" to mask),
                mapOf("output_0" to hidden), "encode"
            )
            checkCurrent(request)
            val caches = CACHE_NAMES.associateWith { zeros4d() as Any }.toMutableMap()
            val firstLogits = logits()
            val prefillOutputs = caches.toMutableMap().apply { put("logits", firstLogits) }
            interpreter!!.runSignature(
                decoderInputs(START, 0, hidden, mask, caches), prefillOutputs, "prefill"
            )
            caches.putAll(prefillOutputs.filterKeys { it in CACHE_NAMES })
            var next = argmax(firstLogits[0][0])
            val output = ArrayList<Int>()
            for (position in 1 until MAX_TOKENS) {
                checkCurrent(request)
                if (next == EOS) break
                output += next
                val stepLogits = logits()
                val stepOutputs = caches.toMutableMap().apply { put("logits", stepLogits) }
                interpreter!!.runSignature(
                    decoderInputs(next, position, hidden, mask, caches), stepOutputs, "decode"
                )
                caches.putAll(stepOutputs.filterKeys { it in CACHE_NAMES })
                next = argmax(stepLogits[0][0])
            }
            checkCurrent(request)
            NativeSentencePiece.decode(tokenizer, output.toIntArray()).trim()
        }
    }

    override fun cancel() { cancelRevision.incrementAndGet() }
    private fun checkCurrent(request: Long) {
        if (request != cancelRevision.get()) throw CancellationException("翻译已取消")
    }

    private suspend fun ensureLoaded() {
        if (interpreter != null) return
        val files = ModelStore.prepare(app)
        val model = FileInputStream(files.model).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, files.model.length()) }
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        tokenizer = NativeSentencePiece.create(files.sourceSpm.absolutePath)
        check(tokenizer != 0L) { "SentencePiece 词表加载失败" }
    }

    private fun decoderInputs(token: Int, position: Int, hidden: Array<Array<FloatArray>>, mask: FloatArray,
                              caches: Map<String, Any>): Map<String, Any> =
        buildMap {
            put("decoder_input_ids", arrayOf(intArrayOf(token)))
            put("decoder_input_pos", intArrayOf(position))
            put("encoder_hidden_states", hidden)
            put("pad_mask", mask)
            caches.forEach { (name, value) -> put(name, value) }
        }

    private fun zeros4d() = Array(1) { Array(MAX_TOKENS) { Array(HEADS) { FloatArray(HEAD_SIZE) } } }
    private fun logits() = Array(1) { Array(1) { FloatArray(VOCAB) } }
    private fun argmax(values: FloatArray): Int {
        var best = 0
        for (i in 1 until values.size) if (values[i] > values[best]) best = i
        return best
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        cancel()
        mutex.withLock {
            interpreter?.close(); interpreter = null
            if (tokenizer != 0L) NativeSentencePiece.destroy(tokenizer)
            tokenizer = 0L
        }
    }

    private companion object {
        const val MAX_TOKENS = 256
        const val HIDDEN = 256
        const val HEADS = 8
        const val HEAD_SIZE = 32
        const val VOCAB = 32001
        const val EOS = 0
        const val START = 32000
        const val PAD = 32000
        const val MASKED_ATTENTION = -1.0e9f
        val CACHE_NAMES = listOf(
            "cross_attn_kv_cache_k_0", "cross_attn_kv_cache_k_1",
            "cross_attn_kv_cache_v_0", "cross_attn_kv_cache_v_1",
            "self_attn_kv_cache_k_0", "self_attn_kv_cache_k_1",
            "self_attn_kv_cache_v_0", "self_attn_kv_cache_v_1",
        )
    }
}
