package com.flashcardreader.app.ai

import org.json.JSONObject

/** One claim about the chapter, and whether the chapter actually made it. */
data class Proposition(
    val text: String,
    val said: Boolean,
    /** The sentence it came from, for a said one. Empty for one the chapter never made. */
    val evidence: String,
)

/** One step in the chapter's argument, with the sentence that anchors it in the text. */
data class Hint(
    val text: String,
    val anchor: String,
    /** Where [anchor] falls in the chapter. The order of these is the book's, not the model's. */
    val position: Int,
)

/** Everything the pass asks about one chapter. */
data class ChapterRecall(
    val propositions: List<Proposition>,
    val hints: List<Hint>,
) {
    companion object {
        /**
         * Enough propositions that tapping them all is not a strategy, few enough to read.
         * The mix matters more than the count: a set that is all-true or all-false teaches
         * nothing except that tapping everything works.
         */
        const val MIN_PROPOSITIONS = 6
        const val MAX_PROPOSITIONS = 8
        const val MIN_OF_EACH_KIND = 2

        /** Franklin used one hint per sentence; a chapter's worth of those is unusable on a phone. */
        const val MIN_HINTS = 5
        const val MAX_HINTS = 7

        /**
         * How far apart two hint anchors must sit in the normalised chapter, in characters.
         *
         * Without this, "five steps in the argument" can be five quotes from the same paragraph,
         * and putting them in order stops being a question about the chapter's structure.
         */
        const val MIN_ANCHOR_GAP = 200

        /**
         * Parses and validates a reply. Returns null when the batch cannot be trusted.
         *
         * Deliberately all-or-nothing, unlike [ReadingCheck.parseAll], which drops bad items and
         * keeps the rest. A recall set is one artefact: drop two propositions and the mix can
         * become all-true, drop a hint and the sequence has a hole in it.
         */
        fun parse(reply: String, chapterText: String): ChapterRecall? {
            val json = ReadingCheck.extractJsonObject(reply) ?: return null
            val propositions = parsePropositions(json, chapterText) ?: return null
            val hints = parseHints(json, chapterText) ?: return null
            return ChapterRecall(propositions, hints)
        }

        private fun parsePropositions(json: JSONObject, chapterText: String): List<Proposition>? {
            val array = json.optJSONArray("propositions") ?: return null
            val seen = HashSet<String>()
            val out = mutableListOf<Proposition>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("text").trim()
                if (text.isEmpty()) continue
                if (!seen.add(text.lowercase())) continue
                val said = item.optBoolean("said")
                val evidence = item.optString("evidence").trim()
                if (said) {
                    // A claim the chapter made has to be quotable from it. Same guard, and the same
                    // reason, as the one on reading-check evidence.
                    if (!ReadingCheck.appearsIn(evidence, chapterText)) return null
                } else {
                    // And one it never made must not turn out to be in the text after all - that
                    // would mark a correct answer wrong, which is worse than asking nothing.
                    if (evidence.isNotEmpty() && ReadingCheck.appearsIn(evidence, chapterText)) return null
                    if (ReadingCheck.appearsIn(text, chapterText)) return null
                }
                out += Proposition(text, said, if (said) evidence else "")
            }
            if (out.size !in MIN_PROPOSITIONS..MAX_PROPOSITIONS) return null
            if (out.count { it.said } < MIN_OF_EACH_KIND) return null
            if (out.count { !it.said } < MIN_OF_EACH_KIND) return null
            return out
        }

        private fun parseHints(json: JSONObject, chapterText: String): List<Hint>? {
            val array = json.optJSONArray("hints") ?: return null
            val out = mutableListOf<Hint>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("hint").trim()
                val anchor = item.optString("anchor").trim()
                if (text.isEmpty() || anchor.isEmpty()) continue
                val position = ReadingCheck.positionIn(anchor, chapterText)
                if (position < 0) return null
                out += Hint(text, anchor, position)
            }
            if (out.size !in MIN_HINTS..MAX_HINTS) return null

            // The whole task rests on the true order being checkable. If the model listed its steps
            // out of order, or anchored several of them to the same passage, there is no sequence
            // to reconstruct and the batch is worthless.
            for (i in 1 until out.size) {
                if (out[i].position - out[i - 1].position < MIN_ANCHOR_GAP) return null
            }
            return out
        }
    }
}
