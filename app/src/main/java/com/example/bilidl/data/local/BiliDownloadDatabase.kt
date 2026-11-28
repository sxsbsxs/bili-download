package com.example.bilidl.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.bilidl.data.local.dao.DownloadResourceDao
import com.example.bilidl.data.local.dao.DownloadTaskDao
import com.example.bilidl.data.local.dao.DownloadTaskEventDao
import com.example.bilidl.data.local.entity.DownloadResourceEntity
import com.example.bilidl.data.local.entity.DownloadTaskEntity
import com.example.bilidl.data.local.entity.DownloadTaskEventEntity

@Database(
    entities = [
        DownloadTaskEntity::class,
        DownloadResourceEntity::class,
        DownloadTaskEventEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class BiliDownloadDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun downloadResourceDao(): DownloadResourceDao
    abstract fun downloadTaskEventDao(): DownloadTaskEventDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE download_tasks ADD COLUMN downloadCover INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var instance: BiliDownloadDatabase? = null

        fun getInstance(context: Context): BiliDownloadDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BiliDownloadDatabase::class.java,
                    "bili_download.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
