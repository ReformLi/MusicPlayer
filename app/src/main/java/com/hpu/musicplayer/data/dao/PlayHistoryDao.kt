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

    /** INSERT OR REPLACE（用于撤销删除，保留原 id） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(history: PlayHistory): Long

    /** 更新记录的结束时间和收听时长 */
    @Query("UPDATE play_history SET endTime = :endTime, thisDuration = :duration WHERE id = :id")
    suspend fun finishPlay(id: Long, endTime: Long, duration: Long)

    /** 仅更新收听时长（定期保存用，防止 App 被杀丢失） */
    @Query("UPDATE play_history SET thisDuration = :duration WHERE id = :id AND endTime IS NULL")
    suspend fun updateDuration(id: Long, duration: Long)

    /** 获取最近一条未结束的历史记录（App 重启后恢复累计用） */
    @Query("SELECT * FROM play_history WHERE songId = :songId AND endTime IS NULL ORDER BY playedAt DESC LIMIT 1")
    suspend fun getUnfinishedForSong(songId: Long): PlayHistory?

    /** 排行榜：按播放次数（只要有 endTime 且实际收听时长 > 0 即计入） */
    @Query("""
        SELECT songId, title, artist, album, duration, path, coverPath, lrcPath,
               COUNT(*) as count, SUM(thisDuration) as totalTime
        FROM play_history
        WHERE endTime IS NOT NULL AND thisDuration > 0
        AND playedAt >= :since
        GROUP BY songId
        ORDER BY count DESC
    """)
    fun getRankByCount(since: Long): Flow<List<RankEntry>>

    /** 排行榜：按播放时长（只要有 endTime 且实际收听时长 > 0 即计入） */
    @Query("""
        SELECT songId, title, artist, album, duration, path, coverPath, lrcPath,
               COUNT(*) as count, SUM(thisDuration) as totalTime
        FROM play_history
        WHERE endTime IS NOT NULL AND thisDuration > 0
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

    /** 补全孤儿记录的 endTime 和 thisDuration（App 崩溃/被杀后恢复，避免丢失数据）
     *  只清理 playedAt < threshold 的记录，保留较新的未结束记录供恢复续播 */
    @Query("""
        UPDATE play_history 
        SET endTime = playedAt + COALESCE(thisDuration, duration * 0.1, 30000),
            thisDuration = COALESCE(NULLIF(thisDuration, 0), duration * 0.1, 30000)
        WHERE endTime IS NULL AND playedAt < :threshold
    """)
    suspend fun finalizeOrphans(threshold: Long)

    /** 原子操作：结束旧记录并插入新记录，避免中间状态导致 Flow 多发射 */
    @Transaction
    suspend fun finishPlayAndInsert(oldId: Long, endTime: Long, duration: Long, newHistory: PlayHistory): Long {
        finishPlay(oldId, endTime, duration)
        return insert(newHistory)
    }

    /** 删除超过 7 天且仍无 endTime 的残留脏数据 */
    @Query("DELETE FROM play_history WHERE endTime IS NULL AND playedAt < :before")
    suspend fun deleteOldOrphans(before: Long)
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
