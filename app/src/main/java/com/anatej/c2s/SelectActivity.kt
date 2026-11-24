package com.anatej.c2s

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anatej.c2s.databinding.ActivitySelectBinding

/** Lets the user circle the part of a captured screen to read. */
class SelectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CAPTURE_ID = "com.anatej.c2s.CAPTURE_ID"
    }

    private lateinit var binding: ActivitySelectBinding
    private val captureViewModel: CaptureViewModel by viewModels()
    private var requestId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra(EXTRA_CAPTURE_ID, -1L)
        binding = ActivitySelectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        val bitmap = captureViewModel.acquire(requestId)
        if (bitmap == null) {
            showExpired()
            return
        }

        binding.regionView.setBitmap(bitmap)
        binding.regionView.listener = RegionSelectView.Listener { cropped -> openReader(cropped) }
        binding.btnRotate.setOnClickListener { rotateBitmap() }
        binding.btnMode.setOnClickListener { toggleMode() }
        binding.btnWholeScreen.setOnClickListener { openReader(bitmap) }
        binding.btnCancel.setOnClickListener { cancel() }
        onBackPressedDispatcher.addCallback(this) { cancel() }
    }

    private fun applyInsets() {
        val topBase = binding.topBar.paddingTop
        val leftBase = binding.topBar.paddingLeft
        val rightBase = binding.topBar.paddingRight
        val bottomBase = binding.topBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.updatePadding(
                top = topBase + bars.top, left = leftBase + bars.left,
                right = rightBase + bars.right, bottom = bottomBase
            )
            (binding.btnWholeScreen.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                (32 * resources.displayMetrics.density).toInt() + bars.bottom
            binding.btnWholeScreen.requestLayout()
            insets
        }
    }

    private fun toggleMode() {
        val next = if (binding.regionView.mode == RegionSelectView.Mode.DRAW) {
            RegionSelectView.Mode.PAN
        } else {
            RegionSelectView.Mode.DRAW
        }
        binding.regionView.mode = next
        binding.btnMode.setText(
            if (next == RegionSelectView.Mode.DRAW) R.string.crop_mode_draw else R.string.crop_mode_pan
        )
    }

    private fun rotateBitmap() {
        val source = captureViewModel.bitmap ?: return
        val rotated = runCatching {
            Bitmap.createBitmap(
                source, 0, 0, source.width, source.height,
                Matrix().apply { postRotate(90f) }, false
            )
        }.getOrNull() ?: return
        binding.regionView.setBitmap(rotated)
        captureViewModel.replace(rotated)
        binding.btnWholeScreen.setOnClickListener { openReader(rotated) }
    }

    private fun openReader(bitmap: Bitmap) {
        binding.regionView.setBitmap(bitmap)
        CaptureHolder.put(requestId, bitmap)
        captureViewModel.transfer(bitmap)
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(MainActivity.EXTRA_CAPTURED_SCREEN, true)
            putExtra(MainActivity.EXTRA_CAPTURE_ID, requestId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }

    private fun showExpired() {
        binding.captureExpiredGroup.visibility = View.VISIBLE
        binding.topBar.visibility = View.GONE
        binding.btnWholeScreen.visibility = View.GONE
        binding.btnExpiredDone.setOnClickListener {
            CaptureAccessibilityService.instance?.captureFinished(requestId)
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            finish()
        }
    }

    private fun cancel() {
        if (Build.VERSION.SDK_INT >= 31) {
            CaptureAccessibilityService.instance?.captureFinished(requestId)
        }
        finish()
    }
}
