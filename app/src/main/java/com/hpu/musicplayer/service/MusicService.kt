package com.hpu.musicplayer.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hpu.musicplayer.MainActivity
import com.hpu.musicplayer.R
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.PlayHistory
import com.hpu.musicplayer.data.PlaybackStateEntity
import com.hpu.musicplayer.data.Song
import com.hpu.musicplayer.receiver.NotificationActionReceiver
import com.hpu.musicplayer.utils.SettingsPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.random.Random

class MusicService : Service() {

    companion object {
        private const val TAG = "MusicService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "playback_channel"

        @Volatile
        private var instance: MusicService? = null
        fun getInstance(): MusicService? = instance

        private val _playerState = MutableStateFlow(PlayerData(null, PlaybackState.STOPPED, 0, 0))
        val playerState: StateFlow<PlayerData> = _playerState.asStateFlow()

        private val _playMode = MutableStateFlow(PlayMode.LIST_LOOP)
        val playModeState: StateFlow<PlayMode> = _playMode.asStateFlow()

        private val _playlistFlow = MutableStateFlow<List<Song>>(emptyList())
        val playlistFlow: StateFlow<List<Song>> = _playlistFlow.asStateFlow()

        private val _currentIndexFlow = MutableStateFlow(-1)
        val currentIndexFlow: StateFlow<Int> = _currentIndexFlow.asStateFlow()
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentCoverBitmap: Bitmap? = null
    private var currentSong: Song? = null
    private var progressJob: Job? = null

    private val playlist = mutableListOf<Song>()
    private var currentIndex = -1
    private var playMode = PlayMode.LIST_LOOP

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var mediaSession: MediaSessionCompat

    private var saveJob: Job? = null
    private var lastSavedProgress = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()
        initMediaSession()
        _playerState.value = PlayerData(null, PlaybackState.STOPPED, 0, 0)
        saveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(5000)
                saveCurrentState()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = LocalBinder()

    private suspend fun saveCurrentState() {
        val song = currentSong ?: return
        val progress = getCurrentPosition()
        if (progress == lastSavedProgress) return
        lastSavedProgress = progress
        val state = PlaybackStateEntity(
            currentSongId = song.id,
            position = progress,
            playMode = playMode.name
        )
        try {
            AppDatabase.getDatabase(this@MusicService).playbackStateDao().saveState(state)
        } catch (e: Exception) {
            Log.e(TAG, "Save state error: ${e.message}")
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlayer").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, 0, 0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = playNext()
                override fun onSkipToPrevious() = playPrevious()
            })
            isActive = true
        }
    }

    fun play(song: Song) {
        Log.d(TAG, "play() called: ${song.title}, path: ${song.path}")

        // 加载封面
        currentCoverBitmap = if (!song.coverPath.isNullOrEmpty()) {
            try {
                BitmapFactory.decodeFile(song.coverPath)
            } catch (e: Exception) {
                Log.e(TAG, "加载封面失败: ${e.message}")
                null
            }
        } else null

        // 同一首歌已在播放，忽略
        if (currentSong?.id == song.id && mediaPlayer?.isPlaying == true) return

        // 同一首歌但暂停，直接恢复
        if (currentSong?.id == song.id && mediaPlayer != null && !mediaPlayer!!.isPlaying) {
            mediaPlayer?.start()
            updateState(song, PlaybackState.PLAYING)
            updateNotificationAndForeground(song, PlaybackState.PLAYING)
            startProgressUpdates()
            CoroutineScope(Dispatchers.IO).launch { saveCurrentState() }
            return
        }

        // 释放旧播放器
        mediaPlayer?.release()
        val mp = MediaPlayer()
        try {
            if (song.path.startsWith("content://")) {
                val uri = Uri.parse(song.path)
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
                }
                val afd = contentResolver.openAssetFileDescriptor(uri, "r")
                if (afd != null) {
                    Log.d(TAG, "openAssetFileDescriptor success, size=${afd.length}")
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                } else {
                    // 降级：拷贝到临时文件
                    Log.w(TAG, "openAssetFileDescriptor returned null, fallback to temp file")
                    val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}.mp3")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (tempFile.length() > 0) {
                        Log.d(TAG, "Temp file created: ${tempFile.length()} bytes")
                        mp.setDataSource(tempFile.absolutePath)
                    } else {
                        throw IllegalStateException("无法访问文件，临时文件为空")
                    }
                }
            } else {
                mp.setDataSource(song.path)
            }
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener { playNext() }
            mediaPlayer = mp
            currentSong = song
            updateState(song, PlaybackState.PLAYING)
            updateNotificationAndForeground(song, PlaybackState.PLAYING)
            startProgressUpdates()

            // 记录播放历史
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(this@MusicService)
                db.playHistoryDao().insert(PlayHistory(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    duration = song.duration,
                    path = song.path,
                    coverPath = song.coverPath,
                    lrcPath = song.lrcPath,
                    playedAt = System.currentTimeMillis()
                ))
            }
            CoroutineScope(Dispatchers.IO).launch { saveCurrentState() }
            Log.d(TAG, "播放成功: ${song.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Play error: ${e.message}", e)
            showToast("播放失败: ${e.localizedMessage}")
            mp.release()
            mediaPlayer = null
            currentSong = null
            updateState(null, PlaybackState.STOPPED)
            stopForeground(true)
            stopSelf()
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        updateState(currentSong, PlaybackState.PAUSED)
        updateNotificationAndForeground(currentSong, PlaybackState.PAUSED)   // 修复：使用 PAUSED
        CoroutineScope(Dispatchers.IO).launch { saveCurrentState() }
    }

    fun resume() {
        mediaPlayer?.start()
        updateState(currentSong, PlaybackState.PLAYING)
        updateNotificationAndForeground(currentSong, PlaybackState.PLAYING)
        startProgressUpdates()   // 添加这行，确保进度流运行
    }

    fun togglePlayPause() {
        if (mediaPlayer?.isPlaying == true) pause() else resume()
    }

    fun seekTo(position: Long) {
        mediaPlayer?.seekTo(position.toInt())
        CoroutineScope(Dispatchers.IO).launch { saveCurrentState() }
    }

    fun clearPlaylist() {
        playlist.clear()
        currentIndex = -1
        _playlistFlow.value = emptyList()
        _currentIndexFlow.value = -1
        stopPlayback()
    }

    fun getCurrentPosition(): Long {
        return try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentPosition error: ${e.message}")
            0L
        }
    }
    fun getDuration(): Long = mediaPlayer?.duration?.toLong() ?: currentSong?.duration ?: 0

    fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    _playerState.value = _playerState.value.copy(
                        progress = mp.currentPosition.toLong(),
                        duration = mp.duration.toLong()
                    )
                }
                delay(100)// 可改为 80ms，每秒约 12.5 次更新，非常丝滑
            }
        }
    }

    fun setPlaylist(songs: List<Song>) {
        playlist.clear()
        playlist.addAll(songs)
        currentIndex = -1
        _playlistFlow.value = playlist.toList()
        _currentIndexFlow.value = currentIndex
    }

    fun playAtIndex(index: Int) {
        if (playlist.isEmpty()) return
        val safeIndex = index.coerceIn(0, playlist.size - 1)
        currentIndex = safeIndex
        _currentIndexFlow.value = currentIndex
        play(playlist[safeIndex])
    }

    fun playNext() {
        if (playlist.isEmpty()) return

        when (playMode) {
            PlayMode.LIST_LOOP -> {
                if (currentIndex < playlist.size - 1) playAtIndex(currentIndex + 1)
                else playAtIndex(0)
            }
            PlayMode.SINGLE_LOOP -> {
                // 安全播放当前歌曲（如果当前歌曲存在），否则播放第一首
                currentSong?.let { play(it) } ?: playAtIndex(0)
            }
            PlayMode.RANDOM -> {
                if (playlist.size == 1) {
                    play(playlist[0])
                    return
                }
                var randomIndex: Int
                do {
                    randomIndex = Random.nextInt(playlist.size)
                } while (randomIndex == currentIndex)
                playAtIndex(randomIndex)
            }
        }
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        when (playMode) {
            PlayMode.LIST_LOOP -> {
                if (currentIndex > 0) playAtIndex(currentIndex - 1)
                else playAtIndex(playlist.size - 1)
            }
            PlayMode.SINGLE_LOOP -> {
                currentSong?.let { play(it) } ?: playAtIndex(0)
            }
            PlayMode.RANDOM -> {
                var randomIndex: Int
                do { randomIndex = Random.nextInt(playlist.size) } while (randomIndex == currentIndex)
                playAtIndex(randomIndex)
            }
        }
    }

    fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || fromIndex >= playlist.size || toIndex < 0 || toIndex >= playlist.size) return
        val item = playlist.removeAt(fromIndex)
        playlist.add(toIndex, item)
        if (currentIndex == fromIndex) currentIndex = toIndex
        else if (currentIndex in minOf(fromIndex, toIndex)..maxOf(fromIndex, toIndex)) {
            if (fromIndex < toIndex) currentIndex-- else currentIndex++
        }
        _playlistFlow.value = playlist.toList()
        _currentIndexFlow.value = currentIndex
    }

    fun removeSongPermanently(index: Int) {
        if (index < 0 || index >= playlist.size) return
        playlist.removeAt(index)
        _playlistFlow.value = playlist.toList()
        when {
            playlist.isEmpty() -> {
                currentIndex = -1
                _currentIndexFlow.value = currentIndex
                stopSelf()
            }
            index < currentIndex -> {
                currentIndex--
                _currentIndexFlow.value = currentIndex
            }
            index == currentIndex -> {
                if (currentIndex >= playlist.size) currentIndex = playlist.size - 1
                playAtIndex(currentIndex)
            }
        }
    }

    fun cyclePlayMode() {
        playMode = when (playMode) {
            PlayMode.LIST_LOOP -> PlayMode.RANDOM
            PlayMode.RANDOM -> PlayMode.SINGLE_LOOP
            PlayMode.SINGLE_LOOP -> PlayMode.LIST_LOOP
        }
        _playMode.value = playMode
        Log.d(TAG, "PlayMode changed to: $playMode")
        // 显示提示
        val modeName = when (playMode) {
            PlayMode.LIST_LOOP -> "列表循环"
            PlayMode.RANDOM -> "随机播放"
            PlayMode.SINGLE_LOOP -> "单曲循环"
            else -> ""
        }
        showToast("$modeName 模式")
    }

    fun setPlayMode(mode: PlayMode) {
        playMode = mode
        _playMode.value = mode
    }

    fun prepareSong(song: Song, startPosition: Long) {
        Log.d(TAG, "prepareSong: ${song.title}, pos=$startPosition")
        mediaPlayer?.release()
        val mp = MediaPlayer()
        try {
            if (song.path.startsWith("content://")) {
                val uri = Uri.parse(song.path)
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
                }
                val afd = contentResolver.openAssetFileDescriptor(uri, "r")
                if (afd != null) {
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                } else {
                    val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}.mp3")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (tempFile.length() > 0) {
                        mp.setDataSource(tempFile.absolutePath)
                    } else {
                        throw IllegalStateException("临时文件为空")
                    }
                }
            } else {
                mp.setDataSource(song.path)
            }
            mp.prepare()
            mp.seekTo(startPosition.toInt())
            mp.setOnCompletionListener { playNext() }
            mediaPlayer = mp
            currentSong = song
            currentIndex = playlist.indexOfFirst { it.id == song.id }

            // 直接设置状态，使用传入的 startPosition 作为进度
            _playerState.value = PlayerData(
                currentSong = song,
                state = PlaybackState.PAUSED,
                progress = startPosition,
                duration = song.duration
            )
            // 同步更新 MediaSession
            mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, startPosition, 1f)
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build())
            updateNotificationAndForeground(song, PlaybackState.PAUSED)
            progressJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "prepareSong error: ${e.message}", e)
            mp.release()
        }
    }

    fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentSong = null
        updateState(null, PlaybackState.STOPPED)
        stopForeground(true)
        stopSelf()
    }

    private fun updateState(song: Song?, state: PlaybackState) {
        _playerState.value = PlayerData(
            currentSong = song,
            state = state,
            progress = getCurrentPosition(),
            duration = getDuration()
        )
        val pbState = if (state == PlaybackState.PLAYING) PlaybackStateCompat.STATE_PLAYING
        else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(pbState, getCurrentPosition(), 1f)
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(song: Song?, state: PlaybackState): Notification {
        if (song == null) {
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle("未播放")
                .build()
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = state == PlaybackState.PLAYING
        val playActionIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playActionTitle = if (isPlaying) "暂停" else "播放"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(currentCoverBitmap ?: BitmapFactory.decodeResource(resources, R.drawable.ic_music_note))
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_skip_previous, "上一首", createActionIntent("PREV"))
            .addAction(playActionIcon, playActionTitle, createActionIntent("PLAY_PAUSE"))
            .addAction(R.drawable.ic_skip_next, "下一首", createActionIntent("NEXT"))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .build()
    }

    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java)
        intent.action = action
        return PendingIntent.getBroadcast(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ======== 通知与前台服务统一管理（消除权限警告） ========
    @SuppressLint("MissingPermission")
    private fun updateNotificationAndForeground(song: Song?, state: PlaybackState) {
        if (song == null) return
        val notification = buildNotification(song, state)

        // 始终确保前台服务运行（即便用户关闭了通知控制，也至少显示一个最小化通知，否则服务可能被杀）
        val useForeground = if (SettingsPreferences.isNotificationControlEnabled(this)) true else Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (useForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            // Android 13+ 且用户关闭了通知权限，不再显示通知但前台服务仍需存在（通过其他方式保证服务存活，实际上如果没有通知权限，前台服务无法启动，这里可考虑停止服务或降级）
            stopForeground(true)
            // 尝试移除通知（无需权限，cancel 总是允许）
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    @SuppressLint("MissingPermission")
    fun updateNotification() {
        if (currentSong != null && SettingsPreferences.isNotificationControlEnabled(this)) {
            val state = if (mediaPlayer?.isPlaying == true) PlaybackState.PLAYING else PlaybackState.PAUSED
            updateNotificationAndForeground(currentSong, state)
        } else {
            // 如果通知被禁用，但服务仍在播放，可以停止前台服务或调整
            hideNotification()
        }
    }

    fun hideNotification() {
        stopForeground(true)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission")
    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@MusicService, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runBlocking { saveCurrentState() }
        mediaPlayer?.release()
        mediaPlayer = null
        progressJob?.cancel()
        mediaSession.release()
        instance = null
        super.onDestroy()
    }
}