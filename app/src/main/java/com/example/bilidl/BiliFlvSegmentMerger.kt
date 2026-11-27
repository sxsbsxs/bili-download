package com.example.bilidl

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

internal data class BiliFlvSegmentInput(
    val file: File,
    val declaredDurationMs: Long
)

private data class FlvSegmentInfo(
    val dataOffset: Int,
    val firstTagTimestamp: Long,
    val lastTagTimestamp: Long,
    val flags: Int,
    val hasMediaTags: Boolean
)

/**
 * Joins legacy DURL FLV fragments at the FLV tag layer.
 *
 * It deliberately does not concatenate complete FLV files: each fragment has
 * its own FLV header and metadata, and its timestamps usually restart at zero.
 * Headers are emitted once and tag timestamps are rebased onto a continuous
 * timeline using the DURL duration when available.
 */
internal object BiliFlvSegmentMerger {
    private const val FLV_HEADER_SIZE = 9
    private const val MAX_HEADER_SIZE = 1024 * 1024
    private const val TAG_HEADER_SIZE = 11
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val MAX_TIMESTAMP = 0xffff_ffffL

    fun isValid(file: File): Boolean =
        runCatching { inspectSegment(file); true }.getOrDefault(false)

    fun merge(segments: List<BiliFlvSegmentInput>, output: File) {
        require(segments.isNotEmpty()) { "至少需要一个 FLV 分段" }
        require(segments.size > 1) { "单个 FLV 分段不需要合并" }
        require(segments.none { it.file.absoluteFile == output.absoluteFile }) {
            "输出文件不能与输入分段相同"
        }

        val normalized = segments.map { segment ->
            if (!segment.file.isFile || segment.file.length() <= 0L) {
                throw IOException("FLV 分段不存在或为空：${segment.file.name}")
            }
            segment.copy(declaredDurationMs = segment.declaredDurationMs.coerceAtLeast(0L))
        }

        val infos = normalized.map { inspectSegment(it.file) }
        val expectedFlags = infos.fold(0) { flags, info -> flags or info.flags }
        val header = readOutputHeader(normalized.first().file, expectedFlags)

        output.parentFile?.mkdirs()
        output.delete()
        try {
            RandomAccessFile(output, "rw").use { destination ->
                destination.write(header)
                destination.writeInt(0) // PreviousTagSize0 follows the FLV header.

                var timelineStart = 0L
                normalized.forEachIndexed { index, segment ->
                    val info = infos[index]
                    val observedSpan = (info.lastTagTimestamp - info.firstTagTimestamp)
                        .coerceAtLeast(0L)
                    val duration = maxOf(segment.declaredDurationMs, observedSpan, 1L)
                    val nextTimeline = safeAdd(timelineStart, duration)
                    writeSegmentTags(
                        source = segment.file,
                        destination = destination,
                        info = info,
                        segmentIndex = index,
                        timelineStart = timelineStart
                    )
                    timelineStart = nextTimeline
                }
                if (destination.length() <= header.size + 4L) {
                    throw IOException("FLV 合并结果没有媒体数据")
                }
                destination.fd.sync()
            }
            // Re-open the finished artifact and validate the exact bytes that
            // will be published, not only the source fragments.
            inspectSegment(output)
        } catch (error: Exception) {
            output.delete()
            if (error is IOException) throw error
            throw IOException("旧式 FLV 分段合并失败：" + (error.message ?: "未知错误"), error)
        }
    }

    private fun inspectSegment(file: File): FlvSegmentInfo {
        RandomAccessFile(file, "r").use { input ->
            val fixedHeader = ByteArray(FLV_HEADER_SIZE)
            readFullyOrThrow(input, fixedHeader, "FLV 文件头不完整")
            if (
                fixedHeader[0] != 'F'.code.toByte() ||
                fixedHeader[1] != 'L'.code.toByte() ||
                fixedHeader[2] != 'V'.code.toByte()
            ) {
                throw IOException("分段不是有效的 FLV 文件：${file.name}")
            }
            if (fixedHeader[3].toInt() != 1) {
                throw IOException("不支持的 FLV 版本：${file.name}")
            }

            val dataOffset = readUInt32(fixedHeader, 5).toInt()
            if (dataOffset < FLV_HEADER_SIZE || dataOffset > MAX_HEADER_SIZE ||
                dataOffset.toLong() + 4L > input.length()
            ) {
                throw IOException("FLV 文件头长度无效：${file.name}")
            }
            input.seek(dataOffset.toLong())
            if (input.readInt() != 0) {
                throw IOException("FLV PreviousTagSize0 无效：${file.name}")
            }

            var firstTimestamp = -1L
            var lastTimestamp = 0L
            var hasMediaTags = false
            while (input.filePointer < input.length()) {
                if (input.length() - input.filePointer < TAG_HEADER_SIZE + 4L) {
                    throw IOException("FLV 标签头被截断：${file.name}")
                }
                val tagHeader = ByteArray(TAG_HEADER_SIZE)
                input.readFully(tagHeader)
                val dataSize = readUInt24(tagHeader, 1)
                val timestamp = readTimestamp(tagHeader)
                val tagType = tagHeader[0].toInt() and 0xff
                if (readUInt24(tagHeader, 8) != 0) {
                    throw IOException("FLV 标签 StreamID 非零：${file.name}")
                }
                if (dataSize.toLong() + 4L > input.length() - input.filePointer) {
                    throw IOException("FLV 标签数据长度越界：${file.name}")
                }
                if (firstTimestamp < 0L) firstTimestamp = timestamp
                lastTimestamp = maxOf(lastTimestamp, timestamp)
                if (tagType == 8 || tagType == 9) hasMediaTags = true

                input.seek(input.filePointer + dataSize)
                val previousTagSize = readUInt32(input.readInt())
                if (previousTagSize != TAG_HEADER_SIZE.toLong() + dataSize) {
                    throw IOException("FLV PreviousTagSize 与标签长度不匹配：${file.name}")
                }
            }

            if (firstTimestamp < 0L || !hasMediaTags) {
                throw IOException("FLV 分段没有音视频标签：${file.name}")
            }
            return FlvSegmentInfo(
                dataOffset = dataOffset,
                firstTagTimestamp = firstTimestamp,
                lastTagTimestamp = lastTimestamp,
                flags = fixedHeader[4].toInt() and 0xff,
                hasMediaTags = hasMediaTags
            )
        }
    }

    private fun readOutputHeader(file: File, combinedFlags: Int): ByteArray {
        RandomAccessFile(file, "r").use { input ->
            val fixed = ByteArray(FLV_HEADER_SIZE)
            readFullyOrThrow(input, fixed, "FLV 文件头不完整")
            val dataOffset = readUInt32(fixed, 5).toInt()
            if (dataOffset < FLV_HEADER_SIZE || dataOffset > MAX_HEADER_SIZE) {
                throw IOException("FLV 文件头长度无效")
            }
            val header = ByteArray(dataOffset)
            fixed.copyInto(header)
            if (dataOffset > FLV_HEADER_SIZE) {
                input.readFully(header, FLV_HEADER_SIZE, dataOffset - FLV_HEADER_SIZE)
            }
            header[4] = combinedFlags.toByte()
            return header
        }
    }

    private fun writeSegmentTags(
        source: File,
        destination: RandomAccessFile,
        info: FlvSegmentInfo,
        segmentIndex: Int,
        timelineStart: Long
    ) {
        RandomAccessFile(source, "r").use { input ->
            input.seek(info.dataOffset.toLong() + 4L) // Skip PreviousTagSize0.
            while (input.filePointer < input.length()) {
                val tagHeader = ByteArray(TAG_HEADER_SIZE)
                input.readFully(tagHeader)
                val dataSize = readUInt24(tagHeader, 1)
                val originalTimestamp = readTimestamp(tagHeader)
                val tagType = tagHeader[0].toInt() and 0xff
                val bodyPosition = input.filePointer

                val skipDuplicateMetadata = segmentIndex > 0 &&
                    tagType == 18 && isOnMetaData(input, bodyPosition, dataSize)
                val adjustedTimestamp = originalTimestamp - info.firstTagTimestamp + timelineStart
                if (adjustedTimestamp !in 0L..MAX_TIMESTAMP) {
                    throw IOException("FLV 合并后时间戳超出格式范围")
                }

                if (skipDuplicateMetadata) {
                    input.seek(bodyPosition + dataSize + 4L)
                    continue
                }

                writeTimestamp(tagHeader, adjustedTimestamp)
                destination.write(tagHeader)
                copyExactly(input, destination, dataSize.toLong())
                val previousTagSize = input.readInt()
                destination.writeInt(previousTagSize)
            }
        }
    }

    private fun isOnMetaData(input: RandomAccessFile, bodyPosition: Long, dataSize: Int): Boolean {
        if (dataSize < 13) return false
        val probeSize = minOf(dataSize, 32)
        val prefix = ByteArray(probeSize)
        input.seek(bodyPosition)
        input.readFully(prefix)
        input.seek(bodyPosition)
        // AMF0 string marker (2), uint16 string length (10), "onMetaData".
        if (prefix[0].toInt() != 2) return false
        val length = ((prefix[1].toInt() and 0xff) shl 8) or (prefix[2].toInt() and 0xff)
        if (length != 10 || prefix.size < 13) return false
        return String(prefix, 3, 10, Charsets.US_ASCII) == "onMetaData"
    }

    private fun copyExactly(
        source: RandomAccessFile,
        destination: RandomAccessFile,
        byteCount: Long
    ) {
        var remaining = byteCount
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (remaining > 0L) {
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            source.readFully(buffer, 0, count)
            destination.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun readFullyOrThrow(input: RandomAccessFile, bytes: ByteArray, message: String) {
        try {
            input.readFully(bytes)
        } catch (error: java.io.EOFException) {
            throw IOException(message, error)
        }
    }

    private fun readTimestamp(header: ByteArray): Long {
        val low = readUInt24(header, 4)
        val high = header[7].toLong() and 0xffL
        return low.toLong() or (high shl 24)
    }

    private fun writeTimestamp(header: ByteArray, timestamp: Long) {
        header[4] = (timestamp ushr 16).toByte()
        header[5] = (timestamp ushr 8).toByte()
        header[6] = timestamp.toByte()
        header[7] = (timestamp ushr 24).toByte()
    }

    private fun readUInt24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 16) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            (bytes[offset + 2].toInt() and 0xff)

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun readUInt32(value: Int): Long = value.toLong() and 0xffff_ffffL

    private fun safeAdd(left: Long, right: Long): Long {
        if (left > Long.MAX_VALUE - right) throw IOException("FLV 分段总时长溢出")
        return left + right
    }
}
