package com.example.bilidl.data.repository

import com.example.bilidl.data.local.entity.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadTaskRecoveryPolicyTest {
    @Test
    fun missingWorkForQueuedOrRunningTasksIsReEnqueued() {
        assertEquals(
            DownloadTaskRecoveryAction.ENQUEUE_MISSING_WORK,
            DownloadTaskRecoveryPolicy.decide(DownloadTaskStatus.QUEUED, 0)
        )
        assertEquals(
            DownloadTaskRecoveryAction.ENQUEUE_MISSING_WORK,
            DownloadTaskRecoveryPolicy.decide(DownloadTaskStatus.RUNNING, 0)
        )
    }

    @Test
    fun existingWorkIsObservedInsteadOfReplaced() {
        assertEquals(
            DownloadTaskRecoveryAction.OBSERVE_EXISTING_WORK,
            DownloadTaskRecoveryPolicy.decide(DownloadTaskStatus.QUEUED, 1)
        )
        assertEquals(
            DownloadTaskRecoveryAction.OBSERVE_EXISTING_WORK,
            DownloadTaskRecoveryPolicy.decide(DownloadTaskStatus.RUNNING, 3)
        )
    }

    @Test
    fun failedWorkLookupNeverTriggersDuplicateEnqueue() {
        assertEquals(
            DownloadTaskRecoveryAction.OBSERVE_EXISTING_WORK,
            DownloadTaskRecoveryPolicy.decide(DownloadTaskStatus.RUNNING, null)
        )
    }

    @Test
    fun pausedFailedAndCompletedTasksAreOnlyRestoredToUi() {
        listOf(
            DownloadTaskStatus.PAUSED,
            DownloadTaskStatus.FAILED,
            DownloadTaskStatus.COMPLETED
        ).forEach { status ->
            assertEquals(
                DownloadTaskRecoveryAction.RESTORE_TERMINAL_STATE,
                DownloadTaskRecoveryPolicy.decide(status, 0)
            )
        }
    }
}
