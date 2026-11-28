package com.example.bilidl

/**
 * Pure transport policies shared by the downloader and its unit tests.
 * Range requests are only parallelized when the origin supplies a validator
 * that can be sent back with If-Range.
 */
internal object DownloadTransportPolicy {
    private const val MIN_FREE_SPACE_BYTES = 2L * 1024L * 1024L

    private val cookieNames = setOf(
        "SESSDATA",
        "bili_jct",
        "DedeUserID",
        "DedeUserID__ckMd5",
        "buvid3",
        "buvid4",
        "sid",
        "b_nut",
        "buvid_fp",
        "buvid_fp_plain"
    )

    fun cookieHeader(rawCookie: String?): String? {
        val raw = rawCookie?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val tokens = raw.replace("\r", ";").replace("\n", ";")
            .split(';')
            .map(String::trim)
            .filter(String::isNotBlank)

        val values = linkedMapOf<String, String>()
        tokens.forEach { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) return@forEach
            val name = token.substring(0, separator).trim()
            val value = token.substring(separator + 1).trim()
            if (name in cookieNames && value.isNotBlank()) values[name] = value
        }

        if (values.isNotEmpty()) {
            return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        // Legacy storage contains only the SESSDATA value, not a Cookie header.
        // Do not turn an unknown multi-cookie header into a bogus SESSDATA value.
        if (!raw.contains(';') && !cookieNames.any { raw.startsWith("$it=") }) {
            return "SESSDATA=$raw"
        }
        return null
    }

    private fun isStrongEtag(etag: String): Boolean {
        val value = etag.trim()
        return value.length >= 2 &&
            value.first() == '"' &&
            value.last() == '"' &&
            !value.startsWith("W/", ignoreCase = true)
    }

    fun supportsVerifiedRanges(etag: String, lastModified: String): Boolean =
        isStrongEtag(etag) || lastModified.isNotBlank()

    fun ifRangeValidator(etag: String, lastModified: String): String =
        if (isStrongEtag(etag)) etag.trim() else lastModified.trim()

    fun responseMatchesValidator(
        expectedEtag: String,
        expectedLastModified: String,
        responseEtag: String,
        responseLastModified: String
    ): Boolean {
        val expected = ifRangeValidator(expectedEtag, expectedLastModified)
        if (expected.isBlank()) return false

        if (isStrongEtag(expectedEtag) &&
            responseEtag.isNotBlank() &&
            responseEtag.trim() != expectedEtag.trim()
        ) return false

        if (expectedLastModified.isNotBlank() &&
            responseLastModified.isNotBlank() &&
            responseLastModified.trim() != expectedLastModified.trim()
        ) return false

        return true
    }

    fun additionalBytesRequired(
        totalBytes: Long,
        chunkSize: Long,
        completed: BooleanArray
    ): Long {
        if (totalBytes <= 0L || chunkSize <= 0L) return 0L
        return completed.indices.sumOf { index ->
            if (completed[index]) {
                0L
            } else {
                val start = index.toLong() * chunkSize
                if (start >= totalBytes) 0L else minOf(chunkSize, totalBytes - start)
            }
        }
    }

    fun hasEnoughSpace(availableBytes: Long, additionalBytes: Long): Boolean {
        if (availableBytes < 0L || additionalBytes < 0L) return false
        val reserve = minOf(MIN_FREE_SPACE_BYTES, additionalBytes / 20L)
        return availableBytes >= additionalBytes + reserve
    }
}
