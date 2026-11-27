package com.example.bilidl

/**
 * Small native I/O core owned by BiliDl.
 *
 * Network/TLS stays on Android's supported HTTP stack. Native code is responsible
 * for safe random-access writes, durable checkpoints and MP4 container validation.
 */
internal class NativeTransfer private constructor() {
    companion object {
        init {
            System.loadLibrary("bilidl_core")
        }

        @JvmStatic external fun writeAt(fd: Int, offset: Long, bytes: ByteArray): Int
        @JvmStatic external fun sync(fd: Int): Boolean
        @JvmStatic external fun validateMp4(path: String): Boolean
    }
}
