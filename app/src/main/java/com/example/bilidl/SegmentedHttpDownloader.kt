package com.example.bilidl

import android.os.ParcelFileDescriptor
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

/**
 * BiliDl's own range downloader. It uses Android's HTTP/TLS implementation,
 * but owns range scheduling, checkpointing, concurrent writes and resume logic.
 */
internal data class DownloadSourceSample(
    val url: String,
    val bytes: Int,
    val elapsedNanos: Long
)

internal object DownloadSourceRankingPolicy {
    /** Faster successful probes first; all original candidates remain as fallbacks. */
    fun rank(
        originalUrls: List<String>,
        samples: List<DownloadSourceSample>
    ): List<String> {
        val ranked = samples.asSequence()
            .filter { it.bytes > 0 && it.url in originalUrls }
            .sortedByDescending {
                it.bytes.toDouble() / it.elapsedNanos.coerceAtLeast(1L)
            }
            .map { it.url }
            .toList()
        return (ranked + originalUrls).distinct()
    }
}

internal object SegmentedHttpDownloader {
    private const val CHUNK_SIZE = 4L * 1024L * 1024L
    private const val BUFFER_SIZE = 256 * 1024
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

    private companion object {
        const val SOURCE_SAMPLE_BYTES = 64 * 1024
        const val SOURCE_PROBE_TIMEOUT_MS = 3_000
        const val MAX_SOURCE_PROBES = 6
    }

    /**
     * BVD-style bounded CDN sampling: probe every candidate in parallel with at
     * most 64 KiB per URL, then try the fastest verified sample first. Every
     * original URL remains in the fallback list if a sample is blocked/unsupported.
     */
    private suspend fun rankCandidatesBySpeed(
        urls: List<String>,
        cookie: String?
    ): List<String> = coroutineScope {
        if (urls.size <= 1) return@coroutineScope urls
        val samples = urls.take(MAX_SOURCE_PROBES).map { url ->
            async(Dispatchers.IO) { sampleSource(url, cookie) }
        }.awaitAll()
        DownloadSourceRankingPolicy.rank(
            originalUrls = urls,
            samples = samples
        )
    }

    private suspend fun sampleSource(
        url: String,
        cookie: String?
    ): DownloadSourceSample {
        val startedAt = System.nanoTime()
        val connection = openConnection(url, cookie).apply {
            connectTimeout = SOURCE_PROBE_TIMEOUT_MS
            readTimeout = SOURCE_PROBE_TIMEOUT_MS
            setRequestProperty("Range", "bytes=0-${SOURCE_SAMPLE_BYTES - 1}")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK &&
                status != HttpURLConnection.HTTP_PARTIAL
            ) {
                return DownloadSourceSample(url, 0, elapsedSince(startedAt))
            }
            val expected = connection.contentLengthLong
                .takeIf { it >= 0L }
                ?.coerceAtMost(SOURCE_SAMPLE_BYTES.toLong())
            var count = 0
            val buffer = ByteArray(8 * 1024)
            connection.inputStream.use { input ->
                while (count < SOURCE_SAMPLE_BYTES) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size, SOURCE_SAMPLE_BYTES - count))
                    if (read < 0) break
                    if (read > 0) count += read
                }
            }
            val rangeValid = if (status == HttpURLConnection.HTTP_PARTIAL) {
                val match = Regex(
                    """bytes\\s+0-(\\d+)/(\\d+)""",
                    RegexOption.IGNORE_CASE
                ).find(connection.getHeaderField("Content-Range").orEmpty())
                match != null && match.groupValues[1].toLongOrNull() == count.toLong() - 1L &&
                    (match.groupValues[2].toLongOrNull() ?: 0L) > count.toLong() - 1L
            } else {
                true
            }
            val bodyValid = expected == null || count.toLong() == expected
            DownloadSourceSample(
                url = url,
                bytes = if (count > 0 && bodyValid && rangeValid) count else 0,
                elapsedNanos = elapsedSince(startedAt)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            DownloadSourceSample(url, 0, elapsedSince(startedAt))
        } finally {
            connection.disconnect()
        }
    }

    private fun elapsedSince(startedAt: Long): Long =
        (System.nanoTime() - startedAt).coerceAtLeast(1L)

    suspend fun download(
        urls: List<String>,
        target: File,
        cookie: String?,
        concurrency: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val orderedUrls = rankCandidatesBySpeed(urls.distinct(), cookie)
        var lastError: Exception? = null
        for (url in orderedUrls) {
            try {
                downloadOne(url, target, cookie, concurrency, isCancelled, onProgress)
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
        onProgress: (Int) -> Unit
    ) {
        val probe = probeWithRetry(url, cookie)
        if (
            probe == null ||
            !DownloadTransportPolicy.supportsVerifiedRanges(probe.etag, probe.lastModified)
        ) {
            // Without a stable validator, parallel ranges could mix bytes from
            // different CDN representations. Use a clean sequential response.
            downloadSequentialWithRetry(url, target, cookie, isCancelled, onProgress)
            return
        }
        downloadRanges(url, target, cookie, probe, concurrency, isCancelled, onProgress)
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
            val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
                .find(contentRange) ?: throw IOException("CDN 未提供有效的 Content-Range")
            val start = match.groupValues[1].toLong()
            val end = match.groupValues[2].toLong()
            val total = match.groupValues[3].toLong()
            if (start != 0L || end != 0L || total <= 0L) {
                throw IOException("CDN 探测返回了不匹配的字节范围")
            }
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
        onProgress: (Int) -> Unit
    ) = coroutineScope {
        target.parentFile?.mkdirs()
        val mapFile = File(target.absolutePath + ".ranges")
        val metaFile = File(target.absolutePath + ".meta")
        val chunkCountLong = ((probe.totalBytes - 1L) / CHUNK_SIZE) + 1L
        if (chunkCountLong > Int.MAX_VALUE) {
            throw IOException("文件分段数量超过当前设备支持范围")
        }
        val chunkCount = chunkCountLong.toInt()
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

        var reusableCheckpoint = reusable
        val completed = if (reusableCheckpoint) {
            mapFile.readText().map { it == '1' }.toBooleanArray()
        } else {
            BooleanArray(chunkCount)
        }

        if (completed.size != chunkCount) {
            completed.fill(false)
            reusableCheckpoint = false
        }

        val additionalBytes = DownloadTransportPolicy.additionalBytesRequired(
            totalBytes = probe.totalBytes,
            chunkSize = CHUNK_SIZE,
            completed = completed
        )
        val availableBytes = target.parentFile?.usableSpace ?: 0L
        if (!DownloadTransportPolicy.hasEnoughSpace(availableBytes, additionalBytes)) {
            throw IOException(
                "存储空间不足：还需要约 " +
                    BiliDownloadEngine.formatSize(additionalBytes) +
                    "，请清理下载目录后重试"
            )
        }

        if (!reusableCheckpoint) {
            java.io.RandomAccessFile(target, "rw").use { it.setLength(probe.totalBytes) }
            metaFile.writeText(expectedMeta)
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
        var completedSinceCheckpoint = 0
        val resumedPercent = ((completedBytes.get() * 100L) / total)
            .toInt()
            .coerceIn(0, 99)
        onProgress(resumedPercent)

        ParcelFileDescriptor.open(
            target,
            ParcelFileDescriptor.MODE_READ_WRITE
        ).use { descriptor ->
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
                            fd = descriptor.fd,
                            start = start,
                            end = end,
                            isCancelled = isCancelled,
                            completedBytes = completedBytes,
                            inFlightBytes = inFlightBytes,
                            totalBytes = total,
                            onProgress = onProgress
                        )
                        synchronized(lock) {
                            completed[index] = true
                            completedSinceCheckpoint++
                            // Persist checkpoints in small batches to avoid syncing after
                            // every range. Uncheckpointed ranges are safely retried on resume.
                            if (completedSinceCheckpoint >= 4) {
                                if (!NativeTransfer.sync(descriptor.fd)) {
                                    throw IOException("分段写入同步失败")
                                }
                                mapFile.writeText(
                                    completed.joinToString("") { if (it) "1" else "0" }
                                )
                                completedSinceCheckpoint = 0
                            }
                        }
                    }
                }
            }.awaitAll()

            if (!NativeTransfer.sync(descriptor.fd)) {
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
        fd: Int,
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
                    url, cookie, probe, fd, start, end, isCancelled
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
        fd: Int,
        start: Long,
        end: Long,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val connection = openConnection(url, cookie).apply {
            setRequestProperty("Range", "bytes=$start-$end")
            val validator = DownloadTransportPolicy.ifRangeValidator(
                probe.etag,
                probe.lastModified
            )
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
            if (!DownloadTransportPolicy.responseMatchesValidator(
                    expectedEtag = probe.etag,
                    expectedLastModified = probe.lastModified,
                    responseEtag = connection.getHeaderField("ETag").orEmpty(),
                    responseLastModified = connection.getHeaderField("Last-Modified").orEmpty()
                )
            ) {
                throw IOException("CDN 文件版本在下载期间发生变化，已停止合并不同版本的数据")
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
                    val written = NativeTransfer.writeAt(fd, offset, block)
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
            // This request is intentionally a complete GET without Range. A 206
            // response is only a partial representation and must never be published
            // as a complete video, even when its Content-Length matches the body.
            if (status != HttpURLConnection.HTTP_OK) {
                throw BiliHttpException(status, "完整文件下载请求")
            }
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            if (total > 0L) {
                val availableBytes = target.parentFile?.usableSpace ?: 0L
                if (!DownloadTransportPolicy.hasEnoughSpace(availableBytes, total)) {
                    throw IOException(
                        "存储空间不足：该文件约 " + BiliDownloadEngine.formatSize(total)
                    )
                }
            }
            var copied = 0L
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
            if (total > 0L && copied != total) {
                throw RetryableDownloadException("下载长度不匹配：$copied / $total 字节")
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
            DownloadTransportPolicy.cookieHeader(cookie)?.let {
                setRequestProperty("Cookie", it)
            }
        }
    }
}
