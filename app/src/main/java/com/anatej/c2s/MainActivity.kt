package com.anatej.c2s

import android.Manifest
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
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.anatej.c2s.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), SelectableOcrView.Listener {

    companion object {
        const val EXTRA_LATEST_SCREENSHOT = "com.anatej.c2s.LATEST_SCREENSHOT"
        const val EXTRA_CAPTURED_SCREEN = "com.anatej.c2s.CAPTURED_SCREEN"
    }

    private lateinit var binding: ActivityMainBinding
    private var currentBitmap: Bitmap? = null
    private var currentResult: OcrEngine.Result? = null
    private var launchedWithImage = false
    private var onboardingShown = false

    private fun prefs() = getSharedPreferences(CaptureAccessibilityService.PREFS, MODE_PRIVATE)

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openImage(uri)
        }

    private val requestMediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Check again instead of trusting the flag: on Android 14+ the user
            // may have granted partial access, which reports "denied" here.
            if (hasMediaAccess()) loadLatestScreenshot()
            else Snackbar.make(
                binding.root, R.string.permission_needed, Snackbar.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge: keep the bars usable, let the image draw behind them.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val base = (32 * resources.displayMetrics.density).toInt()
            binding.topBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.homeGroup.updatePadding(top = base + bars.top, bottom = base + bars.bottom)
            insets
        }

        binding.ocrView.listener = this

        binding.btnPick.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnScreenshot.setOnClickListener { requestScreenshotOcr() }
        binding.btnEnableCapture.setOnClickListener {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        binding.btnBubble.setOnClickListener { toggleFloatingPicker() }
        binding.btnCustomize.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnClipboard.setOnClickListener { showClipboardHistory() }
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

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

    override fun onResume() {
        super.onResume()
        // Offer the accessibility-based instant capture where supported.
        binding.btnEnableCapture.isVisible =
            Build.VERSION.SDK_INT >= 31 && !isCaptureServiceEnabled()

        val bubbleAvailable = Build.VERSION.SDK_INT >= 31
        binding.btnBubble.isVisible = bubbleAvailable
        if (bubbleAvailable) {
            val prefs = getSharedPreferences(
                CaptureAccessibilityService.PREFS, MODE_PRIVATE
            )
            val enabled = prefs.getBoolean(CaptureAccessibilityService.PREF_BUBBLE, false)
            binding.btnBubble.setText(
                if (enabled) R.string.floating_bubble_on else R.string.floating_bubble_off
            )
            if (enabled) CaptureAccessibilityService.instance?.setBubbleVisible(true)
        }
    }

    private fun toggleFloatingPicker() {
        if (Build.VERSION.SDK_INT < 31) return
        if (!isCaptureServiceEnabled()) {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }
        val prefs = getSharedPreferences(CaptureAccessibilityService.PREFS, MODE_PRIVATE)
        val enabled = !prefs.getBoolean(CaptureAccessibilityService.PREF_BUBBLE, false)
        CaptureAccessibilityService.instance?.setBubbleVisible(enabled)
        binding.btnBubble.setText(
            if (enabled) R.string.floating_bubble_on else R.string.floating_bubble_off
        )
    }

    /** Checks the system setting rather than the live service instance, which
     *  can be briefly null while the system (re)binds the service. */
    private fun isCaptureServiceEnabled(): Boolean {
        val component = "$packageName/${CaptureAccessibilityService::class.java.name}"
        val shortComponent = "$packageName/.${CaptureAccessibilityService::class.java.simpleName}"
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
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
                val captured = CaptureHolder.take()
                if (captured != null) {
                    launchedWithImage = true
                    openBitmap(captured)
                } else showHome()
            }
            intent.getBooleanExtra(EXTRA_LATEST_SCREENSHOT, false) -> {
                launchedWithImage = true
                requestScreenshotOcr()
            }
            else -> showHome()
        }
    }

    // ------------------------------------------------------------- image flow

    private fun openImage(uri: Uri) {
        showViewer(loading = true)
        lifecycleScope.launch {
            try {
                val bitmap = ImageLoader.load(this@MainActivity, uri)
                processBitmap(bitmap)
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Failed to open $uri", e)
                showHome()
                Snackbar.make(
                    binding.root,
                    getString(R.string.load_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openBitmap(bitmap: Bitmap) {
        showViewer(loading = true)
        lifecycleScope.launch { processBitmap(bitmap) }
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        val settings = prefs()
        val script = settings.getString(
            CaptureAccessibilityService.PREF_OCR_SCRIPT,
            CaptureAccessibilityService.SCRIPT_LATIN
        ) ?: CaptureAccessibilityService.SCRIPT_LATIN
        val result = OcrEngine.recognize(bitmap, script)
        currentBitmap = bitmap
        currentResult = result
        binding.ocrView.magnifierEnabled =
            settings.getBoolean(CaptureAccessibilityService.PREF_MAGNIFIER, true)
        binding.ocrView.markUncertain =
            settings.getBoolean(CaptureAccessibilityService.PREF_CONFIDENCE, true)
        binding.ocrView.setContent(bitmap, result)
        showViewer(loading = false)
        if (result.isEmpty) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_LONG).show()
        }
        if (settings.getBoolean(CaptureAccessibilityService.PREF_BARCODE, true)) {
            val codes = runCatching { BarcodeEngine.scan(bitmap) }.getOrDefault(emptyList())
            if (codes.isNotEmpty()) showBarcodeSheet(codes)
        }
    }

    private fun requestScreenshotOcr() {
        if (hasMediaAccess()) {
            loadLatestScreenshot()
        } else {
            requestMediaPermission.launch(
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    private fun hasMediaAccess(): Boolean {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                    // Android 14+ partial access: user picked specific photos.
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun loadLatestScreenshot() {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { queryLatestScreenshot() }
            if (uri != null) openImage(uri)
            else Snackbar.make(binding.root, R.string.no_screenshot_found, Snackbar.LENGTH_LONG).show()
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
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(collection, c.getLong(0))
                } else null
            }

        // Prefer the newest screenshot; fall back to the newest image of any kind.
        return firstIdOf(selection, args) ?: firstIdOf(null, null)
    }

    // ---------------------------------------------------------------- UI state

    private fun showHome() {
        binding.homeGroup.isVisible = true
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
        binding.selectionToolbar.isVisible = false
        binding.smartActionsScroll.isVisible = false
        binding.ocrView.clear()
        currentBitmap = null
        currentResult = null
        if (!onboardingShown &&
            !prefs().getBoolean(CaptureAccessibilityService.PREF_ONBOARDING_DONE, false)
        ) {
            onboardingShown = true
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun showViewer(loading: Boolean) {
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = true
        binding.progressGroup.isVisible = loading
        if (loading) binding.selectionToolbar.isVisible = false
    }

    private fun onBackFromViewer() {
        if (launchedWithImage) finish() else showHome()
    }

    // ------------------------------------------------------------- selection

    override fun onSelectionChanged(text: String?, anchor: RectF?) {
        if (text == null || anchor == null) {
            binding.selectionToolbar.isVisible = false
            binding.smartActionsScroll.isVisible = false
            return
        }
        binding.selectionToolbar.isVisible = true
        updateSmartActions(text)
        binding.selectionToolbar.post { positionToolbar(anchor) }
    }

    private fun positionToolbar(anchor: RectF) {
        val toolbar = binding.selectionToolbar
        val container = binding.viewerGroup
        val margin = resources.displayMetrics.density * 12f

        var x = anchor.centerX() - toolbar.width / 2f
        x = max(margin, min(x, container.width - toolbar.width - margin))

        // Above the selection if there is room, otherwise below.
        var y = anchor.top - toolbar.height - margin * 1.5f
        if (y < container.height * 0.12f) y = anchor.bottom + margin * 1.5f
        y = max(margin, min(y, container.height - toolbar.height - margin))

        toolbar.translationX = x
        toolbar.translationY = y
    }

    // --------------------------------------------------------------- actions

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        ClipboardHistory.add(prefs(), text)
        // Android 13+ shows its own clipboard confirmation overlay.
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
        if (search.resolveActivity(packageManager) != null) {
            startActivity(search)
        } else {
            val browse = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://duckduckgo.com/?q=${Uri.encode(text)}")
            )
            runCatching { startActivity(browse) }
        }
    }

    private fun showTextSheet() {
        val text = currentResult?.fullText
        if (text.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_text, null)
        view.findViewById<TextView>(R.id.sheetText).text = text
        view.findViewById<View>(R.id.sheetCopy).setOnClickListener {
            copyToClipboard(text)
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.sheetShare).setOnClickListener { shareText(text) }
        sheet.setContentView(view)
        sheet.show()
    }

    // -------------------------------------------------------- smart actions

    private fun updateSmartActions(text: String) {
        val row = binding.smartActionsRow
        row.removeAllViews()
        val actions = ArrayList<Pair<Int, Intent>>()
        val value = text.trim()

        val url = android.util.Patterns.WEB_URL.matcher(value)
        if (url.find()) {
            val matched = url.group()
            val link = if (matched.startsWith("http")) matched else "https://$matched"
            actions += R.string.action_open_link to Intent(Intent.ACTION_VIEW, Uri.parse(link))
        }
        val email = android.util.Patterns.EMAIL_ADDRESS.matcher(value)
        if (email.find()) {
            actions += R.string.action_email to Intent(
                Intent.ACTION_SENDTO, Uri.parse("mailto:${email.group()}")
            )
        }
        val phone = android.util.Patterns.PHONE.matcher(value)
        if (phone.find()) {
            val digits = phone.group().filter { it.isDigit() || it == '+' }
            if (digits.length >= 7) {
                actions += R.string.action_call to Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
            }
        }
        val looksLikeAddress = value.length in 8..160 &&
            value.any { it.isDigit() } &&
            value.contains(
                Regex("(?i)(street|str\\.|road|rd\\.|avenue|ave\\.|lane|ln\\.|drive|dr\\.|nagar|marg|house|flat|block|sector)")
            )
        if (looksLikeAddress) {
            actions += R.string.action_map to Intent(
                Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(value)}")
            )
        }

        if (actions.isEmpty()) {
            binding.smartActionsScroll.isVisible = false
            return
        }
        actions.forEach { (label, intent) ->
            row.addView(MaterialButton(this, null, R.style.SmallOutlinedButton).apply {
                this.text = getString(label)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                setOnClickListener {
                    if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                    else Snackbar.make(binding.root, R.string.no_action_app, Snackbar.LENGTH_SHORT).show()
                }
            })
        }
        binding.smartActionsScroll.isVisible = true
    }

    // -------------------------------------------------------------- barcodes

    private fun showBarcodeSheet(codes: List<String>) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_barcode, null)
        val list = view.findViewById<LinearLayout>(R.id.barcodeList)

        codes.forEach { code ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = code
                setTextAppearance(R.style.SheetBodyLarge)
                setTextIsSelectable(true)
            })
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            buttons.addView(smallButton(R.string.barcode_copy) {
                copyToClipboard(code)
                sheet.dismiss()
            })
            if (code.startsWith("http://") || code.startsWith("https://")) {
                val link = Intent(Intent.ACTION_VIEW, Uri.parse(code))
                buttons.addView(smallButton(R.string.barcode_open) {
                    if (link.resolveActivity(packageManager) != null) startActivity(link)
                    else Snackbar.make(
                        binding.root, R.string.no_barcode_handler, Snackbar.LENGTH_SHORT
                    ).show()
                })
            }
            row.addView(buttons)
            list.addView(row)
        }

        view.findViewById<View>(R.id.barcodeClose).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun smallButton(label: Int, click: View.OnClickListener): MaterialButton =
        MaterialButton(this, null, R.style.SmallTextButton).apply {
            text = getString(label)
            isAllCaps = false
            setOnClickListener(click)
        }

    // -------------------------------------------------------- clipboard list

    private fun showClipboardHistory() {
        val entries = ClipboardHistory.load(prefs())
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
            entries.forEachIndexed { index, entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 12, 0, 12)
                    contentDescription = getString(R.string.clipboard_item, index + 1)
                    setOnClickListener { copyToClipboard(entry.text) }
                }
                row.addView(TextView(this).apply {
                    text = entry.text.replace('\n', ' ').take(120)
                    maxLines = 2
                    setTextAppearance(R.style.SheetBody)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(smallButton(R.string.copy) { copyToClipboard(entry.text) })
                inner.addView(row)
            }
        }
        container.addView(ScrollView(this).apply { addView(inner) })
        container.addView(smallButton(R.string.clipboard_clear) {
            ClipboardHistory.clear(prefs())
            Snackbar.make(binding.root, R.string.clipboard_cleared, Snackbar.LENGTH_SHORT).show()
            sheetRef?.dismiss()
        })

        val sheet = BottomSheetDialog(this)
        sheetRef = sheet
        sheet.setContentView(container)
        sheet.show()
    }

    private var sheetRef: BottomSheetDialog? = null
}
