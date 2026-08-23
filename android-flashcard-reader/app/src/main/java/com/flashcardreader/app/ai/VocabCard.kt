package com.flashcardreader.app.ai

import org.json.JSONObject

/**
 * A vocabulary card written for the reader rather than by them, from a passage they actually read.
 *
 * Carries its own wrong answers, so it can be answered with one tap from the moment it exists.
 * That is the whole point: writing a card by hand - long-press, dialog, type a definition - is
 * friction this app exists to remove, and a card you have to grade yourself is evidence of nothing.
 */
data class VocabCard(
    val word: String,
    val definition: String,
    /** Exactly three wrong definitions, plausible enough to need the right one to be known. */
    val distractors: List<String>,
)

/**
 * What one stretch of reading produced: questions about it, and words worth keeping from it.
 *
 * Both come back from a single request. The passage is already being uploaded for the questions,
 * so the words cost no extra upload, no second round trip and nothing more to consent to.
 */
data class PassageStudy(
    val checks: List<ReadingCheck>,
    val words: List<VocabCard>,
)

/**
 * Validation for AI-written vocabulary, held to the same standard as the comprehension questions.
 *
 * These become scheduled cards. A card whose "word" is not in the book teaches something the reader
 * never met; a card whose wrong answers are obviously wrong teaches nothing at all and quietly
 * inflates every retention number. Both are worse than no card.
 */
object VocabCards {

    const val REQUIRED_DISTRACTORS = 3

    /** Longest thing still worth calling a word or short phrase. */
    private const val MAX_WORD_CHARS = 40

    /**
     * Reads the words out of a model reply, dropping any that do not check out.
     *
     * A bad word is dropped on its own rather than sinking the batch, matching how the questions
     * in the same reply are handled.
     */
    fun parseAll(json: JSONObject, passage: String, limit: Int = 2): List<VocabCard> {
        val array = json.optJSONArray("words") ?: return emptyList()
        val cards = mutableListOf<VocabCard>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            if (cards.size >= limit) break
            val item = array.optJSONObject(i) ?: continue
            val card = parseOne(item, passage) ?: continue
            if (!seen.add(card.word.lowercase())) continue
            cards.add(card)
        }
        return cards
    }

    private fun parseOne(json: JSONObject, passage: String): VocabCard? {
        val word = json.optString("word").trim()
        val definition = json.optString("definition").trim()
        if (word.isEmpty() || definition.isEmpty()) return null
        if (word.length > MAX_WORD_CHARS) return null

        // The strongest guard: a card must be about something the reader actually met on the page.
        if (!appearsAsWord(word, passage)) return null

        val wrongJson = json.optJSONArray("distractors") ?: return null
        val wrong = (0 until wrongJson.length())
            .map { wrongJson.optString(it).trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (wrong.size != REQUIRED_DISTRACTORS) return null

        // A "wrong" definition that is really the right one makes the card unanswerable.
        if (wrong.any { it.equals(definition, ignoreCase = true) }) return null

        return VocabCard(word, definition, wrong)
    }

    /**
     * Whether [word] occurs in [passage] as a whole word.
     *
     * Whole-word on purpose: "art" must not be accepted because the passage contains "started".
     * Matching is case-insensitive because a word met at the start of a sentence is the same word.
     */
    fun appearsAsWord(word: String, passage: String): Boolean {
        val needle = word.trim().lowercase()
        if (needle.isEmpty()) return false
        val haystack = passage.lowercase()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return false
            val before = at - 1
            val after = at + needle.length
            val boundedLeft = before < 0 || !haystack[before].isLetterOrDigit()
            val boundedRight = after >= haystack.length || !haystack[after].isLetterOrDigit()
            if (boundedLeft && boundedRight) return true
            from = at + 1
        }
    }
}
