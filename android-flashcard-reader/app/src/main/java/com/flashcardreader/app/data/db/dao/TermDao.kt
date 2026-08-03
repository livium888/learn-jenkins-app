package com.flashcardreader.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.flashcardreader.app.data.db.entities.Term
import kotlinx.coroutines.flow.Flow

@Dao
interface TermDao {
    @Insert
    suspend fun insert(term: Term): Long

    @Update
    suspend fun update(term: Term)

    @Query("DELETE FROM terms WHERE id = :termId")
    suspend fun delete(termId: Long)

    @Query("SELECT * FROM terms ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Term>>

    @Query("SELECT * FROM terms WHERE id = :id")
    suspend fun getById(id: Long): Term?

    @Query("SELECT * FROM terms WHERE normalizedText = :normalizedText LIMIT 1")
    suspend fun findByNormalizedText(normalizedText: String): Term?

    /**
     * The whole global vocabulary list, used by the term scanner to find matches
     * in newly rendered page text. Loaded once per reading session and kept in
     * memory (see TermScanner) rather than re-queried per page.
     */
    @Query("SELECT * FROM terms")
    suspend fun getAll(): List<Term>

    @Query("SELECT * FROM terms WHERE due IS NOT NULL AND due <= :now ORDER BY due ASC")
    suspend fun getDue(now: Long): List<Term>
}
