package dev.noah.textgrab

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.noah.textgrab.CaptureAccessibilityService.Companion.BUBBLE_COLORS
import dev.noah.textgrab.CaptureAccessibilityService.Companion.CORNER_BOTH
import dev.noah.textgrab.CaptureAccessibilityService.Companion.CORNER_LEFT
import dev.noah.textgrab.CaptureAccessibilityService.Companion.CORNER_RIGHT
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_ALPHA
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_COLOR
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_EDGE_CORNER
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_EDGE_HEIGHT_DP
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_EDGE_INSET_DP
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_EDGE_LIFT_DP
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_EDGE_SWIPE
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_EDGE_WIDTH_DP
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_SNAP
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREF_SIZE_DP
import dev.noah.textgrab.CaptureAccessibilityService.Companion.PREFS
import dev.noah.textgrab.databinding.ActivitySettingsBinding

/** Controls the picker overlay: size, colour, opacity, snapping and corner swipe. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val colorButtons by lazy {
        arrayOf(
            binding.colorBlue, binding.colorRed, binding.colorGreen,
            binding.colorPurple, binding.colorOrange
        )
    }

    private val cornerButtons by lazy {
        arrayOf(binding.cornerLeft, binding.cornerRight, binding.cornerBoth)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sliderSize.value = prefs.getInt(PREF_SIZE_DP, 56).toFloat().coerceIn(40f, 96f)
        binding.sliderAlpha.value = prefs.getInt(PREF_ALPHA, 100).toFloat().coerceIn(30f, 100f)

        val color = prefs.getInt(PREF_COLOR, BUBBLE_COLORS[0])
        val index = BUBBLE_COLORS.indexOf(color).takeIf { it >= 0 } ?: 0
        colorButtons[index].isChecked = true

        binding.switchSnap.isChecked = prefs.getBoolean(PREF_SNAP, false)
        binding.switchEdge.isChecked = prefs.getBoolean(PREF_EDGE_SWIPE, false)

        val corner = prefs.getString(PREF_EDGE_CORNER, CORNER_BOTH) ?: CORNER_BOTH
        val cornerIndex = when (corner) {
            CORNER_LEFT -> 0
            CORNER_RIGHT -> 1
            else -> 2
        }
        cornerButtons[cornerIndex].isChecked = true
        val edgeOn = binding.switchEdge.isChecked
        binding.cornerGroup.isEnabled = edgeOn
        binding.sliderCornerWidth.isEnabled = edgeOn
        binding.sliderCornerHeight.isEnabled = edgeOn
        binding.sliderCornerInset.isEnabled = edgeOn
        binding.sliderCornerLift.isEnabled = edgeOn

        binding.sliderCornerWidth.value = prefs.getInt(PREF_EDGE_WIDTH_DP, 72)
            .toFloat().coerceIn(40f, 140f)
        binding.sliderCornerHeight.value = prefs.getInt(PREF_EDGE_HEIGHT_DP, 160)
            .toFloat().coerceIn(100f, 280f)
        binding.sliderCornerInset.value = prefs.getInt(PREF_EDGE_INSET_DP, 0)
            .toFloat().coerceIn(0f, 160f)
        binding.sliderCornerLift.value = prefs.getInt(PREF_EDGE_LIFT_DP, 8)
            .toFloat().coerceIn(0f, 200f)
        updatePreview()

        binding.sliderSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) save { putInt(PREF_SIZE_DP, value.toInt()) }
        }
        binding.sliderAlpha.addOnChangeListener { _, value, fromUser ->
            if (fromUser) save { putInt(PREF_ALPHA, value.toInt()) }
        }
        binding.colorGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selected = colorButtons.indexOfFirst { it.id == checkedId }
            if (selected >= 0) save { putInt(PREF_COLOR, BUBBLE_COLORS[selected]) }
        }
        binding.switchSnap.setOnCheckedChangeListener { _, checked ->
            save { putBoolean(PREF_SNAP, checked) }
        }
        binding.cornerGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val side = when (checkedId) {
                R.id.cornerLeft -> CORNER_LEFT
                R.id.cornerRight -> CORNER_RIGHT
                else -> CORNER_BOTH
            }
            save { putString(PREF_EDGE_CORNER, side) }
        }
        binding.sliderCornerWidth.addOnChangeListener { _, value, fromUser ->
            if (fromUser) save { putInt(PREF_EDGE_WIDTH_DP, value.toInt()) }
        }
        binding.sliderCornerHeight.addOnChangeListener { _, value, fromUser ->
            if (fromUser) save { putInt(PREF_EDGE_HEIGHT_DP, value.toInt()) }
        }
        binding.sliderCornerInset.addOnChangeListener { _, value, fromUser ->
            if (fromUser) save { putInt(PREF_EDGE_INSET_DP, value.toInt()) }
        }
        binding.sliderCornerLift.addOnChangeListener { _, value, fromUser ->
            if (fromUser) save { putInt(PREF_EDGE_LIFT_DP, value.toInt()) }
        }
        binding.switchEdge.setOnCheckedChangeListener { _, checked ->
            binding.cornerGroup.isEnabled = checked
            binding.sliderCornerWidth.isEnabled = checked
            binding.sliderCornerHeight.isEnabled = checked
            binding.sliderCornerInset.isEnabled = checked
            binding.sliderCornerLift.isEnabled = checked
            save { putBoolean(PREF_EDGE_SWIPE, checked) }
        }
    }

    private fun save(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        updatePreview()
        if (Build.VERSION.SDK_INT >= 31) {
            CaptureAccessibilityService.instance?.refreshOverlays()
        }
    }

    /** Redraws the schematic whenever a corner setting changes. */
    private fun updatePreview() {
        val side = when (binding.cornerGroup.checkedButtonId) {
            R.id.cornerLeft -> CORNER_LEFT
            R.id.cornerRight -> CORNER_RIGHT
            else -> CORNER_BOTH
        }
        val metrics = resources.displayMetrics
        binding.cornerPreview.update(
            metrics.widthPixels,
            metrics.heightPixels,
            binding.sliderCornerWidth.value.toInt(),
            binding.sliderCornerHeight.value.toInt(),
            binding.sliderCornerInset.value.toInt(),
            binding.sliderCornerLift.value.toInt(),
            side
        )
    }
}
