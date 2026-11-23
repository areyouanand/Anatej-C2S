package com.anatej.c2s

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.anatej.c2s.CaptureAccessibilityService.Companion.BUBBLE_COLORS
import com.anatej.c2s.CaptureAccessibilityService.Companion.CORNER_BOTH
import com.anatej.c2s.CaptureAccessibilityService.Companion.CORNER_LEFT
import com.anatej.c2s.CaptureAccessibilityService.Companion.CORNER_RIGHT
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_ALPHA
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_BARCODE
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_COLOR
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_CONFIDENCE
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_EDGE_CORNER
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_EDGE_HEIGHT_DP
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_EDGE_INSET_DP
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_EDGE_LIFT_DP
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_EDGE_SWIPE
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_EDGE_WIDTH_DP
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_HAPTIC
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_HIDE_CALLS
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_HIDE_KEYBOARD
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_MAGNIFIER
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_OCR_SCRIPT
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_SNAP
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_SOUND
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_SIZE_DP
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_THEME
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREFS
import com.anatej.c2s.CaptureAccessibilityService.Companion.SCRIPT_AUTO
import com.anatej.c2s.CaptureAccessibilityService.Companion.SCRIPT_CHINESE
import com.anatej.c2s.CaptureAccessibilityService.Companion.SCRIPT_DEVANAGARI
import com.anatej.c2s.CaptureAccessibilityService.Companion.SCRIPT_JAPANESE
import com.anatej.c2s.CaptureAccessibilityService.Companion.SCRIPT_KOREAN
import com.anatej.c2s.CaptureAccessibilityService.Companion.SCRIPT_LATIN
import com.anatej.c2s.CaptureAccessibilityService.Companion.THEME_DARK
import com.anatej.c2s.CaptureAccessibilityService.Companion.THEME_LIGHT
import com.anatej.c2s.CaptureAccessibilityService.Companion.THEME_SYSTEM
import com.anatej.c2s.databinding.ActivitySettingsBinding

/** Controls the picker overlay, recognition, appearance and settings backup. */
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

    private val themeButtons by lazy {
        arrayOf(binding.themeSystem, binding.themeLight, binding.themeDark)
    }

    private val scripts by lazy { OcrEngine.availableScripts }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(PrefsBackup.export(prefs).toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                Toast.makeText(this, R.string.settings_exported, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, R.string.settings_import_failed, Toast.LENGTH_SHORT).show()
            }
        }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        if (text == null) {
            Toast.makeText(this, R.string.settings_import_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        runCatching { PrefsBackup.import(prefs, text) }
            .onSuccess {
                App.applyTheme(this)
                if (Build.VERSION.SDK_INT >= 31) {
                    CaptureAccessibilityService.instance?.refreshOverlays()
                    CaptureAccessibilityService.instance?.refreshCallWatch()
                }
                Toast.makeText(this, R.string.settings_imported, Toast.LENGTH_SHORT).show()
                // Recreate so every control is rebound from the imported values
                // without firing the change listeners during the reset.
                recreate()
            }
            .onFailure {
                Toast.makeText(this, R.string.settings_import_failed, Toast.LENGTH_SHORT).show()
            }
    }

    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                prefs.edit().putBoolean(PREF_HIDE_CALLS, true).apply()
                if (Build.VERSION.SDK_INT >= 31) {
                    CaptureAccessibilityService.instance?.refreshCallWatch()
                }
            } else {
                binding.switchHideCalls.isChecked = false
                Toast.makeText(this, R.string.call_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spinnerScript.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, scripts.map { scriptLabel(it) }
        )
        binding.scriptNote.visibility =
            if (BuildConfig.FULL_LANGUAGES) android.view.View.GONE else android.view.View.VISIBLE

        render()
        setupListeners()
    }

    private fun render() {
        binding.sliderSize.value = prefs.getInt(PREF_SIZE_DP, 56).toFloat().coerceIn(40f, 96f)
        binding.sliderAlpha.value = prefs.getInt(PREF_ALPHA, 100).toFloat().coerceIn(30f, 100f)

        val color = prefs.getInt(PREF_COLOR, BUBBLE_COLORS[0])
        val index = BUBBLE_COLORS.indexOf(color).takeIf { it >= 0 } ?: 0
        colorButtons[index].isChecked = true

        binding.switchSnap.isChecked = prefs.getBoolean(PREF_SNAP, false)
        binding.switchEdge.isChecked = prefs.getBoolean(PREF_EDGE_SWIPE, false)

        val corner = prefs.getString(PREF_EDGE_CORNER, CORNER_BOTH) ?: CORNER_BOTH
        cornerButtons[when (corner) {
            CORNER_LEFT -> 0
            CORNER_RIGHT -> 1
            else -> 2
        }].isChecked = true
        val edgeOn = binding.switchEdge.isChecked
        listOf(binding.cornerGroup, binding.sliderCornerWidth, binding.sliderCornerHeight,
            binding.sliderCornerInset, binding.sliderCornerLift).forEach { it.isEnabled = edgeOn }

        binding.sliderCornerWidth.value = prefs.getInt(PREF_EDGE_WIDTH_DP, 72)
            .toFloat().coerceIn(40f, 140f)
        binding.sliderCornerHeight.value = prefs.getInt(PREF_EDGE_HEIGHT_DP, 160)
            .toFloat().coerceIn(100f, 280f)
        binding.sliderCornerInset.value = prefs.getInt(PREF_EDGE_INSET_DP, 0)
            .toFloat().coerceIn(0f, 160f)
        binding.sliderCornerLift.value = prefs.getInt(PREF_EDGE_LIFT_DP, 8)
            .toFloat().coerceIn(0f, 200f)

        val theme = prefs.getString(PREF_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        themeButtons[when (theme) {
            THEME_LIGHT -> 1
            THEME_DARK -> 2
            else -> 0
        }].isChecked = true

        val script = prefs.getString(PREF_OCR_SCRIPT, SCRIPT_LATIN) ?: SCRIPT_LATIN
        binding.spinnerScript.setSelection(scripts.indexOf(script).coerceAtLeast(0))
        binding.switchBarcode.isChecked = prefs.getBoolean(PREF_BARCODE, true)
        binding.switchConfidence.isChecked = prefs.getBoolean(PREF_CONFIDENCE, true)
        binding.switchMagnifier.isChecked = prefs.getBoolean(PREF_MAGNIFIER, true)
        binding.switchHaptic.isChecked = prefs.getBoolean(PREF_HAPTIC, true)
        binding.switchSound.isChecked = prefs.getBoolean(PREF_SOUND, false)
        binding.switchHideKeyboard.isChecked = prefs.getBoolean(PREF_HIDE_KEYBOARD, true)
        binding.switchHideCalls.isChecked = prefs.getBoolean(PREF_HIDE_CALLS, false)
        updatePreview()
    }

    private fun setupListeners() {
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
            listOf(binding.cornerGroup, binding.sliderCornerWidth, binding.sliderCornerHeight,
                binding.sliderCornerInset, binding.sliderCornerLift).forEach { it.isEnabled = checked }
            save { putBoolean(PREF_EDGE_SWIPE, checked) }
        }

        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLight -> THEME_LIGHT
                R.id.themeDark -> THEME_DARK
                else -> THEME_SYSTEM
            }
            prefs.edit().putString(PREF_THEME, mode).apply()
            App.applyTheme(this)
        }
        binding.spinnerScript.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                save { putString(PREF_OCR_SCRIPT, scripts[pos]) }
            }
        }
        binding.switchBarcode.setOnCheckedChangeListener { _, checked ->
            save { putBoolean(PREF_BARCODE, checked) }
        }
        binding.switchConfidence.setOnCheckedChangeListener { _, checked ->
            save { putBoolean(PREF_CONFIDENCE, checked) }
        }
        binding.switchMagnifier.setOnCheckedChangeListener { _, checked ->
            save { putBoolean(PREF_MAGNIFIER, checked) }
        }
        binding.switchHaptic.setOnCheckedChangeListener { _, checked ->
            save { putBoolean(PREF_HAPTIC, checked) }
        }
        binding.switchSound.setOnCheckedChangeListener { _, checked ->
            save { putBoolean(PREF_SOUND, checked) }
        }
        binding.switchHideKeyboard.setOnCheckedChangeListener { _, checked ->
            save { putBoolean(PREF_HIDE_KEYBOARD, checked) }
        }
        binding.switchHideCalls.setOnCheckedChangeListener { _, checked ->
            if (checked && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                callPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                return@setOnCheckedChangeListener
            }
            save { putBoolean(PREF_HIDE_CALLS, checked) }
            if (Build.VERSION.SDK_INT >= 31) {
                CaptureAccessibilityService.instance?.refreshCallWatch()
            }
        }

        binding.btnExport.setOnClickListener { exportLauncher.launch("anatej-c2s-settings.json") }
        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    private fun scriptLabel(script: String) = getString(when (script) {
        SCRIPT_AUTO -> R.string.script_auto
        SCRIPT_CHINESE -> R.string.script_chinese
        SCRIPT_JAPANESE -> R.string.script_japanese
        SCRIPT_KOREAN -> R.string.script_korean
        SCRIPT_DEVANAGARI -> R.string.script_devanagari
        else -> R.string.script_latin
    })

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
