package com.hpu.musicplayer.viewmodel

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.data.repository.MusicRepository
import com.hpu.musicplayer.service.MusicService
import com.hpu.musicplayer.service.PlayMode
import com.hpu.musicplayer.service.PlaybackState
import com.hpu.musicplayer.service.PlayerData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository.getInstance(application)

    // ---------- 暴露 Service 中的状态 ----------
    val playerState: StateFlow<PlayerData> = MusicService.playerState
    val playMode: StateFlow<PlayMode> = MusicService.playModeState
    val playlist: StateFlow<List<Song>> = MusicService.playlistFlow
    val currentPlayIndex: StateFlow<Int> = MusicService.currentIndexFlow

    // ---------- 数据访问统一通过 Repository ----------
    val allSongs = repository.getAllSongs()
    val favoriteSongs = repository.getFavoriteSongs()
    val playHistory = repository.getAllPlayHistory()

    // 缓存主页随机排列结果，跨页面持久化（ViewModel 生命周期 > Fragment）
    var cachedShuffledSongs: List<Song>? = null
    // 标志位：是否已经进行过随机排序（确保只随机一次）
    private var isShuffleInitialized = false
    // 缓存上一次展示列表的 ID 顺序，相同则跳过 submitList
    var lastDisplaySongIds: List<Long>? = null

    /**
     * 获取展示列表：根据设置决定是否随机排列
     * 只随机一次：app 启动后第一次调用时随机，之后始终复用缓存顺序
     * 添加新歌曲时：保持原有随机顺序，新歌曲追加到末尾
     * @param songs 原始歌曲列表（来自数据库，按 title ASC）
     * @param shuffleEnabled 是否启用随机排列
     * @return 展示列表（随机或原始顺序）
     */
    fun getDisplayList(songs: List<Song>, shuffleEnabled: Boolean): List<Song> {
        if (!shuffleEnabled) {
            // 不随机，清除缓存和标志位
            cachedShuffledSongs = null
            isShuffleInitialized = false
            return songs
        }
        // 第一次随机
        if (!isShuffleInitialized) {
            val shuffled = songs.shuffled()
            cachedShuffledSongs = shuffled
            isShuffleInitialized = true
            return shuffled
        }
        // 已随机过，检查是否有新歌曲（songs 比缓存多）
        val cached = cachedShuffledSongs!!
        val cachedIds = cached.map { it.id }.toSet()
        val newSongs = songs.filter { it.id !in cachedIds }
        if (newSongs.isEmpty()) {
            // 没有新歌曲，直接返回缓存
            return cached
        }
        // 有新歌曲，追加到末尾
        val updated = cached + newSongs
        cachedShuffledSongs = updated
        return updated
    }

    // ---------- MediaController 连接 ----------
    private var mediaController: MediaController? = null

    // 统一的操作队列：存放 lambda，连接成功后按序执行
    private val pendingActions = mutableListOf<() -> Unit>()
    private var isConnecting = false
    private val maxPendingActions = 20  // 限制队列最大长度，防止无限增长

    // ---------- 定时器 ----------
    private var timerJob: Job? = null
    private val _timerRemaining = MutableStateFlow(0L)
    val timerRemaining: StateFlow<Long> = _timerRemaining.asStateFlow()

    init {
        connectToService()
    }

    private fun connectToService() {
        if (isConnecting) return
        isConnecting = true

        val context = getApplication<Application>()
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
                Log.d("PlayerViewModel", "Connected to MusicService")
                executePendingActions()
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to connect to MusicService", e)
                pendingActions.clear()
            } finally {
                isConnecting = false
            }
        }, MoreExecutors.directExecutor())
    }

    private fun executePendingActions() {
        if (mediaController == null) return
        pendingActions.forEach { it.invoke() }
        pendingActions.clear()
    }

    // 将操作加入队列并尝试连接
    private fun enqueueAction(action: () -> Unit) {
        if (pendingActions.size >= maxPendingActions) {
            Log.w("PlayerViewModel", "PendingActions queue full, dropping oldest")
            pendingActions.removeAt(0)
        }
        pendingActions.add(action)
        connectToService()
    }

    // ---------- 播放控制 API（使用官方 API，带队列保护） ----------

    fun play(song: Song) {
        if (mediaController == null) {
            enqueueAction { play(song) }
            return
        }
        // 歌曲不在当前列表，回退到自定义命令播放
        val bundle = Bundle().apply {
            putParcelable(MusicService.EXTRA_SONG, song)
        }
        mediaController?.sendCustomCommand(
            SessionCommand(MusicService.ACTION_PLAY_SONG, Bundle.EMPTY),
            bundle
        )
    }

    fun playOrResume(song: Song) {
        val current = playerState.value.currentSong
        if (current?.id == song.id) {
            // 同一首歌：暂停 → 恢复，播放 → 不做任何事
            if (playerState.value.state == PlaybackState.PAUSED) {
                togglePlayPause()   // 恢复播放
            }
        } else {
            // 不同歌曲：正常播放
            play(song)
        }
    }

    fun togglePlayPause() {
        if (mediaController == null) {
            enqueueAction { togglePlayPause() }
            return
        }
        if (mediaController?.isPlaying == true) {
            mediaController?.pause()
        } else {
            mediaController?.play()
        }
    }

    fun seekTo(position: Long) {
        if (mediaController == null) {
            enqueueAction { seekTo(position) }
            return
        }
        mediaController?.seekTo(position)
    }

    fun playNext() {
        if (mediaController == null) {
            enqueueAction { playNext() }
            return
        }
        mediaController?.seekToNext()
    }

    fun playPrevious() {
        if (mediaController == null) {
            enqueueAction { playPrevious() }
            return
        }
        mediaController?.seekToPrevious()
    }

    fun playAtIndex(index: Int) {
        if (mediaController == null) {
            enqueueAction { playAtIndex(index) }
            return
        }
        mediaController?.seekTo(index, 0L)
        mediaController?.play()
    }

    // ---------- 播放列表操作（自定义命令，复用队列） ----------
    fun setPlaylist(songs: List<Song>) {
        if (mediaController == null) {
            enqueueAction { setPlaylist(songs) }
            return
        }
        val bundle = Bundle().apply {
            putParcelableArrayList("songs", ArrayList(songs))
        }
        sendCustomCommandInternal("SET_PLAYLIST", bundle)
    }

    fun removeSongPermanently(index: Int) {
        if (mediaController == null) {
            enqueueAction { removeSongPermanently(index) }
            return
        }
        val bundle = Bundle().apply { putInt("index", index) }
        sendCustomCommandInternal("REMOVE_SONG", bundle)
    }

    fun clearPlaylist() {
        if (mediaController == null) {
            enqueueAction { clearPlaylist() }
            return
        }
        sendCustomCommandInternal("CLEAR_PLAYLIST")
    }

    fun moveSong(from: Int, to: Int) {
        if (mediaController == null) {
            enqueueAction { moveSong(from, to) }
            return
        }
        val bundle = Bundle().apply {
            putInt("from", from)
            putInt("to", to)
        }
        sendCustomCommandInternal("MOVE_SONG", bundle)
    }

    fun cyclePlayMode() {
        if (mediaController == null) {
            enqueueAction { cyclePlayMode() }
            return
        }
        sendCustomCommandInternal(MusicService.ACTION_CYCLE_PLAY_MODE)
    }

    fun setPlayMode(mode: PlayMode) {
        if (mediaController == null) {
            enqueueAction { setPlayMode(mode) }
            return
        }
        val bundle = Bundle().apply { putString("mode", mode.name) }
        sendCustomCommandInternal("SET_PLAY_MODE", bundle)
    }

    fun prepareSong(song: Song, position: Long) {
        if (mediaController == null) {
            enqueueAction { prepareSong(song, position) }
            return
        }
        val bundle = Bundle().apply {
            putParcelable("song", song)
            putLong("position", position)
        }
        sendCustomCommandInternal("PREPARE_SONG", bundle)
    }

    fun stopPlayback() {
        if (mediaController == null) {
            enqueueAction { stopPlayback() }
            return
        }
        sendCustomCommandInternal("STOP_PLAYBACK")
    }

    // 这两个通知操作如果不再使用可以删除
    fun updateNotification() {
        if (mediaController == null) {
            enqueueAction { updateNotification() }
            return
        }
        sendCustomCommandInternal("UPDATE_NOTIFICATION")
    }

    fun hideNotification() {
        if (mediaController == null) {
            enqueueAction { hideNotification() }
            return
        }
        sendCustomCommandInternal("HIDE_NOTIFICATION")
    }

    fun removeSongByIndex(index: Int) {
        if (mediaController == null) {
            enqueueAction { removeSongByIndex(index) }
            return
        }
        val bundle = Bundle().apply {
            putInt(MusicService.EXTRA_INDEX, index)
        }
        sendCustomCommandInternal(MusicService.ACTION_REMOVE_SONG_INDEX, bundle)
    }

    fun restoreSong(song: Song, position: Long) {
        if (mediaController == null) {
            enqueueAction { restoreSong(song, position) }
            return
        }
        val bundle = Bundle().apply {
            putParcelable(MusicService.EXTRA_SONG, song)
            putLong(MusicService.EXTRA_POSITION, position)
        }
        sendCustomCommandInternal(MusicService.ACTION_RESTORE_SONG, bundle)
    }

    fun addToQueue(songs: List<Song>) {
        if (mediaController == null) {
            enqueueAction { addToQueue(songs) }
            return
        }
        val bundle = Bundle().apply {
            putParcelableArrayList("songs", ArrayList(songs))
        }
        sendCustomCommandInternal(MusicService.ACTION_ADD_TO_QUEUE, bundle)
    }

    suspend fun getSongById(id: Long): Song? {
        return repository.getSongById(id)
    }

    suspend fun getSongByPath(path: String): Song? {
        return repository.getSongByPath(path)
    }

    fun updateSong(song: Song) {
        viewModelScope.launch {
            repository.updateSong(song)
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            repository.updateSong(song.copy(isFavorite = !song.isFavorite))
        }
    }

    fun deleteSongFromDatabase(song: Song) {
        viewModelScope.launch {
            repository.deleteSong(song)
        }
    }

    fun clearPlayHistory() {
        viewModelScope.launch {
            repository.deleteAllPlayHistory()
        }
    }

    // ---------- 定时器 ----------
    fun startTimer(minutes: Int) {
        cancelTimer()
        if (minutes <= 0) return

        val millis = minutes * 60_000L
        _timerRemaining.value = millis

        timerJob = viewModelScope.launch {
            var remaining = millis
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _timerRemaining.value = remaining
            }
            // 时间到，停止播放并释放服务资源
            stopPlayback()
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _timerRemaining.value = 0
    }

    override fun onCleared() {
        cancelTimer()
        mediaController?.release()
        mediaController = null
        pendingActions.clear()
        super.onCleared()
    }

    // ---------- 内部辅助：发送自定义命令（不检查队列，直接调用） ----------
    private fun sendCustomCommandInternal(action: String, bundle: Bundle = Bundle()) {
        mediaController?.sendCustomCommand(SessionCommand(action, Bundle()), bundle)
            ?.addListener({
                Log.d("PlayerViewModel", "sendCustomCommand $action succeeded")
            }, MoreExecutors.directExecutor())
    }
}
