package com.example.bilidl

import java.io.File
import java.io.IOException

/**
 * Conservative disk-space guard for remuxing/publishing large media files.
 * The temporary muxed output is approximately the size of its source tracks;
 * reserve at least 5% extra, with an 8 MiB minimum for container overhead.
 */
internal object DownloadStoragePolicy {
    private const val MINIMUM_HEADROOM_BYTES = 8L * 1024L * 1024L
    private const val HEADROOM_DIVISOR = 20L

    fun requiredFreeBytes(sourceBytes: Long): Long {
        require(sourceBytes >= 0L) { "sourceBytes must not be negative" }
        val margin = maxOf(sourceBytes / HEADROOM_DIVISOR, MINIMUM_HEADROOM_BYTES)
        if (sourceBytes > Long.MAX_VALUE - margin) return Long.MAX_VALUE
        return sourceBytes + margin
    }

    fun ensureAvailable(directory: File, sourceBytes: Long) {
        val required = requiredFreeBytes(sourceBytes)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法访问存储目录：" + directory.absolutePath)
        }
        val available = directory.usableSpace
        if (available < required) {
            throw IOException(
                "可用存储空间不足：封装或保存该文件至少需要 " +
                    BiliDownloadEngine.formatSize(required) + "，当前可用 " +
                    BiliDownloadEngine.formatSize(available)
            )
        }
    }
}
