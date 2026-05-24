package com.hpu.musicplayer.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.service.MusicService
import com.hpu.musicplayer.service.PlayMode
import com.hpu.musicplayer.service.PlaybackState
import com.hpu.musicplayer.service.PlayerData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {

    // 直接使用 MusicService 的静态 playerState
    val playerState: StateFlow<PlayerData> = MusicService.playerState

    val playMode: StateFlow<PlayMode> = MusicService.playModeState

    val playlist: StateFlow<List<Song>> = MusicService.playlistFlow
    val currentPlayIndex: StateFlow<Int> = MusicService.currentIndexFlow

    private var timerJob: Job? = null
    private val _timerRemaining = MutableStateFlow(0L) // 剩余毫秒，0表示无定时
    val timerRemaining: StateFlow<Long> = _timerRemaining.asStateFlow()

    fun moveSong(from: Int, to: Int) {
        MusicService.getInstance()?.moveItem(from, to)
    }

    fun playAtIndex(index: Int) {
        MusicService.getInstance()?.playAtIndex(index)
    }

    fun cyclePlayMode() {
        Log.d("PlayerViewModel", "cyclePlayMode called")
        MusicService.getInstance()?.cyclePlayMode()
    }

    fun play(song: Song) {
        MusicService.getInstance()?.play(song)
    }

    fun togglePlayPause() {
        MusicService.getInstance()?.togglePlayPause()
    }

    fun seekTo(position: Long) {
        MusicService.getInstance()?.seekTo(position)
    }

    fun setPlaylist(songs: List<Song>) {
        MusicService.getInstance()?.setPlaylist(songs)
    }

    fun playNext() {
        MusicService.getInstance()?.playNext()
    }

    fun playPrevious() {
        MusicService.getInstance()?.playPrevious()
    }

    fun removeSongPermanently(index: Int) {
        MusicService.getInstance()?.removeSongPermanently(index)
    }

    fun startTimer(minutes: Int) {
        timerJob?.cancel()
        if (minutes <= 0) {
            _timerRemaining.value = 0
            return
        }
        val millis = minutes * 60_000L
        _timerRemaining.value = millis
        timerJob = viewModelScope.launch {
            var remaining = millis
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _timerRemaining.value = remaining
            }
            // 时间到，暂停播放
            MusicService.getInstance()?.pause()
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        _timerRemaining.value = 0
    }

    fun clearPlaylist() {
        MusicService.getInstance()?.clearPlaylist()
    }

    fun removeSong(index: Int) {
        MusicService.getInstance()?.removeSongPermanently(index)
    }
}