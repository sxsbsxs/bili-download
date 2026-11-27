package com.example.bilidl.data.repository

import com.example.bilidl.data.local.entity.DownloadTaskEntity
import com.example.bilidl.data.local.entity.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadTaskSpecMergePolicyTest {
    @Test
    fun sparseWorkerInputDoesNotErasePresentationMetadataOrLifecycleState() {
        val existing = DownloadTaskEntity(
            taskId = "task-merge",
            bvid = "BV1RichData",
            aid = 123L,
            cid = 456L,
            pageNumber = 2,
            title = "Original title",
            uploader = "Uploader",
            durationSeconds = 321,
            coverUrl = "https://example.invalid/cover.jpg",
            qualityId = 64,
            audioOnly = false,
            downloadDanmaku = true,
            downloadSubtitles = true,
            status = DownloadTaskStatus.RUNNING,
            progress = 47,
            stage = "视频流",
            detail = "正在下载",
            completedAt = null,
            outputUri = null,
            outputName = null,
            outputMimeType = null,
            outputSizeBytes = null,
            errorMessage = null,
            attemptCount = 2
        )
        val workerInput = DownloadTaskEntity(
            taskId = "task-merge",
            bvid = null,
            aid = 0L,
            cid = 456L,
            pageNumber = 1,
            title = " ",
            uploader = "",
            durationSeconds = 0,
            coverUrl = null,
            qualityId = 80,
            audioOnly = false,
            downloadDanmaku = false,
            downloadSubtitles = false
        )

        val merged = DownloadTaskSpecMergePolicy.merge(existing, workerInput)

        assertEquals("BV1RichData", merged.bvid)
        assertEquals(123L, merged.aid)
        assertEquals(456L, merged.cid)
        assertEquals(2, merged.pageNumber)
        assertEquals("Original title", merged.title)
        assertEquals("Uploader", merged.uploader)
        assertEquals(321, merged.durationSeconds)
        assertEquals("https://example.invalid/cover.jpg", merged.coverUrl)
        assertEquals(DownloadTaskStatus.RUNNING, merged.status)
        assertEquals(47, merged.progress)
        assertEquals("视频流", merged.stage)
        assertEquals(2, merged.attemptCount)
    }
}
