package com.hpu.musicplayer.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ScanManager {

    private const val TAG = "ScanManager"

    // ---------- 全量扫描（保留旧逻辑，用于完全重建） ----------
    suspend fun scanAll(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                if (!File(path).exists()) continue
                songs.add(extractSongFromPath(context, path))
            }
        }
        Log.d(TAG, "Full scan found ${songs.size} songs")
        songs
    }

    suspend fun scanFolders(context: Context, folderUris: List<Uri>): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        for (uri in folderUris) {
            val doc = DocumentFile.fromTreeUri(context, uri) ?: continue
            scanDocumentTree(context, doc, songs)
        }
        Log.d(TAG, "Custom scan found ${songs.size} songs")
        songs
    }

    // ---------- 增量扫描（保护自定义字段） ----------
    suspend fun scanIncremental(context: Context, folderUris: List<Uri>? = null) = withContext(Dispatchers.IO) {
        // 1. 执行扫描，获取当前设备上的所有目标歌曲
        val newSongs = if (folderUris == null) scanAll(context) else scanFolders(context, folderUris)

        // 2. 执行增量合并写入数据库
        mergeAndSave(context, newSongs)
    }

    private suspend fun mergeAndSave(context: Context, newSongs: List<Song>) {
        val db = AppDatabase.getDatabase(context)
        val existingSongs = db.songDao().getAllSongsOnce()  // 需要一次性获取所有歌曲，避免 Flow

        // 构建旧歌曲路径映射（路径 -> Song）
        val existingMap = mutableMapOf<String, Song>()
        existingSongs.forEach { existingMap[it.path] = it }

        val mergedSongs = mutableListOf<Song>()

        for (newSong in newSongs) {
            val oldSong = existingMap[newSong.path]
            if (oldSong != null) {
                // 合并：基础字段用新的，保护自定义字段
                val merged = oldSong.copy(
                    title = newSong.title,
                    artist = newSong.artist,
                    album = newSong.album,
                    duration = newSong.duration,
                    fileSize = newSong.fileSize,
                    // 封面处理：如果有自定义封面，保留；否则用新扫描的封面
                    coverPath = if (oldSong.customCoverPath != null) oldSong.coverPath
                    else (newSong.coverPath ?: oldSong.coverPath),
                    lrcPath = if (oldSong.customLrcPath != null) oldSong.lrcPath
                    else (newSong.lrcPath ?: oldSong.lrcPath),
                    // isFavorite, customCoverPath, customLrcPath, addedDate 保持旧值
                )
                mergedSongs.add(merged)
            } else {
                // 新歌曲，设置添加日期
                mergedSongs.add(newSong.copy(addedDate = System.currentTimeMillis()))
            }
        }

        // 删除不在当前扫描结果中的歌曲（文件已被删除的）
        val currentPaths = newSongs.map { it.path }.toSet()
        val toDelete = existingSongs.filter { it.path !in currentPaths }
        toDelete.forEach { db.songDao().delete(it) }

        // 全量替换数据库（因为 insertAll 是 replace 策略，会更新现有行）
        db.songDao().deleteAll()
        db.songDao().insertAll(mergedSongs)
        Log.d(TAG, "Incremental scan merged ${mergedSongs.size} songs")
    }

    // 辅助：从文件路径提取歌曲信息（全盘扫描用）
    private fun extractSongFromPath(context: Context, path: String): Song {
        val retriever = MediaMetadataRetriever()
        var title = "未知歌曲"; var artist = "未知艺术家"; var album = "未知专辑"
        var duration = 0L; var coverPath: String? = null; var lrcPath: String? = null
        try {
            retriever.setDataSource(path)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: File(path).nameWithoutExtension.ifEmpty { "未知歌曲" }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "未知艺术家"
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "未知专辑"
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            coverPath = extractCoverFromRetriever(context, retriever, path.hashCode().toString())
                ?: extractCoverArt(context, path)  // 已支持同目录 cover
            lrcPath = findLrcFile(path)             // 已支持同目录 .lrc
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting: $path", e)
        } finally {
            retriever.release()
        }
        val fileSize = File(path).length()
        return Song(
            title = title, artist = artist, album = album,
            duration = duration, path = path,
            coverPath = coverPath, lrcPath = lrcPath,
            fileSize = fileSize,
            addedDate = System.currentTimeMillis()
        )
    }

    // ---------- 自定义扫描增强：支持同目录歌词和封面 ----------
    private fun extractSongFromUri(
        context: Context,
        uri: Uri,
        parentDir: DocumentFile?,
        result: MutableList<Song>
    ) {
        try {
            var fileSize = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                }
            }

            val retriever = MediaMetadataRetriever()
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return
            retriever.setDataSource(afd.fileDescriptor)
            afd.close()

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

            result.add(
                Song(
                    title = title, artist = artist, album = album,
                    duration = duration, path = uri.toString(),
                    coverPath = coverPath, lrcPath = lrcPath,
                    fileSize = fileSize,
                    addedDate = System.currentTimeMillis()
                )
            )
            retriever.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning $uri: ${e.message}")
        }
    }

    private fun scanDocumentTree(context: Context, directory: DocumentFile, result: MutableList<Song>) {
        for (file in directory.listFiles()) {
            if (file.isDirectory) {
                scanDocumentTree(context, file, result)
            } else if (file.isFile) {
                val name = file.name ?: continue
                if (name.endsWith(".mp3", true) || name.endsWith(".flac", true) ||
                    name.endsWith(".wav", true) || name.endsWith(".aac", true) || name.endsWith(".ogg", true)) {
                    extractSongFromUri(context, file.uri, directory, result)   // 传入父文件夹 directory
                }
            }
        }
    }

    /**
     * 从音频文件的 URI 获取父目录 URI，然后查询同目录下的封面图片文件。
     */
    private fun findCoverInSameDir(context: Context, audioUri: Uri): String? {
        val parentUri = getParentUri(audioUri) ?: return null
        Log.d(TAG, "Searching cover in: $parentUri")

        return findChildFileMatching(context, parentUri) { name ->
            val lower = name.lowercase()
            (lower.startsWith("cover") || lower.startsWith("album") || lower.startsWith("folder") || lower.startsWith("art")) &&
                    (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg"))
        }?.let { fileUri ->
            copyCoverToCache(context, fileUri)
        }
    }

    /**
     * 查找同目录下与音频文件同名的 .lrc 歌词文件。
     */
    private fun findLrcInSameDir(context: Context, audioUri: Uri): String? {
        val parentUri = getParentUri(audioUri) ?: return null
        val audioName = audioUri.lastPathSegment?.substringBeforeLast('.') ?: return null

        Log.d(TAG, "Searching lrc for: $audioName in $parentUri")

        return findChildFileMatching(context, parentUri) { name ->
            name.removeSuffix(".lrc").equals(audioName, ignoreCase = true) && name.endsWith(".lrc", ignoreCase = true)
        }?.let { fileUri ->
            copyLrcToLocal(context, fileUri)
        }
    }

    /**
     * 获取文件所在目录的 URI。
     * 支持 content:// 和 file:// 协议，以及 SAF 树形 URI。
     */
    private fun getParentUri(uri: Uri): Uri? {
        return try {
            if (uri.scheme == "file") {
                val parentFile = File(uri.path!!).parentFile
                parentFile?.let { Uri.fromFile(it) }
            } else {
                // 更简单的方法：截掉最后一个路径段
                val uriString = uri.toString()
                val lastSlash = uriString.lastIndexOf('/')
                if (lastSlash > 0) {
                    Uri.parse(uriString.substring(0, lastSlash))
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting parent uri: ${e.message}")
            null
        }
    }

    /**
     * 在指定目录下查询符合条件的子文件。
     * @param predicate 文件名过滤器
     * @return 第一个匹配文件的 URI，找不到返回 null
     */
    private fun findChildFileMatching(context: Context, parentUri: Uri, predicate: (String) -> Boolean): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri, DocumentsContract.getDocumentId(parentUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameCol)
                if (displayName != null && predicate(displayName)) {
                    val docId = cursor.getString(idCol)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, docId)
                    Log.d(TAG, "Found matching file: $displayName -> $childUri")
                    return childUri
                }
            }
        }
        return null
    }

    /**
     * 将封面文件复制到应用缓存，返回缓存路径。
     */
    private fun copyCoverToCache(context: Context, sourceUri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val data = input.readBytes()
                saveCoverToCache(context, sourceUri.hashCode().toString(), data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy cover: ${e.message}")
            null
        }
    }

    /**
     * 将歌词文件复制到应用私有目录，返回本地路径。
     */
    private fun copyLrcToLocal(context: Context, sourceUri: Uri): String? {
        return try {
            val destDir = File(context.filesDir, "custom_lrcs")
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, "${sourceUri.hashCode()}.lrc")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy lrc: ${e.message}")
            null
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
        val coverDir = File(context.cacheDir, "album_art")
        if (!coverDir.exists()) coverDir.mkdirs()
        val coverFile = File(coverDir, "${key}.jpg")
        coverFile.writeBytes(data)
        Log.d(TAG, "Cover saved: ${coverFile.absolutePath}")
        return coverFile.absolutePath
    }

    private fun findLrcFile(audioPath: String): String? {
        val audioFile = File(audioPath)
        val lrcFile = File(audioFile.parent, "${audioFile.nameWithoutExtension}.lrc")
        return if (lrcFile.exists()) lrcFile.absolutePath else null
    }
}