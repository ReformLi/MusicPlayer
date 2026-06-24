package com.hpu.musicplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hpu.musicplayer.data.dao.PlayHistoryDao
import com.hpu.musicplayer.data.dao.PlaybackStateDao
import com.hpu.musicplayer.data.dao.SongDao

@Database(
    entities = [Song::class, PlayHistory::class, PlaybackStateEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration 1 → 2：play_history 表结构变更
         * - 主键从 songId 改为 id（自增）
         * - 新增 endTime、thisDuration 字段
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 创建新表
                db.execSQL("""
                    CREATE TABLE play_history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        songId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        duration INTEGER NOT NULL,
                        path TEXT NOT NULL,
                        coverPath TEXT,
                        lrcPath TEXT,
                        playedAt INTEGER NOT NULL,
                        endTime INTEGER,
                        thisDuration INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // 2. 迁移现有数据（旧主键 songId → 普通字段，endTime=playedAt 作为默认）
                db.execSQL("""
                    INSERT INTO play_history_new 
                        (songId, title, artist, album, duration, path, coverPath, lrcPath, playedAt, endTime, thisDuration)
                    SELECT 
                        songId, title, artist, album, duration, path, coverPath, lrcPath, playedAt, playedAt, 0
                    FROM play_history
                """)

                // 3. 删除旧表，重命名新表
                db.execSQL("DROP TABLE play_history")
                db.execSQL("ALTER TABLE play_history_new RENAME TO play_history")
            }
        }

        /**
         * Migration 2 → 3：songs 表添加 path 唯一索引，防止重复歌曲
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 删除 path 重复的记录（保留 id 最小的那条）
                db.execSQL("DELETE FROM songs WHERE id NOT IN (SELECT MIN(id) FROM songs GROUP BY path)")
                // 2. 创建唯一索引
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_songs_path ON songs (path)")
            }
        }

        private val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "music_db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
