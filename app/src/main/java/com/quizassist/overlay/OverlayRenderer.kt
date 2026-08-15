package com.quizassist.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.quizassist.model.OverlayState
import com.quizassist.model.SolveSnapshot

class OverlayRenderer(
    context: Context,
    private val onCapture: () -> Unit,
    private val onCollapseChanged: (Boolean) -> Unit,
) {
    val root: FrameLayout = FrameLayout(context)
    private val ball = TextView(context)
    private val panel = LinearLayout(context)
    private val status = TextView(context)
    private val flashTitle = TextView(context)
    private val flash = TextView(context)
    private val deepTitle = TextView(context)
    private val deep = TextView(context)
    private val copy = Button(context)
    private var copyText = ""

    val dragTargets: List<View>
        get() = listOf(ball, status)

    init {
        root.setPadding(6, 6, 6, 6)
        setupBall()
        setupPanel(context)
        root.addView(ball)
        root.addView(panel)
        render(SolveSnapshot())
        setCollapsed(true, notify = false)
    }

    fun render(snapshot: SolveSnapshot) {
        status.text = when (snapshot.state) {
            OverlayState.Idle -> "\u7b49\u5f85"
            OverlayState.Capturing -> "\u6b63\u5728\u622a\u56fe..."
            OverlayState.Recognizing -> "\u6b63\u5728\u8bc6\u522b..."
            OverlayState.FlashReady -> "\u5feb\u901f\u7b54\u6848\u5df2\u8fd4\u56de"
            OverlayState.DeepThinking -> "\u6df1\u5ea6\u6a21\u578b\u601d\u8003\u4e2d..."
            OverlayState.DeepReady -> "\u6df1\u5ea6\u7b54\u6848\u5df2\u66f4\u65b0"
            OverlayState.Error -> "\u53d1\u751f\u9519\u8bef"
        } + snapshot.notice.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        flashTitle.text = "\u5feb\u901f\u6a21\u578b ${snapshot.flashElapsedMs?.let { "(${it}ms)" }.orEmpty()}"
        deepTitle.text = "\u6df1\u5ea6\u6a21\u578b ${snapshot.deepElapsedMs?.let { "(${it}ms)" }.orEmpty()}"
        flash.text = snapshot.flashText.ifBlank { "-" }
        deep.text = snapshot.deepText.ifBlank { "-" }
        copyText = snapshot.deepText.ifBlank { snapshot.flashText }
    }

    fun appendFlash(token: String) {
        if (flash.text == "-") flash.text = ""
        flash.append(token)
        copyText = flash.text.toString()
    }

    fun appendDeep(token: String) {
        if (deep.text == "-") deep.text = ""
        deep.append(token)
        copyText = deep.text.toString()
    }

    fun setCollapsed(value: Boolean, notify: Boolean = true) {
        ball.visibility = if (value) View.VISIBLE else View.GONE
        panel.visibility = if (value) View.GONE else View.VISIBLE
        if (notify) onCollapseChanged(value)
    }

    private fun setupBall() {
        ball.text = "\u7b54"
        ball.textSize = 20f
        ball.typeface = Typeface.DEFAULT_BOLD
        ball.gravity = Gravity.CENTER
        ball.setTextColor(Color.WHITE)
        ball.background = rounded(0xE62E6F5E.toInt(), 36f)
        ball.setOnClickListener { setCollapsed(false) }
        ball.layoutParams = FrameLayout.LayoutParams(72, 72)
    }

    private fun setupPanel(context: Context) {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(16, 14, 16, 14)
        panel.background = rounded(0xF21B2430.toInt(), 18f)
        panel.layoutParams = FrameLayout.LayoutParams(680, FrameLayout.LayoutParams.WRAP_CONTENT)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val capture = Button(context).apply {
            text = "\u622a\u56fe\u7b54\u9898"
            setOnClickListener { onCapture() }
        }
        val collapse = Button(context).apply {
            text = "\u6536\u8d77"
            setOnClickListener { setCollapsed(true) }
        }
        copy.text = "\u590d\u5236"
        copy.setOnClickListener {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("quiz-answer", copyText))
        }
        row.addView(capture)
        row.addView(copy)
        row.addView(collapse)

        setupText(status, 13f, 0xFFD7DEE8.toInt(), bold = false)
        setupText(flashTitle, 13f, 0xFF8FE3CF.toInt(), bold = true)
        setupText(flash, 20f, 0xFFFFFFFF.toInt(), bold = false)
        setupText(deepTitle, 13f, 0xFFFFD166.toInt(), bold = true)
        setupText(deep, 15f, 0xFFF4F7FB.toInt(), bold = false)

        val scroll = ScrollView(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(status)
            addDivider(context)
            addView(flashTitle)
            addView(flash)
            addDivider(context)
            addView(deepTitle)
            addView(deep)
        }
        scroll.addView(content)
        panel.addView(scroll)
    }

    private fun setupText(view: TextView, size: Float, color: Int, bold: Boolean) {
        view.setTextColor(color)
        view.textSize = size
        view.includeFontPadding = true
        view.setPadding(0, 8, 0, 8)
        if (bold) view.typeface = Typeface.DEFAULT_BOLD
    }

    private fun LinearLayout.addDivider(context: Context) {
        addView(
            View(context).apply {
                setBackgroundColor(0x334B5563)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1,
                ).apply {
                    topMargin = 6
                    bottomMargin = 6
                }
            },
        )
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
}
