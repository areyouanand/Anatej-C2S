package com.anatej.c2s

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
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

        const val PREF_HAPTIC = "capture_haptic"
        const val PREF_SOUND = "capture_sound"
        const val PREF_THEME = "theme_mode"
        const val PREF_OCR_SCRIPT = "ocr_script"
        const val PREF_BARCODE = "scan_barcodes"
        const val PREF_CONFIDENCE = "mark_uncertain_words"
        const val PREF_MAGNIFIER = "selection_magnifier"
        const val PREF_HIDE_KEYBOARD = "hide_on_keyboard"
        const val PREF_HIDE_CALLS = "hide_on_calls"
        const val PREF_CLIPBOARD_HISTORY = "clipboard_history_json"
        const val PREF_ONBOARDING_DONE = "onboarding_done"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val SCRIPT_AUTO = "auto"
        const val SCRIPT_LATIN = "latin"
        const val SCRIPT_CHINESE = "chinese"
        const val SCRIPT_JAPANESE = "japanese"
        const val SCRIPT_KOREAN = "korean"
        const val SCRIPT_DEVANAGARI = "devanagari"

        /** Preference keys grouped by stored type, used by settings export/import. */
        val BOOLEAN_KEYS = setOf(
            PREF_BUBBLE, PREF_EDGE_SWIPE, PREF_SNAP, PREF_HAPTIC, PREF_SOUND,
            PREF_BARCODE, PREF_CONFIDENCE, PREF_MAGNIFIER, PREF_HIDE_KEYBOARD,
            PREF_HIDE_CALLS
        )
        val INT_KEYS = setOf(
            PREF_SIZE_DP, PREF_ALPHA, PREF_COLOR, PREF_EDGE_WIDTH_DP,
            PREF_EDGE_HEIGHT_DP, PREF_EDGE_INSET_DP, PREF_EDGE_LIFT_DP
        )
        // Clipboard history is deliberately excluded: exported settings may be
        // shared, and copied text should never travel with them.
        val STRING_KEYS = setOf(
            PREF_THEME, PREF_OCR_SCRIPT, PREF_EDGE_CORNER
        )

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

    private var keyboardVisible = false
    private var callActive = false
    private var telephonyCallback: TelephonyCallback? = null

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Build.VERSION.SDK_INT >= 31) {
            refreshOverlays()
            setupCallWatch()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 31) stopCallWatch()
        removeEdgeStrips()
        hideBubble()
        instance = null
        super.onDestroy()
    }

    /**
     * The keyboard is detected from window-state accessibility events, so no
     * extra permission is needed. The picker is only hidden, never destroyed.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (Build.VERSION.SDK_INT < 31 || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val enabled = prefs().getBoolean(PREF_HIDE_KEYBOARD, true)
        val source = "${event.className?.toString().orEmpty()} " +
            event.packageName?.toString().orEmpty()
        val isIme = enabled && source.contains("inputmethod", ignoreCase = true)
        if (isIme != keyboardVisible) {
            keyboardVisible = isIme
            applySuppression()
        }
    }

    override fun onInterrupt() = Unit

    private fun applySuppression() {
        val hide = keyboardVisible || callActive
        val visibility = if (hide) View.GONE else View.VISIBLE
        bubbleView?.visibility = visibility
        edgeViews.forEach { it.visibility = visibility }
    }

    /** Re-reads the call-hiding preference and (re)registers the listener. */
    @RequiresApi(31)
    fun refreshCallWatch() {
        stopCallWatch()
        setupCallWatch()
    }

    @RequiresApi(31)
    private fun setupCallWatch() {
        if (!prefs().getBoolean(PREF_HIDE_CALLS, false)) return
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val active = state != TelephonyManager.CALL_STATE_IDLE
                if (active != callActive) {
                    callActive = active
                    applySuppression()
                }
            }
        }
        runCatching { manager.registerTelephonyCallback(mainExecutor, callback) }
        telephonyCallback = callback
    }

    @RequiresApi(31)
    private fun stopCallWatch() {
        telephonyCallback?.let { callback ->
            val manager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
            runCatching { manager?.unregisterTelephonyCallback(callback) }
        }
        telephonyCallback = null
        if (callActive) {
            callActive = false
            applySuppression()
        }
    }

    /** Rebuild the overlays after a settings change. Call on the main thread. */
    @RequiresApi(31)
    fun refreshOverlays() {
        val prefs = prefs()
        removeEdgeStrips()
        hideBubble()
        if (prefs.getBoolean(PREF_BUBBLE, false)) showBubble()
        if (prefs.getBoolean(PREF_EDGE_SWIPE, false)) showEdgeStrips()
        applySuppression()
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
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.contentDescription = getString(R.string.bubble_content_description)
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
            strip.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            strip.isFocusable = true
            // TalkBack users can activate the strip with a double-tap.
            strip.setOnClickListener { onBubbleTapped() }
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
        Feedback.capture(this, prefs())
        hideBubble()
        handler.postDelayed({ captureForSelection() }, BUBBLE_CAPTURE_DELAY_MS)
    }

    /** Quick-settings tile path: open the existing full-screen viewer. */
    @RequiresApi(31)
    fun captureScreenAndOpen() {
        Feedback.capture(this, prefs())
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

                override fun onFailure(errorCode: Int) {
                    if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                        showCaptureError(R.string.capture_secure)
                    } else if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        showCaptureError(R.string.capture_busy)
                    } else {
                        fallback()
                    }
                }
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
        // Restore both triggers, not just the bubble: a failed swipe should
        // leave the corner strips usable as well.
        if (Build.VERSION.SDK_INT >= 31) refreshOverlays()
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
