package com.example.bilidl

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** HTTP failures retain their status so authentication/permission errors are not retried. */
internal class BiliHttpException(
    val statusCode: Int,
    operation: String,
    detail: String = ""
) : IOException(
    operation + "失败（HTTP " + statusCode + "）" +
        if (detail.isBlank()) "" else "：" + detail
) {
    val isTransient: Boolean
        get() = statusCode == 408 ||
            statusCode == 425 ||
            statusCode == 429 ||
            statusCode in 500..599
}

/** A response ended before the requested bytes arrived and can be safely restarted. */
internal class RetryableDownloadException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

internal object BiliDownloadRetryPolicy {
    fun httpStatus(error: Throwable): Int? {
        val visited = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            if (current is BiliHttpException) return current.statusCode
            current = current.cause
        }
        return null
    }

    fun isRetryable(error: Throwable): Boolean {
        val visited = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            when (current) {
                is BiliHttpException -> if (current.isTransient) return true
                is RetryableDownloadException,
                is SocketTimeoutException,
                is ConnectException,
                is UnknownHostException,
                is SocketException,
                is EOFException -> return true
            }
            current = current.cause
        }
        return false
    }

    fun shouldRetry(error: Throwable, attemptCount: Int, maxRetries: Int): Boolean =
        attemptCount < maxRetries && isRetryable(error)
}
