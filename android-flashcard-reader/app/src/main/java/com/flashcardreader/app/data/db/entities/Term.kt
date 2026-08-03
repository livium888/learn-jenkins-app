package com.flashcardreader.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A flashcard term/phrase. This is intentionally NOT tied to any single book or
 * article: the same global term list is scanned against every source the user
 * opens, so a word tagged in one PDF will still trigger its flashcard when it
 * shows up in an unrelated web article later.
 *
 * FSRS fields (difficulty/stability/due/state/reps/lapses) implement the
 * open-spaced-repetition "DSR" scheduling model - see data/fsrs/Fsrs.kt.
 */
@Entity(tableName = "terms")
data class Term(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Lowercased, whitespace-normalized form used for matching while scanning text. */
    val normalizedText: String,
    /** Original casing, shown in the UI. */
    val displayText: String,
    /** The user's own answer - what they think/know the term means. */
    val definition: String,
    val createdAt: Long,

    // --- FSRS scheduling state ---
    val difficulty: Double = 0.0,
    val stability: Double = 0.0,
    /** Epoch millis of the next scheduled review. Null = never reviewed yet (due immediately). */
    val due: Long? = null,
    val lastReviewedAt: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val state: CardState = CardState.NEW,
)

enum class CardState { NEW, LEARNING, REVIEW, RELEARNING }
