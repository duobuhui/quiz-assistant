package com.quizassist.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.quizassist.cache.AnswerCache
import com.quizassist.engine.QuizSolverEngine
import com.quizassist.model.OverlayState
import com.quizassist.model.SolveEvent
import com.quizassist.model.SolveMode
import com.quizassist.model.SolveSnapshot
import com.quizassist.network.LLMRepository
import com.quizassist.ocr.OcrProcessor
import com.quizassist.questionbank.QuestionBankMatcher
import com.quizassist.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object OverlayController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var renderer: OverlayRenderer? = null
    private var params: WindowManager.LayoutParams? = null
    private var settingsStore: SettingsStore? = null
    private var engine: QuizSolverEngine? = null
    private var appContext: Context? = null
    private var captureSessionReady: Boolean = false
    private var solveJob: Job? = null
    private var snapshot = SolveSnapshot()

    fun show(context: Context): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) {
            error("\u672a\u6388\u6743\u60ac\u6d6e\u7a97\u6743\u9650")
        }
        if (renderer != null) return@runCatching

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val store = SettingsStore(appContext)
        val solver = QuizSolverEngine(
            OcrProcessor(),
            LLMRepository(),
            AnswerCache(appContext),
            QuestionBankMatcher(appContext),
        )
        val overlay = OverlayRenderer(
            context = appContext,
            onCapture = { captureAndSolve() },
            onCollapseChanged = { updateOverlayFlags() },
        )
        val lp = overlayParams()

        windowManager = wm
        settingsStore = store
        engine = solver
        this.appContext = appContext
        renderer = overlay
        params = lp
        attachDrag()
        wm.addView(overlay.root, lp)

        scope.launch {
            val settings = store.settings.first()
            overlay.root.alpha = settings.overlayAlpha
            updateOverlayFlags()
        }
    }

    fun hide() {
        solveJob?.cancel()
        solveJob = null
        val wm = windowManager
        val overlay = renderer
        if (wm != null && overlay != null) {
            runCatching { wm.removeView(overlay.root) }
        }
        windowManager = null
        renderer = null
        params = null
        settingsStore = null
        engine = null
        appContext = null
        captureSessionReady = false
        snapshot = SolveSnapshot()
    }

    fun reloadSettings() {
        settingsStore?.refresh()
        updateOverlayFlags()
    }

    private fun captureAndSolve() {
        val context = appContext ?: return
        val overlay = renderer ?: return
        solveJob?.cancel()
        snapshot = SolveSnapshot(state = OverlayState.Capturing, notice = "\u6b63\u5728\u542f\u52a8\u622a\u56fe\u670d\u52a1")
        overlay.render(snapshot)
        if (ProjectionPermissionStore.data == null) {
            onCaptureFailed("\u5c4f\u5e55\u622a\u56fe\u672a\u6388\u6743\u6216\u5df2\u5931\u6548\uff0c\u8bf7\u56de\u5230\u4e3b\u754c\u9762\u91cd\u65b0\u6388\u6743")
            return
        }
        overlay.root.visibility = View.INVISIBLE
        val hiddenAt = SystemClock.uptimeMillis()
        val intent = if (captureSessionReady) {
            CaptureSessionService.captureIntent(context, hiddenAt)
        } else {
            CaptureSessionService.captureIntent(context, hiddenAt)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            overlay.root.visibility = View.VISIBLE
            onCaptureFailed("\u542f\u52a8\u622a\u56fe\u4f1a\u8bdd\u5931\u8d25\uff1a${it.message.orEmpty()}")
        }
    }

    fun ensureCaptureSession(context: Context): Result<Unit> = runCatching {
        val app = context.applicationContext
        if (ProjectionPermissionStore.data == null) {
            error("\u5c4f\u5e55\u622a\u56fe\u672a\u6388\u6743")
        }
        val intent = CaptureSessionService.startIntent(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun onCaptureSessionReady() {
        captureSessionReady = true
        val overlay = renderer ?: return
        snapshot = snapshot.copy(notice = "\u622a\u56fe\u4f1a\u8bdd\u5df2\u5c31\u7eea\uff0c\u4e0b\u6b21\u53ef\u76f4\u63a5\u622a\u56fe")
        overlay.render(snapshot)
    }

    fun onCaptureSessionStopped() {
        captureSessionReady = false
    }

    fun onCaptured(bitmap: Bitmap) {
        val store = settingsStore ?: return
        val solver = engine ?: return
        val overlay = renderer ?: return
        overlay.root.visibility = View.VISIBLE
        solveJob?.cancel()
        solveJob = scope.launch {
            store.refresh()
            val settings = store.settings.first()
            snapshot = snapshot.copy(
                state = OverlayState.Recognizing,
                preview = if (settings.showImagePreview) bitmap else null,
                notice = "\u6b63\u5728 OCR\uff0c\u5e76\u542f\u52a8\u5feb\u901f/\u6df1\u5ea6\u6a21\u578b",
            )
            overlay.render(snapshot)
            solver.solve(bitmap, settings).collect { event -> handleEvent(event) }
        }
    }

    fun onCaptureFailed(message: String) {
        val overlay = renderer ?: return
        overlay.root.visibility = View.VISIBLE
        snapshot = snapshot.copy(state = OverlayState.Error, notice = message)
        overlay.render(snapshot)
    }

    private fun handleEvent(event: SolveEvent) {
        val overlay = renderer ?: return
        snapshot = when (event) {
            SolveEvent.Capturing -> snapshot.copy(state = OverlayState.Capturing)
            is SolveEvent.OcrFinished -> snapshot.copy(
                state = OverlayState.DeepThinking,
                ocrText = event.text,
                notice = "OCR \u7f6e\u4fe1\u5ea6 ${(event.confidence * 100).toInt()}%\uff0c\u6a21\u5f0f ${event.mode.displayName()}",
            )
            is SolveEvent.QuestionBankStatus -> snapshot.copy(
                notice = when {
                    !event.enabled -> "\u9898\u5e93\u6a21\u5f0f\u5df2\u5173\u95ed\uff0c\u542f\u52a8 Flash + \u6df1\u5ea6\u6a21\u578b"
                    event.entryCount <= 0 -> "\u9898\u5e93\u52a0\u8f7d\u5931\u8d25\uff0c\u542f\u52a8 Flash + \u6df1\u5ea6\u6a21\u578b"
                    event.matched -> "\u9898\u5e93 ${event.entryCount}\u6761\uff0c\u5df2\u547d\u4e2d\uff0c\u4e0d\u8bf7\u6c42\u6a21\u578b"
                    else -> "\u9898\u5e93 ${event.entryCount}\u6761\u672a\u547d\u4e2d\uff0c\u542f\u52a8 Flash + \u6df1\u5ea6\u6a21\u578b"
                },
            )
            is SolveEvent.CacheHit -> snapshot.copy(
                state = OverlayState.DeepReady,
                deepText = event.answer.displayText(),
                notice = "\u547d\u4e2d\u672c\u5730\u7f13\u5b58",
            )
            is SolveEvent.QuestionBankHit -> snapshot.copy(
                state = OverlayState.FlashReady,
                flashText = event.answer.displayText(),
                flashElapsedMs = event.elapsedMs,
                notice = "\u547d\u4e2d\u672c\u5730\u9898\u5e93 ${(event.score * 100).toInt()}%",
            )
            is SolveEvent.QuestionBankValidation -> when (event.answer.localCorrect) {
                false -> snapshot.copy(
                    state = OverlayState.DeepReady,
                    deepText = event.answer.displayText(),
                    deepElapsedMs = event.elapsedMs,
                    notice = "\u6df1\u5ea6\u6821\u9a8c\u53d1\u73b0\u9898\u5e93\u7b54\u6848\u4e0d\u6b63\u786e\uff0c\u5df2\u4f7f\u7528\u6df1\u5ea6\u6a21\u578b\u7b54\u6848",
                )
                true -> snapshot.copy(
                    state = OverlayState.DeepReady,
                    deepText = buildString {
                        appendLine("\u9898\u5e93\u7b54\u6848\u5df2\u901a\u8fc7\u6df1\u5ea6\u6821\u9a8c")
                        if (event.answer.reasoning.isNotBlank()) append(event.answer.reasoning)
                    }.trim(),
                    deepElapsedMs = event.elapsedMs,
                    notice = "\u6df1\u5ea6\u6821\u9a8c\u901a\u8fc7\uff0c\u4fdd\u7559\u672c\u5730\u9898\u5e93\u7b54\u6848",
                )
                null -> snapshot.copy(
                    state = OverlayState.DeepReady,
                    deepText = event.answer.displayText(),
                    deepElapsedMs = event.elapsedMs,
                    notice = "\u6df1\u5ea6\u6a21\u578b\u672a\u8fd4\u56de\u660e\u786e\u6821\u9a8c\u6807\u8bb0\uff0c\u4fdd\u7559\u672c\u5730\u9898\u5e93\u7b54\u6848",
                )
            }
            is SolveEvent.FlashToken -> {
                overlay.appendFlash(event.token)
                return
            }
            is SolveEvent.FlashAnswer -> snapshot.copy(
                state = OverlayState.FlashReady,
                flashText = event.answer.flashDisplayText(),
                flashElapsedMs = event.elapsedMs,
                notice = "\u5feb\u901f\u7b54\u6848\u5df2\u663e\u793a\uff0c\u7b49\u5f85\u6df1\u5ea6\u6a21\u578b",
            )
            is SolveEvent.FlashFailure -> snapshot.copy(
                state = OverlayState.DeepThinking,
                flashText = event.message,
                notice = "\u5feb\u901f\u6a21\u578b\u5931\u8d25\uff0c\u7ee7\u7eed\u7b49\u5f85\u6df1\u5ea6\u6a21\u578b",
            )
            is SolveEvent.DeepToken -> {
                overlay.appendDeep(event.token)
                return
            }
            is SolveEvent.DeepAnswer -> snapshot.copy(
                state = OverlayState.DeepReady,
                deepText = event.answer.displayText(),
                deepElapsedMs = event.elapsedMs,
                notice = "\u6df1\u5ea6\u7b54\u6848\u5df2\u81ea\u52a8\u66f4\u65b0",
            )
            is SolveEvent.DeepTimeout -> snapshot.copy(
                state = OverlayState.FlashReady,
                notice = "\u6df1\u5ea6\u6a21\u578b ${event.timeoutSeconds} \u79d2\u5185\u672a\u8fd4\u56de\uff0c\u4fdd\u7559\u5feb\u901f\u7b54\u6848",
            )
            is SolveEvent.DeepFailure -> snapshot.copy(
                state = if (snapshot.flashText.isNotBlank()) OverlayState.FlashReady else OverlayState.Error,
                deepText = snapshot.deepText.ifBlank { "未返回最终答案" },
                notice = "\u6df1\u5ea6\u6a21\u578b\u6682\u65f6\u4e0d\u53ef\u7528\uff0c\u5df2\u4fdd\u7559\u5feb\u901f\u7b54\u6848\uff1a${event.message}",
            )
            is SolveEvent.Failure -> snapshot.copy(state = OverlayState.Error, notice = event.message)
        }
        overlay.render(snapshot)
    }

    private fun com.quizassist.model.StructuredAnswer.displayText(): String =
        buildString {
            if (answer.isNotBlank()) appendLine("\u7b54\u6848\uff1a$answer")
            if (confidence.isNotBlank()) appendLine("\u7f6e\u4fe1\u5ea6\uff1a$confidence")
            if (reasoning.isNotBlank()) appendLine(reasoning)
            if (isBlank()) append(raw)
        }.trim()

    private fun com.quizassist.model.StructuredAnswer.flashDisplayText(): String =
        raw.takeIf { it.isNotBlank() }?.cleanFlashText() ?: displayText()

    private fun String.cleanFlashText(): String {
        val cleaned = replace(Regex("""(?i)\bassistant\b[:：]?\s*"""), "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val joined = cleaned.joinToString("\n")
        Regex("""[A-D][\.、]\s*[^\n，。；;]{1,20}""").find(joined)?.value?.let { answer ->
            return listOf(answer, "\u6839\u636eOCR\u5224\u65ad").joinToString("\n")
        }
        Regex("""(?:答案|选择|选)\s*[:：]?\s*([A-D])""").find(joined)?.groupValues?.getOrNull(1)?.let { letter ->
            return listOf(letter, "\u6839\u636eOCR\u5224\u65ad").joinToString("\n")
        }
        if (cleaned.size <= 3) return cleaned.joinToString("\n")
        val answerLike = cleaned.firstOrNull { line ->
            line.length <= 30 && (
                Regex("""^[A-D][\.、\s：:].+""").containsMatchIn(line) ||
                    line == "\u65e0\u6cd5\u5224\u65ad" ||
                    !line.contains("\u6211\u4eec") && !line.contains("OCR") && !line.contains("\u9898\u76ee")
                )
        }
        val reasonLike = cleaned.firstOrNull { it != answerLike && it.length <= 30 }
        return listOfNotNull(answerLike, reasonLike).takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: cleaned.takeLast(2).joinToString("\n")
    }

    private fun SolveMode.displayName(): String = when (this) {
        SolveMode.TextOnly -> "\u6587\u672c"
        SolveMode.Vision -> "\u89c6\u89c9"
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 220
        }
    }

    private fun updateOverlayFlags() {
        val store = settingsStore ?: return
        val overlay = renderer ?: return
        val lp = params ?: return
        val wm = windowManager ?: return
        scope.launch {
            val settings = store.settings.first()
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (settings.clickThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
            overlay.root.alpha = settings.overlayAlpha
            runCatching { wm.updateViewLayout(overlay.root, lp) }
        }
    }

    private fun attachDrag() {
        val overlay = renderer ?: return
        val lp = params ?: return
        val wm = windowManager ?: return
        val listener = ViewDragTouchListener(
            getX = { lp.x },
            getY = { lp.y },
            setPosition = { x, y ->
                lp.x = x
                lp.y = y
                runCatching { wm.updateViewLayout(overlay.root, lp) }
            },
        )
        overlay.dragTargets.forEach { it.setOnTouchListener(listener) }
    }

    private class ViewDragTouchListener(
        private val getX: () -> Int,
        private val getY: () -> Int,
        private val setPosition: (Int, Int) -> Unit,
    ) : android.view.View.OnTouchListener {
        private var downX = 0
        private var downY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: android.view.View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = getX()
                    downY = getY()
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > 6f || kotlin.math.abs(dy) > 6f) moved = true
                    setPosition(downX + dx.toInt(), downY + dy.toInt())
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    return true
                }
                else -> return false
            }
        }
    }
}
