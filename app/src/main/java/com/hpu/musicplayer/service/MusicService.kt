package com.hpu.musicplayer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
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
import com.hpu.musicplayer.utils.ScanManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        const val ACTION_REMOVE_SONG_BY_ID = "REMOVE_SONG_BY_ID"

        const val ACTION_RESTORE_SONG = "RESTORE_SONG"
        const val ACTION_ADD_TO_QUEUE = "ADD_TO_QUEUE"

        const val EXTRA_SONG = "song"
        const val EXTRA_POSITION = "position"
        const val EXTRA_MODE = "mode"
        const val EXTRA_INDEX = "index"
        const val EXTRA_SONG_ID = "songId"
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

        // 冷启动恢复标志：恢复期间 onMediaItemTransition 不自动更新 currentSong
        var isRestoringState = false
    }

    private var progressUpdateJob: Job? = null
    private var player: ExoPlayer? = null
    private lateinit var mediaSession: MediaSession
    private val repository by lazy { MusicRepository.getInstance(applicationContext) }

    // 音频焦点
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // WakeLock - 防止 CPU 休眠中断播放
    private var wakeLock: PowerManager.WakeLock? = null

    // 服务级协程作用域（替代每次新建 CoroutineScope）
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 耳机拔出/蓝牙断开广播
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                player?.pause()
            }
        }
    }

    private var currentSong: Song? = null
    private var playMode = PlayMode.LIST_LOOP
    private var playlist = mutableListOf<Song>()
    private var currentIndex = -1

    private var saveJob: Job? = null
    private var lastSavedProgress = 0L

    private var pendingSeekIndex = -1
    private var pendingSeekPosition = 0L

    private val historyMutex = Mutex()
    private var sessionListenTimeMs: Long = 0L
    private var lastKnownPosition: Long = 0L
    private var currentHistoryId: Long = 0L
    private var lastHistorySongId: Long = -1L
    private val minHistoryIntervalMs = 2000L

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
                .add(SessionCommand(ACTION_REMOVE_SONG_BY_ID, Bundle.EMPTY))
                .add(SessionCommand(ACTION_RESTORE_SONG, Bundle.EMPTY))
                .add(SessionCommand(ACTION_ADD_TO_QUEUE, Bundle.EMPTY))
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
                        play(song)
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
                ACTION_REMOVE_SONG_BY_ID -> {
                    val songId = args.getLong(EXTRA_SONG_ID, -1L)
                    if (songId > 0) removeSongById(songId)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_RESTORE_SONG -> {
                    val song = args.getParcelable<Song>(EXTRA_SONG)
                    val position = args.getLong(EXTRA_POSITION, 0L)
                    if (song != null) restoreSong(song, position)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_ADD_TO_QUEUE -> {
                    val songs = if (Build.VERSION.SDK_INT >= 33) {
                        args.getParcelableArrayList("songs", Song::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        args.getParcelableArrayList("songs")
                    }
                    if (songs != null) addToQueue(songs)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    // ------------------- 播放器事件监听 -------------------
    private inner class PlayerEventListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                acquireAudioFocusIfNeeded()
                acquireWakeLock()
            } else {
                releaseWakeLock()
            }
            updateState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 冷启动恢复期间跳过，避免覆盖 restoreSong 设置的 currentSong
            if (isRestoringState) return
            val newIndex = player?.currentMediaItemIndex ?: -1
            Log.d(TAG, "onMediaItemTransition: newIndex=$newIndex, reason=$reason, repeatMode=${player?.repeatMode}, shuffle=${player?.shuffleModeEnabled}")
            if (newIndex != -1 && newIndex < playlist.size) {
                currentIndex = newIndex
                currentSong = playlist[currentIndex]
                _currentIndexFlow.value = currentIndex
                updateState()
                if (player?.playWhenReady == true) {
                    transitionHistory(currentSong!!)
                    saveCurrentStateAsync()
                } else {
                    serviceScope.launch {
                        historyMutex.withLock {
                            finalizeCurrentHistory()
                            currentHistoryId = recordPlayHistory(currentSong!!)
                            lastHistorySongId = currentSong!!.id
                        }
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && pendingSeekIndex != -1) {
                player?.seekTo(pendingSeekIndex, pendingSeekPosition)
                pendingSeekIndex = -1
            }
            // 冷启动恢复完成：player 就绪后清除恢复标志，并同步播放列表
            if (state == Player.STATE_READY && isRestoringState) {
                isRestoringState = false
                serviceScope.launch {
                    try {
                        val songs = repository.getAllSongsOnce()
                        if (songs.isNotEmpty() && songs.map { it.id } != playlist.map { it.id }) {
                            setPlaylist(songs)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "restoreState setPlaylist failed: ${e.message}")
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val errSong = currentSong
            Log.e(TAG, "Play error for \"${errSong?.title}\" (${errSong?.path}): ${error.message}", error)
            showToast("播放失败: ${error.localizedMessage ?: "未知错误"}，已自动跳过")

            // SAF content:// 路径自动纠正：尝试将 content URI 解析为真实文件路径
            if (errSong != null && errSong.path.startsWith("content://")) {
                val resolved = ScanManager.normalizePath(this@MusicService, errSong.path)
                if (resolved != errSong.path && File(resolved).exists()) {
                    Log.i(TAG, "Resolved content URI to file path, retrying: $resolved")
                    val updated = errSong.copy(path = resolved)
                    serviceScope.launch { repository.updateSong(updated) }
                    currentSong = updated
                    val idx = currentIndex
                    if (idx >= 0 && idx < playlist.size) {
                        playlist[idx] = updated
                        _playlistFlow.value = playlist.toList()
                    }
                    player?.apply {
                        removeMediaItem(idx.coerceAtLeast(0))
                        addMediaItem(idx.coerceAtLeast(0), buildMediaItem(updated))
                        seekTo(idx.coerceAtLeast(0), 0)
                        prepare()
                        play()
                    }
                    updateState()
                    return
                }
            }

            // 不杀死服务，改为跳过当前歌曲
            val currentIdx = currentIndex
            if (currentIdx >= 0 && playlist.isNotEmpty()) {
                player?.removeMediaItem(currentIdx)
                if (playlist.isNotEmpty()) {
                    currentIndex = currentIdx.coerceIn(0, playlist.size - 1)
                    if (playlist.isNotEmpty() && currentIndex < playlist.size) {
                        currentSong = playlist[currentIndex]
                        player?.prepare()
                        player?.play()
                    } else {
                        serviceScope.launch { historyMutex.withLock { finalizeCurrentHistory() } }
                        // 播放列表为空，停止
                        currentSong = null
                        currentIndex = -1
                        _currentIndexFlow.value = -1
                        _playerState.value = PlayerData(null, PlaybackState.STOPPED, 0, 0)
                    }
                }
            } else {
                serviceScope.launch { historyMutex.withLock { finalizeCurrentHistory() } }
                currentSong = null
                currentIndex = -1
                _currentIndexFlow.value = -1
                _playerState.value = PlayerData(null, PlaybackState.STOPPED, 0, 0)
            }
            updateState()
        }
    }

    // ------------------- 生命周期 -------------------
    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            try {
                repository.cleanupOrphanHistory()
                Log.i(TAG, "Cleaned up orphan play history records")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean orphan history: ${e.message}", e)
            }
        }
        createNotificationChannel()
        initAudioFocus()
        initWakeLock()

        // 初始化 ExoPlayer（配置扩展 Extractors 以支持更多格式，启用定比特率 MP3 搜索）
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this, DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true))
            )
            .build().apply {
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

        // 注册耳机拔出广播
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        // 启动状态保存协程
        saveJob = serviceScope.launch {
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
        runBlocking {
            // 只持久化时长但不定死 endTime，下次恢复时可续接
            if (currentHistoryId > 0) {
                repository.updatePlayHistoryDuration(currentHistoryId, sessionListenTimeMs)
            }
            saveCurrentState()
        }
        progressUpdateJob?.cancel()
        saveJob?.cancel()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        player?.release()
        mediaSession.release()
        releaseAudioFocus()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8.0+ 要求 startForegroundService() 后 5 秒内必须调用 startForeground()，
        // 否则触发 ANR。必须在 super.onStartCommand() 之前调用，因为 MediaSessionService
        // 需要等待 MediaController 连接后才通过 notification provider 触发 startForeground()，
        // 这个过程可能超过 5 秒。
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("音乐播放器")
            .setContentText("准备就绪")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        return super.onStartCommand(intent, flags, startId)
    }

    // ------------------- 进度更新 -------------------
    private fun startProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = serviceScope.launch {
            while (isActive) {
                if (player?.isPlaying == true) {
                    val playerDuration = player?.duration?.takeIf { it > 0 }
                    val duration = playerDuration ?: (currentSong?.duration ?: 0)
                    val position = player?.currentPosition?.coerceAtLeast(0) ?: _playerState.value.progress

                    val diff = position - lastKnownPosition
                    if (diff in 1..5000) {
                        sessionListenTimeMs += diff
                    }
                    lastKnownPosition = position

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

        val index = playlist.indexOfFirst { it.id == song.id }
        if (index >= 0) {
            playAtIndex(index)
            return
        }

        // 歌曲不在当前播放列表中：追加到队列末尾并播放
        transitionHistory(song)
        playlist.add(song)
        _playlistFlow.value = playlist.toList()
        val newIndex = playlist.size - 1
        currentIndex = newIndex
        currentSong = song
        _currentIndexFlow.value = newIndex

        player?.apply {
            addMediaItem(newIndex, buildMediaItem(song))
            seekTo(newIndex, 0)
            playWhenReady = true
            if (playbackState == Player.STATE_IDLE) {
                prepare()
            }
        }
        updateState()
        saveCurrentStateAsync()
    }

    fun setPlaylist(songs: List<Song>) {
        // 如果 ID 列表和当前完全一致（相同顺序），且当前正在播放，跳过避免打断
        val currentIds = playlist.map { it.id }
        val newIds = songs.map { it.id }
        if (currentIds == newIds && currentSong != null) {
            Log.d(TAG, "setPlaylist: identical IDs, skipping")
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

        if (previousSong != null) {
            currentIndex = playlist.indexOfFirst { it.id == previousSong.id }
            if (currentIndex >= 0) {
                currentSong = playlist[currentIndex]
                _currentIndexFlow.value = currentIndex
                player?.apply {
                    setMediaItems(mediaItems, currentIndex, previousPosition)
                    if (playbackState == Player.STATE_IDLE) {
                        prepare()
                    }
                    playWhenReady = shouldResume
                }
                updateState()
            } else {
                player?.apply {
                    stop()
                    clearMediaItems()
                }
                currentSong = null
                currentIndex = -1
                _currentIndexFlow.value = -1
                updateState()
            }
        } else {
            player?.apply {
                stop()
                clearMediaItems()
                addMediaItems(mediaItems)
            }
            updateState()
        }
    }

    fun playAtIndex(index: Int) {
        if (playlist.isEmpty()) return
        val safeIndex = index.coerceIn(0, playlist.size - 1)
        val song = playlist[safeIndex]
        if (handleSameSongPlayback(song)) return

        transitionHistory(song)
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
        saveCurrentStateAsync()
    }

    fun removeSongById(songId: Long) {
        val index = playlist.indexOfFirst { it.id == songId }
        if (index >= 0) {
            removeSongAtIndex(index)
        }
    }

    fun removeSongAtIndex(index: Int) {
        if (index !in playlist.indices) return

        val wasPlaying = player?.isPlaying == true

        player?.removeMediaItem(index)

        playlist.removeAt(index)
        _playlistFlow.value = playlist.toList()

        when {
            index < currentIndex -> currentIndex--
            index == currentIndex -> {
                if (currentIndex >= playlist.size) currentIndex = playlist.size - 1
                if (currentIndex >= 0) {
                    player?.seekTo(currentIndex, 0)
                    if (wasPlaying) player?.play()
                } else {
                    stopPlayback()
                }
            }
        }

        _currentIndexFlow.value = currentIndex
        updateState()
    }

    fun restoreSong(song: Song, position: Long) {
        val index = playlist.indexOfFirst { it.id == song.id }
        if (index < 0) {
            prepareSong(song, position)
        } else {
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
                duration = song.duration
            )
        }
        // 同步查找未结束记录续接，确保切歌前 currentHistoryId 已就位
        runBlocking {
            val existing = repository.getUnfinishedHistoryForSong(song.id)
            if (existing != null) {
                currentHistoryId = existing.id
                sessionListenTimeMs = existing.thisDuration
                lastKnownPosition = position
            } else {
                transitionHistory(song)
            }
        }
    }

    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return

        val wasEmpty = playlist.isEmpty()
        val currentSize = playlist.size

        // 过滤掉已经在播放队列中的歌曲（按 id 去重）
        val existingIds = playlist.map { it.id }.toSet()
        val newSongs = songs.filter { it.id !in existingIds }

        if (newSongs.isEmpty()) {
            showToast("歌曲已在播放队列中")
            return
        }

        // 添加到内存列表
        playlist.addAll(newSongs)
        _playlistFlow.value = playlist.toList()

        // 添加到 ExoPlayer
        val newMediaItems = newSongs.map { buildMediaItem(it) }
        player?.addMediaItems(newMediaItems)

        // 如果之前队列为空，自动播放第一首新增的歌曲
        if (wasEmpty && playlist.isNotEmpty()) {
            currentIndex = 0
            _currentIndexFlow.value = 0
            playAtIndex(0)
        }

        showToast("已添加 ${newSongs.size} 首歌曲到播放队列")
        saveCurrentStateAsync()
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
        if (currentIndex < 0) {
            currentIndex = 0
            playlist.clear()
            playlist.add(song)
            _playlistFlow.value = playlist.toList()
        }
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
        serviceScope.launch { historyMutex.withLock { finalizeCurrentHistory() } }
        player?.stop()
        player?.clearMediaItems()
        currentSong = null
        currentIndex = -1
        _currentIndexFlow.value = -1
        _playerState.value = PlayerData(null, PlaybackState.STOPPED, 0, 0)
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
        
        // 从 ExoPlayer 中移除对应媒体项
        player?.removeMediaItem(index)
        
        // 从本地列表移除
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
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "音乐播放控制通知"
                setSound(null, null)  // 禁用通知提示音
            }
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

    private suspend fun recordPlayHistory(song: Song): Long {
        return try {
            val unfinished = repository.getUnfinishedHistoryForSong(song.id)
            val baseDuration = unfinished?.thisDuration?.takeIf { it > 0 } ?: 0L
            val id = if (unfinished != null) {
                unfinished.id
            } else {
                repository.recordPlayHistory(song)
            }
            sessionListenTimeMs = baseDuration
            lastKnownPosition = (player?.currentPosition?.coerceAtLeast(0)) ?: 0L
            id
        } catch (e: Exception) {
            Log.e(TAG, "Record play history error: ${e.message}", e)
            0L
        }
    }

    /** 结束当前历史记录的播放，写入 endTime 和 thisDuration */
    private suspend fun finalizeCurrentHistory() {
        if (currentHistoryId == 0L) return
        val id = currentHistoryId
        val duration = sessionListenTimeMs
        val endTime = System.currentTimeMillis()
        currentHistoryId = 0L
        try {
            repository.finishPlayHistory(id, endTime, duration)
            Log.d(TAG, "Finalized history id=$id, duration=${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Finalize history error: ${e.message}", e)
        }
    }

    private fun transitionHistory(song: Song) {
        serviceScope.launch {
            historyMutex.withLock {
                if (song.id == lastHistorySongId) {
                    val lastId = currentHistoryId
                    if (lastId > 0) return@withLock
                }
                finalizeCurrentHistory()
                currentHistoryId = recordPlayHistory(song)
                lastHistorySongId = song.id
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

        val state = when {
            currentSong == null -> PlaybackState.STOPPED
            player?.isPlaying == true -> PlaybackState.PLAYING
            else -> PlaybackState.PAUSED
        }

        _playerState.value = PlayerData(
            currentSong = currentSong,
            state = state,
            progress = progress,
            duration = duration
        )
    }

    // 异步保存播放状态
    private fun saveCurrentStateAsync() {
        serviceScope.launch {
            saveCurrentState()
        }
    }

    // 保存当前播放状态到数据库，同时持久化收听时长
    private suspend fun saveCurrentState() {
        val song = currentSong ?: return
        val progress = withContext(Dispatchers.Main) {
            player?.currentPosition ?: 0
        }
        if (progress != lastSavedProgress) {
            lastSavedProgress = progress
            val state = PlaybackStateEntity(
                currentSongId = song.id,
                position = progress,
                playMode = playMode.name
            )
            try {
                repository.savePlaybackState(state)
            } catch (e: Exception) {
                Log.e(TAG, "Save state error: ${e.message}")
            }
        }
        if (currentHistoryId > 0) {
            try {
                repository.updatePlayHistoryDuration(currentHistoryId, sessionListenTimeMs)
            } catch (e: Exception) {
                Log.e(TAG, "Save history duration error: ${e.message}")
            }
        }
    }

    // ------------------- 音频焦点管理 -------------------
    private fun initAudioFocus() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusChangeListener = OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // 永久失去焦点（如其他应用开始播放），暂停并降低音量
                    Log.d(TAG, "AudioFocus: LOSS")
                    hasAudioFocus = false
                    player?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // 暂时失去焦点（如来电），暂停
                    Log.d(TAG, "AudioFocus: LOSS_TRANSIENT")
                    hasAudioFocus = false
                    player?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // 短暂失去焦点但可以降音播放（如通知音），降低音量
                    Log.d(TAG, "AudioFocus: LOSS_TRANSIENT_CAN_DUCK")
                    player?.volume = 0.3f
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // 重新获得焦点，恢复
                    Log.d(TAG, "AudioFocus: GAIN")
                    hasAudioFocus = true
                    player?.volume = 1.0f
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest?.let {
                audioManager?.requestAudioFocus(it)?.let { result ->
                    hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
    }

    private fun acquireAudioFocusIfNeeded() {
        if (hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager?.requestAudioFocus(it)?.let { result ->
                    hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                { focusChange -> /* handled in init */ },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
        Log.d(TAG, "acquireAudioFocus: $hasAudioFocus")
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    // ------------------- WakeLock 管理 -------------------
    private fun initWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MusicPlayer::PlaybackWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
    }

    private fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(10 * 60 * 1000L) // 10分钟超时
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
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
