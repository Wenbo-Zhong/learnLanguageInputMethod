package dev.learnime.shared

/** Tensor and vocabulary contracts; adapters own native memory and inference calls. */
object OpusModelSpec {
    const val maxTokens = 256
    const val hidden = 256
    const val heads = 8
    const val headSize = 32
    const val vocabulary = 32001
    const val eos = 0
    const val start = 32000
    const val pad = 32000
    const val maskedAttention = -1.0e9f
    val cacheNames = listOf(
        "cross_attn_kv_cache_k_0", "cross_attn_kv_cache_k_1",
        "cross_attn_kv_cache_v_0", "cross_attn_kv_cache_v_1",
        "self_attn_kv_cache_k_0", "self_attn_kv_cache_k_1",
        "self_attn_kv_cache_v_0", "self_attn_kv_cache_v_1",
    )
    fun sourceError(tokenCount: Int): String? = when {
        tokenCount <= 0 -> "请输入中文句子"
        tokenCount >= maxTokens -> "句子分词后过长，请缩短句子"
        else -> null
    }
}

object M2mModelSpec {
    const val eos = 2
    const val layers = 12
    const val beamWidth = 5
    const val maxInputTokens = 256
    const val maxOutputTokens = 128
    const val sentencePieceSize = 128000
    const val vocabulary = 128112
    const val pieces = 128004
    const val chinese = 128102
    const val english = 128022
    fun matchesVocabulary(size: Int, modelSize: Int, pieceCount: Int, chineseId: Int, englishId: Int): Boolean =
        size == sentencePieceSize && modelSize == vocabulary && pieceCount == pieces &&
            chineseId == chinese && englishId == english
}
