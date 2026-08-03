package com.flashcardreader.app.reader

import com.flashcardreader.app.data.db.entities.Term
import com.flashcardreader.app.data.fsrs.Fsrs
import java.util.regex.Pattern

/** [contextSentence] is only populated for due matches - the occurrence log doesn't need it. */
data class TermMatch(val term: Term, val range: IntRange, val contextSentence: String = "")

/**
 * Finds occurrences of tracked terms in a page of text, restricted to terms
 * that FSRS says are due. This is what makes the interstitial global and
 * spacing-aware rather than "quiz on literally every occurrence": a term you
 * just reviewed a minute ago won't fire again just because it appears twice
 * on the same page or in the very next paragraph of a completely different
 * source.
 */
class TermScanner(private val fsrs: Fsrs) {
    fun findDueMatches(pageText: String, allTerms: List<Term>, now: Long): List<TermMatch> {
        val matches = mutableListOf<TermMatch>()
        for (term in allTerms) {
            if (!fsrs.isDue(term, now)) continue
            val pattern = Pattern.compile(
                "\\b" + Pattern.quote(term.normalizedText) + "\\b",
                Pattern.CASE_INSENSITIVE,
            )
            val matcher = pattern.matcher(pageText)
            if (matcher.find()) {
                val context = ContextExtractor.sentenceAround(pageText, matcher.start(), matcher.end())
                matches.add(TermMatch(term, matcher.start()..matcher.end(), context))
            }
        }
        return matches.sortedBy { it.range.first }
    }

    /** All terms present on the page regardless of due-ness, for the silent occurrence log. */
    fun findAllMatches(pageText: String, allTerms: List<Term>): List<TermMatch> {
        val matches = mutableListOf<TermMatch>()
        for (term in allTerms) {
            val pattern = Pattern.compile(
                "\\b" + Pattern.quote(term.normalizedText) + "\\b",
                Pattern.CASE_INSENSITIVE,
            )
            val matcher = pattern.matcher(pageText)
            if (matcher.find()) {
                matches.add(TermMatch(term, matcher.start()..matcher.end()))
            }
        }
        return matches
    }
}
