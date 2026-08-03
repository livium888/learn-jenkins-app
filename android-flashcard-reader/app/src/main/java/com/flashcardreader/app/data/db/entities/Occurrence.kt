package com.flashcardreader.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A log entry: "this term was seen in this source at this spot". Purely for
 * history/stats - it never gates whether a flashcard fires. Firing is decided
 * solely by the term's own FSRS due date (see TermScanner), so re-seeing a word
 * you already know well does not spam you with a quiz.
 */
@Entity(tableName = "occurrences")
data class Occurrence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    val sourceId: Long,
    val charOffset: Int,
    val seenAt: Long,
    /** Whether this particular occurrence actually triggered the flashcard interstitial. */
    val triggeredReview: Boolean,
)
