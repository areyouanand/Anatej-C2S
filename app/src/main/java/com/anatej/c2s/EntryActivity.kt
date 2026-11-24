package com.anatej.c2s

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Public launcher/share trampoline. Internal capture commands are not forwarded. */
class EntryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent
        val target = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            when (source.action) {
                Intent.ACTION_MAIN -> action = Intent.ACTION_MAIN
                Intent.ACTION_VIEW -> {
                    action = Intent.ACTION_VIEW
                    data = source.data
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                Intent.ACTION_SEND -> {
                    action = Intent.ACTION_SEND
                    type = source.type
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val stream = if (Build.VERSION.SDK_INT >= 33) {
                        source.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        source.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    putExtra(Intent.EXTRA_STREAM, stream)
                }
                else -> action = Intent.ACTION_MAIN
            }
        }
        startActivity(target)
        finish()
    }
}
