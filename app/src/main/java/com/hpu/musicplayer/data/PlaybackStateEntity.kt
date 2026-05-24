package com.hpu.musicplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey
    val id: Int = 1,               // 固定为1，单例
    val currentSongId: Long = -1,
    val position: Long = 0,       // 毫秒
    val playMode: String = "LIST_LOOP"  // 枚举名称
)