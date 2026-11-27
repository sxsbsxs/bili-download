package com.example.bilidl

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.bilidl.common.content.BiliContentExporter
import com.example.bilidl.common.content.BiliDanmakuProtoParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Manual live smoke test. Pass a public BV ID with the instrumentation runner:
 * -Pandroid.testInstrumentationRunnerArguments.bvid=BV...
 *
 * This runs against Bilibili and the Android media stack; it is intentionally
 * not part of the ordinary offline JVM test suite.
 */
@RunWith(AndroidJUnit4::class)
class BiliLivePipelineSmokeTest {
    @Test(timeout = 480_000)
    fun publicVideoCanDownloadRemuxAndExportSidecarFormats() {
        val arguments = InstrumentationRegistry.getArguments()
        val bvid = arguments.getString("bvid").orEmpty()
        val requestedQuality = arguments.getString("quality")?.toIntOrNull() ?: 64
        assertTrue("Set instrumentation argument bvid to a public BV number", bvid.startsWith("BV"))

        val repository = BiliRepository()
        val video = repository.inspect("https://www.bilibili.com/video/$bvid")
        assertTrue("Bilibili metadata did not return a CID", video.cid > 0L)
        val available = video.qualities.filter { it.id <= requestedQuality }
        assertTrue(
            "No returned quality at or below requested qn=$requestedQuality: ${video.qualities}",
            available.isNotEmpty()
        )
        val selectedQuality = available.maxByOrNull { it.id }!!.id
        val source = repository.getPlaySource(video, selectedQuality)
        assertTrue(
            "Live API did not return a DASH video stream; this device smoke test needs DASH",
            source.videoUrls.isNotEmpty()
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val workDir = File(context.cacheDir, "bili-live-smoke").apply {
            deleteRecursively()
            mkdirs()
        }
        val videoFile = File(workDir, "video.m4s")
        val audioFile = File(workDir, "audio.m4s")
        val output = File(workDir, "remuxed.mp4")

        try {
            runBlocking {
                SegmentedHttpDownloader.download(
                    urls = source.videoUrls,
                    target = videoFile,
                    cookie = null,
                    concurrency = 2,
                    isCancelled = { false },
                    onProgress = {}
                )
                if (source.audioUrls.isNotEmpty()) {
                    SegmentedHttpDownloader.download(
                        urls = source.audioUrls,
                        target = audioFile,
                        cookie = null,
                        concurrency = 2,
                        isCancelled = { false },
                        onProgress = {}
                    )
                }
            }

            BiliMediaMuxer.muxToMp4(
                videoFile,
                audioFile.takeIf { it.isFile },
                output
            )
            assertTrue("Native MP4 inspection or Android track inspection failed", BiliMediaMuxer.isValidMp4(output))

            val extractor = MediaExtractor()
            val mimeTypes = try {
                extractor.setDataSource(output.absolutePath)
                (0 until extractor.trackCount).map { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                }
            } finally {
                extractor.release()
            }
            assertTrue("Remuxed output has no video track: $mimeTypes", mimeTypes.any { it.startsWith("video/") })
            if (source.audioUrls.isNotEmpty()) {
                assertTrue("Remuxed output lost its audio track: $mimeTypes", mimeTypes.any { it.startsWith("audio/") })
            }

            // Exercise the live danmaku endpoint and the production protobuf/XML
            // parser/exporter with the selected public video CID.
            val danmakuBytes = repository.getDanmakuSegment(video.cid, 1)
            val comments = BiliDanmakuProtoParser.parse(danmakuBytes)
            val xml = BiliContentExporter.danmakuToXml(video.cid, comments)
            assertTrue("Danmaku XML exporter did not emit a document", xml.startsWith("<?xml"))

            // Subtitle tracks are optional for a public video. When present,
            // fetch their BCC JSON and run the production SRT exporter.
            val tracks = repository.getSubtitleTracks(video)
            var exportedSubtitleTracks = 0
            tracks.take(3).forEach { track ->
                val cues = repository.getSubtitleCues(track)
                if (cues.isNotEmpty()) {
                    val srt = BiliContentExporter.subtitlesToSrt(cues)
                    assertTrue("SRT exporter produced no timecode", srt.contains(" --> "))
                    exportedSubtitleTracks++
                }
            }

            Log.i(
                TAG,
                "Live smoke passed: bvid=$bvid cid=${video.cid} qn=$selectedQuality " +
                    "bytes=${output.length()} danmaku=${comments.size} " +
                    "subtitleTracks=${tracks.size} exportedSubtitles=$exportedSubtitleTracks " +
                    "tracks=$mimeTypes"
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "BiliLivePipelineSmoke"
    }
}
