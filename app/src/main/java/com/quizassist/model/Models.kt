package com.quizassist.model

import android.graphics.Bitmap

data class ProviderConfig(
    val baseUrl: String = "https://api.openai.com/v1/",
    val apiKey: String = "",
    val modelName: String = "gpt-4o-mini",
    val temperature: Double = 0.1,
    val enableSearchHint: Boolean = false,
    val reasoningEffort: String = "",
)

data class AppSettings(
    val flashProvider: ProviderConfig = ModelPresets.flash.first().provider,
    val deepProvider: ProviderConfig = ModelPresets.deep.first().provider,
    val maxWaitTimeoutSeconds: Int = 60,
    val useVisionWhenOcrWeak: Boolean = true,
    val showImagePreview: Boolean = false,
    val overlayAlpha: Float = 0.92f,
    val clickThrough: Boolean = false,
    val questionBankMode: Boolean = false,
    val roi: RoiBox? = RoiBox(0.04f, 0.14f, 0.92f, 0.58f),
)

data class RoiBox(
    val leftRatio: Float,
    val topRatio: Float,
    val widthRatio: Float,
    val heightRatio: Float,
) {
    fun isValid(): Boolean =
        leftRatio in 0f..1f && topRatio in 0f..1f &&
            widthRatio > 0.05f && heightRatio > 0.05f &&
            leftRatio + widthRatio <= 1.02f && topRatio + heightRatio <= 1.02f
}

data class OcrResult(
    val text: String,
    val confidence: Float,
    val blockCount: Int,
) {
    val usable: Boolean get() = text.trim().length >= 8 && confidence >= 0.45f
}

enum class SolveMode {
    TextOnly,
    Vision,
}

data class QuizInput(
    val bitmap: Bitmap,
    val croppedBitmap: Bitmap,
    val ocr: OcrResult?,
    val mode: SolveMode,
    val imageBase64Jpeg: String?,
    val cacheKey: String,
)

data class StructuredAnswer(
    val answer: String = "",
    val confidence: String = "",
    val reasoning: String = "",
    val sources: List<String> = emptyList(),
    val raw: String = "",
    val localCorrect: Boolean? = null,
) {
    val hasAnswer: Boolean get() = answer.isNotBlank() || raw.isNotBlank()
}

sealed interface SolveEvent {
    data object Capturing : SolveEvent
    data class OcrFinished(val text: String, val confidence: Float, val mode: SolveMode) : SolveEvent
    data class QuestionBankStatus(val enabled: Boolean, val entryCount: Int, val matched: Boolean) : SolveEvent
    data class CacheHit(val answer: StructuredAnswer) : SolveEvent
    data class QuestionBankHit(val answer: StructuredAnswer, val elapsedMs: Long, val score: Float) : SolveEvent
    data class QuestionBankValidation(val answer: StructuredAnswer, val elapsedMs: Long) : SolveEvent
    data class FlashToken(val token: String) : SolveEvent
    data class FlashAnswer(val answer: StructuredAnswer, val elapsedMs: Long) : SolveEvent
    data class FlashFailure(val message: String) : SolveEvent
    data class DeepToken(val token: String) : SolveEvent
    data class DeepAnswer(val answer: StructuredAnswer, val elapsedMs: Long) : SolveEvent
    data class DeepTimeout(val timeoutSeconds: Int) : SolveEvent
    data class DeepFailure(val message: String) : SolveEvent
    data class Failure(val message: String, val cause: Throwable? = null) : SolveEvent
}

data class SolveSnapshot(
    val state: OverlayState = OverlayState.Idle,
    val preview: Bitmap? = null,
    val ocrText: String = "",
    val flashText: String = "",
    val flashElapsedMs: Long? = null,
    val deepText: String = "",
    val deepElapsedMs: Long? = null,
    val notice: String = "",
)

enum class OverlayState {
    Idle,
    Capturing,
    Recognizing,
    FlashReady,
    DeepThinking,
    DeepReady,
    Error,
}
