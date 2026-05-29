package com.hpu.musicplayer.utils

import android.content.Context
import android.util.Log
import com.hpu.musicplayer.data.AppDatabase
import com.hpu.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CoverMigration {
    private const val TAG = "CoverMigration"
    private const val COVERS_DIR = "covers"

    fun getCoverFile(context: Context, songId: Long): File {
        val dir = File(context.filesDir, COVERS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "cover_${songId}.jpg")
    }

    suspend fun migrateSongCover(context: Context, song: Song) = withContext(Dispatchers.IO) {
        // 自定义封面优先
        val sourcePath = song.customCoverPath ?: song.coverPath
        if (sourcePath.isNullOrEmpty()) return@withContext

        val sourceFile = File(sourcePath)
        // 只迁移外部存储路径（非私有目录非缓存目录）的文件，或者我们统一全部迁移到私有目录
        if (!sourceFile.exists()) return@withContext

        val destFile = getCoverFile(context, song.id)
        if (destFile.exists()) return@withContext  // 已迁移

        try {
            sourceFile.copyTo(destFile, overwrite = true)
//            Log.d(TAG, "Migrated cover for song ${song.id}: $destFile")

            // 更新数据库字段
            val updated = if (song.customCoverPath != null) {
                song.copy(customCoverPath = destFile.absolutePath)
            } else {
                song.copy(coverPath = destFile.absolutePath)
            }
            AppDatabase.getDatabase(context).songDao().update(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate cover for ${song.id}", e)
        }
    }

    suspend fun migrateAllCovers(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val songs = db.songDao().getAllSongsOnce()
        songs.forEach { migrateSongCover(context, it) }
    }

    fun deleteCoverFiles(context: Context, songId: Long) {
        val destFile = getCoverFile(context, songId)
        if (destFile.exists()) destFile.delete()
    }

    fun clearAllCovers(context: Context) {
        val dir = File(context.filesDir, COVERS_DIR)
        Log.d("clearAllCovers", "dir: ${dir}")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}