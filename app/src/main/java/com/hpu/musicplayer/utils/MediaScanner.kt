package com.hpu.musicplayer.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.content.ContentUris
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*

/**
 * 旧版扫描器，已废弃。
 * 请使用 [ScanManager] 进行所有扫描操作。
 * 保留此文件仅用于参考旧实现。
 */
@Deprecated(
    message = "Use ScanManager instead",
    replaceWith = ReplaceWith("ScanManager", "com.hpu.musicplayer.utils.ScanManager")
)
object MediaScanner {

    private const val TAG = "MediaScanner"

    suspend fun scanAndSave(context: Context) {
        withContext(Dispatchers.IO) {
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

            val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            cursor?.use { c ->
                val idColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (c.moveToNext()) {
                    val id = c.getLong(idColumn)
                    val title = c.getString(titleColumn) ?: "未知歌曲"
                    val artist = c.getString(artistColumn) ?: "未知艺术家"
                    val album = c.getString(albumColumn) ?: "未知专辑"
                    val duration = c.getLong(durationColumn)
                    val path = c.getString(dataColumn)

                    // 过滤掉不存在的文件
                    if (path == null || !File(path).exists()) continue

                    val coverPath = extractCoverArt(context, path)
                    val lrcPath = findLrcFile(path)

                    songs.add(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            path = path,
                            coverPath = coverPath,
                            lrcPath = lrcPath
                        )
                    )
                }
            }

            val db = AppDatabase.getDatabase(context)
            db.songDao().deleteAll()
            db.songDao().insertAll(songs)
            Log.d(TAG, "Scanned and saved ${songs.size} songs")
        }
    }

    private fun extractCoverArt(context: Context, audioPath: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioPath)
            val picture = retriever.embeddedPicture
            if (picture != null) {
                val coverDir = File(context.cacheDir, "album_art")
                if (!coverDir.exists()) coverDir.mkdirs()
                val coverFile = File(coverDir, "${audioPath.hashCode()}.jpg")
                coverFile.writeBytes(picture)
                coverFile.absolutePath
            } else {
                // 未内嵌封面则尝试同目录的 cover.jpg/png
                val dir = File(audioPath).parentFile
                dir?.listFiles()?.firstOrNull { file ->
                    val name = file.nameWithoutExtension.lowercase()
                    (name == "cover" || name == "album" || name == "folder") &&
                            (file.extension.lowercase() == "jpg" || file.extension.lowercase() == "png")
                }?.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extract cover error: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    private fun findLrcFile(audioPath: String): String? {
        val audioFile = File(audioPath)
        val lrcFile = File(audioFile.parent, "${audioFile.nameWithoutExtension}.lrc")
        return if (lrcFile.exists()) lrcFile.absolutePath else null
    }

    suspend fun scanFolders(context: Context, folderUris: List<Uri>) {
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<Song>()
            val db = AppDatabase.getDatabase(context)
            db.songDao().deleteAll()

            for (uri in folderUris) {
                val documentFile = DocumentFile.fromTreeUri(context, uri) ?: continue
                scanDocumentTree(context, documentFile, songs)
            }
            db.songDao().insertAll(songs)
            Log.d(TAG, "Scanned ${songs.size} songs from custom folders")
        }
    }

    private fun scanDocumentTree(
        context: Context,
        directory: DocumentFile,
        result: MutableList<Song>
    ) {
        for (file in directory.listFiles()) {
            when {
                file.isDirectory -> scanDocumentTree(context, file, result)
                file.isFile -> {
                    val name = file.name ?: return
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension in listOf("mp3", "flac", "wav", "aac", "ogg")) {
                        extractAndAddSong(context, file.uri, result)
                    }
                }
            }
        }
    }

    private fun extractAndAddSong(context: Context, uri: Uri, result: MutableList<Song>) {
        val retriever = MediaMetadataRetriever()
        try {
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            if (afd != null) {
                retriever.setDataSource(afd.fileDescriptor)
                afd.close()
            } else {
                Log.w(TAG, "Cannot open file: $uri")
                return
            }

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment ?: "未知歌曲"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "未知艺术家"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "未知专辑"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0

            // 提取内嵌封面并保存到缓存
            val coverPath = extractCoverFromRetriever(context, retriever, uri.hashCode().toString())

            // 自定义文件夹扫描暂不支持查找同目录歌词文件（可后续扩展）
            result.add(
                Song(
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    path = uri.toString(),   // 存储 URI 字符串，后续播放需支持 content://
                    coverPath = coverPath,
                    lrcPath = null
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Extract error for $uri: ${e.message}")
        } finally {
            retriever.release()
        }
    }

    private fun extractCoverFromRetriever(context: Context, retriever: MediaMetadataRetriever, key: String): String? {
        return try {
            val picture = retriever.embeddedPicture ?: return null
            val coverDir = File(context.cacheDir, "album_art")
            if (!coverDir.exists()) coverDir.mkdirs()
            val coverFile = File(coverDir, "${key}.jpg")
            coverFile.writeBytes(picture)
            coverFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Cover extraction failed: ${e.message}")
            null
        }
    }

    private fun extractAndAddSong(path: String, result: MutableList<Song>, context: Context) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "未知歌曲"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "未知艺术家"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "未知专辑"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val coverPath = extractCoverArt(context, path)
            val lrcPath = findLrcFile(path)

            result.add(Song(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                path = path,
                coverPath = coverPath,
                lrcPath = lrcPath
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Extract error: $path, ${e.message}")
        } finally {
            retriever.release()
        }
    }
}