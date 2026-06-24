package com.hpu.musicplayer.data.dao

import androidx.room.*
import com.hpu.musicplayer.data.PlayHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {
    /** 按播放开始时间倒序，全部记录 */
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    fun getAllHistory(): Flow<List<PlayHistory>>

    /** 按时间范围查询（结束的记录） */
    @Query("SELECT * FROM play_history WHERE endTime IS NOT NULL AND playedAt >= :since ORDER BY playedAt DESC")
    fun getHistorySince(since: Long): Flow<List<PlayHistory>>

    /** 普通 INSERT（不去重，每次播放一条新记录） */
    @Insert
    suspend fun insert(history: PlayHistory): Long

    /** 更新记录的结束时间和收听时长 */
    @Query("UPDATE play_history SET endTime = :endTime, thisDuration = :duration WHERE id = :id")
    suspend fun finishPlay(id: Long, endTime: Long, duration: Long)

    /** 排行榜：按播放次数（仅统计有效播放：≥5%总时长） */
    @Query("""
        SELECT songId, title, artist, album, duration, path, coverPath, lrcPath,
               COUNT(*) as count, SUM(thisDuration) as totalTime
        FROM play_history
        WHERE endTime IS NOT NULL AND thisDuration >= duration * 0.05
        AND playedAt >= :since
        GROUP BY songId
        ORDER BY count DESC
    """)
    fun getRankByCount(since: Long): Flow<List<RankEntry>>

    /** 排行榜：按播放时长（仅统计有效播放：≥5%总时长） */
    @Query("""
        SELECT songId, title, artist, album, duration, path, coverPath, lrcPath,
               COUNT(*) as count, SUM(thisDuration) as totalTime
        FROM play_history
        WHERE endTime IS NOT NULL AND thisDuration >= duration * 0.05
        AND playedAt >= :since
        GROUP BY songId
        ORDER BY totalTime DESC
    """)
    fun getRankByDuration(since: Long): Flow<List<RankEntry>>

    @Delete
    suspend fun delete(history: PlayHistory)

    /** 删除指定时间之后的记录 */
    @Query("DELETE FROM play_history WHERE playedAt >= :since")
    suspend fun deleteSince(since: Long)

    @Query("DELETE FROM play_history")
    suspend fun deleteAll()
}

/** 排行榜条目（聚合查询结果） */
data class RankEntry(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val coverPath: String?,
    val lrcPath: String?,
    val count: Int,
    val totalTime: Long
)
