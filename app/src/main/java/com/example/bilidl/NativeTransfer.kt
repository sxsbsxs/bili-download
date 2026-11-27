package com.example.bilidl

/**
 * BiliDl's native core owns random-access writes, durable sync and structural
 * MP4 inspection. Network/TLS continues to use Android's supported HTTP stack.
 */
internal class NativeTransfer private constructor() {
    companion object {
        init { System.loadLibrary("bilidl_core") }
        @JvmStatic external fun writeAt(fd: Int, offset: Long, bytes: ByteArray): Int
        @JvmStatic external fun sync(fd: Int): Boolean
        @JvmStatic external fun validateMp4(path: String): Boolean
        @JvmStatic external fun inspectMp4(path: String): String
    }
}
