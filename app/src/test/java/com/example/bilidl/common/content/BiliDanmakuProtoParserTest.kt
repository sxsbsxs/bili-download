package com.example.bilidl.common.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliDanmakuProtoParserTest {
    @Test
    fun parsesDanmakuFieldsAndSkipsUnknownFields() {
        val element = byteArrayOf()
            .plus(varintField(1, 938475L))
            .plus(varintField(2, 12_345L))
            .plus(varintField(3, 1L))
            .plus(varintField(4, 25L))
            .plus(varintField(5, 0xff00aaL))
            .plus(stringField(6, "sender-hash"))
            .plus(stringField(7, "你好，BiliDl"))
            .plus(varintField(8, 1_794_000_000L))
            .plus(varintField(9, 10L))
            .plus(varintField(11, 0L))
            .plus(varintField(20, 777L))
        val response = messageField(1, element)

        val result = BiliDanmakuProtoParser.parse(response)

        assertEquals(1, result.size)
        val comment = result.single()
        assertEquals(938475L, comment.id)
        assertEquals(12_345L, comment.progressMs)
        assertEquals(1, comment.mode)
        assertEquals(25, comment.fontSize)
        assertEquals(0xff00aaL, comment.color)
        assertEquals("sender-hash", comment.senderHash)
        assertEquals("你好，BiliDl", comment.content)
        assertEquals(1_794_000_000L, comment.sentAtEpochSeconds)
        assertEquals(0, comment.pool)
    }

    @Test
    fun ignoresEmptyAndInvalidProgressElements() {
        val emptyComment = stringField(7, "")
        val missingProgress = stringField(7, "content")
        val response = messageField(1, emptyComment) + messageField(1, missingProgress)

        assertTrue(BiliDanmakuProtoParser.parse(response).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedLengthDelimitedField() {
        BiliDanmakuProtoParser.parse(byteArrayOf(0x0a, 0x05, 0x01))
    }

    private fun varintField(field: Int, value: Long): ByteArray =
        encodeVarint((field shl 3).toLong()) + encodeVarint(value)

    private fun stringField(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return encodeVarint(((field shl 3) or 2).toLong()) +
            encodeVarint(bytes.size.toLong()) + bytes
    }

    private fun messageField(field: Int, value: ByteArray): ByteArray =
        encodeVarint(((field shl 3) or 2).toLong()) +
            encodeVarint(value.size.toLong()) + value

    private fun encodeVarint(input: Long): ByteArray {
        var value = input
        val output = ArrayList<Byte>()
        while ((value and -128L) != 0L) {
            output += ((value and 0x7fL) or 0x80L).toByte()
            value = value ushr 7
        }
        output += value.toByte()
        return output.toByteArray()
    }
}
