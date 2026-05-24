package com.hpu.musicplayer.service

import com.hpu.musicplayer.data.Song

enum class PlaybackState { PLAYING, PAUSED, STOPPED }

data class PlayerData(
    val currentSong: Song?,
    val state: PlaybackState,
    val progress: Long,   // 毫秒
    val duration: Long
)