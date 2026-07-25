package com.hpu.musicplayer.utils

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset

object EmbeddedLyricsExtractor {

    private const val TAG = "EmbeddedLyrics"

    fun extractSyncLyrics(audioPath: String): String? {
        val file = File(audioPath)
        if (!file.exists()) return null

        val ext = file.extension.lowercase()
        return try {
            FileInputStream(file).use { fis ->
                val data = fis.readBytes()
                when (ext) {
                    "mp3" -> extractFromMp3(data)
                    "flac", "ogg" -> extractFromVorbisComment(data, ext)
                    "m4a", "mp4", "aac" -> extractFromM4a(data)
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract lyrics from $audioPath: ${e.message}")
            null
        }
    }

    // ------------------------------ MP3 / ID3v2 ------------------------------

    private fun extractFromMp3(data: ByteArray): String? {
        if (data.size < 10) return null
        if (data[0].toInt() != 0x49 || data[1].toInt() != 0x44 || data[2].toInt() != 0x33) {
            return null
        }

        val version = data[3].toInt()
        val tagSize = syncSafeInt(data, 6)
        val tagData = data.copyOfRange(10, (10 + tagSize).coerceAtMost(data.size))

        var offset = 0
        while (offset < tagData.size - 10) {
            val frameId = String(tagData, offset, 4)
            val frameSize: Int
            val headerSize: Int

            if (version == 3) {
                frameSize = bigEndianInt(tagData, offset + 4)
                headerSize = 10
            } else if (version == 4) {
                frameSize = syncSafeInt(tagData, offset + 4)
                headerSize = 10
            } else {
                break
            }

            if (frameSize <= 0 || offset + headerSize + frameSize > tagData.size) break

            val frameData = tagData.copyOfRange(offset + headerSize, offset + headerSize + frameSize)

            if (frameId == "SYLT") {
                val lrc = parseSyltFrame(frameData)
                if (lrc != null) return lrc
            }

            offset += headerSize + frameSize
        }

        return null
    }

    private fun parseSyltFrame(data: ByteArray): String? {
        if (data.size < 8) return null
        var offset = 0
        val encoding = data[offset++].toInt() and 0xFF
        val language = String(data, offset, 3); offset += 3
        val timestampFormat = data[offset++].toInt() and 0xFF
        val contentType = data[offset++].toInt() and 0xFF

        val charset: Charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1, 2 -> Charsets.UTF_16
            3 -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }

        val descEnd = findNullTerminator(data, offset, encoding == 1 || encoding == 2)
        if (descEnd == -1) return null
        offset = descEnd + if (encoding == 1 || encoding == 2) 2 else 1

        val lines = mutableListOf<Pair<Long, String>>()

        while (offset < data.size - 4) {
            val textEnd = findNullTerminator(data, offset, encoding == 1 || encoding == 2)
            if (textEnd == -1) break
            val textBytes = data.copyOfRange(offset, textEnd)
            val text = try {
                String(textBytes, charset)
            } catch (_: Exception) {
                String(textBytes, Charsets.UTF_8)
            }
            offset = textEnd + if (encoding == 1 || encoding == 2) 2 else 1
            if (offset + 4 > data.size) break
            val timestamp = bigEndianInt(data, offset).toLong() and 0xFFFFFFFFL
            offset += 4
            lines.add(timestamp to text)
        }

        if (lines.isEmpty()) return null

        val sorted = lines.sortedBy { it.first }
        val sb = StringBuilder()
        for ((ts, text) in sorted) {
            val minutes = ts / 60000
            val seconds = (ts % 60000) / 1000
            val hundredths = (ts % 1000) / 10
            sb.append(String.format("[%02d:%02d.%02d]%s\n", minutes, seconds, hundredths, text))
        }
        return sb.toString()
    }

    // ------------------------------ FLAC / OGG (Vorbis Comment) ------------------------------

    private fun extractFromVorbisComment(data: ByteArray, ext: String): String? {
        val startIndex: Int
        if (ext == "flac") {
            if (data.size < 4 || data[0].toInt() != 0x66 || data[1].toInt() != 0x4C ||
                data[2].toInt() != 0x61 || data[3].toInt() != 0x43) return null
            startIndex = 4
        } else {
            startIndex = 0
        }

        var offset = startIndex
        val maxScan = (offset + 256 * 1024).coerceAtMost(data.size)

        while (offset < maxScan) {
            if (offset + 4 > data.size) break

            if (ext == "flac") {
                val blockType = data[offset].toInt() and 0x7F
                val isLast = (data[offset].toInt() and 0x80) != 0
                val blockSize = bigEndianInt24(data, offset + 1)
                offset += 4
                if (blockType == 4) {
                    val commentData = data.copyOfRange(offset, offset + blockSize.coerceAtMost(data.size - offset))
                    val lyrics = parseVorbisComments(commentData)
                    if (lyrics != null) return lyrics
                }
                offset += blockSize
                if (isLast) break
            } else {
                if (offset + "vorbis".length <= data.size &&
                    String(data, offset, "vorbis".length) == "vorbis"
                ) {
                    val afterVorbis = offset + "vorbis".length
                    if (afterVorbis + 4 <= data.size) {
                        val vendorLen = littleEndianInt(data, afterVorbis)
                        var commentOffset = afterVorbis + 4 + vendorLen
                        if (commentOffset + 4 <= data.size) {
                            val comments = littleEndianInt(data, commentOffset)
                            commentOffset += 4
                            val lyrics = parseVorbisCommentList(data, commentOffset, comments)
                            if (lyrics != null) return lyrics
                        }
                    }
                    break
                }
                offset++
            }
        }
        return null
    }

    private fun parseVorbisComments(data: ByteArray): String? {
        if (data.size < 8) return null
        var offset = 0
        val vendorLen = littleEndianInt(data, offset); offset += 4
        offset += vendorLen
        if (offset + 4 > data.size) return null
        val count = littleEndianInt(data, offset); offset += 4
        return parseVorbisCommentList(data, offset, count)
    }

    private fun parseVorbisCommentList(data: ByteArray, startOffset: Int, count: Int): String? {
        var offset = startOffset
        for (i in 0 until count) {
            if (offset + 4 > data.size) break
            val len = littleEndianInt(data, offset); offset += 4
            if (offset + len > data.size) break
            val comment = String(data, offset, len, Charsets.UTF_8)
            offset += len
            val eqIdx = comment.indexOf('=')
            if (eqIdx > 0) {
                val key = comment.substring(0, eqIdx).uppercase()
                val value = comment.substring(eqIdx + 1)
                if (key == "LYRICS" || key == "SYNCEDLYRICS" || key == "UNSYNCEDLYRICS") {
                    if (looksLikeLrc(value)) return value
                }
            }
        }
        return null
    }

    // ------------------------------ M4A / MP4 ------------------------------

    private fun extractFromM4a(data: ByteArray): String? {
        try {
            val lyricsTag = "©lyr".toByteArray()
            var idx = 0
            while (idx < data.size - 8) {
                if (data[idx + 4] == lyricsTag[0] && data[idx + 5] == lyricsTag[1] &&
                    data[idx + 6] == lyricsTag[2] && data[idx + 7] == lyricsTag[3]
                ) {
                    val atomSize = bigEndianInt(data, idx)
                    if (atomSize > 16 && idx + atomSize <= data.size) {
                        val content = String(data, idx + 16, atomSize - 16, Charsets.UTF_8)
                        if (looksLikeLrc(content)) return content
                    }
                    break
                }
                idx++
            }
        } catch (_: Exception) {}
        return null
    }

    // ------------------------------ helpers ------------------------------

    private fun looksLikeLrc(text: String): Boolean {
        val trimmed = text.trim()
        val regex = Regex("\\[\\d{2}:\\d{2}[.:]\\d{2,3}\\]")
        return regex.containsMatchIn(trimmed)
    }

    private fun findNullTerminator(data: ByteArray, start: Int, wide: Boolean): Int {
        var i = start
        if (wide) {
            while (i < data.size - 1) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
                i += 2
            }
        } else {
            while (i < data.size) {
                if (data[i].toInt() == 0) return i
                i++
            }
        }
        return -1
    }

    private fun syncSafeInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0x7F) shl 21) or
               ((data[offset + 1].toInt() and 0x7F) shl 14) or
               ((data[offset + 2].toInt() and 0x7F) shl 7) or
               (data[offset + 3].toInt() and 0x7F)
    }

    private fun bigEndianInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
               ((data[offset + 1].toInt() and 0xFF) shl 16) or
               ((data[offset + 2].toInt() and 0xFF) shl 8) or
               (data[offset + 3].toInt() and 0xFF)
    }

    private fun bigEndianInt24(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 16) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               (data[offset + 2].toInt() and 0xFF)
    }

    private fun littleEndianInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               ((data[offset + 2].toInt() and 0xFF) shl 16) or
               ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
