package com.example.bilidl

import android.os.ParcelFileDescriptor
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

internal interface PositionalFileWriter : Closeable {
    fun writeAt(offset: Long, bytes: ByteArray): Int
    fun sync(): Boolean
}

internal fun interface PositionalFileWriterFactory {
    fun open(target: File): PositionalFileWriter
}

internal object NativePositionalFileWriterFactory : PositionalFileWriterFactory {
    override fun open(target: File): PositionalFileWriter {
        val descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE)
        return object : PositionalFileWriter {
            override fun writeAt(offset: Long, bytes: ByteArray): Int =
                NativeTransfer.writeAt(descriptor.fd, offset, bytes)

            override fun sync(): Boolean = NativeTransfer.sync(descriptor.fd)

            override fun close() = descriptor.close()
        }
    }
}

/**
 * BiliDl's own range downloader. It uses Android's HTTP/TLS implementation,
 * but owns range scheduling, checkpointing, concurrent writes and resume logic.
 */
internal object SegmentedHttpDownloader {
    private const val CHUNK_SIZE = 4L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_RANGE_ATTEMPTS = 3
    private const val MAX_SEQUENTIAL_ATTEMPTS = 3
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"

    private data class Probe(
        val totalBytes: Long,
        val etag: String,
        val lastModified: String,
        val resourceKey: String
    )

    suspend fun download(
        urls: List<String>,
        target: File,
        cookie: String?,
        concurrency: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
        writerFactory: PositionalFileWriterFactory = NativePositionalFileWriterFactory
    ) = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                downloadOne(url, target, cookie, concurrency, isCancelled, onProgress, writerFactory)
                return@withContext
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IOException("分段下载失败：" + (lastError?.message ?: "没有可用 CDN 地址"), lastError)
    }

    private suspend fun downloadOne(
        url: String,
        target: File,
        cookie: String?,
        concurrency: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
        writerFactory: PositionalFileWriterFactory
    ) {
        val probe = probeWithRetry(url, cookie)
        if (
            probe == null ||
            !TransferCheckpointPolicy.supportsVerifiedRanges(probe.etag, probe.lastModified)
        ) {
            // Without a stable validator, parallel ranges could mix bytes from
            // different CDN representations. Use a clean sequential response.
            downloadSequentialWithRetry(url, target, cookie, isCancelled, onProgress)
            return
        }
        downloadRanges(url, target, cookie, probe, concurrency, isCancelled, onProgress, writerFactory)
    }

    private suspend fun probeWithRetry(url: String, cookie: String?): Probe? {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_RANGE_ATTEMPTS) {
            try {
                return probe(url, cookie)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (!BiliDownloadRetryPolicy.shouldRetry(error, attempt, MAX_RANGE_ATTEMPTS)) {
                    throw error
                }
                delay(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("CDN 探测失败")
    }

    private fun probe(url: String, cookie: String?): Probe? {
        val connection = openConnection(url, cookie).apply {
            setRequestProperty("Range", "bytes=0-0")
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_OK) return null
            if (status != HttpURLConnection.HTTP_PARTIAL) {
                throw BiliHttpException(status, "CDN 探测")
            }
            val contentRange = connection.getHeaderField("Content-Range").orEmpty()
            val total = Regex("""bytes\s+\d+-\d+/(\d+)""", RegexOption.IGNORE_CASE)
                .find(contentRange)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: throw IOException("CDN 未提供有效的 Content-Range")
            if (total <= 0L) throw IOException("CDN 返回了无效文件大小")
            connection.inputStream.use { input ->
                if (input.read() < 0) throw IOException("CDN 返回了空数据")
            }
            return Probe(
                totalBytes = total,
                etag = connection.getHeaderField("ETag").orEmpty(),
                lastModified = connection.getHeaderField("Last-Modified").orEmpty(),
                resourceKey = runCatching {
                    URL(url).let { it.host + it.path }
                }.getOrDefault("")
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadRanges(
        url: String,
        target: File,
        cookie: String?,
        probe: Probe,
        concurrency: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
        writerFactory: PositionalFileWriterFactory
    ) = coroutineScope {
        target.parentFile?.mkdirs()
        val mapFile = File(target.absolutePath + ".ranges")
        val metaFile = File(target.absolutePath + ".meta")
        val chunkCount = ((probe.totalBytes + CHUNK_SIZE - 1L) / CHUNK_SIZE).toInt()
        val expectedMeta = listOf(
            probe.totalBytes.toString(),
            probe.etag,
            probe.lastModified,
            probe.resourceKey,
            CHUNK_SIZE.toString()
        ).joinToString("\n")

        val targetMatches = target.isFile && target.length() == probe.totalBytes
        val metadataMatches = metaFile.isFile && metaFile.readText() == expectedMeta
        val mapMatches = mapFile.isFile && mapFile.readText().length == chunkCount
        val reusable = TransferCheckpointPolicy.canReuse(
            etag = probe.etag,
            lastModified = probe.lastModified,
            targetMatches = targetMatches,
            metadataMatches = metadataMatches,
            mapMatches = mapMatches
        )

        val completed = if (reusable) {
            mapFile.readText().map { it == '1' }.toBooleanArray()
        } else {
            java.io.RandomAccessFile(target, "rw").use { it.setLength(probe.totalBytes) }
            metaFile.writeText(expectedMeta)
            BooleanArray(chunkCount).also { mapFile.writeText("0".repeat(chunkCount)) }
        }

        if (completed.size != chunkCount) {
            completed.fill(false)
            mapFile.writeText("0".repeat(chunkCount))
        }

        val completedBytes = AtomicLong(
            completed.indices.sumOf { index ->
                if (!completed[index]) 0L
                else minOf(CHUNK_SIZE, probe.totalBytes - index.toLong() * CHUNK_SIZE)
            }
        )
        val total = probe.totalBytes
        val inFlightBytes = AtomicLong(0L)
        val lock = Any()

        writerFactory.open(target).use { writer ->
            val semaphore = Semaphore(concurrency.coerceIn(1, 8))
            completed.indices.filterNot { completed[it] }.map { index ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (isCancelled()) throw CancellationException("下载已取消")
                        val start = index.toLong() * CHUNK_SIZE
                        val end = minOf(total - 1L, start + CHUNK_SIZE - 1L)
                        downloadRangeWithRetry(
                            url = url,
                            cookie = cookie,
                            probe = probe,
                            writer = writer,
                            start = start,
                            end = end,
                            isCancelled = isCancelled,
                            completedBytes = completedBytes,
                            inFlightBytes = inFlightBytes,
                            totalBytes = total,
                            onProgress = onProgress
                        )
                        synchronized(lock) {
                            if (!writer.sync()) {
                                throw IOException("分段写入同步失败")
                            }
                            completed[index] = true
                            mapFile.writeText(completed.joinToString("") { if (it) "1" else "0" })
                        }
                    }
                }
            }.awaitAll()

            if (!writer.sync()) {
                throw IOException("无法将下载数据同步到磁盘")
            }
        }

        mapFile.delete()
        metaFile.delete()
        onProgress(100)
    }

    private suspend fun downloadRangeWithRetry(
        url: String,
        cookie: String?,
        probe: Probe,
        writer: PositionalFileWriter,
        start: Long,
        end: Long,
        isCancelled: () -> Boolean,
        completedBytes: AtomicLong,
        inFlightBytes: AtomicLong,
        totalBytes: Long,
        onProgress: (Int) -> Unit
    ): Long {
        val expected = end - start + 1L
        var lastError: Exception? = null

        for (attempt in 0 until MAX_RANGE_ATTEMPTS) {
            val attemptBytes = AtomicLong(0L)
            try {
                val received = downloadRange(
                    url, cookie, probe, writer, start, end, isCancelled
                ) { bytes ->
                    attemptBytes.addAndGet(bytes)
                    inFlightBytes.addAndGet(bytes)
                    val current = completedBytes.get() + inFlightBytes.get()
                    onProgress(((current * 100L) / totalBytes).toInt().coerceIn(0, 99))
                }
                if (received != expected) {
                    throw RetryableDownloadException("分段长度不匹配：$received / $expected")
                }

                inFlightBytes.addAndGet(-attemptBytes.get())
                completedBytes.addAndGet(received)
                val current = completedBytes.get() + inFlightBytes.get()
                onProgress(((current * 100L) / totalBytes).toInt().coerceIn(0, 99))
                return received
            } catch (cancelled: CancellationException) {
                inFlightBytes.addAndGet(-attemptBytes.get())
                throw cancelled
            } catch (error: Exception) {
                inFlightBytes.addAndGet(-attemptBytes.get())
                val current = completedBytes.get() + inFlightBytes.get()
                onProgress(((current * 100L) / totalBytes).toInt().coerceIn(0, 99))
                lastError = error
                if (!BiliDownloadRetryPolicy.shouldRetry(error, attempt, MAX_RANGE_ATTEMPTS)) {
                    throw error
                }
                delay(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("CDN 分段下载失败")
    }

    private suspend fun downloadRange(
        url: String,
        cookie: String?,
        probe: Probe,
        writer: PositionalFileWriter,
        start: Long,
        end: Long,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val connection = openConnection(url, cookie).apply {
            setRequestProperty("Range", "bytes=$start-$end")
            val validator = probe.etag.ifBlank { probe.lastModified }
            if (validator.isNotBlank()) setRequestProperty("If-Range", validator)
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_PARTIAL) {
                throw BiliHttpException(status, "CDN 分段请求")
            }
            val range = connection.getHeaderField("Content-Range").orEmpty()
            val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
                .find(range) ?: throw IOException("CDN 未提供有效的分段范围")
            val actualStart = match.groupValues[1].toLong()
            val actualEnd = match.groupValues[2].toLong()
            val actualTotal = match.groupValues[3].toLong()
            if (actualStart != start || actualEnd != end || actualTotal != probe.totalBytes) {
                throw IOException("CDN 返回了不匹配的分段范围")
            }

            var offset = start
            var remaining = end - start + 1L
            var received = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                while (remaining > 0L) {
                    if (isCancelled()) throw CancellationException("下载已取消")
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw RetryableDownloadException("分段连接提前结束")
                    if (count == 0) continue
                    val block = if (count == buffer.size) buffer else buffer.copyOf(count)
                    val written = writer.writeAt(offset, block)
                    if (written != count) {
                        throw IOException("本地分段写入失败（$written）")
                    }
                    offset += count
                    remaining -= count
                    received += count
                    onBytes(count.toLong())
                }
            }
            received
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadSequentialWithRetry(
        url: String,
        target: File,
        cookie: String?,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_SEQUENTIAL_ATTEMPTS) {
            target.delete()
            File(target.absolutePath + ".ranges").delete()
            File(target.absolutePath + ".meta").delete()
            try {
                downloadSequential(url, target, cookie, isCancelled, onProgress)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                target.delete()
                if (!BiliDownloadRetryPolicy.shouldRetry(error, attempt, MAX_SEQUENTIAL_ATTEMPTS)) {
                    throw error
                }
                delay(750L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("顺序下载失败")
    }

    private fun downloadSequential(
        url: String,
        target: File,
        cookie: String?,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        val connection = openConnection(url, cookie)
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw BiliHttpException(status, "下载服务器请求")
            }
            val total = connection.contentLengthLong
            var copied = 0L
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(target, false).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastPercent = -1
                    while (true) {
                        if (isCancelled()) throw CancellationException("下载已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (total > 0L) {
                            val percent = ((copied * 100L) / total).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (copied == 0L) throw IOException("下载到的文件为空")
            if (total > 0L && copied < total) {
                throw RetryableDownloadException("下载未完成，连接提前中断")
            }
            File(target.absolutePath + ".ranges").delete()
            File(target.absolutePath + ".meta").delete()
            onProgress(100)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, cookie: String?): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Connection", "keep-alive")
            if (!cookie.isNullOrBlank()) {
                setRequestProperty("Cookie", "SESSDATA=$cookie")
            }
        }
    }
}
