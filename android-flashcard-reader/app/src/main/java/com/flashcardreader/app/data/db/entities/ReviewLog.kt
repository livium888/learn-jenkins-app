package com.flashcardreader.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Which kind of card a review belonged to, so the two can be fitted together or apart. */
enum class CardKind { TERM, READING_CHECK }

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
)
