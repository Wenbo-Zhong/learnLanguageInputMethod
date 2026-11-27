package dev.learnime.android

import android.content.Context
import dev.learnime.shared.TranslationEngine
import dev.learnime.shared.OpusModelSpec
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
    external fun create(path: ByteArray): Long
    external fun decodeUtf8(handle: Long, ids: IntArray): ByteArray?
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
            val tokens = NativeSentencePiece.encodeUtf8(tokenizer, source.toByteArray(Charsets.UTF_8))
            OpusModelSpec.sourceError(tokens.size)?.let { throw IllegalArgumentException(it) }
            val encoded = tokens.toMutableList().apply { add(EOS) }
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
            checkNotNull(NativeSentencePiece.decodeUtf8(tokenizer, output.toIntArray())) { "SentencePiece 解码失败" }
                .toString(Charsets.UTF_8).trim()
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
        val loaded = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        var loadedTokenizer = 0L
        try {
            loadedTokenizer = NativeSentencePiece.create(files.sourceSpm.absolutePath.toByteArray(Charsets.UTF_8))
            check(loadedTokenizer != 0L) { "SentencePiece 词表加载失败" }
            interpreter = loaded
            tokenizer = loadedTokenizer
        } catch (error: Throwable) {
            if (loadedTokenizer != 0L) NativeSentencePiece.destroy(loadedTokenizer)
            loaded.close()
            throw error
        }
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
        const val MAX_TOKENS = OpusModelSpec.maxTokens
        const val HIDDEN = OpusModelSpec.hidden
        const val HEADS = OpusModelSpec.heads
        const val HEAD_SIZE = OpusModelSpec.headSize
        const val VOCAB = OpusModelSpec.vocabulary
        const val EOS = OpusModelSpec.eos
        const val START = OpusModelSpec.start
        const val PAD = OpusModelSpec.pad
        const val MASKED_ATTENTION = OpusModelSpec.maskedAttention
        val CACHE_NAMES = OpusModelSpec.cacheNames
    }
}
