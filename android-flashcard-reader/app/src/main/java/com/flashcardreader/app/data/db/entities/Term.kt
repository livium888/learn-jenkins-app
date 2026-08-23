package com.flashcardreader.app.data.db.entities

import androidx.room.ColumnInfo
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
    /**
     * Three wrong definitions, so this card can be answered with one tap instead of being revealed
     * and self-graded. Empty on cards written by hand, which keep the older reveal-and-rate flow.
     *
     * Stored joined with the same unit separator [ReadingCheckCard] uses, and declared with the
     * identical default here and in the migration - Room compares the two after every upgrade and
     * refuses to open a database when they differ.
     */
    @ColumnInfo(defaultValue = "")
    val distractors: String = "",
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

    // --- Encoding boosters (see data model migration v2) ---
    /** Marked "curious": rides the curiosity/dopamine memory boost (Gruber & Ranganath). */
    val curious: Boolean = false,
    /** "Why does this matter to me?" - the self-reference effect, a strong encoding boost. */
    val selfNote: String = "",
    /** Set when you were confident but got it wrong - the hypercorrection effect: such
     * errors, once corrected, are unusually well remembered, so they're worth flagging. */
    val hyperMiss: Boolean = false,
) {
    /** The three wrong definitions, or empty when this card has none and must be revealed instead. */
    val wrongDefinitions: List<String>
        get() = distractors.split(ReadingCheckCard.SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /** True when this card can be answered by tapping one of four definitions. */
    val isMultipleChoice: Boolean get() = wrongDefinitions.size == ReadingCheckCard.REQUIRED_OPTIONS - 1
}

enum class CardState { NEW, LEARNING, REVIEW, RELEARNING }
