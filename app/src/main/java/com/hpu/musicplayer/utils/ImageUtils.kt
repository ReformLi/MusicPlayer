package com.hpu.musicplayer.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File

object ImageUtils {

    private const val TAG = "ImageUtils"

    fun getAlbumArt(audioFilePath: String): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(audioFilePath)

            val artBytes = retriever.embeddedPicture
            retriever.release()

            if (artBytes != null) {
                BitmapFactory.decodeStream(ByteArrayInputStream(artBytes))
            } else {
                // Try to find external album art
                findExternalAlbumArt(audioFilePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting album art for $audioFilePath", e)
            null
        }
    }

    private fun findExternalAlbumArt(audioFilePath: String): Bitmap? {
        val audioFile = File(audioFilePath)
        val parentDir = audioFile.parentFile ?: return null

        val baseName = audioFile.nameWithoutExtension
        val possibleNames = listOf(
            "$baseName.jpg",
            "$baseName.png",
            "cover.jpg",
            "cover.png",
            "folder.jpg",
            "album.jpg"
        )

        for (name in possibleNames) {
            val artFile = File(parentDir, name)
            if (artFile.exists()) {
                return try {
                    BitmapFactory.decodeFile(artFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading external album art: ${artFile.absolutePath}", e)
                    null
                }
            }
        }

        return null
    }

    fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }
}