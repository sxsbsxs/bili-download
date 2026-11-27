package com.example.bilidl.common.content

import java.util.Locale
import kotlin.math.roundToLong

object BiliContentExporter {
    fun danmakuToXml(cid: Long, items: List<BiliDanmaku>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><i>""")
        append("<chatserver>chat.bilibili.com</chatserver>")
        append("<chatid>").append(cid).append("</chatid>")
        append("<mission>0</mission><maxlimit>").append(items.size).append("</maxlimit>")
        append("<state>0</state><real_name>0</real_name><source>k-v</source>")
        items.sortedBy { it.progressMs }.forEach { item ->
            val seconds = String.format(Locale.US, "%.3f", item.progressMs / 1000.0)
            val parameters = listOf(
                seconds,
                item.mode.toString(),
                item.fontSize.toString(),
                item.color.toString(),
                item.sentAtEpochSeconds.toString(),
                item.pool.toString(),
                escapeXml(item.senderHash),
                item.id.toString(),
                "0"
            ).joinToString(",")
            append("<d p=\"").append(parameters).append("\">")
            append(escapeXml(item.content))
            append("</d>")
        }
        append("</i>")
    }

    fun subtitlesToSrt(cues: List<BiliSubtitleCue>): String = buildString {
        val valid = cues
            .filter {
                it.startSeconds.isFinite() && it.endSeconds.isFinite() &&
                    it.startSeconds >= 0.0 && it.endSeconds > it.startSeconds &&
                    it.content.isNotBlank()
            }
            .sortedBy { it.startSeconds }
        valid.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(formatSrtTime(cue.startSeconds))
                .append(" --> ")
                .append(formatSrtTime(cue.endSeconds))
                .append('\n')
            append(cue.content.replace("\r\n", "\n").replace('\r', '\n').trim())
            append("\n\n")
        }
    }

    private fun formatSrtTime(seconds: Double): String {
        val totalMillis = (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
        val hours = totalMillis / 3_600_000L
        val minutes = (totalMillis % 3_600_000L) / 60_000L
        val wholeSeconds = (totalMillis % 60_000L) / 1_000L
        val millis = totalMillis % 1_000L
        return String.format(
            Locale.US,
            "%02d:%02d:%02d,%03d",
            hours,
            minutes,
            wholeSeconds,
            millis
        )
    }

    private fun escapeXml(value: String): String {
        val safe = value.filter { character ->
            character == '\t' || character == '\n' || character == '\r' ||
                character.code >= 0x20
        }
        return safe
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
