package com.quizassist.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.quizassist.model.OcrResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class OcrProcessor {
    private val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): OcrResult {
        val chinese = recognizeWith(chineseRecognizer, bitmap)
        if (chinese.confidence >= 0.72f && chinese.text.count { it in '\u4e00'..'\u9fff' } >= 4) {
            return chinese
        }
        val latin = recognizeWith(latinRecognizer, bitmap)
        return merge(chinese, latin)
    }

    private suspend fun recognizeWith(recognizer: TextRecognizer, bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val blocks = text.textBlocks.size
                val chars = text.text.count { !it.isWhitespace() }
                val chineseChars = text.text.count { it in '\u4e00'..'\u9fff' }
                val confidence = when {
                    chars == 0 -> 0f
                    chineseChars >= 6 -> 0.9f
                    chineseChars >= 2 && chars >= 12 -> 0.78f
                    blocks >= 3 && chars >= 20 -> 0.85f
                    chars >= 10 -> 0.62f
                    else -> 0.35f
                }
                cont.resume(OcrResult(text.text, confidence, blocks))
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun merge(chinese: OcrResult, latin: OcrResult): OcrResult {
        val chineseCount = chinese.text.count { it in '\u4e00'..'\u9fff' }
        val latinCount = latin.text.count { it.isLetterOrDigit() }
        val text = when {
            chineseCount >= 4 && latin.text.length > chinese.text.length * 1.35f ->
                chinese.text + "\n" + latin.text
            chinese.confidence >= latin.confidence -> chinese.text
            else -> latin.text
        }
        val confidence = maxOf(chinese.confidence, latin.confidence)
        return OcrResult(text = text, confidence = confidence, blockCount = maxOf(chinese.blockCount, latin.blockCount))
    }
}
