package com.hpu.musicplayer.utils

import android.content.Context
import android.content.SharedPreferences

object LyricConfig {
    private const val PREFS_NAME = "lyric_settings"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_OFFSET = "offset"  // 歌词偏移量（毫秒）

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFontSize(context: Context): Float =
        getPrefs(context).getFloat(KEY_FONT_SIZE, 15f)   // 默认 15sp

    fun setFontSize(context: Context, size: Float) {
        getPrefs(context).edit().putFloat(KEY_FONT_SIZE, size).apply()
    }

    // 获取歌词偏移量（毫秒），默认 0
    fun getOffset(context: Context): Int =
        getPrefs(context).getInt(KEY_OFFSET, 0)

    // 设置歌词偏移量（毫秒）
    fun setOffset(context: Context, offsetMs: Int) {
        getPrefs(context).edit().putInt(KEY_OFFSET, offsetMs).apply()
    }
}