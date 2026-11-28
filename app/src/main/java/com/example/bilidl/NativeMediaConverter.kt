package com.example.bilidl

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

enum class NativeMediaConversionMode(
    val title: String,
    val extension: String,
    val mimeType: String,
    val description: String
) {
    MP4_REMUX(
        "重新封装为 MP4",
        "mp4",
        "video/mp4",
        "保留原视频和音频编码，不重新压缩。仅适用于系统 MP4 封装器支持的音视频轨道。"
    ),
    AAC_TO_M4A(
        "提取 AAC 音频为 M4A",
        "m4a",
        "audio/mp4",
        "只保留 AAC 音轨，无损复制，不包含画面，也不重新编码。"
    )
}

data class NativeMediaConversionResult(
    val bytesWritten: Long,
    val sampleCount: Long,
    val durationUs: Long
)

/**
 * Lossless container remuxing with Android's MediaExtractor / MediaMuxer.
 * This is not a software transcoder and reports unsupported codecs explicitly.
 */
object NativeMediaConverter {

    fun suggestOutputName(sourceTitle: String, extension: String): String {
        val withoutExtension = sourceTitle.substringBeforeLast('.', sourceTitle)
        val safe = withoutExtension
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
            .ifBlank { "BiliDl media" }
        return safe + "." + extension
    }

    fun convert(
        context: Context,
        source: Uri,
        target: Uri,
        mode: NativeMediaConversionMode,
        onProgress: (Int) -> Unit = {}
    ): NativeMediaConversionResult {
        val resolver = context.contentResolver
        val extractor = MediaExtractor()
        var descriptor: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var success = false

        try {
            extractor.setDataSource(context, source, null)
            if (extractor.trackCount == 0) throw IOException("源文件没有可读取的媒体轨道")

            val outputTrackByInput = IntArray(extractor.trackCount) { -1 }
            val durationUs = (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrNull()
                } else null
            }.maxOrNull() ?: 0L

            descriptor = resolver.openFileDescriptor(target, "rw")
                ?: throw IOException("无法创建输出文件")
            muxer = MediaMuxer(
                descriptor.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            var includedTracks = 0
            var maxInputSize = 1024 * 1024
            var rotation = 0

            for (inputIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(inputIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                val include = when (mode) {
                    NativeMediaConversionMode.MP4_REMUX -> isVideo || isAudio
                    NativeMediaConversionMode.AAC_TO_M4A -> isAudio && mime == "audio/mp4a-latm"
                }
                if (!include) continue

                val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrNull()
                } else null
                if (maxSize != null && maxSize > 0) {
                    maxInputSize = maxOf(maxInputSize, maxSize)
                }
                if (isVideo && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = runCatching {
                        format.getInteger(MediaFormat.KEY_ROTATION)
                    }.getOrDefault(0)
                }

                try {
                    outputTrackByInput[inputIndex] = muxer.addTrack(format)
                } catch (error: Exception) {
                    throw IOException(
                        "系统 MP4 封装器不支持此轨道：" + mime.substringAfter('/'),
                        error
                    )
                }
                includedTracks++
            }

            if (includedTracks == 0) {
                throw IOException(
                    if (mode == NativeMediaConversionMode.AAC_TO_M4A) {
                        "没有找到可直接复制的 AAC 音轨；当前版本不进行有损转码"
                    } else {
                        "没有找到可封装为 MP4 的音视频轨道"
                    }
                )
            }

            if (rotation == 90 || rotation == 180 || rotation == 270) {
                muxer.setOrientationHint(rotation)
            }
            for (inputIndex in outputTrackByInput.indices) {
                if (outputTrackByInput[inputIndex] >= 0) extractor.selectTrack(inputIndex)
            }

            muxer.start()
            muxerStarted = true
            var sampleCount = 0L
            var bytesWritten = 0L
            var lastProgress = -1
            var buffer = ByteBuffer.allocateDirect(maxInputSize.coerceAtMost(MAX_SAMPLE_BUFFER_BYTES))
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                buffer.clear()
                var sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                if (sampleSize > buffer.capacity()) {
                    if (sampleSize > MAX_SAMPLE_BUFFER_BYTES) {
                        throw IOException("单个媒体数据块超过安全限制，无法处理")
                    }
                    buffer = ByteBuffer.allocateDirect(sampleSize)
                    sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                }

                val inputTrack = extractor.sampleTrackIndex
                val outputTrack = outputTrackByInput.getOrNull(inputTrack) ?: -1
                if (outputTrack >= 0) {
                    val flags = extractor.sampleFlags
                    if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                        throw IOException("源文件包含 DRM 加密轨道，不能无损封装")
                    }
                    bufferInfo.set(0, sampleSize, extractor.sampleTime.coerceAtLeast(0L), flags)
                    muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                    sampleCount++
                    bytesWritten += sampleSize
                }

                if (durationUs > 0L) {
                    val progress = (
                        extractor.sampleTime.coerceAtLeast(0L) * 100L / durationUs
                    ).toInt().coerceIn(0, 99)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        onProgress(progress)
                    }
                }
                if (!extractor.advance()) break
            }

            if (sampleCount == 0L) throw IOException("没有读取到有效媒体数据")
            success = true
            onProgress(100)
            return NativeMediaConversionResult(bytesWritten, sampleCount, durationUs)
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException(
                "转换失败：" + (error.message ?: error.javaClass.simpleName),
                error
            )
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { descriptor?.close() }
            if (!success) runCatching { resolver.delete(target, null, null) }
        }
    }

    private const val MAX_SAMPLE_BUFFER_BYTES = 64 * 1024 * 1024
}
