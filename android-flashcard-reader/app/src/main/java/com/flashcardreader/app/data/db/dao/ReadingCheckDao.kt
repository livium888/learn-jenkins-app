package com.flashcardreader.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.flashcardreader.app.data.db.entities.ReadingCheckCard
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingCheckDao {

    @Insert
    suspend fun insert(card: ReadingCheckCard): Long

    @Update
    suspend fun update(card: ReadingCheckCard)

    @Delete
    suspend fun delete(card: ReadingCheckCard)

    /** Cards that have come around again. A null due date means never reviewed, so due now. */
    @Query("SELECT * FROM reading_checks WHERE due IS NULL OR due <= :now ORDER BY due IS NOT NULL, due ASC")
    suspend fun due(now: Long): List<ReadingCheckCard>

    @Query("SELECT * FROM reading_checks WHERE id = :id")
    suspend fun byId(id: Long): ReadingCheckCard?

    @Query("SELECT * FROM reading_checks WHERE sourceId = :sourceId ORDER BY charOffset ASC")
    suspend fun forSource(sourceId: Long): List<ReadingCheckCard>

    /** Every question, for the backup. */
    @Query("SELECT * FROM reading_checks ORDER BY createdAt ASC")
    suspend fun getAll(): List<ReadingCheckCard>

    /** Used to skip questions a backup would otherwise restore a second time. */
    @Query("SELECT COUNT(*) FROM reading_checks WHERE question = :question")
    suspend fun countByQuestion(question: String): Int

    @Query("SELECT COUNT(*) FROM reading_checks")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM reading_checks WHERE due IS NULL OR due <= :now")
    suspend fun dueCount(now: Long): Int

    /** Answered at least once and never missed - the ones that have actually stuck. */
    @Query("SELECT COUNT(*) FROM reading_checks WHERE reps > 0 AND lapses = 0")
    suspend fun rememberedCount(): Int

    @Query("SELECT COUNT(*) FROM reading_checks WHERE reps > 0")
    suspend fun answeredCount(): Int

    /**
     * Questions answered correctly at least once, anywhere.
     *
     * Deliberately not the same as [rememberedCount], which counts cards that have simply never
     * been missed. This one is "you have got this right", the weaker of the two claims below.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT c.id) FROM reading_checks c
        JOIN review_logs l ON l.cardId = c.id AND l.cardKind = 'READING_CHECK'
        WHERE l.rating > 1
        """,
    )
    suspend fun everCorrectCount(): Int

    /**
     * Questions answered correctly when the passage was *not* the thing just read.
     *
     * This is the number worth trusting. Answering seconds after reading mostly shows the words
     * were still in working memory; answering days later, out of context, is retention. Ratings
     * above 1 are everything except AGAIN, which is the same line the scheduler draws.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT c.id) FROM reading_checks c
        JOIN review_logs l ON l.cardId = c.id AND l.cardKind = 'READING_CHECK'
        WHERE l.rating > 1 AND l.context IN ('REVISIT', 'REVIEW')
        """,
    )
    suspend fun retainedCount(): Int

    /** The same three numbers per book, so a book that stuck can be told from one that didn't. */
    @Query(
        """
        SELECT c.sourceId AS sourceId,
               COUNT(DISTINCT c.id) AS total,
               COUNT(DISTINCT CASE WHEN l.rating > 1 THEN c.id END) AS correct,
               COUNT(DISTINCT CASE WHEN l.rating > 1 AND l.context IN ('REVISIT', 'REVIEW')
                     THEN c.id END) AS retained
        FROM reading_checks c
        LEFT JOIN review_logs l ON l.cardId = c.id AND l.cardKind = 'READING_CHECK'
        GROUP BY c.sourceId
        ORDER BY total DESC
        """,
    )
    suspend fun masteryByBook(): List<BookMasteryRow>

    /** Cleans up with the book, so deleting a source doesn't leave its questions behind. */
    @Query("DELETE FROM reading_checks WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

/** One book's question counts, straight from the grouped query. */
data class BookMasteryRow(
    val sourceId: Long,
    val total: Int,
    val correct: Int,
    val retained: Int,
)
