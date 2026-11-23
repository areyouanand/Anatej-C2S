package com.anatej.c2s

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.anatej.c2s.CaptureAccessibilityService.Companion.CORNER_BOTH
import com.anatej.c2s.CaptureAccessibilityService.Companion.CORNER_LEFT
import com.anatej.c2s.CaptureAccessibilityService.Companion.CORNER_RIGHT
import kotlin.math.min

/**
 * Draws the phone screen and the current corner touch areas so size and
 * position changes can be seen while they are being adjusted.
 */
class CornerPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var boxWidthDp = 72
    private var boxHeightDp = 160
    private var insetDp = 0
    private var liftDp = 8
    private var corner = CORNER_BOTH

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = v * density

    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C20.toInt()
        style = Paint.Style.FILL
    }
    private val screenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1)
    }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x663B82F6
        style = Paint.Style.FILL
    }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CC0FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2)
    }

    fun update(
        screenW: Int,
        screenH: Int,
        widthDp: Int,
        heightDp: Int,
        inset: Int,
        lift: Int,
        selectedCorner: String,
    ) {
        screenWidth = screenW
        screenHeight = screenH
        boxWidthDp = widthDp
        boxHeightDp = heightDp
        insetDp = inset
        liftDp = lift
        corner = selectedCorner
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val scale = min(
            (width - dp(32)) / screenWidth.toFloat(),
            (height - dp(32)) / screenHeight.toFloat()
        ).coerceAtLeast(0.01f)
        val sw = screenWidth * scale
        val sh = screenHeight * scale
        val left = (width - sw) / 2f
        val top = (height - sh) / 2f
        val screen = RectF(left, top, left + sw, top + sh)
        val radius = dp(12)
        canvas.drawRoundRect(screen, radius, radius, screenPaint)
        canvas.drawRoundRect(screen, radius, radius, screenStroke)

        val bw = dp(boxWidthDp) * scale
        val bh = dp(boxHeightDp) * scale
        val inset = dp(insetDp) * scale
        val lift = dp(liftDp) * scale
        val boxTop = screen.bottom - lift - bh
        val drawLeft = corner == CORNER_LEFT || corner == CORNER_BOTH
        val drawRight = corner == CORNER_RIGHT || corner == CORNER_BOTH

        if (drawLeft) {
            val box = RectF(screen.left + inset, boxTop, screen.left + inset + bw, boxTop + bh)
            drawBox(canvas, box)
        }
        if (drawRight) {
            val box = RectF(screen.right - inset - bw, boxTop, screen.right - inset, boxTop + bh)
            drawBox(canvas, box)
        }
    }

    private fun drawBox(canvas: Canvas, box: RectF) {
        val clamped = RectF(
            maxOf(screenBounds.left, box.left),
            maxOf(screenBounds.top, box.top),
            minOf(screenBounds.right, box.right),
            minOf(screenBounds.bottom, box.bottom)
        )
        val radius = dp(6)
        canvas.drawRoundRect(clamped, radius, radius, boxFill)
        canvas.drawRoundRect(clamped, radius, radius, boxStroke)
    }

    private val screenBounds: RectF
        get() {
            val scale = min(
                (width - dp(32)) / screenWidth.toFloat(),
                (height - dp(32)) / screenHeight.toFloat()
            ).coerceAtLeast(0.01f)
            val sw = screenWidth * scale
            val sh = screenHeight * scale
            val left = (width - sw) / 2f
            val top = (height - sh) / 2f
            return RectF(left, top, left + sw, top + sh)
        }
}
