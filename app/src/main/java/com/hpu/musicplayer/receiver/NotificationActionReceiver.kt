package com.hpu.musicplayer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hpu.musicplayer.service.MusicService

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val service = MusicService.getInstance() ?: return
        when (intent?.action) {
            "PREV" -> service.playPrevious()
            "PLAY_PAUSE" -> service.togglePlayPause()
            "NEXT" -> service.playNext()
        }
    }
}