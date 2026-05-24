package com.hpu.musicplayer.utils

import android.content.Context
import android.content.SharedPreferences

object LyricConfig {
    private const val PREFS_NAME = "lyric_settings"
    private const val KEY_FONT_SIZE = "font_size"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFontSize(context: Context): Float =
        getPrefs(context).getFloat(KEY_FONT_SIZE, 16f)   // 默认 16sp

    fun setFontSize(context: Context, size: Float) {
        getPrefs(context).edit().putFloat(KEY_FONT_SIZE, size).apply()
    }
}