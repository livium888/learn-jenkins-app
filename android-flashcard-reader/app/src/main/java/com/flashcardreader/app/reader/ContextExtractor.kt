package com.flashcardreader.app.reader

/**
 * Pulls the sentence surrounding a matched term out of its source text, so
 * the flashcard interstitial can show *where* you met the word instead of
 * just the bare word + your definition. Grounded in levels-of-processing /
 * encoding-specificity: recall is stronger when the retrieval context
 * echoes the context the word was actually encountered in.
 */
object ContextExtractor {
    private const val MAX_LOOKAROUND = 400
    private val ENDERS = charArrayOf('.', '!', '?', '\n')

    fun sentenceAround(text: String, matchStart: Int, matchEnd: Int): String {
        if (text.isEmpty()) return ""
        val start = matchStart.coerceIn(0, text.length)
        val end = matchEnd.coerceIn(start, text.length)

        var begin = start
        val minBegin = (start - MAX_LOOKAROUND).coerceAtLeast(0)
        while (begin > minBegin && text[begin - 1] !in ENDERS) begin--

        var finish = end
        val maxFinish = (end + MAX_LOOKAROUND).coerceAtMost(text.length)
        while (finish < maxFinish && text[finish] !in ENDERS) finish++
        if (finish < text.length && text[finish] in ENDERS) finish++ // keep the punctuation

        return text.substring(begin, finish).trim()
    }
}
