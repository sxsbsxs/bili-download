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
        assertEquals(64, merged.qualityId)
        assertEquals(true, merged.downloadDanmaku)
        assertEquals(true, merged.downloadSubtitles)
        assertEquals(2, merged.attemptCount)
    }

    @Test
    fun explicitUserSpecCanReplaceWorkerDefaultsWithoutResettingLifecycle() {
        val workerFirst = DownloadTaskEntity(
            taskId = "task-race",
            bvid = null,
            aid = 0L,
            cid = 456L,
            pageNumber = 1,
            title = "视频",
            uploader = "",
            durationSeconds = 0,
            coverUrl = null,
            qualityId = 80,
            audioOnly = false,
            downloadDanmaku = false,
            downloadSubtitles = false,
            status = DownloadTaskStatus.RUNNING,
            progress = 23,
            stage = "视频流",
            detail = "正在下载"
        )
        val userSpec = workerFirst.copy(
            bvid = "BV1Race",
            aid = 123L,
            pageNumber = 3,
            title = "真实标题",
            uploader = "UP 主",
            durationSeconds = 180,
            coverUrl = "https://example.invalid/cover.jpg",
            qualityId = 64,
            audioOnly = true,
            downloadDanmaku = true,
            downloadSubtitles = true
        )

        val merged = DownloadTaskSpecMergePolicy.merge(
            workerFirst,
            userSpec,
            preferIncomingSpec = true
        )

        assertEquals("BV1Race", merged.bvid)
        assertEquals(3, merged.pageNumber)
        assertEquals("真实标题", merged.title)
        assertEquals("UP 主", merged.uploader)
        assertEquals(64, merged.qualityId)
        assertEquals(true, merged.audioOnly)
        assertEquals(true, merged.downloadDanmaku)
        assertEquals(true, merged.downloadSubtitles)
        assertEquals(DownloadTaskStatus.RUNNING, merged.status)
        assertEquals(23, merged.progress)
    }
}
