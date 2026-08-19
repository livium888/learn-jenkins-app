package com.flashcardreader.app.reader

import java.text.Normalizer

/**
 * Decides whether a typed answer actually matches the expected word for a cloze card.
 *
 * This is what makes earned credit un-fakeable: credit comes from *producing* the missing word,
 * never from tapping a self-rating. Matching is deliberately forgiving of spelling (case, accents,
 * punctuation, one slip in longer words) and unforgiving of not knowing - a near-miss on the keys
 * still counts, a guess does not.
 */
object AnswerMatcher {

    /** True when [typed] should be accepted as [expected]. Blank input never passes. */
    fun isCorrect(typed: String, expected: String): Boolean {
        val a = normalize(typed)
        val b = normalize(expected)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // Forgive a single slip once the word is long enough that a one-edit neighbour is unlikely
        // to be a different real answer ("cot"/"cat" must still fail).
        return b.length >= 5 && editDistance(a, b) <= 1
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

    /**
     * Damerau-Levenshtein (optimal string alignment): like Levenshtein, but swapping two adjacent
     * letters costs one edit rather than two. That matters because transposition is the most common
     * typing slip - "lantren" for "lantern" is a hit of the keys in the wrong order, not a failure
     * to know the word.
     */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }
}
