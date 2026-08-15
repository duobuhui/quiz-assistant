package com.quizassist.engine

import android.graphics.Bitmap
import com.quizassist.cache.AnswerCache
import com.quizassist.image.BitmapTools
import com.quizassist.model.AppSettings
import com.quizassist.model.QuizInput
import com.quizassist.model.SolveEvent
import com.quizassist.model.SolveMode
import com.quizassist.model.StructuredAnswer
import com.quizassist.network.LLMRepository
import com.quizassist.ocr.OcrProcessor
import com.quizassist.questionbank.QuestionBankMatcher
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class QuizSolverEngine(
    private val ocrProcessor: OcrProcessor,
    private val llmRepository: LLMRepository,
    private val answerCache: AnswerCache,
    private val questionBankMatcher: QuestionBankMatcher,
) {
    fun solve(bitmap: Bitmap, settings: AppSettings): Flow<SolveEvent> = channelFlow {
        send(SolveEvent.Capturing)
        val input = runCatching { prepareInput(bitmap, settings) }
            .getOrElse {
                send(SolveEvent.Failure("Failed to prepare screenshot: ${it.message}", it))
                close()
                return@channelFlow
            }
        send(
            SolveEvent.OcrFinished(
                text = input.ocr?.text.orEmpty(),
                confidence = input.ocr?.confidence ?: 0f,
                mode = input.mode,
            ),
        )

        val bankStartedAt = System.nanoTime()
        val hit = if (settings.questionBankMode) {
            questionBankMatcher.find(input.ocr?.text.orEmpty())
        } else {
            null
        }
        send(
            SolveEvent.QuestionBankStatus(
                enabled = settings.questionBankMode,
                entryCount = questionBankMatcher.entryCount,
                matched = hit != null,
            ),
        )
        if (settings.questionBankMode) {
            if (hit != null) {
                val answer = StructuredAnswer(
                    answer = hit.entry.answer.trim(),
                    confidence = "${(hit.score * 100).toInt()}%",
                    reasoning = "本地题库关键词命中",
                )
                val elapsed = (System.nanoTime() - bankStartedAt) / 1_000_000L
                send(SolveEvent.QuestionBankHit(answer, elapsed, hit.score))
                answerCache.save(input.cacheKey, input.ocr?.text.orEmpty(), answer)
                launch {
                    val raw = StringBuilder()
                    val startedAt = System.nanoTime()
                    try {
                        val completed = withTimeoutOrNull(settings.maxWaitTimeoutSeconds * 1000L) {
                            llmRepository.streamAnswer(
                                settings.deepProvider,
                                input,
                                deep = true,
                                localAnswer = answer.answer,
                            ).collect { token ->
                                raw.append(token)
                                send(SolveEvent.DeepToken(token))
                            }
                            true
                        } ?: false
                        val validationElapsed = (System.nanoTime() - startedAt) / 1_000_000L
                        if (!completed) {
                            send(SolveEvent.DeepTimeout(settings.maxWaitTimeoutSeconds))
                            return@launch
                        }
                        val validation = llmRepository.parseStructuredAnswer(raw.toString())
                        send(SolveEvent.QuestionBankValidation(validation, validationElapsed))
                        if (validation.localCorrect == false && validation.answer.isNotBlank()) {
                            answerCache.save(input.cacheKey, input.ocr?.text.orEmpty(), validation)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (t: Throwable) {
                        send(SolveEvent.DeepFailure("Deep model validation failed: ${t.message.orEmpty()}"))
                    }
                }
                return@channelFlow
            }
        }

        val cached = answerCache.find(input.cacheKey)
        if (cached != null) {
            send(SolveEvent.CacheHit(cached))
            close()
            return@channelFlow
        }

        val questionText = input.ocr?.text.orEmpty()
        val flashDeferred = async {
            val raw = StringBuilder()
            var elapsed = 0L
            try {
                elapsed = measureTimeMillis {
                    llmRepository.streamAnswer(settings.flashProvider, input, deep = false).collect { token ->
                        raw.append(token)
                    }
                }
                val answer = llmRepository.parseStructuredAnswer(raw.toString())
                send(SolveEvent.FlashAnswer(answer, elapsed))
                answer
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                send(SolveEvent.FlashFailure("Flash model failed: ${t.message.orEmpty()}"))
                null
            }
        }

        val deepDeferred = async {
            val raw = StringBuilder()
            var elapsed = 0L
            try {
                elapsed = measureTimeMillis {
                    llmRepository.streamAnswer(settings.deepProvider, input, deep = true).collect { token ->
                        raw.append(token)
                        send(SolveEvent.DeepToken(token))
                    }
                }
                val answer = llmRepository.parseStructuredAnswer(raw.toString())
                send(SolveEvent.DeepAnswer(answer, elapsed))
                answerCache.save(input.cacheKey, questionText, answer)
                answer
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                send(SolveEvent.DeepFailure("Deep model failed: ${t.message.orEmpty()}"))
                null
            }
        }

        val deepTimed = async {
            val answer = withTimeoutOrNull(settings.maxWaitTimeoutSeconds * 1000L) {
                deepDeferred.await()
            }
            TimedAnswer(completed = deepDeferred.isCompleted, answer = answer)
        }
        launch {
            val deepResult = deepTimed.await()
            if (!deepResult.completed) {
                deepDeferred.cancel()
                send(SolveEvent.DeepTimeout(settings.maxWaitTimeoutSeconds))
            }
            if (deepResult.answer == null) {
                val finalFlashAnswer = flashDeferred.await()
                if (finalFlashAnswer != null) {
                    answerCache.save(input.cacheKey, questionText, finalFlashAnswer)
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    suspend fun prepareOnly(bitmap: Bitmap, settings: AppSettings): QuizInput =
        prepareInput(bitmap, settings)

    private suspend fun prepareInput(bitmap: Bitmap, settings: AppSettings): QuizInput {
        val cropped = BitmapTools.cropByRoi(bitmap, settings.roi)
        val enhanced = BitmapTools.prepareForOcr(cropped)
        val enhancedOcr = runCatching { ocrProcessor.recognize(enhanced) }.getOrNull()
        val originalOcr = if (enhancedOcr?.usable == true) {
            null
        } else if (enhancedOcr != null && enhancedOcr.text.count { it in '\u4e00'..'\u9fff' } >= 6) {
            null
        } else {
            runCatching { ocrProcessor.recognize(cropped) }.getOrNull()
        }
        if (enhanced !== cropped) enhanced.recycle()
        val ocr = listOfNotNull(enhancedOcr, originalOcr)
            .maxWithOrNull(compareBy<com.quizassist.model.OcrResult> { it.confidence }.thenBy { it.text.length })
        val mode = if (settings.useVisionWhenOcrWeak && ocr?.usable != true) {
            SolveMode.Vision
        } else {
            SolveMode.TextOnly
        }
        val base64 = if (mode == SolveMode.Vision) BitmapTools.toBase64Jpeg(cropped) else null
        val key = BitmapTools.perceptualEnoughHash(cropped, ocr?.text)
        return QuizInput(
            bitmap = bitmap,
            croppedBitmap = cropped,
            ocr = ocr,
            mode = mode,
            imageBase64Jpeg = base64,
            cacheKey = key,
        )
    }

    private data class TimedAnswer(
        val completed: Boolean,
        val answer: com.quizassist.model.StructuredAnswer?,
    )
}
