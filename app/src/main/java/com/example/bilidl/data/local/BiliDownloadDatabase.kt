package com.example.bilidl.data.local

import android.content.Context
import androidx.room.Database
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
    version = 1,
    exportSchema = true
)
abstract class BiliDownloadDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun downloadResourceDao(): DownloadResourceDao
    abstract fun downloadTaskEventDao(): DownloadTaskEventDao

    companion object {
        @Volatile
        private var instance: BiliDownloadDatabase? = null

        fun getInstance(context: Context): BiliDownloadDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BiliDownloadDatabase::class.java,
                    "bili_download.db"
                )
                    .build()
                    .also { instance = it }
            }
    }
}
