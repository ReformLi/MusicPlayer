package com.hpu.musicplayer.repository

import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.Song
import kotlinx.coroutines.flow.Flow

class MusicRepository private constructor() {

    private lateinit var database: AppDatabase

    companion object {
        @Volatile
        private var INSTANCE: MusicRepository? = null

        fun getInstance(): MusicRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MusicRepository().also { INSTANCE = it }
            }
        }
    }

    fun initialize(database: AppDatabase) {
        this.database = database
    }

    fun getAllSongs(): Flow<List<Song>> {
        return database.songDao().getAllSongs()
    }

//    fun getFavoriteSongs(): Flow<List<Song>> {
//        return database.songDao().getFavoriteSongs()
//    }

//    fun getRecentlyPlayedSongs(): Flow<List<Song>> {
//        return database.recentPlayDao().getRecentSongs()
//    }

//    fun searchSongs(query: String): Flow<List<Song>> {
//        return database.songDao().searchSongs(query)
//    }

//    suspend fun addToRecent(songId: Long) {
//        val recentPlay = RecentPlay(songId = songId, playTime = System.currentTimeMillis())
//        database.recentPlayDao().insertOrUpdateRecentPlay(recentPlay)
//    }
//
//    suspend fun toggleFavorite(songId: Long) {
//        val song = database.songDao().getSongById(songId)
//        song?.let {
//            database.songDao().updateFavoriteStatus(songId, !it.isFavorite)
//        }
//    }
}