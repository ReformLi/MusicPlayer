package com.hpu.musicplayer.data.dao

import androidx.room.*
import com.hpu.musicplayer.data.PlaybackStateEntity

@Dao
interface PlaybackStateDao {
    @Query("SELECT * FROM playback_state WHERE id = 1")
    suspend fun getState(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: PlaybackStateEntity)
}