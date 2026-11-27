package com.example.bilidl.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.bilidl.data.local.entity.DownloadResourceEntity
import com.example.bilidl.data.local.entity.DownloadResourceType
import com.example.bilidl.data.local.entity.DownloadTaskEntity
import com.example.bilidl.data.local.entity.DownloadTaskEventEntity
import com.example.bilidl.data.local.entity.DownloadTaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliDownloadDatabaseTest {
    private lateinit var database: BiliDownloadDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BiliDownloadDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun taskResourceAndEventRecordsPersistAndCascadeOnDelete() = runBlocking {
        val taskId = "task-cascade"
        database.downloadTaskDao().upsert(task(taskId))
        database.downloadResourceDao().upsert(
            DownloadResourceEntity(
                resourceId = "$taskId:media",
                taskId = taskId,
                resourceType = DownloadResourceType.MEDIA,
                displayName = "demo.mp4",
                mimeType = "video/mp4",
                contentUri = "content://media/external/video/1",
                sizeBytes = 4096
            )
        )
        database.downloadTaskEventDao().insert(
            DownloadTaskEventEntity(
                taskId = taskId,
                fromStatus = null,
                toStatus = DownloadTaskStatus.QUEUED,
                stage = "等待下载",
                message = "任务已创建"
            )
        )

        assertEquals(1, database.downloadResourceDao().countByTask(taskId))
        assertEquals(1, database.downloadTaskEventDao().getRecent(taskId, 10).size)

        database.downloadTaskDao().deleteById(taskId)

        assertTrue(database.downloadResourceDao().getByTask(taskId).isEmpty())
        assertTrue(database.downloadTaskEventDao().getRecent(taskId, 10).isEmpty())
    }

    @Test
    fun taskSearchAndLiveObservationReturnUpdatedRecords() = runBlocking {
        val matching = task("task-search", title = "Bilibili sample", bvid = "BV1RoomTest")
        database.downloadTaskDao().upsert(matching)
        database.downloadTaskDao().upsert(task("task-other", title = "Other video", bvid = "BV2Else"))

        val searchResults = database.downloadTaskDao().search("Room").first()
        assertEquals(listOf("task-search"), searchResults.map { it.taskId })

        val observed = database.downloadTaskDao().observeAll().first()
        assertEquals(2, observed.size)
        assertEquals("Bilibili sample", observed.first { it.taskId == "task-search" }.title)
    }

    @Test
    fun progressSnapshotAndStatusCountsAreDurable() = runBlocking {
        val taskId = "task-progress"
        database.downloadTaskDao().upsert(task(taskId, status = DownloadTaskStatus.RUNNING))
        database.downloadTaskDao().updateProgress(
            taskId = taskId,
            progress = 42,
            stage = "视频流",
            detail = "正在接收数据",
            now = 123456L,
            runningStatus = DownloadTaskStatus.RUNNING
        )

        val stored = database.downloadTaskDao().getById(taskId)
        assertEquals(42, stored?.progress)
        assertEquals("视频流", stored?.stage)
        assertEquals(1, database.downloadTaskDao().countByStatus(DownloadTaskStatus.RUNNING))
    }

    private fun task(
        id: String,
        status: Int = DownloadTaskStatus.QUEUED,
        title: String = "Video",
        bvid: String = "BV1TestVideo"
    ) = DownloadTaskEntity(
        taskId = id,
        bvid = bvid,
        aid = 123L,
        cid = 456L,
        pageNumber = 1,
        title = title,
        uploader = "Uploader",
        durationSeconds = 120,
        coverUrl = null,
        qualityId = 80,
        audioOnly = false,
        downloadDanmaku = false,
        downloadSubtitles = false,
        status = status
    )
}
