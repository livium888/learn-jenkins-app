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

    /**
     * Cards waiting to be answered.
     *
     * A null `due` means "never scheduled", which is how every AI-written word arrives - and it is
     * due now, exactly as [com.flashcardreader.app.data.fsrs.Fsrs.isDue], StatsViewModel and
     * ReadingCheckDao all already treat it. Requiring `due IS NOT NULL` made the evening reminder
     * count zero after a session that wrote eight new words, while Progress said eight were due.
     */
    @Query("SELECT * FROM terms WHERE due IS NULL OR due <= :now ORDER BY due IS NOT NULL, due ASC")
    suspend fun getDue(now: Long): List<Term>
}
