package com.example.bilidl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class BiliDownloadSourceSelectorTest {
    @Test
    fun autoProbeRanksReadableSourcesAndKeepsRejectedSourceAsFallback() = runBlocking {
        ProbeServer(delayMillis = 180).use { slow ->
            ProbeServer(delayMillis = 5).use { fast ->
                ProbeServer(statusCode = 403).use { rejected ->
                    val ordered = BiliDownloadSourceSelector.orderCandidates(
                        urls = listOf(slow.url, fast.url, rejected.url),
                        mode = BiliDownloadSourceMode.AUTO_PROBE,
                        customHost = "",
                        sessionCookie = "test-session"
                    )

                    assertEquals(listOf(fast.url, slow.url, rejected.url), ordered)
                    assertEquals("bytes=0-65535", fast.ranges.single())
                    assertTrue(fast.userAgents.single().contains("Edg/124.0.0.0"))
                    assertEquals(BiliCdnRequestPolicy.MOBILE_ORIGIN, fast.origins.single())
                    assertEquals(BiliCdnRequestPolicy.MOBILE_REFERER, fast.referers.single())
                    assertEquals("SESSDATA=test-session", fast.cookies.single())
                }
            }
        }
    }
}

private class ProbeServer(
    private val statusCode: Int = 206,
    private val delayMillis: Long = 0L
) : Closeable {
    private val server = ServerSocket(0)
    private val running = java.util.concurrent.atomic.AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool()
    private val acceptThread = thread(isDaemon = true, name = "bili-source-probe-test") {
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
    val userAgents = CopyOnWriteArrayList<String>()
    val origins = CopyOnWriteArrayList<String>()
    val referers = CopyOnWriteArrayList<String>()
    val cookies = CopyOnWriteArrayList<String>()

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            if (reader.readLine() == null) return
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
            ranges += headers["range"].orEmpty()
            userAgents += headers["user-agent"].orEmpty()
            origins += headers["origin"].orEmpty()
            referers += headers["referer"].orEmpty()
            cookies += headers["cookie"].orEmpty()

            if (delayMillis > 0L) Thread.sleep(delayMillis)
            val output = client.getOutputStream()
            if (statusCode != 206) {
                output.write(
                    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                output.flush()
                return
            }

            val body = ByteArray(64 * 1024) { (it % 251).toByte() }
            val response = (
                "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Content-Range: bytes 0-65535/65536\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1)
            output.write(response)
            output.write(body)
            output.flush()
        }
    }

    override fun close() {
        running.set(false)
        server.close()
        executor.shutdownNow()
        acceptThread.join(1_000)
    }
}
