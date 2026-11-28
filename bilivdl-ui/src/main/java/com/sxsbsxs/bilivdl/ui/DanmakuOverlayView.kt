package com.sxsbsxs.bilivdl.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Xml
import android.view.View
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.max

/**
 * Bilibili danmaku overlay driven by the real player clock.
 *
 * Pause/buffering freezes the overlay because [playingProvider] is queried before
 * scheduling another frame. Seeking needs no synthetic clock correction: the next
 * frame is drawn at the new position supplied by the player.
 */
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
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2f)
        color = 0xCC000000.toInt()
    }

    private data class SpecialMotion(
        val text: String,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val alphaFrom: Float,
        val alphaTo: Float,
        val durationMs: Long,
        val translationDurationMs: Long,
        val delayMs: Long,
        val rotationZ: Float,
        val quadraticEaseOut: Boolean
    )

    private data class RenderItem(
        val text: String,
        val timeMs: Long,
        val color: Int,
        val fontSize: Int,
        val mode: Int,
        val special: SpecialMotion? = null,
        var lane: Int = 0
    )

    private var items: List<RenderItem> = emptyList()

    /** Must read the player's current position directly, not a lagging UI state. */
    var positionProvider: () -> Long = { 0L }
    var playingProvider: () -> Boolean = { false }

    var danmakuEnabled: Boolean = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }

    private var forcedPlaying = false
    private val laneHeight get() = dp(38f)
    private val scrollDurationMs = 8_500L
    private val fixedDurationMs = 4_200L

    fun setDanmaku(value: List<BiliVdlDanmaku>) {
        items = value.asSequence()
            .filter { it.timeMs >= 0L && it.text.isNotBlank() }
            .take(30_000)
            .mapNotNull { source ->
                when (source.mode) {
                    1, 4, 5, 6 -> RenderItem(
                        text = source.text,
                        timeMs = source.timeMs,
                        color = source.color,
                        fontSize = source.fontSize,
                        mode = source.mode
                    )
                    7 -> parseSpecial(source.text)?.let { motion ->
                        RenderItem(
                            text = motion.text,
                            timeMs = source.timeMs,
                            color = source.color,
                            fontSize = source.fontSize,
                            mode = 7,
                            special = motion
                        )
                    }
                    else -> null
                }
            }
            .sortedBy { it.timeMs }
            .toList()
        assignLanes()
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
                            mode in setOf(1, 4, 5, 6, 7)
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
        if (text.isBlank()) return
        val next = BiliVdlDanmaku(
            text = text,
            timeMs = timeMs.coerceAtLeast(0L),
            color = color,
            fontSize = 25,
            mode = mode
        )
        setDanmaku(
            items.map {
                BiliVdlDanmaku(it.text, it.timeMs, it.color, it.fontSize, it.mode)
            } + next
        )
    }

    fun setPlaybackPositionProvider(provider: () -> Long) {
        positionProvider = provider
    }

    fun setPlaying(value: Boolean) {
        forcedPlaying = value
        if (value) postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        assignLanes()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!danmakuEnabled || items.isEmpty() || width <= 0 || height <= 0) return
        val now = runCatching { positionProvider() }.getOrDefault(0L).coerceAtLeast(0L)
        val first = lowerBound(now - SPECIAL_LOOKBACK_MS)
        for (index in first until items.size) {
            val item = items[index]
            if (item.timeMs > now) break
            if (item.mode == 7) {
                drawSpecial(canvas, item, now)
            } else {
                drawStandard(canvas, item, now)
            }
        }
        if (forcedPlaying || runCatching { playingProvider() }.getOrDefault(false)) {
            postInvalidateDelayed(FRAME_DELAY_MS)
        }
    }

    private fun drawStandard(canvas: Canvas, item: RenderItem, now: Long) {
        val fixed = item.mode == 4 || item.mode == 5
        val duration = if (fixed) fixedDurationMs else scrollDurationMs
        val elapsed = now - item.timeMs
        if (elapsed !in 0 until duration) return

        val size = fontPixels(item.fontSize)
        paint.textSize = size
        strokePaint.textSize = size
        paint.alpha = 255
        strokePaint.alpha = 210
        paint.color = 0xFF000000.toInt() or (item.color and 0x00FFFFFF)
        val textWidth = paint.measureText(item.text)
        val top = item.lane * laneHeight + size

        val y = when (item.mode) {
            5 -> top.coerceAtMost(height - dp(4f))
            4 -> (height - dp(8f) - item.lane * laneHeight).coerceAtLeast(size)
            else -> top.coerceAtMost(height * 0.62f)
        }
        val progress = (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val x = when (item.mode) {
            5, 4 -> (width - textWidth) / 2f
            6 -> -textWidth + (width + textWidth) * progress
            else -> width - (width + textWidth) * progress
        }
        canvas.drawText(item.text, x, y, strokePaint)
        canvas.drawText(item.text, x, y, paint)
    }

    /**
     * BiliMiao's parser supports Bilibili's advanced danmaku JSON array:
     * [beginX, beginY, alphaRange, duration, text, rotateZ, rotateY,
     *  endX, endY, translationDuration, delay, noStroke, easing, font, pathData].
     * This renderer handles position, alpha, duration, delay, and Z rotation. The
     * optional SVG path/font extension is deliberately not interpreted as plain text.
     */
    private fun drawSpecial(canvas: Canvas, item: RenderItem, now: Long) {
        val motion = item.special ?: return
        val elapsed = now - item.timeMs - motion.delayMs
        if (elapsed < 0L || elapsed >= motion.durationMs) return
        val total = motion.translationDurationMs.coerceAtLeast(1L)
        var progress = (elapsed.toFloat() / total).coerceIn(0f, 1f)
        if (motion.quadraticEaseOut) progress = 1f - (1f - progress) * (1f - progress)

        val x1 = resolveCoordinate(motion.startX, width.toFloat())
        val y1 = resolveCoordinate(motion.startY, height.toFloat())
        val x2 = resolveCoordinate(motion.endX, width.toFloat())
        val y2 = resolveCoordinate(motion.endY, height.toFloat())
        val x = x1 + (x2 - x1) * progress
        val y = y1 + (y2 - y1) * progress
        val alphaProgress = (elapsed.toFloat() / motion.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val alpha = (motion.alphaFrom + (motion.alphaTo - motion.alphaFrom) * alphaProgress)
            .coerceIn(0f, 1f)
        val size = fontPixels(item.fontSize)
        paint.textSize = size
        paint.alpha = (alpha * 255f).toInt()
        strokePaint.textSize = size
        strokePaint.alpha = (alpha * 210f).toInt()
        paint.color = 0xFF000000.toInt() or (item.color and 0x00FFFFFF)
        val width = paint.measureText(motion.text)
        canvas.save()
        if (motion.rotationZ != 0f) {
            canvas.rotate(motion.rotationZ, x, y)
        }
        canvas.drawText(motion.text, x.coerceIn(-width, this.width.toFloat()), y.coerceIn(size, this.height.toFloat()), strokePaint)
        canvas.drawText(motion.text, x.coerceIn(-width, this.width.toFloat()), y.coerceIn(size, this.height.toFloat()), paint)
        canvas.restore()
        paint.alpha = 255
        strokePaint.alpha = 210
    }

    private fun parseSpecial(raw: String): SpecialMotion? = runCatching {
        val values = JSONArray(raw)
        if (values.length() < 5) return null
        val text = values.optString(4).takeIf { it.isNotBlank() } ?: return null
        val alphaParts = values.optString(2, "1").split('-')
        val alphaFrom = alphaParts.firstOrNull()?.toFloatOrNull() ?: 1f
        val alphaTo = alphaParts.getOrNull(1)?.toFloatOrNull() ?: alphaFrom
        val durationMs = ((values.optString(3).toFloatOrNull() ?: 5f) * 1000f)
            .toLong().coerceIn(250L, 60_000L)
        val startX = values.optString(0).toFloatOrNull() ?: 0.5f
        val startY = values.optString(1).toFloatOrNull() ?: 0.5f
        val endX = values.optString(7).toFloatOrNull() ?: startX
        val endY = values.optString(8).toFloatOrNull() ?: startY
        val rotation = values.optString(5).toFloatOrNull() ?: 0f
        val translation = values.optString(9).toLongOrNull()?.takeIf { it > 0L } ?: durationMs
        val delay = values.optString(10).toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        SpecialMotion(
            text = text,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            alphaFrom = alphaFrom,
            alphaTo = alphaTo,
            durationMs = durationMs,
            translationDurationMs = translation,
            delayMs = delay,
            rotationZ = rotation,
            quadraticEaseOut = values.optString(13) == "0"
        )
    }.getOrNull()

    private fun assignLanes() {
        if (items.isEmpty() || width <= 0 || height <= 0) return
        val lineHeight = laneHeight
        val rowCount = (height / lineHeight).toInt().coerceAtLeast(4)
        val rollingRows = max(1, rowCount - 4)
        val rollingNextFree = LongArray(rollingRows) { Long.MIN_VALUE }
        val topNextFree = LongArray(2) { Long.MIN_VALUE }
        val bottomNextFree = LongArray(2) { Long.MIN_VALUE }
        for (item in items) {
            if (item.mode == 7) {
                item.lane = 0
                continue
            }
            val fixed = item.mode == 4 || item.mode == 5
            val slots = when (item.mode) {
                5 -> topNextFree
                4 -> bottomNextFree
                else -> rollingNextFree
            }
            val lane = slots.indices.firstOrNull { slots[it] <= item.timeMs }
                ?: slots.indices.minByOrNull { slots[it] }
                ?: 0
            item.lane = lane
            val size = fontPixels(item.fontSize)
            paint.textSize = size
            val textWidth = paint.measureText(item.text)
            val duration = if (fixed) fixedDurationMs else scrollDurationMs
            val occupancy = if (fixed) duration else {
                duration + (duration * (textWidth / width.toFloat()).coerceIn(0f, 1f) * 0.55f).toLong()
            }
            slots[lane] = item.timeMs + occupancy
        }
    }

    private fun lowerBound(target: Long): Int {
        var low = 0
        var high = items.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (items[mid].timeMs < target) low = mid + 1 else high = mid
        }
        return low
    }

    private fun fontPixels(value: Int): Float =
        (value.coerceIn(18, 48) * resources.displayMetrics.scaledDensity).coerceAtLeast(dp(18f))

    private fun resolveCoordinate(value: Float, extent: Float): Float =
        if (value in 0f..1f) value * extent else value * resources.displayMetrics.density

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val FRAME_DELAY_MS = 32L
        private const val SPECIAL_LOOKBACK_MS = 60_000L
    }
}
