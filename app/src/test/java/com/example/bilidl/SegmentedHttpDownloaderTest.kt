package com.example.bilidl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SegmentedHttpDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retriesTruncatedRangeAndProducesExactFile() = runBlocking {
        val bytes = payload(CHUNK_SIZE + 32_123)
        LocalRangeServer(
            payload = bytes,
            truncateFirstDataRange = "bytes=0-${CHUNK_SIZE - 1}"
        ).use { server ->
            val target = File(temporaryFolder.root, "retry.bin")
            SegmentedHttpDownloader.download(
                urls = listOf(server.url),
                target = target,
                cookie = null,
                concurrency = 2,
                isCancelled = { false },
                onProgress = {},
                writerFactory = TestFileWriterFactory
            )

            assertArrayEquals(bytes, target.readBytes())
            assertTrue(server.ranges.count { it == "bytes=0-${CHUNK_SIZE - 1}" } >= 2)
            assertFalse(File(target.absolutePath + ".ranges").exists())
            assertFalse(File(target.absolutePath + ".meta").exists())
        }
    }

    @Test
    fun resumesOnlyChunksWhoseValidatorStillMatches() = runBlocking {
        val bytes = payload(CHUNK_SIZE + 9_777)
        LocalRangeServer(payload = bytes, etag = "\"stable\"").use { server ->
            val target = seedFirstChunk(
                "resume.bin", server.url, bytes, etag = "\"stable\""
            )
            SegmentedHttpDownloader.download(
                urls = listOf(server.url),
                target = target,
                cookie = null,
                concurrency = 2,
                isCancelled = { false },
                onProgress = {},
                writerFactory = TestFileWriterFactory
            )

            assertArrayEquals(bytes, target.readBytes())
            assertEquals(1, server.ranges.count { it == "bytes=0-${CHUNK_SIZE - 1}" })
            assertTrue(server.ranges.contains("bytes=$CHUNK_SIZE-${bytes.lastIndex}"))
        }
    }

    @Test
    fun changedEtagInvalidatesOldCheckpointAndRedownloadsEveryChunk() = runBlocking {
        val bytes = payload(CHUNK_SIZE + 4_321)
        LocalRangeServer(payload = bytes, etag = "\"new-representation\"").use { server ->
            val target = seedFirstChunk(
                "changed-etag.bin", server.url, bytes, etag = "\"old-representation\""
            )
            SegmentedHttpDownloader.download(
                urls = listOf(server.url),
                target = target,
                cookie = null,
                concurrency = 2,
                isCancelled = { false },
                onProgress = {},
                writerFactory = TestFileWriterFactory
            )

            assertArrayEquals(bytes, target.readBytes())
            assertTrue(server.ranges.contains("bytes=0-${CHUNK_SIZE - 1}"))
        }
    }

    @Test
    fun fallsBackToSequentialWhenServerIgnoresRangeHeader() = runBlocking {
        val bytes = payload(192 * 1024)
        LocalRangeServer(payload = bytes, ignoreRanges = true).use { server ->
            val target = File(temporaryFolder.root, "sequential.bin")
            SegmentedHttpDownloader.download(
                urls = listOf(server.url),
                target = target,
                cookie = null,
                concurrency = 4,
                isCancelled = { false },
                onProgress = {},
                writerFactory = TestFileWriterFactory
            )

            assertArrayEquals(bytes, target.readBytes())
            assertTrue(server.ranges.contains("bytes=0-${CHUNK_SIZE - 1}"))
            assertTrue(server.ranges.any { it.isEmpty() })
        }
    }

    @Test
    fun usesAChunkSizedBoundedProbeWhenCdnRejectsSingleByteProbe() = runBlocking {
        val bytes = payload(CHUNK_SIZE + 12_345)
        LocalRangeServer(payload = bytes, rejectSingleByteProbe = true).use { server ->
            val target = File(temporaryFolder.root, "bounded-probe.bin")
            SegmentedHttpDownloader.download(
                urls = listOf(server.url),
                target = target,
                cookie = null,
                concurrency = 2,
                isCancelled = { false },
                onProgress = {},
                writerFactory = TestFileWriterFactory
            )

            assertArrayEquals(bytes, target.readBytes())
            assertFalse(server.ranges.contains("bytes=0-0"))
            assertTrue(server.ranges.contains("bytes=0-${CHUNK_SIZE - 1}"))
        }
    }

    @Test
    fun sendsTheActualVideoPageAsRefererToCdnRequests() = runBlocking {
        val bytes = payload(160 * 1024)
        val referer = "https://www.bilibili.com/video/BV1xx411c7mD?p=2"
        LocalRangeServer(payload = bytes).use { server ->
            val target = File(temporaryFolder.root, "referer.bin")
            SegmentedHttpDownloader.download(
                urls = listOf(server.url),
                target = target,
                cookie = null,
                concurrency = 2,
                isCancelled = { false },
                onProgress = {},
                writerFactory = TestFileWriterFactory,
                referer = referer
            )

            assertArrayEquals(bytes, target.readBytes())
            assertTrue(server.referers.isNotEmpty())
            assertTrue(server.referers.all { it == referer })
        }
    }

    @Test
    fun switchesToBackupCdnAfterNonRetryablePrimaryFailure() = runBlocking {
        val bytes = payload(128 * 1024)
        LocalRangeServer(payload = bytes, statusCode = 403).use { rejected ->
            LocalRangeServer(payload = bytes).use { backup ->
                val target = File(temporaryFolder.root, "backup.bin")
                SegmentedHttpDownloader.download(
                    urls = listOf(rejected.url, backup.url),
                    target = target,
                    cookie = null,
                    concurrency = 2,
                    isCancelled = { false },
                    onProgress = {},
                    writerFactory = TestFileWriterFactory
                )

                assertArrayEquals(bytes, target.readBytes())
                assertTrue(rejected.requests.isNotEmpty())
                assertTrue(backup.requests.isNotEmpty())
            }
        }
    }

    private fun seedFirstChunk(
        name: String,
        url: String,
        payload: ByteArray,
        etag: String
    ): File {
        val target = File(temporaryFolder.root, name)
        RandomAccessFile(target, "rw").use { file ->
            file.setLength(payload.size.toLong())
            file.write(payload, 0, CHUNK_SIZE)
        }
        val parsedUrl = URL(url)
        val resourceKey = parsedUrl.host + parsedUrl.path
        File(target.absolutePath + ".meta").writeText(
            listOf(
                payload.size.toString(),
                etag,
                "",
                resourceKey,
                CHUNK_SIZE.toString()
            ).joinToString("\n")
        )
        File(target.absolutePath + ".ranges").writeText("10")
        return target
    }

    private fun payload(size: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + index / 97) % 251).toByte() }

    private companion object {
        const val CHUNK_SIZE = 4 * 1024 * 1024
    }
}

private object TestFileWriterFactory : PositionalFileWriterFactory {
    override fun open(target: File): PositionalFileWriter {
        val channel = RandomAccessFile(target, "rw").channel
        return object : PositionalFileWriter {
            override fun writeAt(offset: Long, bytes: ByteArray): Int {
                val buffer = ByteBuffer.wrap(bytes)
                var position = offset
                while (buffer.hasRemaining()) {
                    position += channel.write(buffer, position)
                }
                return bytes.size
            }

            override fun sync(): Boolean {
                channel.force(true)
                return true
            }

            override fun close() = channel.close()
        }
    }
}

/**
 * Tiny local HTTP server used to exercise the real HttpURLConnection range
 * path without touching Bilibili's CDN or requiring native-library loading.
 */
private class LocalRangeServer(
    private val payload: ByteArray,
    private val etag: String = "\"test-etag\"",
    private val ignoreRanges: Boolean = false,
    private val statusCode: Int? = null,
    private val truncateFirstDataRange: String? = null,
    private val rejectSingleByteProbe: Boolean = false
) : Closeable {
    private val server = ServerSocket(0)
    private val running = AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool()
    private val acceptThread = thread(isDaemon = true, name = "bili-test-http-accept") {
        while (running.get()) {
            try {
                val socket = server.accept()
                executor.execute { handle(socket) }
            } catch (error: IOException) {
                if (running.get()) throw error
            }
        }
    }

    val url: String = "http://127.0.0.1:${server.localPort}/media"
    val ranges = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<String>()
    val referers = CopyOnWriteArrayList<String>()
    private val truncated = AtomicBoolean(false)

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) {
                    headers[line.substring(0, split).trim().lowercase()] =
                        line.substring(split + 1).trim()
                }
            }

            val range = headers["range"].orEmpty()
            ranges += range
            requests += requestLine
            referers += headers["referer"].orEmpty()
            val output = client.getOutputStream()
            if (statusCode != null) {
                val body = "test error".toByteArray()
                output.write(
                    ("HTTP/1.1 $statusCode Test Error\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                output.write(body)
                output.flush()
                return
            }

            if (rejectSingleByteProbe && range == "bytes=0-0") {
                val body = "tiny probe ranges are not supported".toByteArray()
                output.write(
                    ("HTTP/1.1 403 Forbidden\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                output.write(body)
                output.flush()
                return
            }

            val match = Regex("bytes=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).matchEntire(range)
            val useRange = !ignoreRanges && match != null
            val start = if (useRange) match!!.groupValues[1].toInt() else 0
            val requestedEnd = if (useRange) match!!.groupValues[2].toInt() else payload.lastIndex
            if (start !in payload.indices || requestedEnd < start) {
                output.write(
                    "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                output.flush()
                return
            }
            // Servers may clamp a requested end beyond EOF to the final byte.
            val end = minOf(requestedEnd, payload.lastIndex)

            val body = payload.copyOfRange(start, end + 1)
            val code = if (useRange) 206 else 200
            val response = buildString {
                append("HTTP/1.1 $code ")
                append(if (useRange) "Partial Content" else "OK")
                append("\r\nContent-Length: ${body.size}\r\n")
                append("ETag: $etag\r\n")
                if (useRange) append("Content-Range: bytes $start-$end/${payload.size}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(response.toByteArray(Charsets.ISO_8859_1))
            output.flush()

            val shouldTruncate = truncateFirstDataRange != null &&
                range == truncateFirstDataRange &&
                ranges.count { it == range } > 1 &&
                truncated.compareAndSet(false, true)
            if (shouldTruncate) {
                output.write(body, 0, minOf(body.size, 64 * 1024))
                output.flush()
                client.shutdownOutput()
            } else {
                output.write(body)
                output.flush()
            }
        }
    }

    override fun close() {
        running.set(false)
        server.close()
        executor.shutdownNow()
        acceptThread.join(1_000)
    }
}