package com.example.bilidl.common.content

import org.junit.Assert.assertTrue
import org.junit.Test

class BiliContentExporterTest {
    @Test
    fun exportsBilibiliCompatibleXmlAndEscapesUnsafeText() {
        val xml = BiliContentExporter.danmakuToXml(
            cid = 123456L,
            items = listOf(
                BiliDanmaku(
                    id = 9L,
                    progressMs = 1_234L,
                    mode = 1,
                    fontSize = 25,
                    color = 0xffffffL,
                    senderHash = "sender&1",
                    content = "A < B & C",
                    sentAtEpochSeconds = 1_794_000_000L,
                    pool = 0
                )
            )
        )

        assertTrue(xml.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue(xml.contains("<chatid>123456</chatid>"))
        assertTrue(xml.contains("""p="1.234,1,25,16777215,1794000000,0,sender&amp;1,9,0""""))
        assertTrue(xml.contains("A &lt; B &amp; C"))
        assertTrue(xml.endsWith("</i>"))
    }

    @Test
    fun convertsBccCuesToOrderedSrtWithMillisecondTimecodes() {
        val srt = BiliContentExporter.subtitlesToSrt(
            listOf(
                BiliSubtitleCue(65.25, 66.0, "第二句"),
                BiliSubtitleCue(1.5, 2.25, "第一句")
            )
        )

        assertTrue(srt.indexOf("第一句") < srt.indexOf("第二句"))
        assertTrue(srt.contains("00:00:01,500 --> 00:00:02,250"))
        assertTrue(srt.contains("00:01:05,250 --> 00:01:06,000"))
    }

    @Test
    fun dropsInvalidSubtitleCuesInsteadOfWritingBrokenTimecodes() {
        val srt = BiliContentExporter.subtitlesToSrt(
            listOf(
                BiliSubtitleCue(Double.NaN, 1.0, "invalid"),
                BiliSubtitleCue(4.0, 3.0, "reversed"),
                BiliSubtitleCue(0.0, 1.0, "valid")
            )
        )

        assertTrue(srt.contains("valid"))
        assertTrue(!srt.contains("invalid"))
        assertTrue(!srt.contains("reversed"))
    }
}
