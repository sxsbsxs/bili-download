package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressPolicyTest {
    @Test
    fun firstSnapshotIsPublishedAndPersisted() {
        val policy = DownloadProgressPolicy()

        assertEquals(
            DownloadProgressDecision(true, true),
            policy.evaluate(0, "连接中")
        )
    }

    @Test
    fun duplicateCallbacksAreIgnored() {
        val policy = DownloadProgressPolicy()
        policy.evaluate(10, "视频流")

        assertEquals(DownloadProgressDecision.NONE, policy.evaluate(10, "视频流"))
    }

    @Test
    fun roomSnapshotsAreThrottledByPercentage() {
        val policy = DownloadProgressPolicy()
        policy.evaluate(10, "视频流")

        assertEquals(
            DownloadProgressDecision(true, false),
            policy.evaluate(11, "视频流")
        )
        assertEquals(
            DownloadProgressDecision(true, false),
            policy.evaluate(12, "视频流")
        )
        assertEquals(
            DownloadProgressDecision(true, true),
            policy.evaluate(13, "视频流")
        )
    }

    @Test
    fun stageChangesPersistImmediately() {
        val policy = DownloadProgressPolicy()
        policy.evaluate(10, "视频流")

        assertEquals(
            DownloadProgressDecision(true, true),
            policy.evaluate(11, "音频流")
        )
    }

    @Test
    fun completionIsAlwaysPersistedAndProgressIsBounded() {
        val policy = DownloadProgressPolicy()
        policy.evaluate(5, "封装中")

        assertEquals(
            DownloadProgressDecision(true, true),
            policy.evaluate(140, "已完成")
        )
        assertEquals(
            DownloadProgressDecision.NONE,
            policy.evaluate(100, "已完成")
        )
    }
}
