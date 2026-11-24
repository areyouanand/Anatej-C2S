package com.anatej.c2s

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptDetectorTest {
    @Test
    fun `classifies bundled scripts`() {
        assertEquals(ScriptDetector.Script.LATIN, ScriptDetector.detect("Hello world"))
        assertEquals(ScriptDetector.Script.CHINESE, ScriptDetector.detect("你好世界"))
        assertEquals(ScriptDetector.Script.JAPANESE, ScriptDetector.detect("こんにちは"))
        assertEquals(ScriptDetector.Script.KOREAN, ScriptDetector.detect("안녕하세요"))
        assertEquals(ScriptDetector.Script.DEVANAGARI, ScriptDetector.detect("नमस्ते"))
    }
}
