package com.hpu.musicplayer.utils

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {

    /**
     * 应用主题模式到应用
     */
    fun applyThemeMode(themeMode: String) {
        when (themeMode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "system" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
                }
            }
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /**
     * 获取当前系统是否为深色模式
     */
    fun isSystemInDarkTheme(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.resources.configuration.isNightModeActive
        } else {
            @Suppress("DEPRECATION")
            (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * 获取当前应用的主题状态描述
     */
    fun getThemeDescription(context: Context): String {
        return when (SettingsPreferences.getThemeMode(context)) {
            "light" -> "浅色主题"
            "dark" -> "深色主题"
            else -> {
                if (isSystemInDarkTheme(context)) "跟随系统 (深色)" else "跟随系统 (浅色)"
            }
        }
    }

    /**
     * 验证主题模式是否有效
     */
    fun isValidThemeMode(themeMode: String): Boolean {
        return themeMode in listOf("system", "light", "dark")
    }

    /**
     * 获取主题模式的显示名称
     */
    fun getThemeDisplayName(themeMode: String, context: Context): String {
        return when (themeMode) {
            "light" -> context.getString(com.hpu.musicplayer.R.string.theme_light)
            "dark" -> context.getString(com.hpu.musicplayer.R.string.theme_dark)
            "system" -> {
                if (isSystemInDarkTheme(context)) {
                    "${context.getString(com.hpu.musicplayer.R.string.theme_system)} (深色)"
                } else {
                    "${context.getString(com.hpu.musicplayer.R.string.theme_system)} (浅色)"
                }
            }
            else -> context.getString(com.hpu.musicplayer.R.string.theme_system)
        }
    }
}