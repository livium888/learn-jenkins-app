package com.flashcardreader.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A comprehension question written from a passage the reader genuinely read, and then scheduled
 * like any other card.
 *
 * Deliberately **not** a [Term]. A Term exists to be matched against book text - the scanner walks
 * every page looking for them - and a question about a passage must never be searched for inside
 * prose. They share a scheduler (see FsrsState), not a table.
 *
 * [evidence] is quoted verbatim from the passage and is verified to actually appear there before
 * the card is ever created. It is what gets shown on a wrong answer, so the correction cites the
 * book rather than asking you to take the machine's word for it.
 */
@Entity(tableName = "reading_checks")
data class ReadingCheckCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val question: String,
    val correctAnswer: String,
    /** The three wrong options, stored joined - the order is shuffled fresh at every showing. */
    val distractors: String,
    /** The sentence(s) the answer comes from, quoted from the passage. */
    val evidence: String,
    /** The book it came from, and where in it, so the passage can be found again. */
    val sourceId: Long,
    val charOffset: Int,
    val createdAt: Long,

    // --- FSRS scheduling state (same fields as Term; see data/fsrs/Fsrs.kt) ---
    val difficulty: Double = 0.0,
    val stability: Double = 0.0,
    /** Epoch millis of the next scheduled review. Null = never reviewed yet (due immediately). */
    val due: Long? = null,
    val lastReviewedAt: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val state: CardState = CardState.NEW,
    /** Set when a confident streak was broken here - the hypercorrection effect, as with Terms. */
    val hyperMiss: Boolean = false,
) {
    /** The wrong options, split back out of storage. */
    val wrongOptions: List<String>
        get() = distractors.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /** All four options in random order - shuffled per showing so position never gives it away. */
    fun shuffledOptions(): List<String> = (wrongOptions + correctAnswer).shuffled()

    companion object {
        /** A unit separator: it cannot occur in prose, so no answer can ever split itself. */
        const val SEPARATOR = "\u001F"

        /** One right answer and three wrong ones. */
        const val REQUIRED_OPTIONS = 4

        fun joinDistractors(values: List<String>): String = values.joinToString(SEPARATOR)
    }
}
