package com.flashcardreader.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.flashcardreader.app.data.db.entities.Occurrence

@Dao
interface OccurrenceDao {
    @Insert
    suspend fun insert(occurrence: Occurrence): Long

    @Query("SELECT * FROM occurrences WHERE termId = :termId ORDER BY seenAt DESC")
    suspend fun forTerm(termId: Long): List<Occurrence>

    @Query("SELECT COUNT(*) FROM occurrences WHERE termId = :termId")
    suspend fun countForTerm(termId: Long): Int
}
