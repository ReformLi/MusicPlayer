package com.hpu.musicplayer.utils

import java.io.File

// 歌词解析器
data class LrcLine(val time: Long, val text: String)

object LrcParser {

    fun parse(lrcPath: String): List<LrcLine> {
        val file = File(lrcPath)
        if (!file.exists()) return emptyList()

        val lines = file.readLines()
        val result = mutableListOf<LrcLine>()
        // 匹配 [mm:ss.xx] / [mm:ss.xxx] / [mm:ss:xx] / [mm:ss]
        val regex = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{2,3}))?\\]")

        for (line in lines) {
            val match = regex.find(line) ?: continue
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            // 补全毫秒为3位
            val millisStr = match.groupValues[3].padEnd(3, '0')
            val millis = millisStr.toLong()
            val time = min * 60_000 + sec * 1000 + millis
            val text = line.substring(match.range.last + 1).trim()
            if (text.isNotEmpty()) {
                result.add(LrcLine(time, text))
            }
        }
        return result.sortedBy { it.time }
    }
}