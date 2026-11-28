package com.example.bilidl

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Xml
import android.view.View
import com.example.bilidl.common.content.BiliDanmaku
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.max

data class BiliDanmakuEntry(
    val progressMs: Long,
    val text: String,
    val color: Int,
    val mode: Int,
    val fontSize: Float,
    val local: Boolean = false,
    var lane: Int = 0
)

/**
 * Lightweight timed danmaku layer for the in-app Media3 player.
 * It reads Bilibili's public XML format and follows the player's clock,
 * so buffering, pause and seeking do not desynchronize the overlay.
 */
class BiliDanmakuOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val entries = ArrayList<BiliDanmakuEntry>()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.2f)
        color = 0xCC000000.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.WHITE
    }
    private val bounds = Rect()
    var positionProvider: () -> Long = { 0L }
    var playingProvider: () -> Boolean = { false }
    var danmakuEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        setWillNotDraw(false)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setDanmaku(items: List<BiliDanmaku>) {
        val parsed = items
            .asSequence()
            .filter { it.progressMs >= 0L && it.content.isNotBlank() && it.mode in setOf(1, 4, 5) }
            .take(30_000)
            .map {
                BiliDanmakuEntry(
                    progressMs = it.progressMs,
                    text = it.content,
                    color = it.color.toInt(),
                    mode = it.mode,
                    fontSize = it.fontSize.coerceIn(16, 38).toFloat() *
                        resources.displayMetrics.density
                )
            }
            .sortedBy { it.progressMs }
            .toList()
        assignLanes(parsed)
        synchronized(entries) {
            entries.clear()
            entries.addAll(parsed)
        }
        invalidate()
    }

    fun setXml(xml: String) {
        val parsed = ArrayList<BiliDanmakuEntry>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            while (parser.eventType != XmlPullParser.END_DOCUMENT && parsed.size < 20_000) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "d") {
                    val values = parser.getAttributeValue(null, "p").orEmpty().split(',')
                    if (values.size >= 4) {
                        val time = values[0].toDoubleOrNull()
                        val mode = values[1].toIntOrNull() ?: 1
                        val size = values[2].toFloatOrNull() ?: 25f
                        val color = values[3].toLongOrNull()?.toInt() ?: 0xFFFFFF
                        val text = parser.nextText().trim()
                        if (time != null && time >= 0.0 && text.isNotBlank() && mode in setOf(1, 4, 5)) {
                            parsed += BiliDanmakuEntry(
                                progressMs = (time * 1000.0).toLong(),
                                text = text,
                                color = color,
                                mode = mode,
                                fontSize = size.coerceIn(16f, 38f) * resources.displayMetrics.density
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
            // A broken/unsupported XML response should not break video playback.
        }
        parsed.sortBy { it.progressMs }
        assignLanes(parsed)
        synchronized(entries) {
            entries.clear()
            entries.addAll(parsed)
        }
        invalidate()
    }

    fun addLocalDanmaku(text: String, color: Int, mode: Int, progressMs: Long) {
        if (text.isBlank()) return
        val item = BiliDanmakuEntry(
            progressMs = progressMs.coerceAtLeast(0L),
            text = text,
            color = color,
            mode = mode,
            fontSize = dp(25f),
            local = true
        )
        synchronized(entries) {
            entries += item
            entries.sortBy { it.progressMs }
            assignLanes(entries)
        }
        invalidate()
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!danmakuEnabled || width <= 0 || height <= 0) return
        val now = runCatching { positionProvider() }.getOrDefault(0L)
        val lineHeight = dp(31f)
        val topLimit = height * 0.62f
        val snapshot = synchronized(entries) { entries.toList() }

        snapshot.forEach { item ->
            val duration = if (item.mode == 1) 8_500L else 4_200L
            val elapsed = now - item.progressMs
            if (elapsed < 0L || elapsed >= duration) return@forEach

            val size = item.fontSize.coerceIn(dp(16f), dp(38f))
            textPaint.textSize = size
            strokePaint.textSize = size
            textPaint.color = 0xFF000000.toInt() or (item.color and 0x00FFFFFF)
            strokePaint.color = if (item.local) 0xFFFF7299.toInt() else 0xCC000000.toInt()
            val text = item.text
            textPaint.getTextBounds(text, 0, text.length, bounds)
            val textWidth = textPaint.measureText(text)
            val baseline = dp(3f) + item.lane * lineHeight + max(size, bounds.height().toFloat())

            val x = when (item.mode) {
                5, 4 -> (width - textWidth) / 2f
                else -> width - (elapsed.toFloat() / duration.toFloat()) * (width + textWidth)
            }
            val y = when (item.mode) {
                5 -> dp(4f) + item.lane * lineHeight + max(size, bounds.height().toFloat())
                4 -> height - dp(8f) - item.lane * lineHeight
                else -> baseline
            }
            if (item.mode == 1 && y > topLimit) return@forEach

            canvas.drawText(text, x, y, strokePaint)
            canvas.drawText(text, x, y, textPaint)
        }

        if (playingProvider()) postInvalidateDelayed(32L)
    }

    private fun assignLanes(list: List<BiliDanmakuEntry>) {
        val density = resources.displayMetrics.density
        val rowCount = (height / (31f * density)).toInt().coerceIn(3, 18)
        val rollingRows = max(2, rowCount - 2)
        val rollingEnds = LongArray(rollingRows) { Long.MIN_VALUE }
        val topEnds = LongArray(2) { Long.MIN_VALUE }
        val bottomEnds = LongArray(2) { Long.MIN_VALUE }

        list.forEach { item ->
            val ends = when (item.mode) {
                5 -> topEnds
                4 -> bottomEnds
                else -> rollingEnds
            }
            val lane = ends.indexOfFirst { it <= item.progressMs }
            if (lane >= 0) {
                item.lane = lane
                ends[lane] = item.progressMs + if (item.mode == 1) 5_000L else 4_000L
            } else {
                item.lane = item.progressMs.hashCode().and(Int.MAX_VALUE) % ends.size
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}