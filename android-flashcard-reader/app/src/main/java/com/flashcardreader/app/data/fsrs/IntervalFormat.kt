package com.flashcardreader.app.data.fsrs

import com.flashcardreader.app.data.db.entities.Term
import kotlin.math.roundToInt

/**
 * Human-readable "when will I see this next" labels. Used on the flashcard rating
 * buttons (so a choice's consequence is visible before you tap) and in the word
 * list (to show each card's current schedule).
 */
object IntervalFormat {
    private val fsrs = Fsrs()

    /** Time until this term would next be due if answered [rating] right now, e.g. "3 d". */
    fun nextLabel(term: Term, rating: Rating): String {
        val now = System.currentTimeMillis()
        val due = fsrs.review(term, rating, now).due ?: now
        return humanize(due - now)
    }

    /** A card's current schedule state: "New", "Due now", or "Due in 5 d". */
    fun dueLabel(term: Term): String {
        val now = System.currentTimeMillis()
        val due = term.due
        return when {
            due == null -> "New"
            due <= now -> "Due now"
            else -> "Due in ${humanize(due - now)}"
        }
    }

    private fun humanize(ms: Long): String {
        val minutes = ms / 60000.0
        return when {
            minutes < 1 -> "<1 min"
            minutes < 60 -> "${minutes.roundToInt()} min"
            minutes < 1440 -> "${(minutes / 60).roundToInt()} h"
            minutes < 1440 * 30 -> "${(minutes / 1440).roundToInt()} d"
            minutes < 1440 * 365 -> "${(minutes / 1440 / 30).roundToInt()} mo"
            else -> "${(minutes / 1440 / 365).roundToInt()} y"
        }
    }
}
