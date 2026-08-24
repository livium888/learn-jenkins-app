package com.flashcardreader.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.flashcardreader.app.data.db.entities.ChapterRecallCard

@Dao
interface ChapterRecallDao {

    @Insert
    suspend fun insert(card: ChapterRecallCard): Long

    @Update
    suspend fun update(card: ChapterRecallCard)

    @Delete
    suspend fun delete(card: ChapterRecallCard)

    /** Passes that have come around again. A null due date means never answered, so due now. */
    @Query("SELECT * FROM chapter_recalls WHERE due IS NULL OR due <= :now ORDER BY due IS NOT NULL, due ASC")
    suspend fun due(now: Long): List<ChapterRecallCard>

    @Query("SELECT COUNT(*) FROM chapter_recalls WHERE due IS NULL OR due <= :now")
    suspend fun dueCount(now: Long): Int

    /**
     * The pass already written for a chapter, if there is one.
     *
     * Checked before generating: a chapter is an event that happens once, and re-reading it should
     * bring back the pass that exists rather than paying for a second one.
     */
    @Query("SELECT * FROM chapter_recalls WHERE sourceId = :sourceId AND chapterIndex = :chapterIndex")
    suspend fun forChapter(sourceId: Long, chapterIndex: Int): ChapterRecallCard?

    /** Which chapters of a book already have a pass, so finished ones are not offered twice. */
    @Query("SELECT chapterIndex FROM chapter_recalls WHERE sourceId = :sourceId")
    suspend fun chaptersWithPass(sourceId: Long): List<Int>

    @Query("SELECT * FROM chapter_recalls ORDER BY createdAt ASC")
    suspend fun getAll(): List<ChapterRecallCard>

    /** Books are deleted from the library; their passes should not outlive them. */
    @Query("DELETE FROM chapter_recalls WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}
