package com.hpu.musicplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "play_history")
data class PlayHistory(
    @PrimaryKey val songId: Long,      // 歌曲ID，去重
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val coverPath: String?,
    val lrcPath: String?,
    val playedAt: Long = System.currentTimeMillis()  // 最近播放时间戳
)