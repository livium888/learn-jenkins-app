package com.flashcardreader.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.flashcardreader.app.data.db.entities.ReviewLog

@Dao
interface ReviewLogDao {

    @Insert
    suspend fun insert(log: ReviewLog)

    @Insert
    suspend fun insertAll(logs: List<ReviewLog>)

    /**
     * The whole history, for the backup. Capped well above a decade of daily study - past that the
     * oldest reviews add nothing a fit can use, and the file stops being something you can email.
     */
    @Query("SELECT * FROM review_logs ORDER BY reviewedAt ASC LIMIT 50000")
    suspend fun getAll(): List<ReviewLog>

    @Query("SELECT COUNT(*) FROM review_logs")
    suspend fun count(): Int

    /**
     * The reviews that follow a card's first answer, paired with how that first answer was rated.
     *
     * This is exactly the shape initial stability is fitted from: "rated GOOD first time, then
     * remembered (or not) n days later". Done as one query rather than loading the whole history,
     * because the fit only ever needs these.
     */
    @Query(
        """
        SELECT first.rating AS firstRating, later.elapsedDays AS elapsedDays, later.rating AS laterRating
        FROM review_logs AS later
        JOIN review_logs AS first
          ON first.cardId = later.cardId
         AND first.cardKind = later.cardKind
         AND first.wasFirstReview = 1
        WHERE later.wasFirstReview = 0
          AND later.elapsedDays > 0
        ORDER BY later.reviewedAt ASC
        LIMIT 20000
        """,
    )
    suspend fun firstAnswerOutcomes(): List<FirstAnswerOutcome>
}

/** One "first rated X, then recalled or not after n days" pair, straight from the query. */
data class FirstAnswerOutcome(
    val firstRating: Int,
    val elapsedDays: Double,
    val laterRating: Int,
)
