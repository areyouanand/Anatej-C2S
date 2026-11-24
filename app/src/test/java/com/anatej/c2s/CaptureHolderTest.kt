package com.anatej.c2s

import android.graphics.Bitmap
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaptureHolderTest {
    @Before
    fun setUp() = CaptureHolder.clearAll()

    @Test
    fun `request mismatch does not consume another capture`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        CaptureHolder.put(42, bitmap)
        assertNull(CaptureHolder.take(41))
        assertSame(bitmap, CaptureHolder.take(42))
        assertNull(CaptureHolder.take(42))
    }
}
