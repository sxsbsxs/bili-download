package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class BiliFlvSegmentMergerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mergesTagStreamsAndOffsetsEachSegmentOnContinuousTimeline() {
        val first = File(temporaryFolder.root, "part-1.flv").apply {
            writeBytes(flv(
                flags = 1,
                tags = listOf(
                    tag(18, 0, metadata()),
                    tag(8, 0, byteArrayOf(0xaf.toByte(), 1, 0x11, 0x22)),
                    tag(9, 40, byteArrayOf(0x17, 1, 0, 0, 0, 0x01))
                )
            ))
        }
        val second = File(temporaryFolder.root, "part-2.flv").apply {
            writeBytes(flv(
                flags = 4,
                tags = listOf(
                    tag(18, 0, metadata()),
                    tag(8, 0, byteArrayOf(0xaf.toByte(), 1, 0x33, 0x44)),
                    tag(9, 40, byteArrayOf(0x17, 1, 0, 0, 0, 0x02))
                )
            ))
        }
        val output = File(temporaryFolder.root, "merged.flv")

        BiliFlvSegmentMerger.merge(
            listOf(
                BiliFlvSegmentInput(first, declaredDurationMs = 1_000L),
                BiliFlvSegmentInput(second, declaredDurationMs = 1_000L)
            ),
            output
        )

        val parsed = readTags(output)
        assertEquals(1, parsed.count { it.type == 18 })
        assertEquals(listOf(0L, 0L, 40L, 1_000L, 1_000L, 1_040L), parsed.map { it.timestamp })
        assertEquals(listOf(8, 9, 8, 9), parsed.filter { it.type != 18 }.map { it.type })
        RandomAccessFile(output, "r").use { input ->
            val header = ByteArray(9)
            input.readFully(header)
            assertEquals(5, header[4].toInt() and 0xff)
        }
    }

    @Test(expected = IOException::class)
    fun rejectsNonFlvInputInsteadOfConcatenatingGarbage() {
        val first = File(temporaryFolder.root, "not-flv-1.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val second = File(temporaryFolder.root, "not-flv-2.bin").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        BiliFlvSegmentMerger.merge(
            listOf(BiliFlvSegmentInput(first, 1_000L), BiliFlvSegmentInput(second, 1_000L)),
            File(temporaryFolder.root, "bad.flv")
        )
    }

    @Test
    fun rejectsBrokenPreviousTagSize() {
        val broken = flv(
            flags = 5,
            tags = listOf(tag(9, 0, byteArrayOf(0x17, 1, 0, 0, 0, 0x01)))
        ).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val first = File(temporaryFolder.root, "broken.flv").apply { writeBytes(broken) }
        val second = File(temporaryFolder.root, "valid.flv").apply {
            writeBytes(flv(5, listOf(tag(9, 0, byteArrayOf(0x17, 1, 0, 0, 0, 0x02)))))
        }

        try {
            BiliFlvSegmentMerger.merge(
                listOf(BiliFlvSegmentInput(first, 100L), BiliFlvSegmentInput(second, 100L)),
                File(temporaryFolder.root, "broken-output.flv")
            )
            throw AssertionError("Expected invalid FLV to be rejected")
        } catch (_: IOException) {
            assertTrue(true)
        }
    }

    private data class ParsedTag(val type: Int, val timestamp: Long)

    private fun readTags(file: File): List<ParsedTag> {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(9)
            input.readFully(header)
            val offset = readUInt32(header, 5).toInt()
            input.seek(offset.toLong())
            assertEquals(0, input.readInt())
            val tags = ArrayList<ParsedTag>()
            while (input.filePointer < input.length()) {
                val tagHeader = ByteArray(11)
                input.readFully(tagHeader)
                val size = readUInt24(tagHeader, 1)
                val timestamp = readUInt24(tagHeader, 4).toLong() or
                    ((tagHeader[7].toLong() and 0xffL) shl 24)
                tags += ParsedTag(tagHeader[0].toInt() and 0xff, timestamp)
                input.seek(input.filePointer + size)
                assertEquals(11L + size, input.readInt().toLong() and 0xffff_ffffL)
            }
            return tags
        }
    }

    private fun flv(flags: Int, tags: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, flags.toByte()))
        data.writeInt(9)
        data.writeInt(0)
        tags.forEach { data.write(it) }
        data.flush()
        return out.toByteArray()
    }

    private fun tag(type: Int, timestamp: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(type)
        writeUInt24(data, body.size)
        writeUInt24(data, timestamp and 0x00ff_ffff)
        data.writeByte((timestamp ushr 24) and 0xff)
        writeUInt24(data, 0)
        data.write(body)
        data.writeInt(11 + body.size)
        data.flush()
        return out.toByteArray()
    }

    private fun metadata(): ByteArray =
        byteArrayOf(2, 0, 10) + "onMetaData".toByteArray(Charsets.US_ASCII)

    private fun writeUInt24(output: DataOutputStream, value: Int) {
        output.writeByte((value ushr 16) and 0xff)
        output.writeByte((value ushr 8) and 0xff)
        output.writeByte(value and 0xff)
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
}
