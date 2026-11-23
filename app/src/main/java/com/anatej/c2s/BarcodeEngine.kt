package com.anatej.c2s

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Reads QR codes and barcodes from a frozen frame, fully on-device. */
object BarcodeEngine {

    private val scanner by lazy { BarcodeScanning.getClient() }

    suspend fun scan(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { codes ->
                cont.resume(
                    codes.mapNotNull { it.rawValue?.takeIf { v -> v.isNotBlank() } }.distinct()
                )
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
