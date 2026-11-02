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

        val lines = readLinesGuessingCharset(file)
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

    /**
     * 读取歌词文本并猜测字符集。
     * readLines() 固定 UTF-8，而国内 .lrc 常见 GBK 编码，直接读会整篇乱码。
     * 策略：BOM 优先（UTF-8/UTF-16 LE/BE）→ 无 BOM 先严格 UTF-8 解码，
     * 失败则回退 GBK（GBK 解码是宽容的，最坏也只是个别字符替换）。
     */
    private fun readLinesGuessingCharset(file: File): List<String> {
        val bytes = file.readBytes()
        val text: String = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> {
                // 宽容解码 UTF-8，按 U+FFFD 替换符占比判定真实编码：
                // - 纯 GBK 文件按 UTF-8 宽容解码会产生海量替换符（占比远超 5%）→ 整体回退 GBK
                // - UTF-8 主体 + 个别坏字节（下载损坏/截断/拼接）只产生极少量替换符 → 接受 UTF-8，
                //   局部一个替换符远好于旧「严格解码全有或全无」策略把个别坏字节
                //   放大成整篇 GBK 乱码
                val lenient = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                val replacementCount = lenient.count { it == '�' }
                if (replacementCount == 0 || replacementCount * 20 < lenient.length) {
                    lenient
                } else {
                    String(bytes, charset("GBK"))
                }
            }
        }
        return text.lines()
    }
}