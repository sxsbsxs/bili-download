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
) : IOException(messageFor(statusCode, operation, detail)) {
    val isTransient: Boolean
        get() = statusCode == 408 ||
            statusCode == 425 ||
            statusCode == 429 ||
            statusCode in 500..599

    companion object {
        private fun messageFor(statusCode: Int, operation: String, detail: String): String {
            val hint = when (statusCode) {
                401, 403 -> "播放地址可能已过期、当前账号无权访问或 CDN 拒绝了本次请求；请重新解析并检查登录状态"
                404 -> "资源不存在或已失效，请重新解析视频"
                408 -> "服务器响应超时，请检查网络后重试"
                425, 429 -> "请求过于频繁，已触发服务器限流，请稍后重试"
                in 500..599 -> "CDN 服务暂时异常，可以稍后重试"
                else -> "请检查网络连接和视频访问权限"
            }
            return operation + "失败（HTTP " + statusCode + "）：" + hint +
                if (detail.isBlank()) "" else "；服务器信息：" + detail.take(160)
        }
    }
}

/** A response ended before the requested bytes arrived and can be safely restarted. */
internal class RetryableDownloadException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

internal object BiliDownloadRetryPolicy {
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
