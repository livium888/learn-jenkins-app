package com.flashcardreader.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Which kind of card a review belonged to, so the two can be fitted together or apart. */
enum class CardKind { TERM, READING_CHECK, CHAPTER_RECALL }

/**
 * Where an answer was given, which is what separates understanding something from retaining it.
 *
 * Answering a question seconds after reading the passage, with the page still in front of you, is
 * weak evidence - it mostly shows the words were still in working memory. The same question
 * answered days later, out of context, is the evidence that actually matters. Khan Academy draws
 * exactly this line: its highest level is not earned on the exercise you just did, but on getting
 * it right later in a mixed assessment. Without recording this the app cannot tell the two apart,
 * and "never missed" quietly flatters the easy case.
 */
enum class ReviewContext {
    /** In the reader, immediately after reading the passage the question was written from. */
    READING,

    /** In the reader, but a question from earlier in the book coming round again. */
    REVISIT,

    /** In the review queue - delayed, and mixed in among other books and other kinds of card. */
    REVIEW,

    /** Recorded before this distinction existed. Counts as evidence of nothing in particular. */
    UNKNOWN,
}

/**
 * One answered review, kept so the scheduler can eventually be fitted to how *this* person forgets.
 *
 * FSRS ships published default weights as a cold start, and its authors are explicit that re-fitting
 * them against a real review history is where the accuracy comes from. None of that is possible
 * without a history, and until now the app kept only each card's current state - the answer, but
 * never the working. This is the working.
 *
 * Deliberately append-only and tiny: five numbers per review. A decade of daily study is a few
 * megabytes, and having the raw history means a better fitting method later can be applied to
 * reviews already done rather than starting the clock again.
 */
// The index has to be declared here as well as created in the migration. Room compares the two
// after every upgrade and refuses to open a database whose indices it did not expect - which is
// how this crashed on launch for anyone upgrading, while a clean install was fine.
@Entity(
    tableName = "review_logs",
    indices = [Index(name = "index_review_logs_card", value = ["cardId", "cardKind"])],
)
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val cardKind: CardKind,
    /** 1-4, the FSRS scale. */
    val rating: Int,
    /** Days since that card's previous review; 0 when this was its first. */
    val elapsedDays: Double,
    /** The card's state *before* this answer, which is what a fit has to explain. */
    val stabilityBefore: Double,
    val difficultyBefore: Double,
    /** True when this was the card's very first answer - what initial stability is fitted from. */
    val wasFirstReview: Boolean,
    val reviewedAt: Long,
    /**
     * Where the answer was given. Declared with a default on both sides - here and in the
     * migration - so Room's schema check sees the same column either way; a mismatch there is
     * what crashed the app on launch when this table was first added.
     */
    @ColumnInfo(defaultValue = "UNKNOWN")
    val context: ReviewContext = ReviewContext.UNKNOWN,
)
