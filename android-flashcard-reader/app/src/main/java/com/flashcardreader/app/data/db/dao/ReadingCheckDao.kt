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

    @Query("SELECT COUNT(*) FROM reading_checks")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM reading_checks WHERE due IS NULL OR due <= :now")
    suspend fun dueCount(now: Long): Int

    /** Answered at least once and never missed - the ones that have actually stuck. */
    @Query("SELECT COUNT(*) FROM reading_checks WHERE reps > 0 AND lapses = 0")
    suspend fun rememberedCount(): Int

    @Query("SELECT COUNT(*) FROM reading_checks WHERE reps > 0")
    suspend fun answeredCount(): Int

    /** Cleans up with the book, so deleting a source doesn't leave its questions behind. */
    @Query("DELETE FROM reading_checks WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}
