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
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Image viewer with explicit Idle / Selected / Adjusting selection states.
 * All word boxes are in bitmap pixel space; [imageMatrix] maps them to view space.
 */
class SelectableOcrView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** [text] is null when the selection was cleared. [anchor] is in view coordinates. */
        fun onSelectionChanged(text: String?, anchor: RectF?)

        /** True only while a handle or sweep drag is actively adjusting the range. */
        fun onSelectionAdjustingChanged(adjusting: Boolean)
    }

    var listener: Listener? = null
    var magnifierEnabled = true
    var markUncertain = true
    var topSafePx = 0
    var bottomSafePx = 0

    private var bitmap: Bitmap? = null
    private var result: OcrEngine.Result? = null

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var fitScale = 1f
    private var currentScale = 1f

    private enum class DragMode { SWEEP, HANDLE_START, HANDLE_END }

    private sealed interface SelectionState {
        data object Idle : SelectionState
        data class Selected(val start: Int, val end: Int) : SelectionState
        data class Adjusting(
            val start: Int,
            val end: Int,
            val mode: DragMode,
            val anchorWord: Int,
            val x: Float,
            val y: Float,
        ) : SelectionState
    }

    private var state: SelectionState = SelectionState.Idle
    private var lastLineTapTime = 0L
    private var lastLineTapWord = -1

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 40; style = Paint.Style.FILL
    }
    private val hintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 90; style = Paint.Style.STROKE; strokeWidth = dp(1.2f)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x663B82F6; style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt(); style = Paint.Style.FILL
    }
    private val handleStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val backgroundPaint = Paint().apply { color = 0xFF101014.toInt() }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val uncertainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFCC80.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }
    private val magnifierBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }

    private val handleRadius get() = dp(7f)
    private val handleTouchRadius get() = dp(26f)
    private val scratchRect = RectF()
    private val lineRect = RectF()
    private val firstRect = RectF()
    private val lastRect = RectF()
    private val magnifierPath = Path()
    private val magnifierMatrix = Matrix()
    private val mappedPoint = FloatArray(2)

    private val range: Pair<Int, Int>?
        get() = when (val s = state) {
            is SelectionState.Selected -> s.start to s.end
            is SelectionState.Adjusting -> s.start to s.end
            SelectionState.Idle -> null
        }

    fun setContent(bmp: Bitmap, ocr: OcrEngine.Result) {
        bitmap = bmp
        result = ocr
        resetSelection(notify = true)
        if (width > 0 && height > 0) resetFit()
        invalidate()
    }

    fun clear() {
        bitmap = null
        result = null
        resetSelection(notify = true)
        invalidate()
    }

    private fun resetSelection(notify: Boolean) {
        val wasAdjusting = state is SelectionState.Adjusting
        state = SelectionState.Idle
        lastLineTapTime = 0L
        lastLineTapWord = -1
        if (wasAdjusting) listener?.onSelectionAdjustingChanged(false)
        if (notify) notifySelection()
    }

    fun selectAll() {
        val r = result ?: return
        if (r.isEmpty) return
        state = SelectionState.Selected(0, r.words.size - 1)
        notifySelection(announce = true)
        invalidate()
    }

    private fun selectLine(index: Int) = selectGroup(index) { a, b -> a.lineId == b.lineId }
    private fun selectParagraph(index: Int) = selectGroup(index) { a, b -> a.blockId == b.blockId }

    private fun selectGroup(index: Int, sameGroup: (OcrEngine.Word, OcrEngine.Word) -> Boolean) {
        val r = result ?: return
        val anchorWord = r.words[index]
        var start = index
        var end = index
        while (start > 0 && sameGroup(r.words[start - 1], anchorWord)) start--
        while (end < r.words.size - 1 && sameGroup(r.words[end + 1], anchorWord)) end++
        state = SelectionState.Selected(start, end)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        notifySelection(announce = true)
        invalidate()
    }

    fun clearSelection() {
        if (state is SelectionState.Idle) return
        resetSelection(notify = true)
        invalidate()
    }

    fun hasSelection() = state !is SelectionState.Idle
    val isAdjusting: Boolean get() = state is SelectionState.Adjusting

    val selectedText: String?
        get() {
            val r = result ?: return null
            val (start, end) = range ?: return null
            return r.textOf(start, end)
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) resetFit()
        notifySelection()
    }

    private fun resetFit() {
        val bmp = bitmap ?: return
        fitScale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        currentScale = fitScale
        imageMatrix.reset()
        imageMatrix.postScale(fitScale, fitScale)
        imageMatrix.postTranslate(
            (width - bmp.width * fitScale) / 2f,
            (height - bmp.height * fitScale) / 2f
        )
        syncInverse()
    }

    private fun syncInverse() = imageMatrix.invert(inverseMatrix)

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
        val r = result ?: return

        val selectedRange = range
        if (selectedRange == null) {
            for (box in r.lineBoxes) {
                scratchRect.set(box)
                imageMatrix.mapRect(scratchRect)
                scratchRect.inset(-dp(2f), -dp(2f))
                val radius = scratchRect.height() * 0.25f
                canvas.drawRoundRect(scratchRect, radius, radius, hintPaint)
                canvas.drawRoundRect(scratchRect, radius, radius, hintStroke)
            }
            if (markUncertain) {
                r.words.forEach { word ->
                    if (word.confidence in 0.01f..0.6f) {
                        scratchRect.set(word.box)
                        imageMatrix.mapRect(scratchRect)
                        val y = scratchRect.bottom + dp(1f)
                        canvas.drawLine(scratchRect.left, y, scratchRect.right, y, uncertainPaint)
                    }
                }
            }
            return
        }

        populateHandleRects(selectedRange.first, selectedRange.second)
        var i = selectedRange.first
        while (i <= selectedRange.second) {
            val lineId = r.words[i].lineId
            lineRect.set(r.words[i].box)
            var j = i
            while (j + 1 <= selectedRange.second && r.words[j + 1].lineId == lineId) {
                j++
                lineRect.union(r.words[j].box)
            }
            imageMatrix.mapRect(lineRect)
            lineRect.inset(-dp(3f), -dp(3f))
            canvas.drawRoundRect(lineRect, dp(4f), dp(4f), selectionPaint)
            i = j + 1
        }

        canvas.drawLine(firstRect.left, firstRect.top, firstRect.left, firstRect.bottom, handleStemPaint)
        canvas.drawCircle(firstRect.left, firstRect.top - handleRadius * 0.7f, handleRadius, handlePaint)
        canvas.drawLine(lastRect.right, lastRect.top, lastRect.right, lastRect.bottom, handleStemPaint)
        canvas.drawCircle(lastRect.right, lastRect.bottom + handleRadius * 0.7f, handleRadius, handlePaint)

        val adjusting = state as? SelectionState.Adjusting
        if (magnifierEnabled && adjusting != null) drawMagnifier(canvas, bmp, adjusting)
    }

    private fun populateHandleRects(start: Int, end: Int) {
        val r = result ?: return
        firstRect.set(r.words[start].box)
        lastRect.set(r.words[end].box)
        var i = start
        while (i <= end) {
            val lineId = r.words[i].lineId
            lineRect.set(r.words[i].box)
            var j = i
            while (j + 1 <= end && r.words[j + 1].lineId == lineId) {
                j++
                lineRect.union(r.words[j].box)
            }
            if (i == start) firstRect.set(lineRect)
            lastRect.set(lineRect)
            i = j + 1
        }
        imageMatrix.mapRect(firstRect)
        imageMatrix.mapRect(lastRect)
        firstRect.inset(-dp(3f), -dp(3f))
        lastRect.inset(-dp(3f), -dp(3f))
    }

    private fun drawMagnifier(canvas: Canvas, bmp: Bitmap, adjusting: SelectionState.Adjusting) {
        val radius = dp(48f)
        val minY = topSafePx + radius
        val maxY = (height - bottomSafePx - radius).coerceAtLeast(minY)
        val cx = adjusting.x.coerceIn(radius, (width - radius).coerceAtLeast(radius))
        val cy = (adjusting.y - radius * 1.5f).coerceIn(minY, maxY)
        magnifierPath.reset()
        magnifierPath.addCircle(cx, cy, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(magnifierPath)
        canvas.drawCircle(cx, cy, radius, backgroundPaint)
        toBitmapSpace(adjusting.x, adjusting.y, mappedPoint)
        val zoom = max(currentScale * 1.8f, fitScale * 2.5f)
        magnifierMatrix.reset()
        magnifierMatrix.postScale(zoom, zoom)
        magnifierMatrix.postTranslate(cx - mappedPoint[0] * zoom, cy - mappedPoint[1] * zoom)
        canvas.drawBitmap(bmp, magnifierMatrix, bitmapPaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, magnifierBorderPaint)
    }

    // --------------------------------------------------------------- gestures

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (state is SelectionState.Adjusting) return true
                val target = (currentScale * detector.scaleFactor).coerceIn(fitScale * 0.8f, fitScale * 12f)
                val factor = target / currentScale
                currentScale = target
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampTranslation()
                syncInverse()
                invalidate()
                notifySelection()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (state is SelectionState.Adjusting || scaleDetector.isInProgress) return false
                imageMatrix.postTranslate(-dx, -dy)
                clampTranslation()
                syncInverse()
                invalidate()
                notifySelection()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val hit = wordAt(e.x, e.y, forgiving = true)
                val now = SystemClock.uptimeMillis()
                if (hit != -1 && hit == lastLineTapWord && now - lastLineTapTime < 700L) {
                    selectParagraph(hit)
                    lastLineTapTime = 0L
                    return true
                }
                if (hit != -1) {
                    state = SelectionState.Selected(hit, hit)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    notifySelection(announce = true)
                } else {
                    clearSelection()
                }
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val hit = wordAt(e.x, e.y, forgiving = true)
                if (hit != -1) {
                    selectLine(hit)
                    lastLineTapTime = SystemClock.uptimeMillis()
                    lastLineTapWord = hit
                    return true
                }
                val target = if (currentScale > fitScale * 1.4f) fitScale else fitScale * 2.5f
                val factor = target / currentScale
                currentScale = target
                imageMatrix.postScale(factor, factor, e.x, e.y)
                clampTranslation()
                syncInverse()
                invalidate()
                notifySelection()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val hit = wordAt(e.x, e.y, forgiving = true)
                if (hit == -1) return
                state = SelectionState.Adjusting(hit, hit, DragMode.SWEEP, hit, e.x, e.y)
                listener?.onSelectionAdjustingChanged(true)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                notifySelection()
                invalidate()
            }
        })

    init {
        gestureDetector.setIsLongpressEnabled(true)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.recognized_text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state is SelectionState.Adjusting) return true
                val mode = when {
                    hitsHandle(event.x, event.y, start = true) -> DragMode.HANDLE_START
                    hitsHandle(event.x, event.y, start = false) -> DragMode.HANDLE_END
                    else -> null
                }
                val current = range
                if (mode != null && current != null) {
                    state = SelectionState.Adjusting(
                        current.first, current.second, mode,
                        if (mode == DragMode.HANDLE_START) current.first else current.second,
                        event.x, event.y
                    )
                    parent?.requestDisallowInterceptTouchEvent(true)
                    listener?.onSelectionAdjustingChanged(true)
                    notifySelection()
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val adjusting = state as? SelectionState.Adjusting
                if (adjusting != null && event.pointerCount == 1) {
                    onSelectionDrag(adjusting, event.x, event.y)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val adjusting = state as? SelectionState.Adjusting
                if (adjusting != null) {
                    state = SelectionState.Selected(adjusting.start, adjusting.end)
                    listener?.onSelectionAdjustingChanged(false)
                    notifySelection(announce = true)
                    invalidate()
                    return true
                }
            }
        }
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    private fun onSelectionDrag(old: SelectionState.Adjusting, x: Float, y: Float) {
        val nearest = nearestWord(x, y)
        if (nearest == -1) {
            state = old.copy(x = x, y = y)
            invalidate()
            return
        }
        var start = old.start
        var end = old.end
        var mode = old.mode
        when (old.mode) {
            DragMode.SWEEP -> {
                start = min(old.anchorWord, nearest)
                end = max(old.anchorWord, nearest)
            }
            DragMode.HANDLE_START -> {
                if (nearest <= end) start = nearest else {
                    start = end
                    end = nearest
                    mode = DragMode.HANDLE_END
                }
            }
            DragMode.HANDLE_END -> {
                if (nearest >= start) end = nearest else {
                    end = start
                    start = nearest
                    mode = DragMode.HANDLE_START
                }
            }
        }
        val changed = start != old.start || end != old.end
        state = SelectionState.Adjusting(start, end, mode, old.anchorWord, x, y)
        if (changed) {
            performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE
                else HapticFeedbackConstants.CONTEXT_CLICK
            )
            notifySelection()
        }
        // Always invalidate: the loupe follows the finger even inside one word.
        invalidate()
    }

    // ------------------------------------------------------------- hit tests

    private fun toBitmapSpace(x: Float, y: Float, out: FloatArray) {
        out[0] = x
        out[1] = y
        inverseMatrix.mapPoints(out)
    }

    private fun wordAt(vx: Float, vy: Float, forgiving: Boolean): Int {
        val r = result ?: return -1
        toBitmapSpace(vx, vy, mappedPoint)
        val x = mappedPoint[0]
        val y = mappedPoint[1]
        val slop = if (forgiving) dp(6f) / currentScale else 0f
        r.words.forEachIndexed { i, w ->
            if (x >= w.box.left - slop && x <= w.box.right + slop &&
                y >= w.box.top - slop && y <= w.box.bottom + slop
            ) return i
        }
        return -1
    }

    private fun nearestWord(vx: Float, vy: Float): Int {
        val r = result ?: return -1
        toBitmapSpace(vx, vy, mappedPoint)
        val x = mappedPoint[0]
        val y = mappedPoint[1]
        var best = -1
        var bestDist = Float.MAX_VALUE
        r.words.forEachIndexed { i, w ->
            val dx = max(0f, max(w.box.left - x, x - w.box.right))
            val dy = max(0f, max(w.box.top - y, y - w.box.bottom))
            val d = dx * dx + dy * dy * 9f
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun hitsHandle(x: Float, y: Float, start: Boolean): Boolean {
        val (first, last) = range ?: return false
        populateHandleRects(first, last)
        val rect = if (start) firstRect else lastRect
        val hx = if (start) rect.left else rect.right
        val hy = if (start) rect.top - handleRadius * 0.7f else rect.bottom + handleRadius * 0.7f
        return abs(x - hx) < handleTouchRadius && abs(y - hy) < handleTouchRadius
    }

    private fun clampTranslation() {
        val bmp = bitmap ?: return
        scratchRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(scratchRect)
        var dx = 0f
        var dy = 0f
        if (scratchRect.width() <= width) {
            dx = (width - scratchRect.width()) / 2f - scratchRect.left
        } else {
            if (scratchRect.left > 0) dx = -scratchRect.left
            if (scratchRect.right < width) dx = width - scratchRect.right
        }
        if (scratchRect.height() <= height) {
            dy = (height - scratchRect.height()) / 2f - scratchRect.top
        } else {
            if (scratchRect.top > 0) dy = -scratchRect.top
            if (scratchRect.bottom < height) dy = height - scratchRect.bottom
        }
        imageMatrix.postTranslate(dx, dy)
    }

    private fun notifySelection(announce: Boolean = false) {
        val r = result
        val selectedRange = range
        if (r == null || selectedRange == null) {
            contentDescription = context.getString(R.string.recognized_text)
            listener?.onSelectionChanged(null, null)
            return
        }
        firstRect.set(r.words[selectedRange.first].box)
        for (i in selectedRange.first..selectedRange.second) firstRect.union(r.words[i].box)
        imageMatrix.mapRect(firstRect)
        val text = r.textOf(selectedRange.first, selectedRange.second)
        contentDescription = context.getString(R.string.selection_announcement, text)
        if (announce) announceForAccessibility(contentDescription)
        listener?.onSelectionChanged(text, firstRect)
    }

    // ---------------------------------------------------------- accessibility

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = wordNodeProvider

    private val wordNodeProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST_VIEW_ID) {
                return super.createAccessibilityNodeInfo(virtualViewId)?.apply {
                    contentDescription = context.getString(R.string.recognized_text)
                }
            }
            val r = result ?: return null
            if (virtualViewId !in r.words.indices) return null
            val word = r.words[virtualViewId]
            scratchRect.set(word.box)
            imageMatrix.mapRect(scratchRect)
            val bounds = Rect(
                scratchRect.left.toInt(), scratchRect.top.toInt(),
                scratchRect.right.toInt(), scratchRect.bottom.toInt()
            )
            return AccessibilityNodeInfo.obtain(this@SelectableOcrView, virtualViewId).apply {
                text = word.text
                contentDescription = context.getString(
                    R.string.word_announcement, virtualViewId + 1, word.text
                )
                setBoundsInParent(bounds)
                isEnabled = true
                isVisibleToUser = true
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
            }
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId !in (result?.words?.indices ?: emptyList())) return false
            when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    state = SelectionState.Selected(virtualViewId, virtualViewId)
                    notifySelection(announce = true)
                    invalidate()
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
                    return true
                }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                    selectLine(virtualViewId)
                    return true
                }
            }
            return super.performAction(virtualViewId, action, arguments)
        }
    }
}
