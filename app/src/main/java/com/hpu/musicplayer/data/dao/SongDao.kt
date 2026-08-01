package com.hpu.musicplayer.data.dao

import androidx.room.*
import com.hpu.musicplayer.data.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): Song?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<Song>)

    @Delete
    suspend fun delete(song: Song)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()

    @Update
    suspend fun update(song: Song)

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteSongs(): Flow<List<Song>>


    @Query("SELECT * FROM songs WHERE path = :path LIMIT 1")
    suspend fun getSongByPath(path: String): Song?

    @Query("DELETE FROM songs WHERE path NOT IN (:existingPaths)")
    suspend fun deleteMissingSongs(existingPaths: List<String>)


    // 一次性获取完整库（非 Flow），与 getAllSongs() 的展示顺序保持一致
    @Query("SELECT * FROM songs ORDER BY title ASC")
    suspend fun getAllSongsOnce(): List<Song>
}