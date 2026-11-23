package com.anatej.c2s

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import androidx.appcompat.app.AppCompatActivity
import com.anatej.c2s.databinding.ActivityAboutBinding

/** Shows app version and the maintainer's GitHub links. */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        binding.versionText.text = getString(R.string.about_version, version)

        val handle = getString(R.string.about_github_handle)
        val linked = SpannableString(handle).apply {
            setSpan(
                URLSpan("https://github.com/areyouanand"),
                0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.githubLink.text = linked
        binding.githubLink.movementMethod = LinkMovementMethod.getInstance()

        binding.sourceLink.text = getString(R.string.repo_url)
    }
}
