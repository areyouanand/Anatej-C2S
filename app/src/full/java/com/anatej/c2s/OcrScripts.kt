package com.anatej.c2s

import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** Full build: every bundled ML Kit script model is available. */
object OcrScripts {
    val available: Array<String> = arrayOf(
        CaptureAccessibilityService.SCRIPT_LATIN,
        CaptureAccessibilityService.SCRIPT_AUTO,
        CaptureAccessibilityService.SCRIPT_CHINESE,
        CaptureAccessibilityService.SCRIPT_JAPANESE,
        CaptureAccessibilityService.SCRIPT_KOREAN,
        CaptureAccessibilityService.SCRIPT_DEVANAGARI,
    )

    fun recognizerFor(script: String): TextRecognizer? = when (script) {
        CaptureAccessibilityService.SCRIPT_CHINESE ->
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        CaptureAccessibilityService.SCRIPT_JAPANESE ->
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        CaptureAccessibilityService.SCRIPT_KOREAN ->
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        CaptureAccessibilityService.SCRIPT_DEVANAGARI ->
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        else -> null
    }
}
