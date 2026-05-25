package com.hpu.musicplayer.utils

/**
 * 主题变化监听器接口
 * 允许组件注册监听主题变化事件
 */
interface ThemeChangeListener {
    /**
     * 当主题发生变化时调用
     * @param themeMode 新的主题模式 ("light", "dark", "system")
     */
    fun onThemeChanged(themeMode: String)
}

/**
 * 主题变化管理器
 * 用于管理主题变化监听器的注册和通知
 */
object ThemeChangeManager {
    private val listeners = mutableListOf<ThemeChangeListener>()

    /**
     * 注册主题变化监听器
     */
    fun registerListener(listener: ThemeChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * 注销主题变化监听器
     */
    fun unregisterListener(listener: ThemeChangeListener) {
        listeners.remove(listener)
    }

    /**
     * 通知所有监听器主题发生变化
     */
    fun notifyThemeChanged(themeMode: String) {
        listeners.forEach { listener ->
            try {
                listener.onThemeChanged(themeMode)
            } catch (e: Exception) {
                // 防止某个监听器的异常影响其他监听器
                e.printStackTrace()
            }
        }
    }

    /**
     * 清除所有监听器
     */
    fun clearListeners() {
        listeners.clear()
    }
}