package com.hpu.musicplayer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.PlaybackStateEntity
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File


class MusicService : MediaSessionService() {

    companion object {
        private const val TAG = "MusicService"

        private const val NOTIFICATION_ID = 1

        private const val CHANNEL_ID = "playback_channel"

        // 自定义命令常量（移除了通知相关）
        const val ACTION_PLAY_SONG = "PLAY_SONG"
        const val ACTION_CYCLE_PLAY_MODE = "CYCLE_PLAY_MODE"
        const val ACTION_SET_PLAYLIST = "SET_PLAYLIST"
        const val ACTION_REMOVE_SONG = "REMOVE_SONG"
        const val ACTION_CLEAR_PLAYLIST = "CLEAR_PLAYLIST"
        const val ACTION_MOVE_SONG = "MOVE_SONG"
        const val ACTION_STOP_PLAYBACK = "STOP_PLAYBACK"
        const val ACTION_PREPARE_SONG = "PREPARE_SONG"
        const val ACTION_SET_PLAY_MODE = "SET_PLAY_MODE"
        const val ACTION_REMOVE_SONG_INDEX = "REMOVE_SONG_INDEX"

        const val ACTION_RESTORE_SONG = "RESTORE_SONG"

        const val EXTRA_SONG = "song"
        const val EXTRA_POSITION = "position"
        const val EXTRA_MODE = "mode"
        const val EXTRA_INDEX = "index"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"

        // 状态流（全局可读）
        private val _playerState = MutableStateFlow(PlayerData(null, PlaybackState.STOPPED, 0, 0))
        val playerState: StateFlow<PlayerData> = _playerState.asStateFlow()

        private val _playMode = MutableStateFlow(PlayMode.LIST_LOOP)
        val playModeState: StateFlow<PlayMode> = _playMode.asStateFlow()

        private val _playlistFlow = MutableStateFlow<List<Song>>(emptyList())
        val playlistFlow: StateFlow<List<Song>> = _playlistFlow.asStateFlow()

        private val _currentIndexFlow = MutableStateFlow(-1)
        val currentIndexFlow: StateFlow<Int> = _currentIndexFlow.asStateFlow()
    }

    private var progressUpdateJob: Job? = null
    private var player: ExoPlayer? = null
    private lateinit var mediaSession: MediaSession
    private val repository by lazy { MusicRepository.getInstance(applicationContext) }

    private var currentSong: Song? = null
    private var playMode = PlayMode.LIST_LOOP
    private var playlist = mutableListOf<Song>()
    private var currentIndex = -1

    private var saveJob: Job? = null
    private var lastSavedProgress = 0L

    private var pendingSeekIndex = -1
    private var pendingSeekPosition = 0L

    // ------------------- MediaSession 回调 -------------------
    private inner class MySessionCallback : MediaSession.Callback {
        @UnstableApi
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_PLAY_SONG, Bundle.EMPTY))
                .add(SessionCommand(ACTION_CYCLE_PLAY_MODE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SET_PLAYLIST, Bundle.EMPTY))
                .add(SessionCommand(ACTION_REMOVE_SONG, Bundle.EMPTY))
                .add(SessionCommand(ACTION_CLEAR_PLAYLIST, Bundle.EMPTY))
                .add(SessionCommand(ACTION_MOVE_SONG, Bundle.EMPTY))
                .add(SessionCommand(ACTION_STOP_PLAYBACK, Bundle.EMPTY))
                .add(SessionCommand(ACTION_PREPARE_SONG, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SET_PLAY_MODE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_REMOVE_SONG_INDEX, Bundle.EMPTY))
                .add(SessionCommand(ACTION_RESTORE_SONG, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ACTION_PLAY_SONG -> {
                    val song = if (Build.VERSION.SDK_INT >= 33)
                        args.getParcelable(EXTRA_SONG, Song::class.java)
                    else
                        args.getParcelable(EXTRA_SONG)
                    if (song != null) {
                        Log.d(TAG, "ACTION_PLAY_SONG received: ${song.title}")
                        playSongFromCommand(song)   // 下面会定义这个方法
                    } else {
                        Log.e(TAG, "ACTION_PLAY_SONG: missing song")
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_CYCLE_PLAY_MODE -> {
                    Log.d(TAG, "Received CYCLE_PLAY_MODE command, current playMode=$playMode")
                    cyclePlayMode()
                    Log.d(TAG, "After cyclePlayMode, new playMode=$playMode")
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_SET_PLAYLIST -> {
                    val songs = if (Build.VERSION.SDK_INT >= 33)
                        args.getParcelableArrayList("songs", Song::class.java)
                    else
                        args.getParcelableArrayList("songs")
                    if (songs != null) setPlaylist(songs)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_REMOVE_SONG -> {
                    val index = args.getInt(EXTRA_INDEX, -1)
                    if (index >= 0) removeSongPermanently(index)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_CLEAR_PLAYLIST -> {
                    clearPlaylist()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_MOVE_SONG -> {
                    val from = args.getInt(EXTRA_FROM, -1)
                    val to = args.getInt(EXTRA_TO, -1)
                    if (from >= 0 && to >= 0) moveItem(from, to)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_STOP_PLAYBACK -> {
                    stopPlayback()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_PREPARE_SONG -> {
                    val song = if (Build.VERSION.SDK_INT >= 33)
                        args.getParcelable(EXTRA_SONG, Song::class.java)
                    else
                        args.getParcelable(EXTRA_SONG)
                    val position = args.getLong(EXTRA_POSITION, 0L)
                    if (song != null) prepareSong(song, position)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_SET_PLAY_MODE -> {
                    val modeName = args.getString(EXTRA_MODE)
                    val mode = try { PlayMode.valueOf(modeName!!) } catch (e: Exception) { PlayMode.LIST_LOOP }
                    setPlayMode(mode)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_REMOVE_SONG_INDEX -> {
                    val index = args.getInt(EXTRA_INDEX, -1)
                    if (index >= 0) removeSongAtIndex(index)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_RESTORE_SONG -> {
                    val song = args.getParcelable<Song>(EXTRA_SONG)
                    val position = args.getLong(EXTRA_POSITION, 0L)
                    if (song != null) restoreSong(song, position)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    // ------------------- 播放器事件监听 -------------------
    private inner class PlayerEventListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newIndex = player?.currentMediaItemIndex ?: -1
            Log.d(TAG, "onMediaItemTransition: newIndex=$newIndex, reason=$reason, repeatMode=${player?.repeatMode}, shuffle=${player?.shuffleModeEnabled}")
            if (newIndex != -1 && newIndex < playlist.size) {
                currentIndex = newIndex
                currentSong = playlist[currentIndex]
                _currentIndexFlow.value = currentIndex
                updateState()
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && player?.playWhenReady == true) {
                    recordPlayHistory(currentSong!!)
                    saveCurrentStateAsync()
                }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && pendingSeekIndex != -1) {
                player?.seekTo(pendingSeekIndex, pendingSeekPosition)
                pendingSeekIndex = -1
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Play error: ${error.message}", error)
            showToast("播放失败: ${error.localizedMessage}")
            currentSong = null
            currentIndex = -1
            _currentIndexFlow.value = -1
            _playerState.value = PlayerData(null, PlaybackState.STOPPED, 0, 0)
            player?.stop()
            stopForeground(true)
            stopSelf()
        }
    }

    // ------------------- 生命周期 -------------------
    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 初始化 ExoPlayer
        player = ExoPlayer.Builder(this).build().apply {
            addListener(PlayerEventListener())
        }

        // 初始化 MediaSession
        mediaSession = MediaSession.Builder(this, player!!)
            .setId("MusicPlayer")
            .setCallback(MySessionCallback())
            .build()

        // 配置 Media3 自动通知（使用默认实现）
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
//                .setSmallIcon(R.drawable.ic_music_note)
                .build()
        )

        // 启动状态保存协程
        saveJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(5000)
                saveCurrentState()
            }
        }

        // 开始进度更新协程
        startProgressUpdates()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player?.isPlaying != true) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runBlocking { saveCurrentState() }
        progressUpdateJob?.cancel()
        saveJob?.cancel()
        player?.release()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 如果当前没有歌曲（即服务尚未开始播放任何内容），则显示一个默认的前台通知
        if (currentSong == null) {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle("音乐播放器")
                .setContentText("准备就绪")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    // ------------------- 进度更新 -------------------
    private fun startProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                if (player?.isPlaying == true) {
                    val playerDuration = player?.duration?.takeIf { it > 0 }
                    val duration = playerDuration ?: (currentSong?.duration ?: 0)
                    val position = player?.currentPosition?.coerceAtLeast(0) ?: _playerState.value.progress

                    _playerState.value = _playerState.value.copy(
                        progress = position,
                        duration = duration
                    )
                }
                delay(100)
            }
        }
    }

    // ------------------- 业务方法 -------------------
    fun play(song: Song) {
        if (handleSameSongPlayback(song)) return

        if (playlist.isEmpty() || playlist.none { it.id == song.id }) {
            setPlaylist(listOf(song))
        }
        val index = playlist.indexOfFirst { it.id == song.id }
        if (index >= 0) playAtIndex(index)
    }

    private fun playSongFromCommand(song: Song) {
        if (handleSameSongPlayback(song)) return

        val index = playlist.indexOfFirst { it.id == song.id }
        if (index >= 0) {
            playAtIndex(index)
            return
        }

        val mediaItem = buildMediaItem(song)
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
        playlist.clear()
        playlist.add(song)
        currentSong = song
        currentIndex = 0
        _currentIndexFlow.value = 0
        _playlistFlow.value = playlist.toList()
        updateState()
        recordPlayHistory(song)
        saveCurrentStateAsync()
    }

    fun setPlaylist(songs: List<Song>) {
        // 如果列表完全一样，直接返回，避免打断播放
        if (songs == playlist) {
            Log.d(TAG, "setPlaylist: identical list, skipping")
            return
        }

        Log.d(TAG, "setPlaylist: updating playlist with ${songs.size} songs")
        val previousSong = currentSong
        val previousPosition = player?.currentPosition ?: 0L
        val shouldResume = player?.playWhenReady == true

        playlist.clear()
        playlist.addAll(songs)
        _playlistFlow.value = playlist.toList()

        val mediaItems = playlist.map { buildMediaItem(it) }
        player?.apply {
            stop()                  // 停止当前播放（避免旧音频残留）
            clearMediaItems()
        }

        if (previousSong != null) {
            currentIndex = playlist.indexOfFirst { it.id == previousSong.id }
            if (currentIndex >= 0) {
                currentSong = playlist[currentIndex]
                _currentIndexFlow.value = currentIndex
                player?.apply {
                    setMediaItems(mediaItems, currentIndex, previousPosition)
                    prepare()
                    playWhenReady = shouldResume
                }
                updateState()
            } else {
                // 原歌曲不在新列表中，清除状态
                currentSong = null
                currentIndex = -1
                _currentIndexFlow.value = -1
                updateState()
            }
        } else {
            player?.addMediaItems(mediaItems)
            updateState()
        }
    }

    fun playAtIndex(index: Int) {
        if (playlist.isEmpty()) return
        val safeIndex = index.coerceIn(0, playlist.size - 1)
        val song = playlist[safeIndex]
        if (handleSameSongPlayback(song)) return

        Log.d(TAG, "playAtIndex: seeking to $safeIndex and playing")
        currentIndex = safeIndex
        currentSong = song
        _currentIndexFlow.value = currentIndex
        player?.apply {
            seekTo(safeIndex, 0)
            playWhenReady = true
            // 如果播放器处于 Idle 状态，可能需要 prepare
            if (playbackState == Player.STATE_IDLE) {
                prepare()
            }
        }
        updateState()
        recordPlayHistory(song)
        saveCurrentStateAsync()
    }

    fun removeSongAtIndex(index: Int) {
        if (index !in playlist.indices) return

        val wasPlaying = player?.isPlaying == true

        // 从 ExoPlayer 中移除对应媒体项
        player?.removeMediaItem(index)

        // 从本地列表移除
        playlist.removeAt(index)
        _playlistFlow.value = playlist.toList()

        // 调整当前索引
        when {
            index < currentIndex -> currentIndex--
            index == currentIndex -> {
                // 删除的是当前播放的歌曲，这种情况应特殊处理（你的需求可能不允许删除当前歌曲，但以防万一）
                if (currentIndex >= playlist.size) currentIndex = playlist.size - 1
                if (currentIndex >= 0) {
                    // 切换到下一首或上一首
                    player?.seekTo(currentIndex, 0)
                    if (wasPlaying) player?.play()
                } else {
                    stopPlayback()
                }
            }
            // index > currentIndex，无需改变 currentIndex
        }

        _currentIndexFlow.value = currentIndex
        updateState()
    }

    fun restoreSong(song: Song, position: Long) {
        val index = playlist.indexOfFirst { it.id == song.id }
        if (index < 0) {
            prepareSong(song, position)
            return
        }

        currentSong = song
        currentIndex = index
        _currentIndexFlow.value = index

        pendingSeekIndex = index
        pendingSeekPosition = position

        player?.apply {
            playWhenReady = false
            prepare()
        }

        _playerState.value = PlayerData(
            currentSong = song,
            state = PlaybackState.PAUSED,
            progress = position,
            duration = song.duration   // 这里用歌曲元数据时长
        )
    }

    fun cyclePlayMode() {
        val newMode = when (playMode) {
            PlayMode.LIST_LOOP -> PlayMode.RANDOM
            PlayMode.RANDOM -> PlayMode.SINGLE_LOOP
            PlayMode.SINGLE_LOOP -> PlayMode.LIST_LOOP
        }
        setPlayMode(newMode)   // 内部会更新 _playMode 和 ExoPlayer 设置
        saveCurrentStateAsync()
        showToast(
            when (newMode) {
                PlayMode.LIST_LOOP -> "列表循环"
                PlayMode.RANDOM -> "随机播放"
                PlayMode.SINGLE_LOOP -> "单曲循环"
            } + " 模式"
        )
    }

    fun setPlayMode(mode: PlayMode) {
        Log.d(TAG, "playMode111=$mode,playlist.size=${playlist.size}")
        playMode = mode
        _playMode.value = mode

        player?.apply {
            when (mode) {
                PlayMode.LIST_LOOP -> {
                    repeatMode = Player.REPEAT_MODE_ALL
                    shuffleModeEnabled = false
                }
                PlayMode.RANDOM -> {
                    repeatMode = Player.REPEAT_MODE_ALL
                    shuffleModeEnabled = true
                }
                PlayMode.SINGLE_LOOP -> {
                    repeatMode = Player.REPEAT_MODE_ONE
                    shuffleModeEnabled = false
                }
            }
        }
    }

    fun prepareSong(song: Song, position: Long) {
        val mediaItem = buildMediaItem(song)
        currentSong = song
        currentIndex = playlist.indexOfFirst { it.id == song.id }
        _currentIndexFlow.value = currentIndex

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            seekTo(position)
            // 不自动播放
        }
        _playerState.value = PlayerData(
            currentSong = song,
            state = PlaybackState.PAUSED,
            progress = position,
            duration = song.duration
        )
    }

    fun stopPlayback() {
        player?.stop()
        player?.clearMediaItems()
        currentSong = null
        currentIndex = -1
        _currentIndexFlow.value = -1
        updateState()
    }

    fun clearPlaylist() {
        playlist.clear()
        _playlistFlow.value = emptyList()
        currentIndex = -1
        _currentIndexFlow.value = -1
        stopPlayback()
    }

    fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return
        val item = playlist.removeAt(fromIndex)
        playlist.add(toIndex, item)
        _playlistFlow.value = playlist.toList()

        when {
            fromIndex == currentIndex -> currentIndex = toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex--
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex++
        }
        _currentIndexFlow.value = currentIndex
    }

    fun removeSongPermanently(index: Int) {
        if (index !in playlist.indices) return
        playlist.removeAt(index)
        _playlistFlow.value = playlist.toList()

        when {
            playlist.isEmpty() -> {
                currentIndex = -1
                _currentIndexFlow.value = -1
                stopPlayback()
            }
            index < currentIndex -> {
                currentIndex--
                _currentIndexFlow.value = currentIndex
            }
            index == currentIndex -> {
                if (currentIndex >= playlist.size) currentIndex = playlist.size - 1
                if (currentIndex >= 0) playAtIndex(currentIndex) else stopPlayback()
            }
        }
    }

    // ------------------- 辅助方法 -------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildMediaItem(song: Song): MediaItem {
        val uri = if (song.path.startsWith("content://")) {
            Uri.parse(song.path).also { ensurePersistableReadPermission(it) }
        } else {
            Uri.fromFile(File(song.path))
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album ?: "")
            .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun handleSameSongPlayback(song: Song): Boolean {
        if (currentSong?.id != song.id) return false

        if (player?.isPlaying == true) {
            return true
        }

        player?.play()
        updateState()
        startProgressUpdates()
        saveCurrentStateAsync()
        return true
    }

    private fun ensurePersistableReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "takePersistableUriPermission not available: ${e.message}")
        }
    }

    private fun recordPlayHistory(song: Song) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.recordPlayHistory(song)
            } catch (e: Exception) {
                Log.e(TAG, "Record play history error: ${e.message}", e)
            }
        }
    }

    private fun updateState() {
        // 优先使用播放器提供的 duration，如果无效则回退到歌曲元数据
        val playerDuration = player?.duration?.takeIf { it > 0 }
        val duration = playerDuration ?: (currentSong?.duration ?: 0)

        // 进度：播放器有有效位置时用它，否则保留上次进度（避免变为 0）
        val playerPosition = player?.currentPosition?.takeIf { it >= 0 }
        val progress = playerPosition ?: _playerState.value.progress

        _playerState.value = PlayerData(
            currentSong = currentSong,
            state = if (player?.isPlaying == true) PlaybackState.PLAYING else PlaybackState.PAUSED,
            progress = progress,
            duration = duration
        )
    }

    // 修改 saveCurrentStateAsync 方法（用于播放器暂停等场景）
    private fun saveCurrentStateAsync() {
        // 在主线程上调用 saveCurrentState，其中数据库部分会自动切换
        CoroutineScope(Dispatchers.Main).launch {
            saveCurrentState()
        }
    }

    // 修改 saveCurrentState 方法，将数据库操作切换到 IO 线程
    private suspend fun saveCurrentState() {
        // 确保当前在主线程上获取 player 状态（因为 saveJob 已经切换了，这里主要保护直接调用的场景）
        withContext(Dispatchers.Main) {
            val song = currentSong ?: return@withContext
            val progress = player?.currentPosition ?: 0
            if (progress == lastSavedProgress) return@withContext
            lastSavedProgress = progress

            // 构建要保存的状态对象
            val state = PlaybackStateEntity(
                currentSongId = song.id,
                position = progress,
                playMode = playMode.name
            )

            // 3. 数据库写入操作切换到 IO 线程，避免阻塞主线程
            try {
                repository.savePlaybackState(state)
            } catch (e: Exception) {
                Log.e(TAG, "Save state error: ${e.message}")
            }
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

// 数据类与枚举
data class PlayerData(
    val currentSong: Song?,
    val state: PlaybackState,
    val progress: Long,
    val duration: Long
)
enum class PlaybackState { PLAYING, PAUSED, STOPPED }
enum class PlayMode { LIST_LOOP, RANDOM, SINGLE_LOOP }
