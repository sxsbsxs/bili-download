package com.example.bilidl

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

class FlvContainerValidatorTest {
    @Test
    fun acceptsACompleteVideoTag() {
        val file = temporaryFile(validVideoFlv())
        try {
            FlvContainerValidator.validate(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsInvalidSignature() {
        val bytes = validVideoFlv().also { it[0] = 'X'.code.toByte() }
        val file = temporaryFile(bytes)
        try {
            assertThrows(IOException::class.java) {
                FlvContainerValidator.validate(file)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsTruncatedTagPayload() {
        val bytes = validVideoFlv().copyOf(24)
        val file = temporaryFile(bytes)
        try {
            assertThrows(IOException::class.java) {
                FlvContainerValidator.validate(file)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsFilesWithoutMediaTags() {
        val bytes = byteArrayOf(
            0x46, 0x4c, 0x56, 0x01, 0x04, 0x00, 0x00, 0x00, 0x09,
            0x00, 0x00, 0x00, 0x00
        ).map { it.toByte() }.toByteArray()
        val file = temporaryFile(bytes)
        try {
            assertThrows(IOException::class.java) {
                FlvContainerValidator.validate(file)
            }
        } finally {
            file.delete()
        }
    }

    private fun temporaryFile(bytes: ByteArray): File =
        File.createTempFile("bilidl-flv-", ".flv").apply { writeBytes(bytes) }

    private fun validVideoFlv(): ByteArray {
        val bytes = byteArrayOf(
            0x46, 0x4c, 0x56, 0x01, 0x01, 0x00, 0x00, 0x00, 0x09, // header
            0x00, 0x00, 0x00, 0x00, // PreviousTagSize0
            0x09, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // video tag
            0x17, // one-byte video payload
            0x00, 0x00, 0x00, 0x0c // PreviousTagSize = 11 + 1
        )
        return bytes.map { it.toByte() }.toByteArray()
    }
}
