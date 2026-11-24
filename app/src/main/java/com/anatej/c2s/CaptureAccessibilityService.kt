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
import android.os.SystemClock
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/** Single-slot, request-checked bitmap handoff. No disk persistence. */
object CaptureHolder {
    private data class Item(val requestId: Long, val bitmap: Bitmap)
    private var item: Item? = null

    @Synchronized
    fun put(requestId: Long, bitmap: Bitmap) {
        val previous = item
        item = Item(requestId, bitmap)
        if (previous != null && previous.bitmap !== bitmap) previous.bitmap.recycle()
    }

    @Synchronized
    fun take(requestId: Long): Bitmap? {
        val current = item ?: return null
        if (current.requestId != requestId) return null
        item = null
        return current.bitmap
    }

    @Synchronized
    fun clear(requestId: Long) {
        val current = item ?: return
        if (current.requestId == requestId) {
            current.bitmap.recycle()
            item = null
        }
    }

    @Synchronized
    fun clearAll() {
        item?.bitmap?.recycle()
        item = null
    }
}

/** Screenshot-only accessibility service with explicit capture and overlay state. */
class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        const val PREFS = "anatej_c2s_prefs"
        const val PRIVATE_PREFS = "anatej_c2s_private_prefs"
        const val PREF_BUBBLE = "floating_picker_enabled"
        const val PREF_BUBBLE_X = "floating_picker_x"
        const val PREF_BUBBLE_Y = "floating_picker_y"
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
        const val PREF_HISTORY_ENABLED = "clipboard_history_enabled"
        const val PREF_HISTORY_EXPIRY = "clipboard_history_expiry"
        const val PREF_HISTORY_LOCK = "clipboard_history_lock"
        const val PREF_ONBOARDING_DONE = "onboarding_done"

        const val HISTORY_EXPIRY_HOUR = "hour"
        const val HISTORY_EXPIRY_DAY = "day"
        const val HISTORY_EXPIRY_WEEK = "week"
        const val HISTORY_EXPIRY_NEVER = "never"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val SCRIPT_AUTO = "auto"
        const val SCRIPT_LATIN = "latin"
        const val SCRIPT_CHINESE = "chinese"
        const val SCRIPT_JAPANESE = "japanese"
        const val SCRIPT_KOREAN = "korean"
        const val SCRIPT_DEVANAGARI = "devanagari"

        val BOOLEAN_KEYS = setOf(
            PREF_BUBBLE, PREF_EDGE_SWIPE, PREF_SNAP, PREF_HAPTIC, PREF_SOUND,
            PREF_BARCODE, PREF_CONFIDENCE, PREF_MAGNIFIER, PREF_HIDE_KEYBOARD,
            PREF_HIDE_CALLS, PREF_HISTORY_ENABLED, PREF_HISTORY_LOCK
        )
        val INT_KEYS = setOf(
            PREF_SIZE_DP, PREF_ALPHA, PREF_COLOR, PREF_EDGE_WIDTH_DP,
            PREF_EDGE_HEIGHT_DP, PREF_EDGE_INSET_DP, PREF_EDGE_LIFT_DP,
            PREF_BUBBLE_X, PREF_BUBBLE_Y
        )
        val STRING_KEYS = setOf(
            PREF_THEME, PREF_OCR_SCRIPT, PREF_EDGE_CORNER, PREF_HISTORY_EXPIRY
        )

        val BUBBLE_COLORS = intArrayOf(
            0xFF3B82F6.toInt(), 0xFFEF4444.toInt(), 0xFF22C55E.toInt(),
            0xFFA855F7.toInt(), 0xFFF97316.toInt()
        )

        private const val SHADE_DISMISS_DELAY_MS = 800L
        private const val BUBBLE_CAPTURE_DELAY_MS = 200L
        private const val CAPTURE_TIMEOUT_MS = 60_000L
        private const val OVERLAY_REFRESH_DELAY_MS = 80L
        private const val DEFAULT_SIZE_DP = 56
        private const val DEFAULT_ALPHA = 100
    }

    private enum class CaptureState { IDLE, SCHEDULED, CAPTURING, AWAITING_SELECTION, READING }
    private enum class CallWatchState { DISABLED, UNSUPPORTED, NO_PERMISSION, IDLE, ACTIVE, FAILED }

    private data class OverlayConfig(
        val bubble: Boolean,
        val edge: Boolean,
        val signature: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var edgeViews = ArrayList<View>()
    private var overlaySignature: String? = null
    private var overlayRefreshRunnable: Runnable? = null
    private var captureTimeoutRunnable: Runnable? = null

    private var captureState = CaptureState.IDLE
    private var captureRequestId = 0L
    private var captureForSelection = false
    private var destroyed = false

    private var keyboardVisible = false
    private var callWatchState = CallWatchState.DISABLED
    private var telephonyCallback: TelephonyCallback? = null

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Build.VERSION.SDK_INT >= 31) {
            refreshOverlays()
            refreshKeyboardWatch()
            refreshCallWatch()
        }
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        captureExecutor.shutdownNow()
        if (Build.VERSION.SDK_INT >= 31) {
            stopCallWatch()
        }
        removeEdgeStrips()
        hideBubble()
        CaptureHolder.clearAll()
        captureState = CaptureState.IDLE
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (Build.VERSION.SDK_INT < 31 || event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val editable = event.source?.isEditable == true ||
                event.className?.toString().orEmpty().contains("EditText", ignoreCase = true)
            if (prefs().getBoolean(PREF_HIDE_KEYBOARD, true)) {
                keyboardVisible = editable
                applySuppression()
            }
            event.source?.recycle()
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            updateKeyboardVisibility()
        }
    }

    override fun onInterrupt() = Unit

    // ------------------------------------------------------------ overlays

    private fun overlayConfig(): OverlayConfig {
        val p = prefs()
        val bubble = p.getBoolean(PREF_BUBBLE, false)
        val edge = p.getBoolean(PREF_EDGE_SWIPE, false)
        val signature = listOf(
            bubble, edge,
            p.getInt(PREF_SIZE_DP, DEFAULT_SIZE_DP),
            p.getInt(PREF_COLOR, BUBBLE_COLORS[0]),
            p.getInt(PREF_ALPHA, DEFAULT_ALPHA),
            p.getBoolean(PREF_SNAP, false),
            p.getString(PREF_EDGE_CORNER, CORNER_BOTH),
            p.getInt(PREF_EDGE_WIDTH_DP, 72),
            p.getInt(PREF_EDGE_HEIGHT_DP, 160),
            p.getInt(PREF_EDGE_INSET_DP, 0),
            p.getInt(PREF_EDGE_LIFT_DP, 8),
        ).joinToString("|")
        return OverlayConfig(bubble, edge, signature)
    }

    @RequiresApi(31)
    fun refreshOverlays() {
        if (destroyed || captureState != CaptureState.IDLE) return
        val config = overlayConfig()
        val attached = (bubbleView != null) == config.bubble &&
            (edgeViews.isNotEmpty()) == config.edge
        if (overlaySignature == config.signature && attached) {
            applySuppression()
            return
        }

        overlayRefreshRunnable?.let { handler.removeCallbacks(it) }
        overlayRefreshRunnable = null
        detachTriggerOverlays()
        overlaySignature = config.signature
        if (config.bubble) showBubble()
        if (config.edge) showEdgeStrips()
        applySuppression()
    }

    @RequiresApi(31)
    fun scheduleOverlayRefresh() {
        overlayRefreshRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { refreshOverlays() }
        overlayRefreshRunnable = runnable
        handler.postDelayed(runnable, OVERLAY_REFRESH_DELAY_MS)
    }

    @RequiresApi(31)
    fun setBubbleVisible(visible: Boolean) {
        if (prefs().getBoolean(PREF_BUBBLE, false) == visible) {
            refreshOverlays()
            return
        }
        prefs().edit().putBoolean(PREF_BUBBLE, visible).apply()
        refreshOverlays()
    }

    private fun detachTriggerOverlays() {
        hideBubble()
        removeEdgeStrips()
    }

    /** Keep windows attached but invisible so capture never includes the picker. */
    private fun suspendTriggerOverlays() {
        bubbleView?.visibility = View.GONE
        edgeViews.forEach { it.visibility = View.GONE }
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
            x = prefs().getInt(PREF_BUBBLE_X, 24)
            y = prefs().getInt(PREF_BUBBLE_Y, resources.displayMetrics.heightPixels / 3)
        }
        styleBubble(view, params)
        clampBubblePosition(params)

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
                        clampBubblePosition(params)
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        if (prefs().getBoolean(PREF_SNAP, false)) snapToEdge(params)
                        clampBubblePosition(params)
                        prefs().edit().putInt(PREF_BUBBLE_X, params.x).putInt(PREF_BUBBLE_Y, params.y).apply()
                        runCatching { wm.updateViewLayout(view, params) }
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> true
            }
        }

        val added = runCatching { wm.addView(view, params) }.isSuccess
        if (!added) return
        bubbleView = view
        bubbleParams = params
    }

    @RequiresApi(31)
    private fun styleBubble(view: View, params: WindowManager.LayoutParams) {
        val p = prefs()
        val size = (p.getInt(PREF_SIZE_DP, DEFAULT_SIZE_DP).coerceIn(40, 96) *
            resources.displayMetrics.density).toInt()
        val color = p.getInt(PREF_COLOR, BUBBLE_COLORS[0]).takeIf { it in BUBBLE_COLORS } ?: BUBBLE_COLORS[0]
        val alpha = p.getInt(PREF_ALPHA, DEFAULT_ALPHA).coerceIn(30, 100)
        params.width = size
        params.height = size
        view.alpha = alpha / 100f
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(resources.displayMetrics.density.toInt(), Color.WHITE)
        }
        view.setPadding(
            (size * 0.25f).toInt(), (size * 0.25f).toInt(),
            (size * 0.25f).toInt(), (size * 0.25f).toInt()
        )
    }

    private fun clampBubblePosition(params: WindowManager.LayoutParams) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        params.x = params.x.coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

    private fun hideBubble() {
        bubbleView?.let { view ->
            // Hide before remove so a slow/failed window removal can never put
            // the picker into the captured frame.
            view.visibility = View.GONE
            runCatching { (getSystemService(WINDOW_SERVICE) as? WindowManager)?.removeView(view) }
        }
        bubbleView = null
        bubbleParams = null
    }

    @RequiresApi(31)
    private fun showEdgeStrips() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val p = prefs()
        val density = resources.displayMetrics.density
        val width = (p.getInt(PREF_EDGE_WIDTH_DP, 72).coerceIn(40, 140) * density).toInt()
        val height = (p.getInt(PREF_EDGE_HEIGHT_DP, 160).coerceIn(100, 280) * density).toInt()
        val inset = (p.getInt(PREF_EDGE_INSET_DP, 0).coerceIn(0, 160) * density).toInt()
        val lift = (p.getInt(PREF_EDGE_LIFT_DP, 8).coerceIn(0, 200) * density).toInt()
        val threshold = 90f * density
        val gravities = when (p.getString(PREF_EDGE_CORNER, CORNER_BOTH)) {
            CORNER_LEFT -> intArrayOf(Gravity.BOTTOM or Gravity.START)
            CORNER_RIGHT -> intArrayOf(Gravity.BOTTOM or Gravity.END)
            else -> intArrayOf(Gravity.BOTTOM or Gravity.START, Gravity.BOTTOM or Gravity.END)
        }
        for (gravity in gravities) {
            val strip = View(this)
            strip.contentDescription = getString(R.string.corner_swipe_hint)
            strip.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            strip.isFocusable = true
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
                        startTime = SystemClock.uptimeMillis()
                        fired = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val upward = startY - event.rawY
                        val sideways = abs(event.rawX - startX)
                        val quick = SystemClock.uptimeMillis() - startTime < 800L
                        if (!fired && upward > threshold && sideways < threshold && quick) {
                            fired = true
                            onBubbleTapped()
                        }
                        true
                    }
                    else -> true
                }
            }
            if (runCatching { wm.addView(strip, params) }.isSuccess) edgeViews.add(strip)
        }
    }

    private fun removeEdgeStrips() {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        edgeViews.forEach { view ->
            view.visibility = View.GONE
            runCatching { wm?.removeView(view) }
        }
        edgeViews.clear()
    }

    // ---------------------------------------------------------- suppression

    @RequiresApi(31)
    fun refreshKeyboardWatch() = updateKeyboardVisibility()

    private fun updateKeyboardVisibility() {
        val enabled = prefs().getBoolean(PREF_HIDE_KEYBOARD, true)
        val types = runCatching { windows.map { it.type } }.getOrDefault(emptyList())
        val imeVisible = AccessibilityWindowInfo.TYPE_INPUT_METHOD in types
        // Some accessibility-overlay implementations return an empty window
        // list. In that case retain the editable-focus fallback until focus
        // moves away instead of flickering the picker on every window event.
        val visible = enabled && (imeVisible || (types.isEmpty() && keyboardVisible))
        if (visible != keyboardVisible) {
            keyboardVisible = visible
            applySuppression()
        }
    }

    private fun applySuppression() {
        val hide = keyboardVisible || callWatchState == CallWatchState.ACTIVE
        val visibility = if (hide) View.GONE else View.VISIBLE
        bubbleView?.visibility = visibility
        edgeViews.forEach { it.visibility = visibility }
    }

    @RequiresApi(31)
    fun refreshCallWatch() {
        val enabled = prefs().getBoolean(PREF_HIDE_CALLS, false)
        val permitted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (telephonyCallback != null && enabled && permitted) {
            queryCallState()
            return
        }
        stopCallWatch()
        setupCallWatch()
    }

    @RequiresApi(31)
    private fun setupCallWatch() {
        if (!prefs().getBoolean(PREF_HIDE_CALLS, false)) {
            callWatchState = CallWatchState.DISABLED
            applySuppression()
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            callWatchState = CallWatchState.NO_PERMISSION
            applySuppression()
            return
        }
        val manager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: run {
            callWatchState = CallWatchState.FAILED
            applySuppression()
            return
        }
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                callWatchState = if (state == TelephonyManager.CALL_STATE_IDLE) {
                    CallWatchState.IDLE
                } else {
                    CallWatchState.ACTIVE
                }
                applySuppression()
            }
        }
        val registered = runCatching { manager.registerTelephonyCallback(mainExecutor, callback) }.isSuccess
        if (!registered) {
            callWatchState = CallWatchState.FAILED
            applySuppression()
            return
        }
        telephonyCallback = callback
        queryCallState()
    }

    @RequiresApi(31)
    private fun queryCallState() {
        val manager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val state = runCatching { manager.callState }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
        callWatchState = if (state == TelephonyManager.CALL_STATE_IDLE) CallWatchState.IDLE else CallWatchState.ACTIVE
        applySuppression()
    }

    @RequiresApi(31)
    private fun stopCallWatch() {
        telephonyCallback?.let { callback ->
            val manager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
            runCatching { manager?.unregisterTelephonyCallback(callback) }
        }
        telephonyCallback = null
        callWatchState = CallWatchState.DISABLED
        applySuppression()
    }

    // ------------------------------------------------------------- capture

    @RequiresApi(31)
    private fun onBubbleTapped() = beginCapture(forSelection = true, delay = BUBBLE_CAPTURE_DELAY_MS, dismissShade = false)

    /** Quick-settings tile path: capture and open the full-screen reader. */
    @RequiresApi(31)
    fun captureScreenAndOpen() = beginCapture(forSelection = false, delay = SHADE_DISMISS_DELAY_MS, dismissShade = true)

    @RequiresApi(31)
    private fun beginCapture(forSelection: Boolean, delay: Long, dismissShade: Boolean) {
        if (destroyed || captureState != CaptureState.IDLE) return
        Feedback.capture(this, prefs())
        captureRequestId++
        captureForSelection = forSelection
        captureState = CaptureState.SCHEDULED
        suspendTriggerOverlays()
        if (dismissShade) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        val requestId = captureRequestId
        handler.postDelayed({
            if (!destroyed && captureState == CaptureState.SCHEDULED && captureRequestId == requestId) {
                executeCapture(requestId)
            }
        }, delay)
        scheduleCaptureTimeout(requestId)
    }

    @RequiresApi(31)
    private fun executeCapture(requestId: Long) {
        captureState = CaptureState.CAPTURING
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            captureExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val software = toSoftwareBitmap(result)
                    handler.post {
                        if (destroyed || captureRequestId != requestId ||
                            captureState != CaptureState.CAPTURING
                        ) {
                            software?.recycle()
                            return@post
                        }
                        if (software == null) {
                            failCapture(requestId, R.string.capture_error, fallbackToLatest = false)
                            return@post
                        }
                        CaptureHolder.put(requestId, software)
                        if (captureForSelection) {
                            captureState = CaptureState.AWAITING_SELECTION
                            startActivity(Intent(this@CaptureAccessibilityService, SelectActivity::class.java).apply {
                                putExtra(SelectActivity.EXTRA_CAPTURE_ID, requestId)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } else {
                            captureState = CaptureState.READING
                            openMain(captured = true, requestId = requestId)
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    handler.post {
                        if (destroyed || captureRequestId != requestId ||
                            captureState != CaptureState.CAPTURING
                        ) return@post
                        when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                                failCapture(requestId, R.string.capture_secure, fallbackToLatest = false)
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                failCapture(requestId, R.string.capture_busy, fallbackToLatest = false)
                            else -> failCapture(requestId, R.string.capture_failed, fallbackToLatest = !captureForSelection)
                        }
                    }
                }
            }
        )
    }

    @RequiresApi(31)
    private fun toSoftwareBitmap(result: ScreenshotResult): Bitmap? {
        var wrapped: Bitmap? = null
        return try {
            wrapped = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        } finally {
            wrapped?.recycle()
            result.hardwareBuffer.close()
        }
    }

    private fun scheduleCaptureTimeout(requestId: Long) {
        captureTimeoutRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (captureRequestId == requestId && captureState != CaptureState.IDLE) {
                CaptureHolder.clear(requestId)
                captureState = CaptureState.IDLE
                captureRequestId = 0L
                if (Build.VERSION.SDK_INT >= 31) refreshOverlays()
                Toast.makeText(this, R.string.capture_expired_body, Toast.LENGTH_LONG).show()
            }
        }
        captureTimeoutRunnable = runnable
        handler.postDelayed(runnable, CAPTURE_TIMEOUT_MS)
    }

    private fun failCapture(requestId: Long, message: Int, fallbackToLatest: Boolean) {
        CaptureHolder.clear(requestId)
        clearCaptureState()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (fallbackToLatest) openMain(captured = false, requestId = -1L)
        if (Build.VERSION.SDK_INT >= 31) refreshOverlays()
    }

    /** Called by selector/reader when the captured frame is no longer needed. */
    fun captureFinished(requestId: Long) {
        if (requestId <= 0 || captureRequestId != requestId || captureState == CaptureState.IDLE) return
        CaptureHolder.clear(requestId)
        clearCaptureState()
        if (Build.VERSION.SDK_INT >= 31) refreshOverlays()
    }

    private fun clearCaptureState() {
        captureTimeoutRunnable?.let { handler.removeCallbacks(it) }
        captureTimeoutRunnable = null
        captureState = CaptureState.IDLE
        captureRequestId = 0L
        captureForSelection = false
    }

    private fun openMain(captured: Boolean, requestId: Long) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(
                if (captured) MainActivity.EXTRA_CAPTURED_SCREEN else MainActivity.EXTRA_LATEST_SCREENSHOT,
                true
            )
            putExtra(MainActivity.EXTRA_CAPTURE_ID, requestId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }
}
