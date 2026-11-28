package com.sxsbsxs.bilivdl.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Xml
import android.view.View
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.max

/** Lightweight BiliVdl-style danmaku overlay. Playback time is provided by the host player. */
class DanmakuOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        style = Paint.Style.FILL
        setShadowLayer(3f, 1f, 2f, 0xCC000000.toInt())
    }
    private var items: List<BiliVdlDanmaku> = emptyList()
    var positionProvider: () -> Long = { 0L }
    var playingProvider: () -> Boolean = { false }
    var danmakuEnabled: Boolean = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }
    private var playing = false
    private val laneHeight = 48f
    private val duration = 9000L

    fun setDanmaku(value: List<BiliVdlDanmaku>) {
        items = value.sortedBy { it.timeMs }.take(30_000)
        invalidate()
    }

    fun setXml(xml: String) {
        val parsed = ArrayList<BiliVdlDanmaku>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            while (parser.eventType != XmlPullParser.END_DOCUMENT && parsed.size < 20_000) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "d") {
                    val values = parser.getAttributeValue(null, "p").orEmpty().split(',')
                    if (values.size >= 4) {
                        val seconds = values[0].toDoubleOrNull()
                        val mode = values[1].toIntOrNull() ?: 1
                        val size = values[2].toIntOrNull() ?: 25
                        val color = values[3].toLongOrNull()?.toInt() ?: 0xFFFFFF
                        val text = parser.nextText().trim()
                        if (seconds != null && seconds >= 0.0 && text.isNotBlank() &&
                            mode in setOf(1, 4, 5, 6)
                        ) {
                            parsed += BiliVdlDanmaku(
                                text = text,
                                timeMs = (seconds * 1000.0).toLong(),
                                color = color,
                                fontSize = size,
                                mode = mode
                            )
                        }
                    } else {
                        parser.next()
                    }
                } else {
                    parser.next()
                }
            }
        } catch (_: Exception) {
            // Invalid or unsupported XML should not interrupt media playback.
        }
        setDanmaku(parsed)
    }

    fun clear() {
        items = emptyList()
        invalidate()
    }

    fun addLocalDanmaku(text: String, color: Int, mode: Int, timeMs: Long) {
        items = items + BiliVdlDanmaku(text, timeMs, color, 25, mode)
        invalidate()
    }

    fun setPlaybackPositionProvider(provider: () -> Long) {
        positionProvider = provider
    }

    fun setDanmakuEnabled(value: Boolean) {
        danmakuEnabled = value
    }

    fun setPlaying(value: Boolean) {
        playing = value
        if (value) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!danmakuEnabled || items.isEmpty() || width == 0 || height == 0) return
        val now = positionProvider()
        val lanes = max(1, (height / laneHeight).toInt())
        val active = items.asSequence()
            .filter { it.mode == 1 || it.mode == 4 || it.mode == 5 || it.mode == 6 }
            .filter { now >= it.timeMs && now - it.timeMs < duration }
            .take(100)
        active.forEach { item ->
            val elapsed = now - item.timeMs
            val lane = ((item.timeMs / 1300L) % lanes).toInt()
            paint.textSize = (item.fontSize.coerceIn(18, 48) * resources.displayMetrics.scaledDensity)
                .coerceAtLeast(22f)
            paint.color = (0xFF shl 24) or (item.color and 0xFFFFFF)
            val textWidth = paint.measureText(item.text)
            val topY = lane * laneHeight + paint.textSize
            val y = when (item.mode) {
                4 -> height - lane * laneHeight - 4f
                else -> topY
            }.coerceIn(paint.textSize, height - 4f)
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val x = when (item.mode) {
                5, 4 -> (width - textWidth) / 2f
                6 -> -textWidth + (width + textWidth) * progress
                else -> width - (width + textWidth) * progress
            }
            canvas.drawText(item.text, x, y, paint)
        }
        if (playing || playingProvider()) postInvalidateDelayed(32L)
    }
}
