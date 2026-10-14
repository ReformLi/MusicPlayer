package com.hpu.musicplayer.utils

import java.io.File

// 歌词解析器
data class LrcLine(val time: Long, val text: String)

object LrcParser {

    // 正则提升为对象级常量，避免每次 parse 重复编译
    // 匹配 [mm:ss.xx] / [mm:ss.xxx] / [mm:ss:xx] / [mm:ss]
    private val TIME_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{2,3}))?\\]")

    fun parse(lrcPath: String): List<LrcLine> {
        val file = File(lrcPath)
        if (!file.exists()) return emptyList()

        val lines = file.readLines()
        val result = mutableListOf<LrcLine>()

        for (line in lines) {
            // 用 findAll 支持一行多个时间戳（如 [00:01.00][00:15.00]副歌），
            // 同一句歌词在不同时间点都能正确高亮
            val matches = TIME_REGEX.findAll(line).toList()
            if (matches.isEmpty()) continue

            // 歌词文本：取最后一个时间戳之后的内容
            val lastMatch = matches.last()
            val text = line.substring(lastMatch.range.last + 1).trim()
            if (text.isEmpty()) continue

            // 每个时间戳各生成一条 LrcLine（指向同一句歌词）
            for (match in matches) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                // 补全毫秒为3位
                val millisStr = match.groupValues[3].padEnd(3, '0')
                val millis = millisStr.toLong()
                val time = min * 60_000 + sec * 1000 + millis
                result.add(LrcLine(time, text))
            }
        }
        return result.sortedBy { it.time }
    }
}