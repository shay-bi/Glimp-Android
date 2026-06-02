package com.vuforia.engine.native_sample

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class ScanFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val contentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb((0.5f * 255).toInt(), (0.85f * 255).toInt(), (0.65f * 255).toInt(), (0.13f * 255).toInt())
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scrollIndex = 0
    private val visibleCols = 12

    private sealed class Mode {
        data object Scrolling : Mode()
        data class Expression(val index: Int) : Mode()
    }
    private var mode: Mode = Mode.Scrolling

    private val glimpMatrix = listOf(
        intArrayOf(0,1,1,1,1,0, 0, 1,0,0,0,0,0, 0,1,1,1,0, 0, 1,0,0,0,0,1, 0,0, 1,1,1,1,0),
        intArrayOf(1,0,0,0,0,0, 0, 1,0,0,0,0,0, 0,0,1,0,0, 0, 1,1,0,0,1,1, 0,0, 1,0,0,0,1),
        intArrayOf(1,0,0,0,0,0, 0, 1,0,0,0,0,0, 0,0,1,0,0, 0, 1,0,1,1,0,1, 0,0, 1,0,0,0,1),
        intArrayOf(1,0,0,1,1,0, 0, 1,0,0,0,0,0, 0,0,1,0,0, 0, 1,0,0,0,0,1, 0,0, 1,1,1,1,0),
        intArrayOf(1,0,0,0,1,0, 0, 1,0,0,0,0,0, 0,0,1,0,0, 0, 1,0,0,0,0,1, 0,0, 1,0,0,0,0),
        intArrayOf(1,0,0,0,1,0, 0, 1,0,0,0,0,0, 0,0,1,0,0, 0, 1,0,0,0,0,1, 0,0, 1,0,0,0,0),
        intArrayOf(0,1,1,1,1,0, 0, 1,1,1,1,1,0, 0,1,1,1,0, 0, 1,0,0,0,0,1, 0,0, 1,0,0,0,0),
        intArrayOf(0,0,0,0,0,0, 0, 0,0,0,0,0,0, 0,0,0,0,0, 0, 0,0,0,0,0,0, 0,0, 0,0,0,0,0),
    )

    private val expressions = listOf(
        // Smile
        listOf(
            intArrayOf(0,0,0,0,0,0,0,0),
            intArrayOf(0,0,1,0,0,1,0,0),
            intArrayOf(0,0,0,0,0,0,0,0),
            intArrayOf(0,0,0,0,0,0,0,0),
            intArrayOf(0,1,0,0,0,0,1,0),
            intArrayOf(0,1,0,0,0,0,1,0),
            intArrayOf(0,0,1,1,1,1,0,0),
            intArrayOf(0,0,0,0,0,0,0,0),
        ),
        // Diamond
        listOf(
            intArrayOf(0,0,0,1,1,0,0,0),
            intArrayOf(0,0,1,1,1,1,0,0),
            intArrayOf(0,1,1,1,1,1,1,0),
            intArrayOf(1,1,1,1,1,1,1,1),
            intArrayOf(0,1,1,1,1,1,1,0),
            intArrayOf(0,0,1,1,1,1,0,0),
            intArrayOf(0,0,0,1,1,0,0,0),
            intArrayOf(0,0,0,0,0,0,0,0),
        ),
        // Heart
        listOf(
            intArrayOf(0,1,1,0,0,1,1,0),
            intArrayOf(1,0,0,1,1,0,0,1),
            intArrayOf(1,0,0,0,0,0,0,1),
            intArrayOf(1,0,0,0,0,0,0,1),
            intArrayOf(0,1,0,0,0,0,1,0),
            intArrayOf(0,0,1,0,0,1,0,0),
            intArrayOf(0,0,0,1,1,0,0,0),
            intArrayOf(0,0,0,0,0,0,0,0),
        )
    )

    private val scrollRunnable = object : Runnable {
        override fun run() {
            scrollIndex++
            invalidate()

            val maxScroll = glimpMatrix.first().size - visibleCols
            if (scrollIndex > maxScroll) {
                handler.postDelayed({ startExpressionSequence() }, 800L)
            } else {
                handler.postDelayed(this, 100L)
            }
        }
    }

    private var expressionIndex = 0
    private val expressionRunnable = object : Runnable {
        override fun run() {
            expressionIndex++
            if (expressionIndex < expressions.size) {
                mode = Mode.Expression(expressionIndex)
                invalidate()
                handler.postDelayed(this, 1500L)
            } else {
                startScrollAnimation()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startScrollAnimation()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun startScrollAnimation() {
        handler.removeCallbacksAndMessages(null)
        scrollIndex = 0
        mode = Mode.Scrolling
        invalidate()
        handler.postDelayed(scrollRunnable, 100L)
    }

    private fun startExpressionSequence() {
        expressionIndex = 0
        mode = Mode.Expression(expressionIndex)
        invalidate()
        handler.postDelayed(expressionRunnable, 1500L)
    }

    private fun currentPixels(): List<IntArray> {
        return when (val m = mode) {
            is Mode.Scrolling -> {
                glimpMatrix.map { row ->
                    val start = scrollIndex.coerceAtMost(row.size)
                    val end = min(start + visibleCols, row.size)
                    val slice = row.copyOfRange(start, end)
                    if (slice.size < visibleCols) {
                        IntArray(visibleCols).also { out ->
                            System.arraycopy(slice, 0, out, 0, slice.size)
                        }
                    } else {
                        slice
                    }
                }
            }
            is Mode.Expression -> expressions[m.index]
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val targetAspect = 12f / 8.5f
        val maxWidth = w * 0.8f
        val calculatedHeight = maxWidth * targetAspect

        val polaroidLeft = (w - maxWidth) / 2f
        val polaroidTop = (h - calculatedHeight) / 2f
        val polaroidRect = RectF(polaroidLeft, polaroidTop, polaroidLeft + maxWidth, polaroidTop + calculatedHeight)

        canvas.drawRoundRect(polaroidRect, 8f.dp(), 8f.dp(), whitePaint)

        val contentSideInset = polaroidRect.width() * 0.12f
        val topInset = contentSideInset
        val bottomInset = contentSideInset * 2.2f

        val contentRect = RectF(
            polaroidRect.left + contentSideInset,
            polaroidRect.top + topInset,
            polaroidRect.right - contentSideInset,
            polaroidRect.bottom - bottomInset
        )
        canvas.drawRoundRect(contentRect, 4f.dp(), 4f.dp(), goldPaint)

        val pixels = currentPixels()
        val colCount = pixels.firstOrNull()?.size ?: 8
        val rowCount = pixels.size
        val pixelSize = (contentRect.width() * 0.75f) / colCount.toFloat()
        val faceX = contentRect.centerX() - (colCount * pixelSize / 2f)
        val faceY = contentRect.centerY() - (rowCount * pixelSize / 2f)

        for (y in pixels.indices) {
            val row = pixels[y]
            for (x in row.indices) {
                if (row[x] == 1) {
                    val left = faceX + (x * pixelSize)
                    val top = faceY + (y * pixelSize)
                    canvas.drawRect(left, top, left + pixelSize, top + pixelSize, contentPaint)
                }
            }
        }
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density
}
