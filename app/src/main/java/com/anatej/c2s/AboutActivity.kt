package com.anatej.c2s

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anatej.c2s.databinding.ActivityAboutBinding

/** Shows app version, build variant and maintainer links. */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val baseLeft = binding.root.paddingLeft
        val baseTop = binding.root.paddingTop
        val baseRight = binding.root.paddingRight
        val baseBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                baseLeft + bars.left, baseTop + bars.top,
                baseRight + bars.right, baseBottom + bars.bottom
            )
            insets
        }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        binding.versionText.text = getString(R.string.about_version, version)
        binding.variantText.text = getString(
            R.string.about_variant,
            getString(if (BuildConfig.FULL_LANGUAGES) R.string.variant_full else R.string.variant_latin)
        )

        val linked = SpannableString(getString(R.string.about_github_handle)).apply {
            setSpan(URLSpan("https://github.com/areyouanand"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.githubLink.text = linked
        binding.githubLink.movementMethod = LinkMovementMethod.getInstance()
        binding.sourceLink.text = getString(R.string.repo_url)
        binding.btnReplayIntro.setOnClickListener { startActivity(Intent(this, OnboardingActivity::class.java)) }
        binding.btnGestureHelp.setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }
    }
}
