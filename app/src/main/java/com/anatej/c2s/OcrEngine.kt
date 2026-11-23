package com.anatej.c2s

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin wrapper around ML Kit's bundled on-device text recognizer.
 * The models ship inside the APK, so recognition works fully offline
 * and without Google Play services (GrapheneOS etc.).
 */
object OcrEngine {

    /** One recognized word with its bounding box in bitmap pixel coordinates. */
    data class Word(
        val text: String,
        val box: RectF,
        val lineId: Int,
        val blockId: Int,
        val confidence: Float,
    )

    data class Result(
        val words: List<Word>,
        val lineBoxes: List<RectF>,
    ) {
        val isEmpty get() = words.isEmpty()

        /** Joins a word range [start..end] back into readable text. */
        fun textOf(start: Int, end: Int): String {
            val sb = StringBuilder()
            for (i in start..end) {
                if (i > start) {
                    val prev = words[i - 1]
                    val cur = words[i]
                    sb.append(
                        when {
                            prev.blockId != cur.blockId -> "\n\n"
                            prev.lineId != cur.lineId -> "\n"
                            else -> " "
                        }
                    )
                }
                sb.append(words[i].text)
            }
            return sb.toString()
        }

        val fullText: String get() = if (isEmpty) "" else textOf(0, words.size - 1)
    }

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val extraRecognizers = HashMap<String, TextRecognizer>()

    /** Scripts compiled into this build variant. */
    val availableScripts: Array<String> get() = OcrScripts.available

    private fun recognizerFor(script: String): TextRecognizer {
        if (script == CaptureAccessibilityService.SCRIPT_LATIN) return latinRecognizer
        return extraRecognizers.getOrPut(script) {
            OcrScripts.recognizerFor(script) ?: latinRecognizer
        }
    }

    fun warmUp() {
        // Trigger lazy init + model load off the critical path.
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        latinRecognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnCompleteListener { bmp.recycle() }
    }

    suspend fun recognize(bitmap: Bitmap, script: String = CaptureAccessibilityService.SCRIPT_LATIN): Result {
        if (script == CaptureAccessibilityService.SCRIPT_AUTO && OcrScripts.available.size > 2) {
            var best = recognizeWith(bitmap, CaptureAccessibilityService.SCRIPT_LATIN)
            for (candidate in OcrScripts.available) {
                if (candidate == CaptureAccessibilityService.SCRIPT_LATIN ||
                    candidate == CaptureAccessibilityService.SCRIPT_AUTO
                ) continue
                val attempt = recognizeWith(bitmap, candidate)
                if (attempt.words.size > best.words.size) best = attempt
            }
            return best
        }
        return recognizeWith(bitmap, script)
    }

    private suspend fun recognizeWith(bitmap: Bitmap, script: String): Result {
        val recognizer = recognizerFor(script)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val words = ArrayList<Word>()
                    val lineBoxes = ArrayList<RectF>()
                    var lineId = 0
                    text.textBlocks.forEachIndexed { blockId, block ->
                        for (line in block.lines) {
                            line.boundingBox?.let { lineBoxes.add(RectF(it)) }
                            for (element in line.elements) {
                                val box = element.boundingBox ?: continue
                                if (element.text.isBlank()) continue
                                words.add(
                                    Word(
                                        element.text, RectF(box), lineId, blockId,
                                        element.confidence
                                    )
                                )
                            }
                            lineId++
                        }
                    }
                    cont.resume(Result(words, lineBoxes))
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }
}
