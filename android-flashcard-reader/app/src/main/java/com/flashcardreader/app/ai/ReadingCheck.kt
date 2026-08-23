package com.flashcardreader.app.ai

import org.json.JSONObject

/**
 * A comprehension question generated from a passage, with its answer key.
 *
 * [evidence] is quoted from the passage and is checked against it before this object is ever
 * built - see [ReadingCheck.parse].
 */
data class ReadingCheck(
    val question: String,
    val correctAnswer: String,
    /** Exactly three wrong options. */
    val distractors: List<String>,
    val evidence: String,
) {
    companion object {
        const val REQUIRED_DISTRACTORS = 3

        /**
         * Parses the model's reply, returning null for anything that isn't a usable question.
         *
         * Validation here is not defensive politeness, it is the point. These questions become
         * scheduled cards: a hallucinated one doesn't merely waste a moment, it teaches something
         * false and then keeps coming back to teach it again. So a reply is rejected unless it
         * parses, carries a real question, offers exactly three distinct wrong options, and cites
         * evidence that genuinely appears in the passage. Anything short of that is dropped and the
         * round is silently skipped - no question is always better than a wrong one.
         */
        fun parse(reply: String, passage: String): ReadingCheck? {
            val json = extractJson(reply) ?: return null
            return parseOne(json, passage)
        }

        /**
         * Parses a reply that may carry several questions - one per distinct idea the model found
         * in the passage.
         *
         * Each is validated on its own and a bad one is dropped rather than sinking the batch: if
         * three questions come back and one cites evidence that isn't in the text, the other two
         * are still worth asking. Duplicates are dropped too, on both the question and the quoted
         * evidence, because "two key ideas" that cite the same sentence is one idea asked twice.
         *
         * A reply in the old single-question shape still parses, as one question.
         */
        fun parseAll(reply: String, passage: String, limit: Int = MAX_QUESTIONS): List<ReadingCheck> {
            val json = extractJson(reply) ?: return emptyList()
            val array = json.optJSONArray("questions")
                ?: return listOfNotNull(parseOne(json, passage)).take(limit)

            val checks = mutableListOf<ReadingCheck>()
            val seenQuestions = mutableSetOf<String>()
            val seenEvidence = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                if (checks.size >= limit) break
                val item = array.optJSONObject(i) ?: continue
                val check = parseOne(item, passage) ?: continue
                if (!seenQuestions.add(normalize(check.question))) continue
                if (!seenEvidence.add(normalize(check.evidence))) continue
                checks.add(check)
            }
            return checks
        }

        /** The most questions worth asking about one stretch of reading. */
        const val MAX_QUESTIONS = 3

        private fun parseOne(json: JSONObject, passage: String): ReadingCheck? {
            val question = json.optString("question").trim()
            val answer = json.optString("answer").trim()
            val evidence = json.optString("evidence").trim()
            if (question.isEmpty() || answer.isEmpty() || evidence.isEmpty()) return null

            val wrongJson = json.optJSONArray("distractors") ?: return null
            val wrong = (0 until wrongJson.length())
                .map { wrongJson.optString(it).trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
            if (wrong.size != REQUIRED_DISTRACTORS) return null

            // A "wrong" option that is really the right one makes the question unanswerable.
            if (wrong.any { it.equals(answer, ignoreCase = true) }) return null

            // The citation has to be real. This is the single strongest guard against a question
            // about something the passage never said.
            if (!appearsIn(evidence, passage)) return null

            return ReadingCheck(question, answer, wrong, evidence)
        }

        /**
         * Finds the JSON object in a reply. Models like to wrap JSON in prose or a ```json fence,
         * and rejecting an otherwise good answer over its packaging would be its own kind of bug.
         */
        internal fun extractJsonObject(reply: String): JSONObject? = extractJson(reply)

        private fun extractJson(reply: String): JSONObject? {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull()
        }

        /**
         * Whether the quoted evidence really occurs in the passage, comparing on collapsed
         * whitespace so re-wrapped line breaks don't count as a mismatch. Curly and straight
         * quotes are folded together for the same reason: models normalise punctuation silently.
         */
        fun appearsIn(evidence: String, passage: String): Boolean {
            val needle = normalize(evidence)
            if (needle.length < MIN_EVIDENCE_CHARS) return false
            return normalize(passage).contains(needle)
        }

        private const val MIN_EVIDENCE_CHARS = 20

        private fun normalize(text: String): String = text
            .replace('‘', '\'').replace('’', '\'')
            .replace('“', '"').replace('”', '"')
            .replace('—', '-').replace('–', '-')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }
}
