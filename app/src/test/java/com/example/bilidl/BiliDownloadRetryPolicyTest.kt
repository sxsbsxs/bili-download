package com.example.bilidl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class BiliDownloadRetryPolicyTest {
    @Test
    fun retriesTransientHttpResponses() {
        assertTrue(BiliDownloadRetryPolicy.isRetryable(BiliHttpException(429, "CDN")))
        assertTrue(BiliDownloadRetryPolicy.isRetryable(BiliHttpException(503, "CDN")))
        assertFalse(BiliDownloadRetryPolicy.isRetryable(BiliHttpException(403, "CDN")))
        assertFalse(BiliDownloadRetryPolicy.isRetryable(BiliHttpException(404, "CDN")))
    }

    @Test
    fun findsHttpStatusThroughDownloaderWrappers() {
        val wrapped = IOException(
            "分段下载失败",
            IOException("CDN failure", BiliHttpException(403, "CDN"))
        )

        assertTrue(BiliDownloadRetryPolicy.httpStatus(wrapped) == 403)
        assertTrue(BiliDownloadRetryPolicy.httpStatus(SocketTimeoutException("timeout")) == null)
    }

    @Test
    fun retriesNetworkInterruptions() {
        assertTrue(BiliDownloadRetryPolicy.isRetryable(SocketTimeoutException("read timed out")))
        assertTrue(BiliDownloadRetryPolicy.isRetryable(RetryableDownloadException("partial response")))
    }

    @Test
    fun retryCountIsBounded() {
        val timeout = SocketTimeoutException("read timed out")
        assertTrue(BiliDownloadRetryPolicy.shouldRetry(timeout, 0, 3))
        assertTrue(BiliDownloadRetryPolicy.shouldRetry(timeout, 2, 3))
        assertFalse(BiliDownloadRetryPolicy.shouldRetry(timeout, 3, 3))
        assertFalse(BiliDownloadRetryPolicy.shouldRetry(IllegalArgumentException("bad input"), 0, 3))
    }
}
