package com.anatej.c2s

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREFS
import com.anatej.c2s.CaptureAccessibilityService.Companion.PREF_ONBOARDING_DONE
import com.anatej.c2s.databinding.ActivityOnboardingBinding

/** One-time, three-step introduction shown from the home screen. */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var step = 0

    private val titles by lazy {
        arrayOf(
            getString(R.string.onboarding_1_title),
            getString(R.string.onboarding_2_title),
            getString(R.string.onboarding_3_title),
        )
    }
    private val bodies by lazy {
        arrayOf(
            getString(R.string.onboarding_1_body),
            getString(R.string.onboarding_2_body),
            getString(R.string.onboarding_3_body),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        render()

        binding.btnNext.setOnClickListener {
            if (step == titles.lastIndex) finishOnboarding() else {
                step++
                render()
            }
        }
        binding.btnSkip.setOnClickListener { finishOnboarding() }
        onBackPressedDispatcher.addCallback(this) { finishOnboarding() }
        binding.btnOpenSettings.setOnClickListener {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun render() {
        binding.stepTitle.text = titles[step]
        binding.stepBody.text = bodies[step]
        binding.btnNext.text = getString(
            if (step == titles.lastIndex) R.string.onboarding_done else R.string.onboarding_next
        )
        binding.btnOpenSettings.visibility =
            if (step == 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun finishOnboarding() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ONBOARDING_DONE, true).apply()
        finish()
    }
}
