package com.anatej.c2s

import com.google.mlkit.vision.text.TextRecognizer

/** Latin-only build: no extra script recognizers are compiled in. */
object OcrScripts {
    val available: Array<String> = arrayOf(CaptureAccessibilityService.SCRIPT_LATIN)

    fun recognizerFor(script: String): TextRecognizer? = null
}
