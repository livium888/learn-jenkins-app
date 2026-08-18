package com.flashcardreader.app.reader

import java.text.Normalizer

/**
 * Decides whether a typed answer actually matches the expected word for a cloze card.
 *
 * This is what makes earned credit un-fakeable: credit comes from *producing* the missing word,
 * never from tapping a self-rating. Matching is deliberately forgiving of spelling (case, accents,
 * punctuation, one typo in longer words) and unforgiving of not knowing - a near-miss on the keys
 * still counts, a guess does not.
 */
object AnswerMatcher {

    /** True when [typed] should be accepted as [expected]. Blank input never passes. */
    fun isCorrect(typed: String, expected: String): Boolean {
        val a = normalize(typed)
        val b = normalize(expected)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // Forgive a single slip (transposition/typo) once the word is long enough that a one-edit
        // neighbour is unlikely to be a different real answer.
        return b.length >= 5 && levenshtein(a, b) <= 1
    }

    /** Lowercase, strip accents and punctuation, collapse whitespace. Keeps multi-word names intact. */
    private fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            // Combining accent marks are neither letters nor digits, so this drops them too.
            if (c.isLetterOrDigit()) sb.append(c) else if (c.isWhitespace()) sb.append(' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = curr
            curr = swap
        }
        return prev[b.length]
    }
}
