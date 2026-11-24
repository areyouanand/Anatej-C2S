package com.anatej.c2s

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.anatej.c2s.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val left = binding.root.paddingLeft
        val top = binding.root.paddingTop
        val right = binding.root.paddingRight
        val bottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }
}
