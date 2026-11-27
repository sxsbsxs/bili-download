package com.example.bilidl.data.local.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskStatusTest {
    @Test
    fun queuedTaskCanStartPauseOrFail() {
        assertTrue(DownloadTaskStatus.canTransition(DownloadTaskStatus.QUEUED, DownloadTaskStatus.RUNNING))
        assertTrue(DownloadTaskStatus.canTransition(DownloadTaskStatus.QUEUED, DownloadTaskStatus.PAUSED))
        assertTrue(DownloadTaskStatus.canTransition(DownloadTaskStatus.QUEUED, DownloadTaskStatus.FAILED))
    }

    @Test
    fun runningTaskCanRetryPauseFailOrComplete() {
        assertTrue(DownloadTaskStatus.canTransition(DownloadTaskStatus.RUNNING, DownloadTaskStatus.QUEUED))
        assertTrue(DownloadTaskStatus.canTransition(DownloadTaskStatus.RUNNING, DownloadTaskStatus.PAUSED))
        assertTrue(DownloadTaskStatus.canTransition(DownloadTaskStatus.RUNNING, DownloadTaskStatus.FAILED))
        assertTrue(DownloadTaskStatus.canTransition(DownloadTaskStatus.RUNNING, DownloadTaskStatus.COMPLETED))
    }

    @Test
    fun failedAndPausedTasksCanOnlyReturnToQueue() {
        assertTrue(DownloadTaskStatus.canTransition(DownloadTaskStatus.FAILED, DownloadTaskStatus.QUEUED))
        assertTrue(DownloadTaskStatus.canTransition(DownloadTaskStatus.PAUSED, DownloadTaskStatus.QUEUED))
        assertFalse(DownloadTaskStatus.canTransition(DownloadTaskStatus.FAILED, DownloadTaskStatus.RUNNING))
        assertFalse(DownloadTaskStatus.canTransition(DownloadTaskStatus.PAUSED, DownloadTaskStatus.COMPLETED))
    }

    @Test
    fun completedTaskIsImmutableAndTerminal() {
        assertTrue(DownloadTaskStatus.isTerminal(DownloadTaskStatus.COMPLETED))
        assertFalse(DownloadTaskStatus.canTransition(DownloadTaskStatus.COMPLETED, DownloadTaskStatus.QUEUED))
        assertFalse(DownloadTaskStatus.canTransition(DownloadTaskStatus.COMPLETED, DownloadTaskStatus.RUNNING))
    }

    @Test
    fun unknownStatusCannotTransition() {
        assertFalse(DownloadTaskStatus.canTransition(99, DownloadTaskStatus.QUEUED))
        assertFalse(DownloadTaskStatus.canTransition(DownloadTaskStatus.RUNNING, 99))
    }
}
