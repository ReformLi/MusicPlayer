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
    version = 1,
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
         * 数据库迁移定义。
         * 当前版本为 1，无需迁移。
         * 升级数据库版本时，必须在此添加对应的 Migration 对象。
         * 示例:
         *   val MIGRATION_1_2 = object : Migration(1, 2) {
         *       override fun migrate(db: SupportSQLiteDatabase) { ... }
         *   }
         */
        private val ALL_MIGRATIONS = emptyArray<Migration>()

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "music_db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .fallbackToDestructiveMigration() // 兜底：无匹配迁移时重建数据库
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}

