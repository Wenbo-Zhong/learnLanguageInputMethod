package dev.learnime.shared

/** Platform adapters provide resource text; ranking and correction are shared. */
class EnglishLexicon(text: String) {
    private val frequencies = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size != 2 || parts[0].isEmpty() || parts[0].any { it !in 'a'..'z' }) null
        else parts[1].toLongOrNull()?.let { parts[0] to it }
    }.toMap()
    private val sorted = frequencies.keys.sorted()
    private val nine = frequencies.keys.groupBy { word ->
        word.map { c -> "22233344455566677778889999"[c - 'a'] }.joinToString("")
    }
    fun suggestions(input: String): List<String> {
        val word = input.lowercase()
        if (word.isEmpty() || word.length > 40) return listOf(input)
        val edits = HashSet<String>()
        if (word.length >= 3 && word !in frequencies) {
            for (i in word.indices) {
                edits += word.removeRange(i, i + 1)
                for (c in 'a'..'z') edits += word.substring(0, i) + c + word.substring(i + 1)
                if (i < word.lastIndex) edits += word.substring(0, i) + word[i+1] + word[i] + word.substring(i+2)
            }
            for (i in 0..word.length) for (c in 'a'..'z') edits += word.substring(0, i) + c + word.substring(i)
        }
        val corrections = edits.filter { it in frequencies }.sortedWith(ranking).take(3)
        var start = sorted.binarySearch(word).let { if (it < 0) -it-1 else it }
        val completions = ArrayList<String>()
        while (start < sorted.size && sorted[start].startsWith(word) && completions.size < 5000) completions += sorted[start++]
        return (listOf(word) + corrections + completions.sortedWith(ranking).take(5)).distinct().take(8).map {
            when {
                input.all(Char::isUpperCase) -> it.uppercase()
                input.first().isUpperCase() -> it.replaceFirstChar(Char::uppercaseChar)
                else -> it
            }
        }
    }
    private val ranking get() = compareByDescending<String> { frequencies[it] ?: 0L }.thenBy { it }
    fun correction(input: String): String {
        if (input.lowercase() in frequencies) return input
        return suggestions(input).drop(1).firstOrNull { distance(input.lowercase(), it.lowercase()) <= 1 } ?: input
    }
    fun nineSuggestions(input: String): List<String> =
        if (input.isEmpty() || input.any { it !in '2'..'9' }) emptyList()
        else nine[input].orEmpty().sortedWith(ranking).take(4)
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length+1) { IntArray(b.length+1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i-1][j]+1, d[i][j-1]+1, d[i-1][j-1] + if (a[i-1] == b[j-1]) 0 else 1)
            if (i > 1 && j > 1 && a[i-1] == b[j-2] && a[i-2] == b[j-1]) d[i][j] = minOf(d[i][j], d[i-2][j-2]+1)
        }
        return d[a.length][b.length]
    }
}

/** Longest phrase conversion is independent of UIKit/Android and resource IO. */
class TraditionalText(characters: String, phrases: String) {
    private val mapping = HashMap<String, String>()
    private var maximum = 1
    init {
        sequenceOf(characters, phrases).forEach { content -> content.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0].isNotEmpty()) {
                mapping[parts[0]] = parts[1].substringBefore(' ')
                maximum = maxOf(maximum, parts[0].length)
            }
        } }
    }
    fun convert(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            var count = minOf(maximum, text.length-index)
            while (count > 1 && text.substring(index, index+count) !in mapping) count--
            val key = text.substring(index, index+count); append(mapping[key] ?: key); index += count
        }
    }
}
