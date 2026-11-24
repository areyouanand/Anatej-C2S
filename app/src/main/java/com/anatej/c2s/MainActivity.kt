package com.anatej.c2s

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.anatej.c2s.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), SelectableOcrView.Listener {

    companion object {
        const val EXTRA_LATEST_SCREENSHOT = "com.anatej.c2s.LATEST_SCREENSHOT"
        const val EXTRA_CAPTURED_SCREEN = "com.anatej.c2s.CAPTURED_SCREEN"
        const val EXTRA_CAPTURE_ID = "com.anatej.c2s.CAPTURE_ID"
    }

    private lateinit var binding: ActivityMainBinding
    private var currentBitmap: Bitmap? = null
    private var currentResult: OcrEngine.Result? = null
    private var currentCodes: List<BarcodeEngine.Result> = emptyList()
    private var launchedWithImage = false
    private var onboardingShown = false
    private var activeCaptureId = -1L
    private var requestGeneration = 0L
    private var imageJob: Job? = null
    private var retryRequest: (() -> Unit)? = null
    private var textToSave = ""
    private var lastSmartText: String? = null
    private var sheetRef: BottomSheetDialog? = null

    private fun prefs() = getSharedPreferences(CaptureAccessibilityService.PREFS, MODE_PRIVATE)
    private fun historyPrefs() = ClipboardHistory.prefs(this)

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openImage(uri)
        }

    private val requestMediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            if (hasMediaAccess()) loadLatestScreenshot()
            else Snackbar.make(binding.root, R.string.permission_needed, Snackbar.LENGTH_LONG).show()
        }

    private val saveTextLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = textToSave
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = contentResolver.openOutputStream(uri)
                            ?: throw IOException("No output stream")
                        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }.isSuccess
                }
                Toast.makeText(
                    this@MainActivity,
                    if (saved) R.string.text_saved else R.string.save_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val credentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) showClipboardHistoryInternal()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        binding.ocrView.listener = this

        binding.btnPick.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnScreenshot.setOnClickListener { requestScreenshotOcr() }
        binding.btnEnableCapture.setOnClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        binding.btnBubble.setOnClickListener { toggleFloatingPicker() }
        binding.btnCustomize.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnClipboard.setOnClickListener { showClipboardHistory() }
        binding.btnAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        binding.btnCodes.setOnClickListener { showBarcodeSheet(currentCodes) }
        binding.btnRetry.setOnClickListener { retryRequest?.invoke() }

        binding.btnBack.setOnClickListener { onBackFromViewer() }
        binding.btnSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.btnCopyAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { copyToClipboard(it) }
        }
        binding.btnShareAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { shareText(it) }
        }
        binding.btnTextMode.setOnClickListener { showTextSheet() }

        binding.toolbarCopy.setOnClickListener {
            binding.ocrView.selectedText?.let {
                copyToClipboard(it)
                binding.ocrView.clearSelection()
            }
        }
        binding.toolbarSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.toolbarShare.setOnClickListener { binding.ocrView.selectedText?.let { shareText(it) } }
        binding.toolbarSearch.setOnClickListener { binding.ocrView.selectedText?.let { searchWeb(it) } }

        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.ocrView.hasSelection() -> binding.ocrView.clearSelection()
                binding.viewerGroup.isVisible -> onBackFromViewer()
                else -> finish()
            }
        }
        handleIntent(intent)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val topBase = (4 * resources.displayMetrics.density).toInt()
            binding.topBar.updatePadding(
                top = bars.top + topBase, left = bars.left, right = bars.right
            )
            binding.homeGroup.updatePadding(
                left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom
            )
            binding.smartActionsScroll.updatePadding(bottom = bars.bottom)
            binding.errorGroup.updatePadding(
                left = bars.left + (32 * resources.displayMetrics.density).toInt(),
                top = bars.top + (32 * resources.displayMetrics.density).toInt(),
                right = bars.right + (32 * resources.displayMetrics.density).toInt(),
                bottom = bars.bottom + (32 * resources.displayMetrics.density).toInt()
            )
            binding.ocrView.topSafePx = bars.top + (56 * resources.displayMetrics.density).toInt()
            binding.ocrView.bottomSafePx = bars.bottom
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        binding.btnEnableCapture.isVisible =
            Build.VERSION.SDK_INT >= 31 && !isCaptureServiceEnabled()
        val bubbleAvailable = Build.VERSION.SDK_INT >= 31
        binding.btnBubble.isVisible = bubbleAvailable
        if (bubbleAvailable) {
            val enabled = prefs().getBoolean(CaptureAccessibilityService.PREF_BUBBLE, false)
            binding.btnBubble.setText(
                if (enabled) R.string.floating_bubble_on else R.string.floating_bubble_off
            )
            // Diff-based refresh: no window churn or bubble-position reset.
            CaptureAccessibilityService.instance?.refreshOverlays()
        }
    }

    private fun toggleFloatingPicker() {
        if (Build.VERSION.SDK_INT < 31) return
        if (!isCaptureServiceEnabled()) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            return
        }
        val enabled = !prefs().getBoolean(CaptureAccessibilityService.PREF_BUBBLE, false)
        CaptureAccessibilityService.instance?.setBubbleVisible(enabled)
        binding.btnBubble.setText(
            if (enabled) R.string.floating_bubble_on else R.string.floating_bubble_off
        )
    }

    private fun isCaptureServiceEnabled(): Boolean {
        val component = "$packageName/${CaptureAccessibilityService::class.java.name}"
        val shortComponent = "$packageName/.${CaptureAccessibilityService::class.java.simpleName}"
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(component, ignoreCase = true) || it.equals(shortComponent, ignoreCase = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        when {
            uri != null -> {
                launchedWithImage = true
                openImage(uri)
            }
            intent.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false) -> {
                val requestId = intent.getLongExtra(EXTRA_CAPTURE_ID, -1L)
                activeCaptureId = requestId
                val captured = CaptureHolder.take(requestId)
                if (captured != null) {
                    launchedWithImage = true
                    openBitmap(captured)
                } else {
                    showError(getString(R.string.capture_expired_body)) {
                        completeCapture()
                        showHome()
                    }
                }
            }
            intent.getBooleanExtra(EXTRA_LATEST_SCREENSHOT, false) -> {
                launchedWithImage = true
                requestScreenshotOcr()
            }
            else -> showHome()
        }
    }

    // ------------------------------------------------------------- image flow

    private fun openImage(uri: Uri) = startImageLoad { ImageLoader.load(this, uri) }

    private fun openBitmap(bitmap: Bitmap) = startImageLoad { bitmap }

    private fun startImageLoad(loader: suspend () -> Bitmap) {
        val generation = ++requestGeneration
        imageJob?.cancel()
        retryRequest = { startImageLoad(loader) }
        imageJob = lifecycleScope.launch {
            showViewer(loading = true)
            try {
                val bitmap = loader()
                processBitmap(bitmap, generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == requestGeneration) {
                    showError(getString(R.string.load_failed, e.localizedMessage ?: ""), retryRequest)
                }
            }
        }
    }

    private suspend fun processBitmap(bitmap: Bitmap, generation: Long) {
        val settings = prefs()
        val script = settings.getString(
            CaptureAccessibilityService.PREF_OCR_SCRIPT,
            CaptureAccessibilityService.SCRIPT_LATIN
        ) ?: CaptureAccessibilityService.SCRIPT_LATIN
        val (result, codes) = coroutineScope {
            val ocr = async { OcrEngine.recognize(bitmap, script) }
            val barcodes = if (settings.getBoolean(CaptureAccessibilityService.PREF_BARCODE, true)) {
                async { runCatching { BarcodeEngine.scan(bitmap) }.getOrDefault(emptyList()) }
            } else {
                async { emptyList<BarcodeEngine.Result>() }
            }
            ocr.await() to barcodes.await()
        }
        currentCoroutineContext().ensureActive()
        if (generation != requestGeneration) return

        currentBitmap = bitmap
        currentResult = result
        currentCodes = codes
        binding.ocrView.magnifierEnabled =
            settings.getBoolean(CaptureAccessibilityService.PREF_MAGNIFIER, true)
        binding.ocrView.markUncertain =
            settings.getBoolean(CaptureAccessibilityService.PREF_CONFIDENCE, true)
        binding.ocrView.setContent(bitmap, result)
        showViewer(loading = false)
        binding.btnCodes.isVisible = codes.isNotEmpty()
        if (result.isEmpty) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun requestScreenshotOcr() {
        if (hasMediaAccess()) loadLatestScreenshot()
        else requestMediaPermission.launch(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    private fun hasMediaAccess(): Boolean {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun loadLatestScreenshot() {
        val generation = ++requestGeneration
        imageJob?.cancel()
        retryRequest = { loadLatestScreenshot() }
        imageJob = lifecycleScope.launch {
            showViewer(loading = true)
            try {
                val uri = withContext(Dispatchers.IO) { queryLatestScreenshot() }
                if (uri != null) {
                    val bitmap = ImageLoader.load(this@MainActivity, uri)
                    processBitmap(bitmap, generation)
                } else {
                    showError(getString(R.string.no_screenshot_found), retryRequest)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == requestGeneration) {
                    showError(getString(R.string.load_failed, e.localizedMessage ?: ""), retryRequest)
                }
            }
        }
    }

    private fun queryLatestScreenshot(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("%Screenshots%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            args = arrayOf("%/Screenshots/%")
        }
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        fun firstIdOf(sel: String?, selArgs: Array<String>?): Uri? =
            contentResolver.query(collection, projection, sel, selArgs, order)?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
            }
        return firstIdOf(selection, args) ?: firstIdOf(null, null)
    }

    // ---------------------------------------------------------------- UI state

    private fun showHome() {
        completeCapture()
        binding.homeGroup.isVisible = true
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
        binding.errorGroup.isVisible = false
        binding.selectionToolbar.isVisible = false
        binding.smartActionsScroll.isVisible = false
        binding.btnCodes.isVisible = false
        binding.ocrView.clear()
        currentBitmap = null
        currentResult = null
        currentCodes = emptyList()
        retryRequest = null
        if (!onboardingShown &&
            !prefs().getBoolean(CaptureAccessibilityService.PREF_ONBOARDING_DONE, false)
        ) {
            onboardingShown = true
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun showViewer(loading: Boolean) {
        binding.homeGroup.isVisible = false
        binding.errorGroup.isVisible = false
        binding.viewerGroup.isVisible = true
        binding.progressGroup.isVisible = loading
        if (loading) {
            binding.selectionToolbar.isVisible = false
            binding.smartActionsScroll.isVisible = false
            binding.btnCodes.isVisible = false
        }
    }

    private fun showError(message: String, retry: (() -> Unit)?) {
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
        binding.selectionToolbar.isVisible = false
        binding.smartActionsScroll.isVisible = false
        binding.errorGroup.isVisible = true
        binding.errorText.text = message
        binding.btnRetry.isVisible = retry != null
        retryRequest = retry
    }

    private fun onBackFromViewer() {
        completeCapture()
        if (launchedWithImage) finish() else showHome()
    }

    private fun completeCapture() {
        if (activeCaptureId > 0) {
            CaptureAccessibilityService.instance?.captureFinished(activeCaptureId)
            activeCaptureId = -1L
        }
    }

    // ------------------------------------------------------------- selection

    override fun onSelectionChanged(text: String?, anchor: RectF?) {
        if (text == null || anchor == null || binding.ocrView.isAdjusting) {
            binding.selectionToolbar.isVisible = false
            binding.smartActionsScroll.isVisible = false
            return
        }
        binding.selectionToolbar.isVisible = true
        if (text != lastSmartText) {
            lastSmartText = text
            updateSmartActions(text)
        }
        binding.selectionToolbar.post { positionToolbar(anchor) }
    }

    override fun onSelectionAdjustingChanged(adjusting: Boolean) {
        if (adjusting) {
            binding.selectionToolbar.isVisible = false
            binding.smartActionsScroll.isVisible = false
        } else if (binding.ocrView.hasSelection()) {
            binding.selectionToolbar.isVisible = true
            lastSmartText = null
        }
    }

    private fun positionToolbar(anchor: RectF) {
        val toolbar = binding.selectionToolbar
        val container = binding.viewerGroup
        val margin = resources.displayMetrics.density * 12f
        var x = anchor.centerX() - toolbar.width / 2f
        x = max(margin, min(x, container.width - toolbar.width - margin))
        val topLimit = binding.topBar.height + margin
        val bottomLimit = container.height - binding.ocrView.bottomSafePx - toolbar.height - margin
        var y = anchor.top - toolbar.height - margin * 1.5f
        if (y < topLimit) y = anchor.bottom + margin * 1.5f
        y = max(topLimit, min(y, bottomLimit.coerceAtLeast(topLimit)))
        toolbar.translationX = x
        toolbar.translationY = y
    }

    // --------------------------------------------------------------- actions

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        ClipboardHistory.add(prefs(), historyPrefs(), text)
        if (Build.VERSION.SDK_INT < 33) {
            Snackbar.make(binding.root, R.string.copied, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun searchWeb(text: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, text)
        }
        if (search.resolveActivity(packageManager) != null) startActivity(search)
        else runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, "https://duckduckgo.com/?q=${Uri.encode(text)}".toUri()))
        }
    }

    private fun showTextSheet() {
        val text = currentResult?.fullText
        if (text.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_text, binding.root, false)
        val editor = view.findViewById<EditText>(R.id.sheetText)
        editor.setText(text)
        view.findViewById<View>(R.id.sheetCopy).setOnClickListener {
            copyToClipboard(editor.text.toString())
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.sheetShare).setOnClickListener { shareText(editor.text.toString()) }
        view.findViewById<View>(R.id.sheetSave).setOnClickListener {
            textToSave = editor.text.toString()
            saveTextLauncher.launch("anatej-c2s.txt")
        }
        sheetRef = sheet
        sheet.setContentView(view)
        sheet.show()
    }

    // -------------------------------------------------------- smart actions

    private fun updateSmartActions(text: String) {
        val row = binding.smartActionsRow
        row.removeAllViews()
        val actions = SmartContent.detect(text)
        if (actions.isEmpty()) {
            binding.smartActionsScroll.isVisible = false
            return
        }
        actions.forEach { action ->
            val label = when (action.type) {
                SmartContent.Type.URL -> R.string.action_open_link
                SmartContent.Type.EMAIL -> R.string.action_email
                SmartContent.Type.PHONE -> R.string.action_call
                SmartContent.Type.MAP -> R.string.action_map
            }
            row.addView(MaterialButton(this, null, R.style.SmallOutlinedButton).apply {
                this.text = getString(label)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                setOnClickListener { openSmartAction(action) }
            })
        }
        binding.smartActionsScroll.isVisible = true
    }

    private fun openSmartAction(action: SmartContent.Action) {
        val intent = when (action.type) {
            SmartContent.Type.URL -> Intent(Intent.ACTION_VIEW, action.value.toUri())
            SmartContent.Type.EMAIL -> Intent(Intent.ACTION_SENDTO, "mailto:${action.value}".toUri())
            SmartContent.Type.PHONE -> Intent(Intent.ACTION_DIAL, "tel:${action.value}".toUri())
            SmartContent.Type.MAP -> Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(action.value)}".toUri())
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        else Snackbar.make(binding.root, R.string.no_action_app, Snackbar.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------- barcodes

    private fun showBarcodeSheet(codes: List<BarcodeEngine.Result>) {
        if (codes.isEmpty()) return
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_barcode, binding.root, false)
        val list = view.findViewById<LinearLayout>(R.id.barcodeList)
        codes.forEach { code ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = code.title
                setTextAppearance(R.style.SheetBodyLarge)
                setTextIsSelectable(true)
            })
            if (code.details.isNotBlank() && code.details != code.title) {
                row.addView(TextView(this).apply {
                    text = code.details
                    setTextAppearance(R.style.SheetBody)
                })
            }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(smallButton(R.string.copy_details) {
                copyToClipboard(code.details.ifBlank { code.raw })
                sheet.dismiss()
            })
            if (code.kind == BarcodeEngine.Kind.WIFI && !code.secondary.isNullOrBlank()) {
                buttons.addView(smallButton(R.string.copy_password) {
                    copyToClipboard(code.secondary.orEmpty())
                    sheet.dismiss()
                })
            } else {
                barcodeAction(code)?.let { action ->
                    buttons.addView(smallButton(action.first) {
                        if (action.second.resolveActivity(packageManager) != null) startActivity(action.second)
                        else Snackbar.make(binding.root, R.string.no_action_app, Snackbar.LENGTH_SHORT).show()
                    })
                }
            }
            row.addView(buttons)
            list.addView(row)
        }
        view.findViewById<View>(R.id.barcodeClose).setOnClickListener { sheet.dismiss() }
        sheetRef = sheet
        sheet.setContentView(view)
        sheet.show()
    }

    private fun barcodeAction(code: BarcodeEngine.Result): Pair<Int, Intent>? = when (code.kind) {
        BarcodeEngine.Kind.URL -> code.primary?.let { R.string.barcode_open to Intent(Intent.ACTION_VIEW, it.toUri()) }
        BarcodeEngine.Kind.EMAIL -> code.primary?.let { R.string.action_email to Intent(Intent.ACTION_SENDTO, "mailto:$it".toUri()) }
        BarcodeEngine.Kind.PHONE -> code.primary?.let { R.string.action_call to Intent(Intent.ACTION_DIAL, "tel:$it".toUri()) }
        BarcodeEngine.Kind.SMS -> code.primary?.let {
            R.string.send_sms to Intent(Intent.ACTION_SENDTO, "sms:$it".toUri()).apply {
                putExtra("sms_body", code.secondary.orEmpty())
            }
        }
        BarcodeEngine.Kind.GEO -> {
            val lat = code.primary?.toDoubleOrNull()
            val lng = code.secondary?.toDoubleOrNull()
            if (lat != null && lng != null) R.string.action_map to Intent(Intent.ACTION_VIEW, "geo:$lat,$lng".toUri())
            else null
        }
        BarcodeEngine.Kind.CALENDAR -> R.string.open_calendar to Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, code.title)
            code.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            code.startMillis?.let { putExtra(CalendarContract.Events.DTSTART, it) }
            code.endMillis?.let { putExtra(CalendarContract.Events.DTEND, it) }
        }
        BarcodeEngine.Kind.CONTACT -> when {
            !code.primary.isNullOrBlank() -> R.string.action_call to Intent(Intent.ACTION_DIAL, "tel:${code.primary}".toUri())
            !code.secondary.isNullOrBlank() -> R.string.action_email to Intent(Intent.ACTION_SENDTO, "mailto:${code.secondary}".toUri())
            else -> null
        }
        else -> null
    }

    private fun smallButton(label: Int, click: View.OnClickListener): MaterialButton =
        MaterialButton(this, null, R.style.SmallTextButton).apply {
            text = getString(label)
            isAllCaps = false
            setOnClickListener(click)
        }

    // -------------------------------------------------------- clipboard list

    private fun showClipboardHistory() {
        val settings = prefs()
        if (settings.getBoolean(CaptureAccessibilityService.PREF_HISTORY_LOCK, false)) {
            val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguard.isDeviceSecure) {
                Snackbar.make(binding.root, R.string.history_no_lock, Snackbar.LENGTH_LONG).show()
                return
            }
            val intent = keyguard.createConfirmDeviceCredentialIntent(
                getString(R.string.clipboard_history), getString(R.string.history_lock)
            )
            if (intent == null) {
                Snackbar.make(binding.root, R.string.history_no_lock, Snackbar.LENGTH_LONG).show()
                return
            }
            credentialLauncher.launch(intent)
        } else {
            showClipboardHistoryInternal()
        }
    }

    private fun showClipboardHistoryInternal() {
        val entries = ClipboardHistory.load(historyPrefs(), prefs())
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.clipboard_history)
            setTextAppearance(R.style.SheetTitle)
        })
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (entries.isEmpty()) {
            inner.addView(TextView(this).apply {
                text = getString(R.string.clipboard_history_empty)
                setTextAppearance(R.style.SheetBody)
                setPadding(0, 24, 0, 24)
            })
        } else {
            val format = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            entries.forEachIndexed { index, entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 12, 0, 12)
                }
                val textColumn = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textColumn.addView(TextView(this).apply {
                    text = entry.text.replace('\n', ' ').take(120)
                    maxLines = 2
                    setTextAppearance(R.style.SheetBody)
                })
                textColumn.addView(TextView(this).apply {
                    text = format.format(Date(entry.time))
                    setTextAppearance(R.style.SheetBody)
                    alpha = 0.65f
                })
                row.addView(textColumn)
                row.addView(smallButton(R.string.copy) { copyToClipboard(entry.text) })
                row.contentDescription = getString(R.string.clipboard_item, index + 1)
                inner.addView(row)
            }
        }
        container.addView(ScrollView(this).apply { addView(inner) })
        container.addView(smallButton(R.string.clipboard_clear) {
            ClipboardHistory.clear(historyPrefs())
            Snackbar.make(binding.root, R.string.clipboard_cleared, Snackbar.LENGTH_SHORT).show()
            sheetRef?.dismiss()
        })
        val sheet = BottomSheetDialog(this)
        sheetRef = sheet
        sheet.setContentView(container)
        sheet.show()
    }

    override fun onDestroy() {
        completeCapture()
        sheetRef?.dismiss()
        sheetRef = null
        imageJob?.cancel()
        super.onDestroy()
    }
}
