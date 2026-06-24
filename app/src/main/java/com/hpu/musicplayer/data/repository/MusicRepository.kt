package com.hpu.musicplayer.data.repository

import android.content.Context
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.PlayHistory
import com.hpu.musicplayer.data.PlaybackStateEntity
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.data.dao.RankEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MusicRepository private constructor(context: Context) {

    private val database = AppDatabase.getDatabase(context.applicationContext)
    private val songDao = database.songDao()
    private val playHistoryDao = database.playHistoryDao()
    private val playbackStateDao = database.playbackStateDao()

    fun getAllSongs(): Flow<List<Song>> = songDao.getAllSongs()

    fun getFavoriteSongs(): Flow<List<Song>> = songDao.getFavoriteSongs()

    fun getAllPlayHistory(): Flow<List<PlayHistory>> = playHistoryDao.getAllHistory()

    fun getPlayHistorySince(since: Long): Flow<List<PlayHistory>> = playHistoryDao.getHistorySince(since)

    fun getRankByCount(since: Long): Flow<List<RankEntry>> = playHistoryDao.getRankByCount(since)

    fun getRankByDuration(since: Long): Flow<List<RankEntry>> = playHistoryDao.getRankByDuration(since)

    suspend fun getSongById(id: Long): Song? = withContext(Dispatchers.IO) {
        songDao.getSongById(id)
    }

    suspend fun getSongByPath(path: String): Song? = withContext(Dispatchers.IO) {
        songDao.getSongByPath(path)
    }

    suspend fun getAllSongsOnce(): List<Song> = withContext(Dispatchers.IO) {
        songDao.getAllSongsOnce()
    }

    suspend fun insertSongs(songs: List<Song>) = withContext(Dispatchers.IO) {
        songDao.insertAll(songs)
    }

    suspend fun updateSong(song: Song) = withContext(Dispatchers.IO) {
        songDao.update(song)
    }

    suspend fun deleteSong(song: Song) = withContext(Dispatchers.IO) {
        songDao.delete(song)
    }

    suspend fun deleteAllSongs() = withContext(Dispatchers.IO) {
        songDao.deleteAll()
    }

    suspend fun deleteMissingSongs(existingPaths: List<String>) = withContext(Dispatchers.IO) {
        songDao.deleteMissingSongs(existingPaths)
    }

    suspend fun savePlaybackState(state: PlaybackStateEntity) = withContext(Dispatchers.IO) {
        playbackStateDao.saveState(state)
    }

    suspend fun getPlaybackState(): PlaybackStateEntity? = withContext(Dispatchers.IO) {
        playbackStateDao.getState()
    }

    /** 开始播放时插入一条历史记录，返回自增 ID（用于后续更新 endTime） */
    suspend fun recordPlayHistory(song: Song): Long = withContext(Dispatchers.IO) {
        playHistoryDao.insert(
            PlayHistory(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                duration = song.duration,
                path = song.path,
                coverPath = song.coverPath,
                lrcPath = song.lrcPath,
                playedAt = System.currentTimeMillis()
            )
        )
    }

    /** 播放结束时更新 endTime 和实际收听时长 */
    suspend fun finishPlayHistory(id: Long, endTime: Long, thisDuration: Long) = withContext(Dispatchers.IO) {
        playHistoryDao.finishPlay(id, endTime, thisDuration)
    }

    suspend fun insertPlayHistory(history: PlayHistory) = withContext(Dispatchers.IO) {
        playHistoryDao.insert(history)
    }

    suspend fun deletePlayHistory(history: PlayHistory) = withContext(Dispatchers.IO) {
        playHistoryDao.delete(history)
    }

    suspend fun deleteAllPlayHistory() = withContext(Dispatchers.IO) {
        playHistoryDao.deleteAll()
    }

    suspend fun deletePlayHistorySince(since: Long) = withContext(Dispatchers.IO) {
        playHistoryDao.deleteSince(since)
    }

    companion object {
        @Volatile
        private var INSTANCE: MusicRepository? = null

        fun getInstance(context: Context): MusicRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MusicRepository(context).also { INSTANCE = it }
            }
        }
    }
}
