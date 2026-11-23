package com.anatej.c2s

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlin.math.abs

/**
 * Hands a freshly captured screen bitmap to the next screen without going
 * through disk.
 */
object CaptureHolder {
    private var bitmap: Bitmap? = null

    @Synchronized
    fun put(b: Bitmap) {
        bitmap = b
    }

    @Synchronized
    fun take(): Bitmap? {
        val b = bitmap
        bitmap = null
        return b
    }
}

/**
 * Screenshot-only accessibility service. It reads no window content. A draggable
 * bubble and two bottom-corner touch strips are drawn as accessibility overlays,
 * so the picker can be started over any app without extra permissions.
 */
class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        const val PREFS = "anatej_c2s_prefs"
        const val PREF_BUBBLE = "floating_picker_enabled"
        const val PREF_EDGE_SWIPE = "corner_swipe_enabled"
        const val PREF_EDGE_CORNER = "corner_swipe_side"
        const val PREF_EDGE_WIDTH_DP = "corner_box_width_dp"
        const val PREF_EDGE_HEIGHT_DP = "corner_box_height_dp"
        const val PREF_EDGE_INSET_DP = "corner_box_inset_dp"
        const val PREF_EDGE_LIFT_DP = "corner_box_lift_dp"
        const val CORNER_LEFT = "left"
        const val CORNER_RIGHT = "right"
        const val CORNER_BOTH = "both"
        const val PREF_SIZE_DP = "bubble_size_dp"
        const val PREF_COLOR = "bubble_color"
        const val PREF_ALPHA = "bubble_alpha"
        const val PREF_SNAP = "bubble_snap_to_edge"

        val BUBBLE_COLORS = intArrayOf(
            0xFF3B82F6.toInt(),
            0xFFEF4444.toInt(),
            0xFF22C55E.toInt(),
            0xFFA855F7.toInt(),
            0xFFF97316.toInt()
        )

        private const val SHADE_DISMISS_DELAY_MS = 800L
        private const val BUBBLE_CAPTURE_DELAY_MS = 200L
        private const val DEFAULT_SIZE_DP = 56
        private const val DEFAULT_ALPHA = 100
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private val edgeViews = ArrayList<View>()

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Build.VERSION.SDK_INT >= 31) refreshOverlays()
    }

    override fun onDestroy() {
        removeEdgeStrips()
        hideBubble()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** Rebuild the overlays after a settings change. Call on the main thread. */
    @RequiresApi(31)
    fun refreshOverlays() {
        val prefs = prefs()
        removeEdgeStrips()
        hideBubble()
        if (prefs.getBoolean(PREF_BUBBLE, false)) showBubble()
        if (prefs.getBoolean(PREF_EDGE_SWIPE, false)) showEdgeStrips()
    }

    @RequiresApi(31)
    fun setBubbleVisible(visible: Boolean) {
        prefs().edit().putBoolean(PREF_BUBBLE, visible).apply()
        refreshOverlays()
    }

    @RequiresApi(31)
    private fun showBubble() {
        if (bubbleView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @SuppressLint("InflateParams")
        val view = LayoutInflater.from(this).inflate(R.layout.view_bubble, null)
        val params = overlayParams(Gravity.TOP or Gravity.START).apply {
            x = 24
            y = resources.displayMetrics.heightPixels / 3
        }
        styleBubble(view, params)

        val slop = 8f * resources.displayMetrics.density
        var startX = 0f
        var startY = 0f
        var baseX = params.x
        var baseY = params.y
        var moved = false

        view.setOnClickListener { onBubbleTapped() }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    baseX = params.x
                    baseY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = (baseX + dx).toInt()
                        params.y = (baseY + dy).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved && prefs().getBoolean(PREF_SNAP, false)) {
                        snapToEdge(params)
                        runCatching { wm.updateViewLayout(view, params) }
                    } else if (!moved) {
                        v.performClick()
                    }
                    true
                }

                else -> true
            }
        }

        runCatching { wm.addView(view, params) }
        bubbleView = view
        bubbleParams = params
        windowManager = wm
    }

    @RequiresApi(31)
    private fun styleBubble(view: View, params: WindowManager.LayoutParams) {
        val prefs = prefs()
        val size = (prefs.getInt(PREF_SIZE_DP, DEFAULT_SIZE_DP).coerceIn(40, 96) *
            resources.displayMetrics.density).toInt()
        val color = prefs.getInt(
            PREF_COLOR, BUBBLE_COLORS[0]
        ).takeIf { it in BUBBLE_COLORS } ?: BUBBLE_COLORS[0]
        val alpha = prefs.getInt(PREF_ALPHA, DEFAULT_ALPHA).coerceIn(30, 100)

        params.width = size
        params.height = size
        view.alpha = alpha / 100f
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        view.background = drawable
        view.setPadding(
            (size * 0.25f).toInt(), (size * 0.25f).toInt(),
            (size * 0.25f).toInt(), (size * 0.25f).toInt()
        )
        view.layoutParams?.let { lp ->
            lp.width = size
            lp.height = size
        }
    }

    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val width = params.width.takeIf { it > 0 } ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val leftDistance = params.x + width / 2
        val rightDistance = screenWidth - (params.x + width / 2)
        params.x = if (leftDistance < rightDistance) 0 else screenWidth - width
    }

    private fun overlayParams(gravity: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

    private fun hideBubble() {
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView = null
        bubbleParams = null
        windowManager = null
    }

    /** Bottom-corner swipe strips. A quick upward swipe starts the picker. */
    @RequiresApi(31)
    private fun showEdgeStrips() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = prefs()
        val density = resources.displayMetrics.density
        val width = (prefs.getInt(PREF_EDGE_WIDTH_DP, 72).coerceIn(40, 140) * density).toInt()
        val height = (prefs.getInt(PREF_EDGE_HEIGHT_DP, 160).coerceIn(100, 280) * density).toInt()
        val inset = (prefs.getInt(PREF_EDGE_INSET_DP, 0).coerceIn(0, 160) * density).toInt()
        val lift = (prefs.getInt(PREF_EDGE_LIFT_DP, 8).coerceIn(0, 200) * density).toInt()
        val threshold = (90f * resources.displayMetrics.density)

        val gravities = when (prefs.getString(PREF_EDGE_CORNER, CORNER_BOTH)) {
            CORNER_LEFT -> intArrayOf(Gravity.BOTTOM or Gravity.START)
            CORNER_RIGHT -> intArrayOf(Gravity.BOTTOM or Gravity.END)
            else -> intArrayOf(Gravity.BOTTOM or Gravity.START, Gravity.BOTTOM or Gravity.END)
        }
        for (gravity in gravities) {
            val strip = View(this)
            strip.contentDescription = getString(R.string.corner_swipe_hint)
            val params = overlayParams(gravity).apply {
                this.width = width
                this.height = height
                this.x = inset
                y = -lift
            }
            var startX = 0f
            var startY = 0f
            var startTime = 0L
            var fired = false
            strip.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        startTime = System.currentTimeMillis()
                        fired = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val upward = startY - event.rawY
                        val sideways = abs(event.rawX - startX)
                        val quick = System.currentTimeMillis() - startTime < 800L
                        if (!fired && upward > threshold && sideways < threshold && quick) {
                            fired = true
                            onBubbleTapped()
                        }
                        true
                    }

                    else -> true
                }
            }
            runCatching { wm.addView(strip, params) }
            edgeViews.add(strip)
        }
    }

    private fun removeEdgeStrips() {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        edgeViews.forEach { runCatching { wm.removeView(it) } }
        edgeViews.clear()
    }

    /** Remove the bubble first so it cannot appear in the captured frame. */
    @RequiresApi(31)
    private fun onBubbleTapped() {
        hideBubble()
        handler.postDelayed({ captureForSelection() }, BUBBLE_CAPTURE_DELAY_MS)
    }

    /** Quick-settings tile path: open the existing full-screen viewer. */
    @RequiresApi(31)
    fun captureScreenAndOpen() {
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        handler.postDelayed({ doCapture() }, SHADE_DISMISS_DELAY_MS)
    }

    @RequiresApi(31)
    private fun doCapture() {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val software = toSoftwareBitmap(result)
                    result.hardwareBuffer.close()
                    if (software != null) {
                        CaptureHolder.put(software)
                        openMain(captured = true)
                    } else {
                        fallback()
                    }
                }

                override fun onFailure(errorCode: Int) = fallback()
            }
        )
    }

    /** Bubble or corner swipe: open the region picker for the captured screen. */
    @RequiresApi(31)
    private fun captureForSelection() {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val software = toSoftwareBitmap(result)
                    result.hardwareBuffer.close()
                    if (software != null) {
                        CaptureHolder.put(software)
                        startActivity(Intent(
                            this@CaptureAccessibilityService,
                            SelectActivity::class.java
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        showCaptureError(R.string.capture_error)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    val message = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> R.string.capture_busy
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> R.string.capture_secure
                        else -> R.string.capture_error
                    }
                    showCaptureError(message)
                }
            }
        )
    }

    @RequiresApi(31)
    private fun toSoftwareBitmap(result: ScreenshotResult): Bitmap? =
        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            ?.copy(Bitmap.Config.ARGB_8888, false)

    private fun showCaptureError(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= 31 && prefs().getBoolean(PREF_BUBBLE, false)) {
            showBubble()
        }
    }

    private fun fallback() {
        Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show()
        openMain(captured = false)
    }

    private fun openMain(captured: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(
                if (captured) MainActivity.EXTRA_CAPTURED_SCREEN
                else MainActivity.EXTRA_LATEST_SCREENSHOT,
                true
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }
}
