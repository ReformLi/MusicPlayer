package com.hpu.musicplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "play_history")
data class PlayHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,  // 自增主键
    val songId: Long,       // 歌曲ID（不再作为主键，可重复）
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val coverPath: String?,
    val lrcPath: String?,
    val playedAt: Long = System.currentTimeMillis(),  // 播放开始时间
    val endTime: Long? = null,      // 播放结束时间，null=正在播放
    val thisDuration: Long = 0      // 本次实际收听时长(ms)
)
