package com.anatej.c2s

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the frozen screenshot and lets the user draw around the text to copy.
 * The drawn region is cropped and handed back in bitmap pixel coordinates.
 */
class RegionSelectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface Listener {
        fun onRegionSelected(bitmap: Bitmap)
    }

    var listener: Listener? = null

    private var bitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private val path = Path()
    private val region = RectF()
    private var hasRegion = false
    private val points = ArrayList<Pair<Float, Float>>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        resetPath()
        if (width > 0 && height > 0) fitBitmap()
        invalidate()
    }

    private fun resetPath() {
        path.reset()
        points.clear()
        region.set(0f, 0f, 0f, 0f)
        hasRegion = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) fitBitmap()
    }

    private fun fitBitmap() {
        val bmp = bitmap ?: return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(
            (width - bmp.width * scale) / 2f,
            (height - bmp.height * scale) / 2f
        )
        imageMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (hasRegion) {
            if (points.size > 1) {
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            } else {
                val radius = dp(4f)
                canvas.drawRoundRect(region, radius, radius, fillPaint)
                canvas.drawRoundRect(region, radius, radius, strokePaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetPath()
                points.add(event.x to event.y)
                path.moveTo(event.x, event.y)
                hasRegion = true
                region.set(event.x, event.y, event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!hasRegion) return true
                for (i in 0 until event.historySize) {
                    val x = event.getHistoricalX(i)
                    val y = event.getHistoricalY(i)
                    points.add(x to y)
                    path.lineTo(x, y)
                    region.union(x, y)
                }
                points.add(event.x to event.y)
                path.lineTo(event.x, event.y)
                region.union(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!hasRegion) return true
                points.add(event.x to event.y)
                region.union(event.x, event.y)
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

                val span = max(region.width(), region.height())
                if (span < dp(24f)) {
                    // A tap: use a comfortable text-sized box around the point.
                    val half = dp(80f)
                    region.set(event.x - half, event.y - half, event.x + half, event.y + half)
                } else {
                    region.inset(-dp(6f), -dp(6f))
                }
                cropAndReturn(bmp)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                resetPath()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun cropAndReturn(bmp: Bitmap) {
        val viewRect = RectF(region)
        inverseMatrix.mapRect(viewRect) // view coordinates -> bitmap pixels

        val left = max(0, min(bmp.width - 1, viewRect.left.toInt()))
        val top = max(0, min(bmp.height - 1, viewRect.top.toInt()))
        val right = max(left + 1, min(bmp.width, viewRect.right.toInt()))
        val bottom = max(top + 1, min(bmp.height, viewRect.bottom.toInt()))
        val width = right - left
        val height = bottom - top
        if (width < 8 || height < 8) {
            // Tiny crop: fall back to the whole screen rather than failing.
            listener?.onRegionSelected(bmp)
            return
        }
        val cropped = runCatching {
            Bitmap.createBitmap(bmp, left, top, width, height)
        }.getOrNull() ?: bmp
        resetPath()
        listener?.onRegionSelected(cropped)
    }
}
