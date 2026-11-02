package com.hpu.musicplayer.utils

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.hpu.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object ScanManager {

    private const val TAG = "ScanManager"

    // ---------- 全量扫描（可选进度回调） ----------
    suspend fun scanAll(
        context: Context,
        onProgress: ((Int) -> Unit)? = null
    ): List<Song> = withContext(Dispatchers.IO) {
        // 先触发系统媒体扫描，确保文件被索引
        MediaScannerConnection.scanFile(
            context,
            arrayOf(Environment.getExternalStorageDirectory().absolutePath),
            null, null
        )
        // 挂起等待系统扫描完成
        scanAndWait(context, Environment.getExternalStorageDirectory().absolutePath)

        val songs = mutableListOf<Song>()
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        // 投影：Q+ 优先用 RELATIVE_PATH + DISPLAY_NAME 拼路径（DATA 列在 Scoped Storage 下常为 null 导致漏扫），
        // DATA 作为旧版本/兼容兜底
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA
        )
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val path = resolveMediaPath(cursor, idCol, relPathCol, nameCol, dataCol, uri) ?: continue
                // content:// 路径无法用 File.exists() 判断（恒 false），需跳过该检查；
                // 否则 resolveMediaPath 的 content URI 兜底分支永远扫不到歌
                if (!path.startsWith("content://") && !File(path).exists()) continue
                songs.add(extractSongFromPath(context, path))
                onProgress?.invoke(songs.size)
            }
        }
        Log.d(TAG, "Full scan found ${songs.size} songs")
        songs
    }

    /**
     * 解析 MediaStore 行的真实文件路径。
     * 优先级：DATA → RELATIVE_PATH+DISPLAY_NAME 拼接 → content URI（兜底，播放器支持 content://）。
     * Android 10+ 的 Scoped Storage 下 DATA 常为 null，需用 RELATIVE_PATH 拼接避免漏扫。
     */
    private fun resolveMediaPath(
        cursor: android.database.Cursor,
        idCol: Int,
        relPathCol: Int,
        nameCol: Int,
        dataCol: Int,
        contentUri: Uri
    ): String? {
        // 1. DATA 列（旧版本 / 兼容路径最可靠）
        if (dataCol >= 0) {
            val data = cursor.getString(dataCol)
            if (!data.isNullOrEmpty()) return data
        }
        // 2. Q+ 用 RELATIVE_PATH + DISPLAY_NAME 拼接（RELATIVE_PATH 形如 "Music/"）
        if (relPathCol >= 0 && nameCol >= 0) {
            val relPath = cursor.getString(relPathCol)
            val name = cursor.getString(nameCol)
            if (!relPath.isNullOrEmpty() && !name.isNullOrEmpty()) {
                val base = Environment.getExternalStorageDirectory().absolutePath
                val candidate = if (relPath.endsWith("/")) "$base/$relPath$name" else "$base/$relPath/$name"
                if (File(candidate).exists()) return candidate
            }
        }
        // 3. 兜底：构造 content URI（无法得到真实路径时，播放器/扫描已支持 content://）
        if (idCol >= 0) {
            return ContentUris.withAppendedId(contentUri, cursor.getLong(idCol)).toString()
        }
        return null
    }

    private suspend fun scanAndWait(context: Context, path: String) {
        suspendCancellableCoroutine<Unit> { continuation ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(path),
                null
            ) { _, _ ->
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    // ---------- 自定义扫描（可选进度回调） ----------
    suspend fun scanFolders(
        context: Context,
        folderUris: List<Uri>,
        onProgress: ((Int) -> Unit)? = null
    ): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        for (uri in folderUris) {
            val doc = DocumentFile.fromTreeUri(context, uri) ?: continue
            scanDocumentTree(context, doc, songs, onProgress)
        }
        // 触发系统扫描并等待完成
        scanAndWait(context, Environment.getExternalStorageDirectory().absolutePath)
        Log.d(TAG, "Custom scan found ${songs.size} songs")
        songs
    }


    /**
     * 将 SAF content URI 标准化为文件绝对路径，保证与 scanAll 路径格式一致。
     * 先从 MediaStore.DATA 查询，失败则尝试从 Document ID 拼接，都不行返回原值。
     */
    fun normalizePath(context: Context, path: String): String {
        if (!path.startsWith("content://")) return path
        return try {
            val uri = Uri.parse(path)
            // 方案1：查询 MediaStore.DATA（對已索引文件最可靠）
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) {
                        val filePath = cursor.getString(idx)
                        if (filePath != null && File(filePath).exists()) return filePath
                    }
                }
            }
            // 方案2：从 DocumentsContract document ID 拼接（"primary:Music/a.mp3" → "/storage/emulated/0/Music/a.mp3"）
            try {
                val docId = DocumentsContract.getDocumentId(uri)
                val colonIdx = docId.indexOf(':')
                if (colonIdx > 0) {
                    val relativePath = docId.substring(colonIdx + 1)
                    val storagePath = Environment.getExternalStorageDirectory().absolutePath
                    val candidate = "$storagePath/$relativePath"
                    if (File(candidate).exists()) return candidate
                }
            } catch (_: Exception) { }
            path // 无法解析，保留原 content URI
        } catch (e: Exception) {
            path
        }
    }

    // 辅助：从文件路径或 content URI 提取歌曲信息
    private fun extractSongFromPath(context: Context, path: String): Song {
        val retriever = MediaMetadataRetriever()
        var title = "未知歌曲"; var artist = "未知艺术家"; var album = "未知专辑"
        var duration = 0L; var coverPath: String? = null; var lrcPath: String? = null
        var fileSize = 0L
        try {
            // content:// 需用 setDataSource(context, Uri) 重载，否则抛 IllegalArgumentException
            if (path.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(path))
                // 文件大小通过 contentResolver 查询 SIZE 列
                context.contentResolver.query(Uri.parse(path), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        if (idx >= 0) fileSize = c.getLong(idx)
                    }
                }
            } else {
                retriever.setDataSource(path)
                fileSize = File(path).length()
            }
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: File(path).nameWithoutExtension.ifEmpty { "未知歌曲" }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "未知艺术家"
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "未知专辑"
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            coverPath = extractCoverFromRetriever(context, retriever, path.hashCode().toString())
                ?: extractCoverArt(context, path)
            lrcPath = findLrcFile(path) ?: extractEmbeddedLyrics(context, path)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting: $path", e)
        } finally {
            retriever.release()
        }
        return Song(
            title = title, artist = artist, album = album,
            duration = duration, path = path,
            coverPath = coverPath, lrcPath = lrcPath,
            fileSize = fileSize,
            addedDate = System.currentTimeMillis()
        )
    }

    // ---------- 自定义扫描增强（支持同目录歌词和封面） ----------
    private fun extractSongFromUri(
        context: Context,
        uri: Uri,
        parentDir: DocumentFile?,
        result: MutableList<Song>
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            var fileSize = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                }
            }

            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            if (afd == null) {
                Log.w(TAG, "openAssetFileDescriptor returned null for $uri")
                return
            }
            // use 确保 afd 在 setDataSource 抛异常时也能 close
            afd.use {
                retriever.setDataSource(it.fileDescriptor)
            }

            // ---------- 封面 ----------
            var coverPath: String? = null
            val embeddedCover = retriever.embeddedPicture
            if (embeddedCover != null) {
                coverPath = saveCoverToCache(context, uri.hashCode().toString(), embeddedCover)
            } else if (parentDir != null) {
                val coverFile = parentDir.listFiles().firstOrNull { file ->
                    file.isFile && file.name?.let { name ->
                        val lower = name.lowercase()
                        (lower.startsWith("cover") || lower.startsWith("album") ||
                                lower.startsWith("folder") || lower.startsWith("art")) &&
                                (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg"))
                    } == true
                }
                if (coverFile != null) {
                    context.contentResolver.openInputStream(coverFile.uri)?.use { input ->
                        val data = input.readBytes()
                        coverPath = saveCoverToCache(context, coverFile.uri.hashCode().toString(), data)
                    }
                }
            }

            // ---------- 歌词 ----------
            var lrcPath: String? = null
            // 从 uri 中提取纯文件名（不含路径）
            val docFile = DocumentFile.fromSingleUri(context, uri)
            val rawName = docFile?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "unknown.mp3"
            val audioName = rawName.substringBeforeLast('.')
            if (parentDir != null) {
                val lrcFile = parentDir.listFiles().firstOrNull { file ->
                    file.isFile && file.name?.let { name ->
                        // 直接精确匹配（无前缀）
                        name.equals("${audioName}.lrc", ignoreCase = true)
                    } == true
                }
                if (lrcFile != null) {
                    val lrcDest = File(context.filesDir, "custom_lrcs/${lrcFile.uri.hashCode()}.lrc")
                    lrcDest.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(lrcFile.uri)?.use { input ->
                        lrcDest.outputStream().use { output -> input.copyTo(output) }
                    }
                    lrcPath = lrcDest.absolutePath
                }
            }

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: audioName.ifEmpty { "未知歌曲" }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "未知艺术家"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "未知专辑"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0

            val songPath = normalizePath(context, uri.toString())

            if (lrcPath == null && !songPath.startsWith("content://")) {
                lrcPath = extractEmbeddedLyrics(context, songPath)
            }

            result.add(
                Song(
                    title = title, artist = artist, album = album,
                    duration = duration, path = songPath,
                    coverPath = coverPath, lrcPath = lrcPath,
                    fileSize = fileSize,
                    addedDate = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning $uri: ${e.message}")
        } finally {
            // 无论成功/异常/提前 return，都释放 retriever，避免泄漏
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun scanDocumentTree(
        context: Context,
        directory: DocumentFile,
        result: MutableList<Song>,
        onProgress: ((Int) -> Unit)? = null
    ) {
        for (file in directory.listFiles()) {
            if (file.isDirectory) {
                scanDocumentTree(context, file, result, onProgress)
            } else if (file.isFile) {
                val name = file.name ?: continue
                if (name.endsWith(".mp3", true) || name.endsWith(".flac", true) ||
                    name.endsWith(".wav", true) || name.endsWith(".aac", true) || name.endsWith(".ogg", true)) {
                    extractSongFromUri(context, file.uri, directory, result)
                    onProgress?.invoke(result.size)
                }
            }
        }
    }

    // ---------- 封面提取工具 ----------
    private fun extractCoverArt(context: Context, audioPath: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioPath)
            val picture = retriever.embeddedPicture
            if (picture != null) {
                saveCoverToCache(context, audioPath.hashCode().toString(), picture)
            } else {
                val dir = File(audioPath).parentFile
                val coverFile = dir?.listFiles()?.firstOrNull { file ->
                    val name = file.nameWithoutExtension.lowercase()
                    (name == "cover" || name == "album" || name == "folder") &&
                            (file.extension.equals("jpg", true) || file.extension.equals("png", true))
                }
                if (coverFile != null) {
                    saveCoverToCache(context, coverFile.nameWithoutExtension, coverFile.readBytes())
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractCoverArt failed: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    private fun extractCoverFromRetriever(context: Context, retriever: MediaMetadataRetriever, key: String): String? {
        return try {
            val picture = retriever.embeddedPicture ?: return null
            saveCoverToCache(context, key, picture)
        } catch (e: Exception) { null }
    }

    private fun saveCoverToCache(context: Context, key: String, data: ByteArray): String {
        // 使用 filesDir 而非 cacheDir，避免系统清理缓存导致封面丢失
        val coverDir = File(context.filesDir, "album_art")
        if (!coverDir.exists()) coverDir.mkdirs()
        val coverFile = File(coverDir, "${key}.jpg")
        coverFile.writeBytes(data)
        return coverFile.absolutePath
    }

    private fun findLrcFile(audioPath: String): String? {
        val audioFile = File(audioPath)
        val lrcFile = File(audioFile.parent, "${audioFile.nameWithoutExtension}.lrc")
        return if (lrcFile.exists()) lrcFile.absolutePath else null
    }

    private fun extractEmbeddedLyrics(context: Context, audioPath: String): String? {
        val lrcText = EmbeddedLyricsExtractor.extractSyncLyrics(audioPath) ?: return null
        return try {
            val destDir = File(context.filesDir, "embedded_lrcs")
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, "${audioPath.hashCode()}.lrc")
            destFile.writeText(lrcText)
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save embedded lyrics: ${e.message}")
            null
        }
    }
}