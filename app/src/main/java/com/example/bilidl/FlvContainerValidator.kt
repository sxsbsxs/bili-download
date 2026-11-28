package com.example.bilidl

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Lightweight structural validation for the legacy FLV direct-download path.
 * It walks FLV tag boundaries and PreviousTagSize fields without loading the
 * media payload into memory.
 */
internal object FlvContainerValidator {
    private const val HEADER_SIZE = 9L
    private const val FIRST_TAG_SIZE = 11L
    private const val PREVIOUS_TAG_SIZE_BYTES = 4L

    fun validate(file: File) {
        if (!file.isFile || file.length() < HEADER_SIZE + PREVIOUS_TAG_SIZE_BYTES) {
            throw IOException("FLV 文件太短或不存在")
        }

        RandomAccessFile(file, "r").use { input ->
            val signature = ByteArray(3)
            input.readFully(signature)
            if (!signature.contentEquals(byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte()))) {
                throw IOException("FLV 文件头签名无效")
            }

            val version = input.readUnsignedByte()
            if (version != 1) throw IOException("不支持的 FLV 版本：$version")
            val flags = input.readUnsignedByte()
            if ((flags and 0x05) == 0) throw IOException("FLV 未声明音频或视频轨道")

            val dataOffset = input.readUnsignedInt()
            val length = input.length()
            if (dataOffset < HEADER_SIZE || dataOffset > length - PREVIOUS_TAG_SIZE_BYTES) {
                throw IOException("FLV DataOffset 超出文件范围")
            }

            input.seek(dataOffset)
            if (input.readUnsignedInt() != 0L) {
                throw IOException("FLV PreviousTagSize0 必须为 0")
            }

            var mediaTagCount = 0
            while (input.filePointer < length) {
                val tagStart = input.filePointer
                if (length - tagStart < FIRST_TAG_SIZE + PREVIOUS_TAG_SIZE_BYTES) {
                    throw IOException("FLV 标签头不完整")
                }

                val tagType = input.readUnsignedByte()
                if (tagType != 8 && tagType != 9 && tagType != 18) {
                    throw IOException("FLV 包含未知标签类型：$tagType")
                }
                val dataSize = input.readUnsignedInt24()
                // Timestamp (3), TimestampExtended (1), StreamID (3).
                input.skipBytes(7)

                val payloadStart = input.filePointer
                val previousSizePosition = payloadStart + dataSize
                if (previousSizePosition < payloadStart ||
                    previousSizePosition > length - PREVIOUS_TAG_SIZE_BYTES
                ) {
                    throw IOException("FLV 标签数据长度超出文件范围")
                }

                input.seek(previousSizePosition)
                val expectedPreviousSize = FIRST_TAG_SIZE + dataSize
                if (input.readUnsignedInt() != expectedPreviousSize) {
                    throw IOException("FLV PreviousTagSize 与标签长度不匹配")
                }

                if ((tagType == 8 || tagType == 9) && dataSize > 0L) {
                    mediaTagCount++
                }
            }

            if (mediaTagCount == 0) {
                throw IOException("FLV 中没有可用的音频或视频数据")
            }
        }
    }

    private fun RandomAccessFile.readUnsignedInt(): Long {
        return (readUnsignedByte().toLong() shl 24) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 8) or
            readUnsignedByte().toLong()
    }

    private fun RandomAccessFile.readUnsignedInt24(): Long {
        return (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 8) or
            readUnsignedByte().toLong()
    }
}
