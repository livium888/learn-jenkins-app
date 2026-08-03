package com.flashcardreader.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.flashcardreader.app.data.db.entities.Source
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Insert
    suspend fun insert(source: Source): Long

    @Update
    suspend fun update(source: Source)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM sources ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Source>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: Long): Source?
}
