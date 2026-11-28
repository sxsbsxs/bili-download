package com.example.bilidl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * BVD-style source ranking: consume at most 64 KiB from each candidate,
 * sort reachable sources by sample throughput, and keep failed sources
 * behind the originals as a final fallback.
 */
internal object BiliDownloadSourceSelector {
    private const val SAMPLE_BYTES = 64 * 1024
    private const val TIMEOUT_MS = 3_000

    suspend fun orderCandidates(
        urls: List<String>,
        mode: BiliDownloadSourceMode,
        customHost: String,
        sessionCookie: String?
    ): List<String> {
        val originals = urls.filter(::isHttpUrl).distinct()
        if (originals.isEmpty()) return emptyList()

        return when (mode) {
            BiliDownloadSourceMode.DEFAULT -> originals
            BiliDownloadSourceMode.AUTO_PROBE -> rank(originals, emptyList(), sessionCookie)
            BiliDownloadSourceMode.CUSTOM_HOST -> {
                val custom = BiliDownloadSourcePolicy.customHostCandidates(originals, customHost)
                if (custom.isEmpty()) originals else rank(custom, originals, sessionCookie)
            }
        }
    }

    private suspend fun rank(
        urls: List<String>,
        fallback: List<String>,
        sessionCookie: String?
    ): List<String> = coroutineScope {
        val results = urls.distinct().map { url ->
            async(Dispatchers.IO) { probe(url, sessionCookie) }
        }.awaitAll()
        val available = results.filter { it.bytes > 0 }
            .sortedByDescending { it.bytes.toDouble() / it.elapsedNanos.coerceAtLeast(1L) }
            .map { it.url }
        (available + fallback + urls).distinct()
    }

    private suspend fun probe(url: String, sessionCookie: String?): Sample {
        val startedAt = System.nanoTime()
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                BiliCdnRequestPolicy.configure(this, sessionCookie)
                setRequestProperty("Range", "bytes=0-65535")
            }
        }.getOrNull() ?: return Sample(url, 0, elapsedSince(startedAt))

        var received = 0
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                return Sample(url, 0, elapsedSince(startedAt))
            }
            val expected = connection.contentLengthLong
                .takeIf { it >= 0L }
                ?.coerceAtMost(SAMPLE_BYTES.toLong())
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (received < SAMPLE_BYTES) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, SAMPLE_BYTES - received))
                    if (count < 0) break
                    received += count
                }
            }
            if (expected != null && received.toLong() != expected) received = 0
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            received = 0
        } catch (_: Exception) {
            received = 0
        } finally {
            connection.disconnect()
        }
        return Sample(url, received, elapsedSince(startedAt))
    }

    private fun elapsedSince(startedAt: Long): Long =
        (System.nanoTime() - startedAt).coerceAtLeast(1L)

    private fun isHttpUrl(value: String): Boolean =
        runCatching {
            val parsed = URL(value)
            parsed.protocol in setOf("http", "https") && parsed.host.isNotBlank()
        }.getOrDefault(false)

    private data class Sample(val url: String, val bytes: Int, val elapsedNanos: Long)
}
