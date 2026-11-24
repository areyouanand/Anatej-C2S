package com.anatej.c2s

/** Small Unicode-script classifier used to avoid running every OCR model. */
object ScriptDetector {

    enum class Script { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }

    fun detect(text: String): Script {
        var latin = 0
        var cjk = 0
        var kana = 0
        var hangul = 0
        var devanagari = 0
        for (codePoint in text.codePoints()) {
            when {
                codePoint in 0x3040..0x30FF -> { kana++; cjk++ }
                codePoint in 0x4E00..0x9FFF || codePoint in 0x3400..0x4DBF ||
                    codePoint in 0xF900..0xFAFF -> cjk++
                codePoint in 0xAC00..0xD7AF || codePoint in 0x1100..0x11FF -> hangul++
                codePoint in 0x0900..0x097F -> devanagari++
                codePoint in 0x0041..0x005A || codePoint in 0x0061..0x007A ||
                    codePoint in 0x00C0..0x024F -> latin++
            }
        }
        return when {
            kana > 0 -> Script.JAPANESE
            hangul > 0 -> Script.KOREAN
            devanagari > 0 -> Script.DEVANAGARI
            cjk > latin / 3 -> Script.CHINESE
            else -> Script.LATIN
        }
    }

    fun preference(script: Script): String = when (script) {
        Script.CHINESE -> CaptureAccessibilityService.SCRIPT_CHINESE
        Script.JAPANESE -> CaptureAccessibilityService.SCRIPT_JAPANESE
        Script.KOREAN -> CaptureAccessibilityService.SCRIPT_KOREAN
        Script.DEVANAGARI -> CaptureAccessibilityService.SCRIPT_DEVANAGARI
        Script.LATIN -> CaptureAccessibilityService.SCRIPT_LATIN
    }
}
