package com.hpu.musicplayer.data.dao

import androidx.room.*
import com.hpu.musicplayer.data.PlayHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    fun getAllHistory(): Flow<List<PlayHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: PlayHistory)

    @Delete
    suspend fun delete(history: PlayHistory)   // 单条删除

    @Query("DELETE FROM play_history")
    suspend fun deleteAll()
}