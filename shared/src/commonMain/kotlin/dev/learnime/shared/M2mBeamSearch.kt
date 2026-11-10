package dev.learnime.shared

/** Model-independent search; native adapters retain the attention state identified by [state]. */
data class M2mBeam(val score: Double, val tokens: List<Int>, val state: Int) {
    val lastToken: Int get() = tokens.last()
}

class M2mBeamSearch(targetId: Int) {
    var beams: List<M2mBeam> = listOf(M2mBeam(0.0, listOf(targetId), 0))
        private set
    private val candidates = mutableListOf<M2mBeam>()
    private val finished = mutableListOf<M2mBeam>()
    var done: Boolean = false
        private set
    val output: List<Int> get() = finished.firstOrNull()?.tokens
        ?: error("翻译未能生成完整结果，请重试")

    fun add(beam: M2mBeam, token: Int, logProbability: Double, state: Int) {
        candidates += M2mBeam(beam.score + logProbability, beam.tokens + token, state)
    }

    fun advance() {
        candidates.sortByDescending { it.score }
        val next = mutableListOf<M2mBeam>()
        for ((rank, candidate) in candidates.withIndex()) {
            if (candidate.lastToken == 2) {
                if (rank < 5) {
                    finished += M2mBeam(candidate.score / candidate.tokens.size,
                        candidate.tokens.drop(1).dropLast(1), candidate.state)
                }
            } else {
                next += candidate
            }
            if (next.size == 5) break
        }
        finished.sortByDescending { it.score }
        if (finished.size > 5) finished.subList(5, finished.size).clear()
        done = finished.size == 5 || next.isEmpty()
        beams = next
        candidates.clear()
    }

    fun retains(state: Int): Boolean = beams.any { it.state == state }
}
