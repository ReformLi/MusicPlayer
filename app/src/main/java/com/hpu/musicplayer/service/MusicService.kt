package com.hpu.musicplayer.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
        // 加载封面 Bitmap
        currentCoverBitmap = if (!song.coverPath.isNullOrEmpty()) {
            try {
                BitmapFactory.decodeFile(song.coverPath)
            } catch (e: Exception) {
                Log.e(TAG, "加载封面失败: ${e.message}")
                null
            }
        } else null

        // 如果同一首歌正在播放，不做处理
        if (currentSong?.id == song.id && mediaPlayer?.isPlaying == true) return

        // 如果是同一首歌暂停了，直接恢复播放
        if (currentSong?.id == song.id && mediaPlayer?.isPlaying == false) {
            mediaPlayer?.start()
            updateState(song, PlaybackState.PLAYING)
            if (SettingsPreferences.isNotificationControlEnabled(this@MusicService)) {
                startForeground(NOTIFICATION_ID, buildNotification(song, PlaybackState.PLAYING))
            }
            startProgressUpdates()
            CoroutineScope(Dispatchers.IO).launch { saveCurrentState() }
            return
        }

        // 不同歌曲或未初始化，创建新播放器
        mediaPlayer?.release()
        val mp = MediaPlayer()
        try {
            if (song.path.startsWith("content://")) {
                val afd = contentResolver.openAssetFileDescriptor(Uri.parse(song.path), "r")
                if (afd != null) {
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                } else {
                    Log.e(TAG, "Cannot open content URI: ${song.path}")
                    showToast("无法打开文件")
                    return
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
            startProgressUpdates()
            if (SettingsPreferences.isNotificationControlEnabled(this@MusicService)) {
                startForeground(NOTIFICATION_ID, buildNotification(song, PlaybackState.PLAYING))
            }

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
        } catch (e: Exception) {
            Log.e(TAG, "Play error: ${e.message}", e)
            showToast("播放失败: ${e.message}")
            mp.release()
            if (mediaPlayer === mp) mediaPlayer = null
            currentSong = null
            updateState(null, PlaybackState.STOPPED)
            stopForeground(true)
            stopSelf()
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        updateState(currentSong, PlaybackState.PAUSED)
        startForeground(NOTIFICATION_ID, buildNotification(currentSong, PlaybackState.PAUSED))
        CoroutineScope(Dispatchers.IO).launch { saveCurrentState() }
    }

    fun resume() {
        mediaPlayer?.start()
        updateState(currentSong, PlaybackState.PLAYING)
        if (SettingsPreferences.isNotificationControlEnabled(this@MusicService)) {
            startForeground(NOTIFICATION_ID, buildNotification(currentSong, PlaybackState.PLAYING))
        }
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

    fun getCurrentPosition(): Long = mediaPlayer?.currentPosition?.toLong() ?: 0
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
                delay(300)
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
            PlayMode.SINGLE_LOOP -> play(playlist[currentIndex])
            PlayMode.RANDOM -> {
                if (playlist.size == 1) { play(playlist[0]); return }
                var randomIndex: Int
                do { randomIndex = Random.nextInt(playlist.size) } while (randomIndex == currentIndex)
                playAtIndex(randomIndex)
            }
        }
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        when (playMode) {
            PlayMode.LIST_LOOP, PlayMode.SINGLE_LOOP -> {
                if (currentIndex > 0) playAtIndex(currentIndex - 1)
                else playAtIndex(playlist.size - 1)
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
    }

    fun setPlayMode(mode: PlayMode) {
        playMode = mode
        _playMode.value = mode
    }

    fun prepareSong(song: Song, startPosition: Long) {
        mediaPlayer?.release()
        val mp = MediaPlayer()
        try {
            if (song.path.startsWith("content://")) {
                val afd = contentResolver.openAssetFileDescriptor(Uri.parse(song.path), "r")
                if (afd != null) {
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                } else {
                    Log.e(TAG, "prepareSong: Cannot open content URI")
                    return
                }
            } else {
                mp.setDataSource(song.path)
            }
            mp.prepare()
            mp.seekTo(startPosition.toInt())
            mediaPlayer = mp
            currentSong = song
            updateState(song, PlaybackState.PAUSED)
            startForeground(NOTIFICATION_ID, buildNotification(song, PlaybackState.PAUSED))
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

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun updateNotification() {
        if (currentSong != null && SettingsPreferences.isNotificationControlEnabled(this)) {
            val state = if (mediaPlayer?.isPlaying == true) PlaybackState.PLAYING else PlaybackState.PAUSED
            val notification = buildNotification(currentSong, state)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun hideNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
        // 如果正在播放但通知被禁用，停止前台服务但保持播放
        if (mediaPlayer?.isPlaying == true) {
            stopForeground(false)
        }
    }

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