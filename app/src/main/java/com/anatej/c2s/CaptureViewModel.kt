package com.anatej.c2s

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel

/** Activity-scoped owner for the frozen selector bitmap. */
class CaptureViewModel : ViewModel() {

    private var retained: Bitmap? = null
    private var transferred = false

    fun acquire(requestId: Long): Bitmap? {
        if (retained == null) retained = CaptureHolder.take(requestId)
        return retained
    }

    val bitmap: Bitmap? get() = retained

    /** Call after the view has switched to [replacement]. */
    fun replace(replacement: Bitmap) {
        val previous = retained
        retained = replacement
        if (previous !== replacement) previous?.recycle()
    }

    /** Call after the view has released the frame and CaptureHolder owns it. */
    fun transfer(bitmap: Bitmap) {
        val previous = retained
        retained = null
        transferred = true
        if (previous !== bitmap) previous?.recycle()
    }

    override fun onCleared() {
        if (!transferred) retained?.recycle()
        retained = null
    }
}
