package com.anatej.c2s

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OcrEngineResultTest {
    @Test
    fun `joins words with line and block breaks`() {
        val result = OcrEngine.Result(
            words = listOf(
                OcrEngine.Word("Hello", RectF(0f, 0f, 10f, 10f), 0, 0, 0.9f),
                OcrEngine.Word("world", RectF(11f, 0f, 20f, 10f), 0, 0, 0.9f),
                OcrEngine.Word("Next", RectF(0f, 11f, 10f, 20f), 1, 0, 0.9f),
                OcrEngine.Word("Block", RectF(0f, 22f, 10f, 30f), 2, 1, 0.9f),
            ),
            lineBoxes = emptyList(),
        )
        assertEquals("Hello world\nNext\n\nBlock", result.fullText)
    }
}
