package com.hpu.musicplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var title: String,
    var artist: String,
    var album: String,
    val duration: Long,          // 毫秒
    val path: String,            // 音频文件绝对路径
    val coverPath: String? = null,  // 专辑封面路径
    val lrcPath: String? = null,     // 歌词文件路径
    var isFavorite: Boolean = false,   // 新增

    var customCoverPath: String? = null,   // 用户自定义封面路径
    var customLrcPath: String? = null,     // 用户自定义歌词路径
    var fileSize: Long = 0,               // 文件大小（字节）
    var addedDate: Long = System.currentTimeMillis()   // 新增，默认当前时间
)