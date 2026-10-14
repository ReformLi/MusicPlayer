package com.hpu.musicplayer.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FLAC → AAC/M4A 转码器。
 *
 * 背景：华为 OMX.google.flac.decoder 对损坏的 FLAC（含坏帧）容错差，解码中崩溃。
 * 系统 MediaPlayer 兜底虽能出声，但进度/切歌同步复杂。更彻底的方案是：
 * 播放失败时，后台把损坏的 FLAC 转码为 AAC/M4A（走 MediaExtractor → MediaCodec 解码
 * → MediaCodec AAC 编码 → MediaMuxer 封装），下次播放直接命中缓存的 M4A。
 *
 * 注：Android 无 MP3 编码器、MediaMuxer 不支持 MP3 封装，故转码为 AAC/M4A。
 */
object AudioTranscoder {

    private const val TAG = "AudioTranscoder"

    // 输出 AAC 参数
    private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 192_000   // 192kbps，接近无损感知质量
    private const val SAMPLE_RATE = 44_100
    private const val CHANNEL_COUNT = 2

    /** 转码缓存目录：context.filesDir/transcoded */
    fun cacheDir(context: Context): File =
        File(context.filesDir, "transcoded").apply { if (!exists()) mkdirs() }

    /** 根据源文件路径生成缓存文件的唯一文件名 */
    fun cacheFileName(sourcePath: String): String {
        // 用源路径的 hashCode 保证唯一性，扩展名 .m4a
        val hash = sourcePath.hashCode().toUInt().toString(16)
        return "transcoded_$hash.m4a"
    }

    /**
     * 删除某源文件对应的转码缓存文件与映射记录。
     * 在用户删除歌曲时调用，避免缓存文件残留占用内部空间。
     */
    fun deleteCachedTranscode(context: Context, sourcePath: String) {
        val cachedPath = SettingsPreferences.getTranscodedPath(context, sourcePath)
        if (cachedPath != null) {
            try { File(cachedPath).delete() } catch (_: Exception) {}
        }
        SettingsPreferences.removeTranscodedPath(context, sourcePath)
    }

    /**
     * 同步转码 FLAC → M4A（调用方应在 IO 线程执行）。
     *
     * @param sourcePath 源 FLAC 文件绝对路径
     * @param outputPath 输出 M4A 文件绝对路径
     * @return 是否成功
     */
    fun transcodeToAac(sourcePath: String, outputPath: String): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var audioTrackIndex = -1

        return try {
            // 1. 准备输出文件
            val outputFile = File(outputPath)
            if (outputFile.exists()) outputFile.delete()

            // 2. MediaExtractor 读取源 FLAC
            extractor = MediaExtractor().apply {
                setDataSource(sourcePath)
            }

            val audioTrack = findAudioTrack(extractor)
            if (audioTrack < 0) {
                Log.e(TAG, "No audio track found in source")
                return false
            }
            extractor.selectTrack(audioTrack)

            val inputFormat = extractor.getTrackFormat(audioTrack)
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 } ?: SAMPLE_RATE
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 } ?: CHANNEL_COUNT
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false

            // 3. 解码器
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            // 4. AAC 编码器
            val encoderFormat = MediaFormat.createAudioFormat(AAC_MIME, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            encoder = MediaCodec.createEncoderByType(AAC_MIME).apply {
                configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            // 5. MediaMuxer 封装为 M4A
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 6. 转码主循环
            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()

            // 解码器输出的 PCM 缓冲（可能比编码器 input buffer 大，需分块喂入）
            var pendingPcm: ByteBuffer? = null
            // 全局递增的 PTS（µs）：每个编码器输入块对应 1024 samples 的时长
            var nextPtsUs = 0L
            val samplesPerFrame = 1024L
            val frameDurationUs = samplesPerFrame * 1_000_000L / sampleRate

            while (!encoderDone) {
                // 6a. 喂数据给解码器
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 6b. 解码器输出 → 累积到 pendingPcm
                if (!decoderDone) {
                    val decOutIndex = decoder.dequeueOutputBuffer(decoderInfo, 10_000)
                    when {
                        decOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续 */ }
                        decOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 忽略 */ }
                        decOutIndex >= 0 -> {
                            val decBuf = decoder.getOutputBuffer(decOutIndex)
                            val isEos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            if (decBuf != null && decoderInfo.size > 0) {
                                // 把解码数据追加到 pendingPcm
                                val src = decBuf.duplicate()
                                src.position(decoderInfo.offset)
                                src.limit(decoderInfo.offset + decoderInfo.size)
                                pendingPcm = appendPcm(pendingPcm, src)
                            }

                            decoder.releaseOutputBuffer(decOutIndex, false)
                            if (isEos) decoderDone = true
                        }
                    }
                }

                // 6c. 从 pendingPcm 分块喂给编码器（每块不超过编码器 input buffer 容量）
                if (!encoderDone) {
                    val encInIndex = encoder.dequeueInputBuffer(10_000)
                    if (encInIndex >= 0) {
                        val encBuf = encoder.getInputBuffer(encInIndex)!!
                        encBuf.clear()
                        val pending = pendingPcm
                        if (pending != null && pending.remaining() > 0) {
                            // 每帧字节数：samplesPerFrame(1024) × channelCount × 2字节(16bit) = 4096 字节
                            val bytesPerFrame = (samplesPerFrame * channelCount * 2).toInt()
                            // 取 min(pending 剩余, encBuf 容量) 的字节数，并对齐到帧边界
                            var chunkSize = minOf(pending.remaining(), encBuf.remaining())
                            chunkSize = (chunkSize / bytesPerFrame) * bytesPerFrame
                            if (chunkSize == 0) chunkSize = minOf(pending.remaining(), bytesPerFrame)
                            val src = pending.duplicate()
                            src.limit(pending.position() + chunkSize)
                            encBuf.put(src)
                            pending.position(pending.position() + chunkSize)
                            if (pending.remaining() == 0) pendingPcm = null

                            // 用当前块的 PTS，然后递增到下一块
                            val pts = nextPtsUs
                            nextPtsUs += frameDurationUs

                            val flags = if (decoderDone && pendingPcm == null) {
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            } else 0
                            encoder.queueInputBuffer(
                                encInIndex, 0, chunkSize, pts, flags
                            )
                        } else if (decoderDone) {
                            // 解码完成且无剩余数据：发送编码器 EOS
                            encoder.queueInputBuffer(
                                encInIndex, 0, 0, nextPtsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            // 无数据可喂，稍后再试
                        }
                    }
                }

                // 6d. 编码器输出 → 写入 Muxer
                val encOutIndex = encoder.dequeueOutputBuffer(encoderInfo, 10_000)
                when {
                    encOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续 */ }
                    encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            val newFormat = encoder.outputFormat
                            audioTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    encOutIndex >= 0 -> {
                        val encBuf = encoder.getOutputBuffer(encOutIndex)
                        if (encBuf != null && (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (encoderInfo.size != 0 && muxerStarted && audioTrackIndex >= 0) {
                                encBuf.position(encoderInfo.offset)
                                encBuf.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(audioTrackIndex, encBuf, encoderInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(encOutIndex, false)
                        if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                        }
                    }
                }
            }

            // 7. 清理
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}

            val success = outputFile.exists() && outputFile.length() > 0
            Log.i(TAG, "Transcode ${if (success) "succeeded" else "failed"}: $sourcePath -> $outputPath (${outputFile.length()} bytes)")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Transcode failed: $sourcePath -> $outputPath", e)
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            if (muxerStarted) { try { muxer?.stop() } catch (_: Exception) {} }
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            // 失败时清理半成品
            File(outputPath).delete()
            false
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** 把解码出的 PCM 数据追加到累积缓冲（若为空则新建）。返回新的累积缓冲。 */
    private fun appendPcm(pending: ByteBuffer?, src: ByteBuffer): ByteBuffer {
        val srcSize = src.remaining()
        if (pending == null) {
            val newBuf = ByteBuffer.allocate(srcSize)
            newBuf.put(src)
            newBuf.flip()
            return newBuf
        }
        val unread = pending.remaining()
        // 总是分配一个足够大的新缓冲，避免 compact 后的容量判断出错
        val newBuf = ByteBuffer.allocate(unread + srcSize)
        newBuf.put(pending)
        newBuf.put(src)
        newBuf.flip()
        return newBuf
    }

    /**
     * 在 IO 线程异步转码，返回输出路径（失败返回 null）。
     */
    suspend fun transcodeAsync(context: Context, sourcePath: String): String? =
        withContext(Dispatchers.IO) {
            val outPath = File(cacheDir(context), cacheFileName(sourcePath)).absolutePath
            if (transcodeToAac(sourcePath, outPath)) outPath else null
        }
}
