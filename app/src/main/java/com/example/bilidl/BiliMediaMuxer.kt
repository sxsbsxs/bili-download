package com.example.bilidl

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException

/**
 * Owns container packaging after the Bilibili DASH streams have been downloaded.
 *
 * Extraction and transfer stay in BiliDownloadEngine/SegmentedHttpDownloader;
 * this component is only responsible for sample extraction, timestamp ordering,
 * Android MediaMuxer output and native structural validation.
 */
internal object BiliMediaMuxer {
    fun muxToMp4(videoFile: File, audioFile: File?, output: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = if (audioFile != null) MediaExtractor() else null
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
                ?: throw IOException("视频流格式无法由当前 Android 设备读取")
            var audioTrack = -1
            if (audioExtractor != null && audioFile != null) {
                audioExtractor.setDataSource(audioFile.absolutePath)
                audioTrack = findTrack(audioExtractor, "audio/")
                    ?: throw IOException("音频流格式无法由当前 Android 设备读取")
            }

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio = if (audioTrack >= 0 && audioExtractor != null) {
                muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            } else {
                -1
            }
            muxer.start()
            val videoBuffer = sampleBuffer(videoExtractor.getTrackFormat(videoTrack))
            val audioBuffer = if (audioTrack >= 0 && audioExtractor != null) {
                sampleBuffer(audioExtractor.getTrackFormat(audioTrack))
            } else {
                null
            }
            writeInterleavedSamples(
                videoExtractor = videoExtractor,
                videoBuffer = videoBuffer,
                videoOutputTrack = outVideo,
                audioExtractor = audioExtractor?.takeIf { audioTrack >= 0 },
                audioBuffer = audioBuffer,
                audioOutputTrack = outAudio,
                muxer = muxer
            )
            muxer.stop()
            inspectMp4OrThrow(output, "MP4")
        } catch (error: Exception) {
            output.delete()
            if (error is IOException) throw error
            throw IOException("音视频合并失败：" + (error.message ?: "当前设备可能不支持该编码"))
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Package the AAC DASH track into a standalone M4A container. The downloaded
     * .m4s is only a transport fragment; it must not be published by renaming it.
     */
    fun muxAudioToM4a(audioFile: File, output: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            val audioTrack = findTrack(extractor, "audio/")
                ?: throw IOException("下载的音频流中没有可读取的音轨")
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime != "audio/mp4a-latm") {
                throw IOException("当前 B站返回的音频编码为 $mime，暂不支持无损封装为 M4A")
            }

            output.parentFile?.mkdirs()
            output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()

            val buffer = sampleBuffer(format)
            var sample = readNextSample(extractor, buffer)
            while (sample != null) {
                muxer.writeSampleData(outputTrack, buffer, sample)
                extractor.advance()
                sample = readNextSample(extractor, buffer)
            }
            muxer.stop()

            inspectMp4OrThrow(output, "M4A")
            if (output.length() <= 0L) throw IOException("生成的 M4A 文件为空")
        } catch (error: Exception) {
            output.delete()
            if (error is IOException) throw error
            throw IOException("M4A 封装失败：" + (error.message ?: "设备不支持此音频格式"))
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * Verify the actual sample tables and media-data references before publishing.
     * A plausible ftyp/moov/mdat header is not enough to prove a playable file.
     */
    private fun inspectMp4OrThrow(file: File, formatLabel: String) {
        val report = NativeTransfer.inspectMp4(file.absolutePath)
        if (!report.startsWith("OK:")) {
            throw IOException("$formatLabel 容器结构校验失败：$report")
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) {
                extractor.selectTrack(index)
                return index
            }
        }
        return null
    }

    /**
     * Keep a sample buffer large enough for the selected codec's declared input size.
     * Some high-bitrate keyframes are larger than a small fixed 4 MiB buffer.
     */
    private fun sampleBuffer(format: MediaFormat): java.nio.ByteBuffer {
        val declaredSize = runCatching {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        }.getOrDefault(0)
        val capacity = maxOf(4 * 1024 * 1024, declaredSize)
            .coerceAtMost(64 * 1024 * 1024)
        return java.nio.ByteBuffer.allocate(capacity)
    }

    private fun readNextSample(
        extractor: MediaExtractor,
        buffer: java.nio.ByteBuffer
    ): MediaCodec.BufferInfo? {
        val presentationTimeUs = extractor.sampleTime
        if (presentationTimeUs < 0L) return null

        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return null

        return MediaCodec.BufferInfo().apply {
            set(0, size, presentationTimeUs, extractor.sampleFlags)
            buffer.position(0)
            buffer.limit(size)
        }
    }

    /**
     * The video and audio DASH streams have independent extractors. Read one
     * pending sample from each and write whichever has the earlier timestamp.
     * This produces a properly time-ordered MP4 without re-encoding either track.
     */
    private fun writeInterleavedSamples(
        videoExtractor: MediaExtractor,
        videoBuffer: java.nio.ByteBuffer,
        videoOutputTrack: Int,
        audioExtractor: MediaExtractor?,
        audioBuffer: java.nio.ByteBuffer?,
        audioOutputTrack: Int,
        muxer: MediaMuxer
    ) {
        var videoSample = readNextSample(videoExtractor, videoBuffer)
        var audioSample = if (audioExtractor != null && audioBuffer != null) {
            readNextSample(audioExtractor, audioBuffer)
        } else {
            null
        }

        while (videoSample != null || audioSample != null) {
            val writeVideo = audioSample == null ||
                (videoSample != null &&
                    videoSample.presentationTimeUs <= audioSample.presentationTimeUs)

            if (writeVideo) {
                val sample = videoSample ?: break
                muxer.writeSampleData(videoOutputTrack, videoBuffer, sample)
                videoExtractor.advance()
                videoSample = readNextSample(videoExtractor, videoBuffer)
            } else {
                val extractor = audioExtractor ?: break
                val buffer = audioBuffer ?: break
                val sample = audioSample ?: break
                muxer.writeSampleData(audioOutputTrack, buffer, sample)
                extractor.advance()
                audioSample = readNextSample(extractor, buffer)
            }
        }
    }

}
