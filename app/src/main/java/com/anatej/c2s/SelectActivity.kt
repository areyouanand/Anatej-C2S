package com.anatej.c2s

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anatej.c2s.databinding.ActivitySelectBinding

/** Lets the user circle the part of a captured screen to read. */
class SelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bitmap = CaptureHolder.take()
        if (bitmap == null) {
            finish()
            return
        }

        binding = ActivitySelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = view.paddingTop + bars.top)
            insets
        }

        binding.regionView.setBitmap(bitmap)
        binding.regionView.listener = RegionSelectView.Listener { cropped -> openReader(cropped) }

        binding.btnWholeScreen.setOnClickListener { openReader(bitmap) }
        binding.btnCancel.setOnClickListener { cancel() }

        onBackPressedDispatcher.addCallback(this) { cancel() }
    }

    private fun openReader(bitmap: android.graphics.Bitmap) {
        CaptureHolder.put(bitmap)
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(MainActivity.EXTRA_CAPTURED_SCREEN, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }

    private fun cancel() {
        if (Build.VERSION.SDK_INT >= 31) {
            // Rebuild overlays from saved settings: the bubble comes back only
            // if the user actually left it enabled.
            CaptureAccessibilityService.instance?.refreshOverlays()
        }
        finish()
    }
}
