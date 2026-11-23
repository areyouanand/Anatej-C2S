package com.anatej.c2s

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the frozen screenshot and lets the user draw around the text to copy.
 * The drawn region is stored in bitmap pixel coordinates and returned as a crop.
 * Pinch with two fingers to zoom into dense text.
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
    private var baseScale = 1f
    private var currentScale = 1f

    private val path = Path()
    private val regionBmp = RectF()
    private var hasRegion = false
    private val pointsBmp = ArrayList<Pair<Float, Float>>()
    private var scaling = false

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

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.pick_region_hint)
    }

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        resetPath()
        if (width > 0 && height > 0) fitBitmap()
        invalidate()
    }

    private fun resetPath() {
        path.reset()
        pointsBmp.clear()
        regionBmp.set(0f, 0f, 0f, 0f)
        hasRegion = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) fitBitmap()
    }

    private fun fitBitmap() {
        val bmp = bitmap ?: return
        baseScale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        currentScale = baseScale
        imageMatrix.reset()
        imageMatrix.postScale(baseScale, baseScale)
        imageMatrix.postTranslate(
            (width - bmp.width * baseScale) / 2f,
            (height - bmp.height * baseScale) / 2f
        )
        imageMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (!hasRegion || pointsBmp.size < 2) return
        path.reset()
        val mapped = floatArrayOf(0f, 0f)
        pointsBmp.forEachIndexed { index, point ->
            mapped[0] = point.first
            mapped[1] = point.second
            imageMatrix.mapPoints(mapped)
            if (index == 0) path.moveTo(mapped[0], mapped[1])
            else path.lineTo(mapped[0], mapped[1])
        }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    private fun toBitmapSpace(vx: Float, vy: Float): Pair<Float, Float> {
        val pts = floatArrayOf(vx, vy)
        inverseMatrix.mapPoints(pts)
        return pts[0] to pts[1]
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        if (event.pointerCount > 1 || scaling) {
            if (!scaling) {
                scaling = true
                resetPath()
            }
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) scaling = false
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetPath()
                val point = toBitmapSpace(event.x, event.y)
                pointsBmp.add(point)
                regionBmp.set(point.first, point.second, point.first, point.second)
                hasRegion = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!hasRegion) return true
                for (i in 0 until event.historySize) {
                    val point = toBitmapSpace(event.getHistoricalX(i), event.getHistoricalY(i))
                    pointsBmp.add(point)
                    regionBmp.union(point.first, point.second)
                }
                val point = toBitmapSpace(event.x, event.y)
                pointsBmp.add(point)
                regionBmp.union(point.first, point.second)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!hasRegion) return true
                val point = toBitmapSpace(event.x, event.y)
                pointsBmp.add(point)
                regionBmp.union(point.first, point.second)
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                announceForAccessibility(context.getString(R.string.region_selected))

                val span = max(regionBmp.width(), regionBmp.height()) * currentScale
                if (span < dp(24f)) {
                    val half = dp(80f) / currentScale
                    regionBmp.set(
                        point.first - half, point.second - half,
                        point.first + half, point.second + half
                    )
                } else {
                    val pad = dp(6f) / currentScale
                    regionBmp.inset(-pad, -pad)
                }
                cropAndReturn()
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

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (currentScale * detector.scaleFactor)
                    .coerceIn(baseScale * 0.8f, baseScale * 10f)
                val factor = target / currentScale
                currentScale = target
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                imageMatrix.invert(inverseMatrix)
                invalidate()
                return true
            }
        })

    private fun cropAndReturn() {
        val bmp = bitmap ?: return
        val left = max(0, min(bmp.width - 1, regionBmp.left.toInt()))
        val top = max(0, min(bmp.height - 1, regionBmp.top.toInt()))
        val right = max(left + 1, min(bmp.width, regionBmp.right.toInt()))
        val bottom = max(top + 1, min(bmp.height, regionBmp.bottom.toInt()))
        val width = right - left
        val height = bottom - top
        if (width < 8 || height < 8) {
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
