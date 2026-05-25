package com.hpu.musicplayer.utils

import android.content.Context
import android.content.SharedPreferences

object SettingsPreferences {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_NOTIFICATION_CONTROL = "notification_control"
    private const val KEY_THEME_MODE = "theme_mode"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Notification control settings
    fun isNotificationControlEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_NOTIFICATION_CONTROL, true) // 默认开启

    fun setNotificationControlEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NOTIFICATION_CONTROL, enabled).apply()
    }

    // Theme settings
    fun getThemeMode(context: Context): String =
        getPrefs(context).getString(KEY_THEME_MODE, "system") ?: "system"

    fun setThemeMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
    }
}