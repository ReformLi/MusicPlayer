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
import android.media.MediaPlayer
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
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
import com.hpu.musicplayer.utils.AudioTranscoder
import com.hpu.musicplayer.utils.ScanManager
import com.hpu.musicplayer.utils.SettingsPreferences
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
    private var lastWakeLockRenewalAt = 0L

    // 服务级协程作用域（替代每次新建 CoroutineScope）
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 耳机拔出/蓝牙断开广播
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                if (isFallbackActive()) {
                    fallbackPlayer?.pause()
                    _playerState.value = _playerState.value.copy(state = PlaybackState.PAUSED)
                } else {
                    player?.pause()
                }
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

    // 系统 MediaPlayer 兜底：用于播放损坏的 FLAC（华为 OMX.google.flac.decoder 容错差会崩溃）
    // 系统 MediaPlayer 内部走 AOSP 软解，对坏帧容错性更好
    private var fallbackPlayer: MediaPlayer? = null
    private var fallbackProgressJob: Job? = null

    // 错误处理重入保护：onPlayerError 处理期间（兜底 + 跳过）拒绝再次进入，
    // 否则 ExoPlayer 反复解码同一个损坏文件会形成「兜底失败 → play() 重试 → 再崩」的死循环，
    // 表现为：卡在同一首歌、上一首/下一首/停止按钮全部失效。
    private var errorHandlingInProgress = false

    // 音频焦点暂时丢失（来电等）前是否在播放，GAIN 时据此恢复播放
    @Volatile
    private var resumeOnFocusGain = false

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
                // 兜底期间切歌：释放系统 MediaPlayer，恢复 ExoPlayer 正常播放
                releaseFallbackPlayer()
                currentIndex = newIndex
                currentSong = playlist[currentIndex]
                _currentIndexFlow.value = currentIndex
                updateState()
                if (player?.playWhenReady == true) {
                    transitionHistory(currentSong!!)
                    saveCurrentStateAsync()
                }
            }
        }

        // 兜底期间的播放/暂停同步：ExoPlayer 的 playWhenReady 变化时同步系统 MediaPlayer
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val mp = fallbackPlayer ?: return
            if (playWhenReady) {
                // 用户点击播放：恢复 MediaPlayer（ExoPlayer 仍会尝试播损坏文件并崩溃，
                // 但 onPlayerError 里已通过 isFallbackActive 抑制重复兜底/跳过）
                if (!mp.isPlaying) {
                    try { mp.start() } catch (_: Exception) {}
                    _playerState.value = _playerState.value.copy(state = PlaybackState.PLAYING)
                }
            } else {
                // 用户点击暂停：暂停 MediaPlayer
                if (mp.isPlaying) {
                    try { mp.pause() } catch (_: Exception) {}
                    _playerState.value = _playerState.value.copy(state = PlaybackState.PAUSED)
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

            // 恢复的歌曲直接报错（如损坏文件）：STATE_READY 永不触发，
            // 残留的 isRestoringState/pendingSeek 会永久阻断 setPlaylist 并在下一次 STATE_READY 误 seek
            if (isRestoringState || pendingSeekIndex != -1) {
                Log.w(TAG, "Clearing restore flags on player error (was restoring=$isRestoringState)")
                isRestoringState = false
                pendingSeekIndex = -1
                pendingSeekPosition = 0
            }

            // 错误处理重入保护：处理期间直接丢弃重复的 error 回调，
            // 阻断「play() 重试 → 再崩 → 再兜底」的死循环。
            if (errorHandlingInProgress) {
                Log.w(TAG, "Error handling already in progress, ignoring duplicate error for \"${errSong?.title}\"")
                return
            }

            // 兜底播放已激活：ExoPlayer 再次崩溃（如用户点播放触发重播损坏文件）时抑制，
            // 避免重复启动兜底或误跳过，MediaPlayer 已在出声。
            if (isFallbackActive()) {
                Log.w(TAG, "Fallback active, ignoring ExoPlayer error: \"${errSong?.title}\"")
                return
            }

            errorHandlingInProgress = true
            try {
                handlePlayerError(error, errSong)
            } finally {
                errorHandlingInProgress = false
            }
        }

        private fun handlePlayerError(error: PlaybackException, errSong: Song?) {
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

            // FLAC 解码崩溃兜底：华为 OMX.google.flac.decoder 对损坏的 FLAC（含坏帧）容错差，
            // 直接崩溃（dequeueInputBuffer IllegalStateException）。
            // 注意：onPlayerError 的 error 是 ExoPlaybackException，真正的解码异常在其 cause 里。
            val cause = error.cause
            if (cause is MediaCodecDecoderException && errSong != null &&
                errSong.path.endsWith(".flac", ignoreCase = true)) {
                // 后台转码该损坏 FLAC 为 M4A，下次播放直接命中缓存，彻底解决
                triggerTranscode(errSong)

                val started = startFallbackPlayback(errSong)
                if (started) {
                    Log.w(TAG, "FLAC decoder crash, fallback to system MediaPlayer: \"${errSong.title}\"")
                    return
                }
                Log.w(TAG, "Fallback MediaPlayer also failed, skipping: \"${errSong.title}\"")
            }

            // 不杀死服务，改为跳过当前歌曲（保留在列表）
            skipCurrentAndPlayNext()
        }
    }

    // 后台触发 FLAC → M4A 转码，成功后记录缓存路径，下次播放直接命中
    private fun triggerTranscode(song: Song) {
        val sourcePath = song.path
        // 已有缓存则跳过
        if (SettingsPreferences.getTranscodedPath(this, sourcePath) != null) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                val outPath = AudioTranscoder.transcodeAsync(this@MusicService, sourcePath)
                if (outPath != null) {
                    SettingsPreferences.setTranscodedPath(this@MusicService, sourcePath, outPath)
                    Log.i(TAG, "Transcoded FLAC cached: \"${song.title}\" -> $outPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcode failed for \"${song.title}\": ${e.message}")
            }
        }
    }

    // 跳过当前报错的歌曲，并切到下一首。
    // 关键：不移除失败歌曲（保留在列表），只推进索引到下一首。
    // 这样用户点「上一首」能跳回失败歌曲，且转码完成后可重新播放。
    //
    // 死循环修复：ExoPlayer 处于错误状态时，若直接 seekTo(next)+play()，
    // play() 会把 playWhenReady 重新置 true，触发 ExoPlayer 再次解码损坏文件 →
    // 再次 onPlayerError → 再次兜底，形成无限循环，导致上一首/下一首/停止全部失效。
    // 因此这里必须先 stop() 让 ExoPlayer 彻底脱离当前损坏媒体源（回 IDLE），
    // 再推进索引 + prepare()，且【不调用 play()】——播放由下一首的正常流程/用户操作驱动。
    private fun skipCurrentAndPlayNext() {
        val currentIdx = currentIndex
        if (currentIdx >= 0 && currentIdx < playlist.size) {
            if (playlist.size > 1) {
                // 推进到下一首（取模回绕：最后一首失败时回到第一首，而不是 coerceIn 卡在同一首）
                val nextIndex = (currentIdx + 1) % playlist.size
                currentIndex = nextIndex
                currentSong = playlist[nextIndex]
                _currentIndexFlow.value = nextIndex
                transitionHistory(currentSong!!)

                player?.apply {
                    // 先彻底断开错误状态：暂停 + stop 回到 IDLE，清掉对损坏文件的引用
                    playWhenReady = false
                    stop()
                    // 显式 seek 到下一首并 prepare，但不 play()
                    // （playWhenReady 仍为 false，等待用户/流程触发，避免再次误触损坏文件）
                    seekTo(nextIndex, 0)
                    prepare()
                }
                updateState()
            } else {
                // 列表只剩这一首（单曲）：停止，但保留歌曲
                serviceScope.launch { historyMutex.withLock { finalizeCurrentHistory() } }
                player?.apply {
                    playWhenReady = false
                    stop()
                }
                currentSong = null
                currentIndex = -1
                _currentIndexFlow.value = -1
                _playerState.value = PlayerData(null, PlaybackState.STOPPED, 0, 0)
            }
        } else {
            serviceScope.launch { historyMutex.withLock { finalizeCurrentHistory() } }
            player?.apply {
                playWhenReady = false
                stop()
            }
            currentSong = null
            currentIndex = -1
            _currentIndexFlow.value = -1
            _playerState.value = PlayerData(null, PlaybackState.STOPPED, 0, 0)
        }
        updateState()
    }

    // ------------------- 兜底播放（系统 MediaPlayer）-------------------
    // 用于播放损坏的 FLAC：华为 OMX.google.flac.decoder 容错差会崩溃，
    // 改用系统 MediaPlayer（AOSP 软解）尽量出声。

    /**
     * 启动系统 MediaPlayer 兜底播放 [song]。
     * 返回 true 表示兜底启动成功；false 表示兜底也失败，应回退到跳过逻辑。
     */
    private fun startFallbackPlayback(song: Song): Boolean {
        return try {
            // 先暂停 ExoPlayer，避免它反复触发 onPlayerError
            player?.playWhenReady = false
            releaseFallbackPlayer()

            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(song.path)
                setOnPreparedListener { p ->
                    p.start()
                    // 兜底播放时 ExoPlayer 处于 pause，onIsPlayingChanged 不会触发，
                    // 焦点/WakeLock 需在此显式获取
                    acquireAudioFocusIfNeeded()
                    acquireWakeLock()
                    _playerState.value = _playerState.value.copy(
                        state = PlaybackState.PLAYING,
                        duration = p.duration.toLong().takeIf { it > 0 } ?: song.duration
                    )
                    startFallbackProgressUpdates()
                }
                setOnCompletionListener {
                    Log.i(TAG, "Fallback MediaPlayer completed: \"${song.title}\"")
                    releaseFallbackPlayer()
                    playNextAfterFallback()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Fallback MediaPlayer error what=$what extra=$extra: \"${song.title}\"")
                    releaseFallbackPlayer()
                    // 兜底也失败：跳过这首损坏的歌，切下一首
                    skipCurrentAndPlayNext()
                    true
                }
                prepareAsync()
            }
            fallbackPlayer = mp
            true
        } catch (e: Exception) {
            Log.e(TAG, "startFallbackPlayback failed: ${e.message}", e)
            releaseFallbackPlayer()
            false
        }
    }

    // 兜底进度轮询：用 MediaPlayer 的 currentPosition 驱动进度条
    private fun startFallbackProgressUpdates() {
        fallbackProgressJob?.cancel()
        fallbackProgressJob = serviceScope.launch {
            while (isActive) {
                val mp = fallbackPlayer ?: break
                if (mp.isPlaying) {
                    renewWakeLockIfNeeded()
                    val position = mp.currentPosition.toLong().coerceAtLeast(0)
                    val duration = mp.duration.toLong().takeIf { it > 0 }
                        ?: (currentSong?.duration ?: 0)
                    _playerState.value = _playerState.value.copy(
                        progress = position,
                        duration = duration
                    )
                    // 与正常进度协程相同的口径累计收听时长，避免兜底期间历史时长丢失
                    val diff = position - lastKnownPosition
                    if (diff in 1..5000) {
                        sessionListenTimeMs += diff
                    }
                    lastKnownPosition = position
                }
                delay(100)
            }
        }
    }

    // 兜底播放结束后切下一首（复用正常切歌逻辑，并处理播放模式语义）
    private fun playNextAfterFallback() {
        val currentIdx = currentIndex
        when {
            // 单曲循环：重播当前首（兜底播放走系统 MediaPlayer，不走 ExoPlayer 的 repeat 逻辑）
            playMode == PlayMode.SINGLE_LOOP && currentIdx in playlist.indices -> {
                playAtIndex(currentIdx)
            }
            // 随机：等概率随机选一首（尽量不选当前首）
            playMode == PlayMode.RANDOM && playlist.isNotEmpty() -> {
                if (playlist.size == 1) {
                    playAtIndex(0)
                } else {
                    var next = (0 until playlist.size).random()
                    if (next == currentIdx) next = (next + 1) % playlist.size
                    playAtIndex(next)
                }
            }
            // 顺序推进，列表末尾按 LIST_LOOP 回绕
            currentIdx >= 0 && currentIdx < playlist.size - 1 -> {
                playAtIndex(currentIdx + 1)
            }
            playMode == PlayMode.LIST_LOOP && playlist.isNotEmpty() -> {
                playAtIndex(0)
            }
            else -> {
                // 列表末尾或单曲：停止
                stopPlayback()
            }
        }
    }

    private fun releaseFallbackPlayer() {
        fallbackProgressJob?.cancel()
        fallbackProgressJob = null
        fallbackPlayer?.apply {
            try {
                if (isPlaying) stop()
            } catch (_: Exception) {}
            try {
                reset()
            } catch (_: Exception) {}
            try {
                release()
            } catch (_: Exception) {}
        }
        fallbackPlayer = null
        // 兜底期间显式获取的 WakeLock 在此释放（isHeld 检查保证幂等；
        // 若紧接着 ExoPlayer 开始播放，onIsPlayingChanged 会重新获取）
        releaseWakeLock()
    }

    private fun isFallbackActive(): Boolean = fallbackPlayer != null

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
        // 启用解码器回退：华为 OMX.google.flac.decoder 对部分 FLAC 会在解码中崩溃
        // （MediaCodecDecoderException / dequeueInputBuffer IllegalStateException），
        // 开启后 MediaCodec 失败会自动回退到软解（FFmpeg 扩展不可用时的内置兜底）。
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderersFactory)
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
        // 兜底播放时 ExoPlayer 处于 pause（isPlaying=false），但 fallbackPlayer 正在出声，
        // 必须同时检查，否则用户划掉任务会误杀正在兜底播放的服务
        if (player?.isPlaying != true && !isFallbackActive()) {
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
        releaseFallbackPlayer()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        // media3 推荐顺序：先释放 MediaSession（解除对 player 的引用），再释放 player
        mediaSession.release()
        player?.release()
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
                    renewWakeLockIfNeeded()
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
                    // 当前播放项未变时，用增量同步代替 setMediaItems，
                    // 避免重建时间线导致正在播放的音频中断（卡顿）
                    val currentItemSame = currentMediaItem?.mediaId?.toLongOrNull() == previousSong.id
                    val synced = currentItemSame && syncPlaylistIncrementally()
                    if (!synced) {
                        Log.d(TAG, "setPlaylist: full rebuild (currentItemSame=$currentItemSame)")
                        setMediaItems(mediaItems, currentIndex, previousPosition)
                    } else {
                        Log.d(TAG, "setPlaylist: incremental sync, no audio interruption")
                    }
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

    /**
     * 增量同步 ExoPlayer 时间线到当前 [playlist] 的顺序，
     * 通过 addMediaItem / removeMediaItem / moveMediaItem 完成，
     * 不重置当前播放项与进度（不会重新打开媒体源），
     * 避免 setMediaItems 重建时间线导致播放中的音频中断。
     * 仅在当前播放项未变时调用；仅在发生异常时才返回 false 交由调用方回退。
     */
    private fun Player.syncPlaylistIncrementally(): Boolean {
        return try {
            val desired = playlist.map { it.id }
            val desiredSet = desired.toSet()

            // 1. 移除不在目标列表中的项（从高索引到低索引，避免索引错位）
            var removeIdx = mediaItemCount - 1
            while (removeIdx >= 0) {
                val id = getMediaItemAt(removeIdx).mediaId.toLongOrNull()
                if (id == null || id !in desiredSet) {
                    removeMediaItem(removeIdx)
                }
                removeIdx--
            }

            // 2. 按目标顺序对齐：缺失项插入，乱序项移动到正确位置。
            //    所有操作都不触碰当前播放项的媒体源，播放不会中断。
            var cursor = 0
            while (cursor < desired.size) {
                val curId = if (cursor < mediaItemCount) {
                    getMediaItemAt(cursor).mediaId.toLongOrNull()
                } else null
                if (curId == desired[cursor]) {
                    cursor++
                    continue
                }
                var foundAt = -1
                for (i in cursor until mediaItemCount) {
                    if (getMediaItemAt(i).mediaId.toLongOrNull() == desired[cursor]) {
                        foundAt = i
                        break
                    }
                }
                if (foundAt >= 0) {
                    moveMediaItem(foundAt, cursor)
                } else {
                    addMediaItem(cursor, buildMediaItem(playlist[cursor]))
                }
                cursor++
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncPlaylistIncrementally failed: ${e.message}", e)
            false
        }
    }

    fun playAtIndex(index: Int) {
        if (playlist.isEmpty()) return
        val safeIndex = index.coerceIn(0, playlist.size - 1)
        val song = playlist[safeIndex]
        if (handleSameSongPlayback(song)) return

        // 切歌时释放兜底播放器，恢复 ExoPlayer 正常流程
        releaseFallbackPlayer()

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
        if (index >= 0) {
            applyRestore(index, song, position)
        } else {
            // 恢复时直接把完整库作为播放列表，避免"单曲播放列表"导致后续 play() 追加
            // 造成队列偏离，进而在播放中触发整表重排（冷启动首次返回列表卡顿的根因）
            // 查库移到 IO 线程：onCustomCommand 在主线程，runBlocking 全表查询有 ANR 风险
            serviceScope.launch {
                val library = repository.getAllSongsOnce()
                val libIndex = library.indexOfFirst { it.id == song.id }
                withContext(Dispatchers.Main) {
                    if (libIndex >= 0) {
                        setPlaylist(library)
                        applyRestore(libIndex, song, position)
                    } else {
                        // 歌曲不在库中（已被删除等）：退回单曲恢复
                        prepareSong(song, position)
                    }
                }
            }
            return
        }
    }

    // restoreSong 的公共收尾：设置索引/状态/pendingSeek，并续接未结束的历史记录
    private fun applyRestore(index: Int, song: Song, position: Long) {
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
        // 同步查找未结束记录续接，确保切歌前 currentHistoryId 已就位
        serviceScope.launch {
            val existing = withContext(Dispatchers.IO) {
                repository.getUnfinishedHistoryForSong(song.id)
            }
            withContext(Dispatchers.Main) {
                if (existing != null) {
                    currentHistoryId = existing.id
                    sessionListenTimeMs = existing.thisDuration
                    lastKnownPosition = position
                    lastHistorySongId = song.id
                } else {
                    transitionHistory(song)
                }
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
        releaseFallbackPlayer()
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

        // 同步 ExoPlayer timeline，否则拖拽后 timeline 与 playlist 永久错位，
        // 后续 removeMediaItem/addMediaItem/onMediaItemTransition 全部按错误索引工作
        player?.moveMediaItem(fromIndex, toIndex)

        when {
            fromIndex == currentIndex -> currentIndex = toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex--
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex++
        }
        _currentIndexFlow.value = currentIndex
    }

    fun removeSongPermanently(index: Int) {
        if (index !in playlist.indices) return

        // 清理转码缓存文件
        AudioTranscoder.deleteCachedTranscode(this, playlist[index].path)

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
        // 优先使用转码缓存（损坏 FLAC 已转码为 M4A），避免再次触发华为解码器崩溃
        val effectivePath = if (song.path.endsWith(".flac", ignoreCase = true)) {
            SettingsPreferences.getTranscodedPath(this, song.path)
                ?.takeIf { File(it).exists() } ?: song.path
        } else {
            song.path
        }
        val uri = if (effectivePath.startsWith("content://")) {
            Uri.parse(effectivePath).also { ensurePersistableReadPermission(it) }
        } else {
            Uri.fromFile(File(effectivePath))
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
                if (song.id == lastHistorySongId && currentHistoryId > 0) return@withLock
                val oldId = currentHistoryId
                if (oldId > 0) {
                    currentHistoryId = repository.finishPlayAndRecordNext(
                        oldId, System.currentTimeMillis(), sessionListenTimeMs, song
                    )
                } else {
                    currentHistoryId = recordPlayHistory(song)
                }
                lastHistorySongId = song.id
                sessionListenTimeMs = 0L
                lastKnownPosition = 0L
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
        // 必须用 Main.immediate：onDestroy 的 runBlocking 在主线程阻塞时，
        // Dispatchers.Main 会 post 到主 Looper（死锁/ANR）；immediate 在已处于主线程时内联执行
        val progress = withContext(Dispatchers.Main.immediate) {
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
        // 记录暂时失焦前是否在播放（含兜底播放），GAIN 时据此恢复
        val focusChangeListener = OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // 永久失去焦点（如其他应用开始播放），暂停并降低音量
                    Log.d(TAG, "AudioFocus: LOSS")
                    hasAudioFocus = false
                    player?.pause()
                    // 兜底 MediaPlayer 也要暂停，否则来电/其他应用播放时兜底仍出声
                    try { fallbackPlayer?.pause() } catch (_: Exception) {}
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // 暂时失去焦点（如来电），暂停；记录之前是否在播放以便 GAIN 恢复
                    Log.d(TAG, "AudioFocus: LOSS_TRANSIENT")
                    hasAudioFocus = false
                    resumeOnFocusGain = player?.isPlaying == true || isFallbackActive()
                    player?.pause()
                    try { fallbackPlayer?.pause() } catch (_: Exception) {}
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // 短暂失去焦点但可以降音播放（如通知音），降低音量
                    Log.d(TAG, "AudioFocus: LOSS_TRANSIENT_CAN_DUCK")
                    player?.volume = 0.3f
                    try { fallbackPlayer?.setVolume(0.3f, 0.3f) } catch (_: Exception) {}
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // 重新获得焦点，恢复音量；若此前因来电等被暂时暂停则恢复播放
                    Log.d(TAG, "AudioFocus: GAIN")
                    hasAudioFocus = true
                    player?.volume = 1.0f
                    try { fallbackPlayer?.setVolume(1.0f, 1.0f) } catch (_: Exception) {}
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        if (isFallbackActive()) {
                            try { fallbackPlayer?.start() } catch (_: Exception) {}
                        } else if (player?.playWhenReady == false) {
                            player?.play()
                        }
                    }
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
            // 注意：此处只构建请求对象，不实际请求焦点——
            // 服务创建不代表要播放（冷启动恢复 playWhenReady=false），
            // 无条件抢焦点会打断用户正在使用的其他播放器；实际请求延迟到 acquireAudioFocusIfNeeded
        } else {
            // minSdk=26（Android O），此分支为不可达的死代码，保留结构以防万一
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
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
            // minSdk=26（Android O），不可达；仅作结构兜底
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                { _ -> },
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
            // 非计数模式：重复 acquire 会重置超时计时（即"续期"），isHeld 状态下调用安全。
            // 超时仅作为漏释放时的兜底保险，正常路径由 releaseWakeLock 显式释放。
            it.acquire(10 * 60 * 1000L) // 10分钟超时
        }
        lastWakeLockRenewalAt = android.os.SystemClock.elapsedRealtime()
    }

    /** 播放中定期续期 WakeLock，防止连续播放超过超时后 CPU 休眠中断播放 */
    private fun renewWakeLockIfNeeded() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWakeLockRenewalAt >= 5 * 60 * 1000L) { // 每 5 分钟续一次
            lastWakeLockRenewalAt = now
            acquireWakeLock()
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
