package com.companion.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Canvas-rendered face with cached paints and a frame loop that only runs during motion. */
class FaceView(context: Context) : View(context), FaceBehavior.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val animator = FaceAnimator()
    private val behavior = FaceBehavior(handler, this)

    private val density = resources.displayMetrics.density
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val irisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val irisRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.13f
    }

    private val panelRect = RectF()
    private val eyeRect = RectF()
    private val highlightRect = RectF()
    private val diagnosticButtonRect = RectF()
    private val diagnosticButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val diagnosticButtonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var panelGradient: LinearGradient? = null
    private var eyeGradient: LinearGradient? = null
    private var eyeWidth = 0f
    private var eyeHeight = 0f
    private var panelRadius = 0f
    private var faceCenterY = 0f
    private var status = "MERHABA"
    private var displayStatus = status
    private var caption = ""
    private var displayCaption = ""
    private var onFaceTapListener: (() -> Unit)? = null
    private var onDiagnosticTapListener: (() -> Unit)? = null
    private var diagnosticTouch = false
    private var running = false
    private var destroyed = false
    private var frameQueued = false
    private var touchDownAt = 0L

    private val frameRunnable = Runnable {
        frameQueued = false
        if (!running || destroyed) return@Runnable
        val now = SystemClock.uptimeMillis()
        animator.update(now)
        invalidate()
        if (animator.isAnimating(now)) scheduleFrame()
    }

    init {
        isClickable = true
        setPadding(0, 0, 0, 0)
        // Keep Android's normal hardware acceleration enabled. The drawing code uses no shadows.
    }

    fun start() {
        if (destroyed || running) return
        running = true
        behavior.start()
        invalidate()
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(frameRunnable)
        frameQueued = false
        behavior.stop()
    }

    fun destroy() {
        if (destroyed) return
        stop()
        destroyed = true
        onFaceTapListener = null
        onDiagnosticTapListener = null
        handler.removeCallbacksAndMessages(null)
    }

    fun setOnFaceTapListener(listener: (() -> Unit)?) {
        onFaceTapListener = listener
    }

    fun setOnDiagnosticTapListener(listener: (() -> Unit)?) {
        onDiagnosticTapListener = listener
    }

    fun showVoiceListening() = behavior.onVoiceListening()

    fun showVoiceProcessing() = behavior.onVoiceProcessing()

    fun showVoiceSpeaking() = behavior.onVoiceSpeaking()

    fun showVoiceError(message: String) = behavior.onVoiceError(message)

    fun finishVoiceInteraction() = behavior.finishVoiceInteraction()

    /** Shows the latest user transcript or spoken reply without persisting it. */
    fun setCaption(text: String?) {
        caption = text?.trim()?.take(MAX_CAPTION_CHARS).orEmpty()
        updateDisplayText()
        invalidate()
    }

    override fun setExpression(expression: FaceExpression, status: String, durationMs: Long) {
        this.status = status
        updateDisplayText()
        animator.transitionTo(expression, SystemClock.uptimeMillis(), durationMs)
        requestAnimatedDraw()
    }

    override fun moveGaze(x: Float, y: Float, durationMs: Long) {
        animator.moveGaze(x, y, SystemClock.uptimeMillis(), durationMs)
        requestAnimatedDraw()
    }

    override fun blink(durationMs: Long) {
        animator.startBlink(SystemClock.uptimeMillis(), durationMs)
        requestAnimatedDraw()
    }

    private fun requestAnimatedDraw() {
        if (!running || destroyed) return
        invalidate()
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (!running || destroyed || frameQueued) return
        frameQueued = true
        val delayMs = if (animator.isSettledSleeping) 83L else 33L
        handler.postDelayed(frameRunnable, delayMs)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val panelWidth = w * 0.93f
        val panelHeight = h * 0.72f
        val panelCenterY = h * 0.5f
        panelRect.set(
            (w - panelWidth) * 0.5f,
            panelCenterY - panelHeight * 0.5f,
            (w + panelWidth) * 0.5f,
            panelCenterY + panelHeight * 0.5f
        )
        updateDisplayText()
        panelRadius = min(dp(34f), panelHeight * 0.16f)
        panelGradient = LinearGradient(
            0f,
            panelRect.top,
            0f,
            panelRect.bottom,
            intArrayOf(Color.rgb(17, 29, 43), Color.rgb(10, 17, 27)),
            null,
            Shader.TileMode.CLAMP
        )

        eyeHeight = min(h * 0.30f, w * 0.18f)
        eyeWidth = min(w * 0.25f, eyeHeight * 1.5f)
        faceCenterY = h * 0.455f
        eyeGradient = LinearGradient(
            0f,
            faceCenterY - eyeHeight * 0.5f,
            0f,
            faceCenterY + eyeHeight * 0.5f,
            intArrayOf(Color.rgb(218, 252, 255), Color.rgb(104, 224, 255), Color.rgb(37, 177, 238)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        diagnosticButtonRect.set(
            w - dp(126f),
            h - dp(54f),
            w - dp(16f),
            h - dp(16f)
        )
        diagnosticButtonBorderPaint.strokeWidth = dp(1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (width == 0 || height == 0) return

        val now = SystemClock.uptimeMillis()
        animator.update(now)

        val breathing = animator.breathing(now)
        val scale = 1f + breathing * 0.0035f
        canvas.save()
        canvas.scale(scale, scale, width * 0.5f, panelRect.centerY())

        panelPaint.shader = panelGradient
        panelPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(panelRect, panelRadius, panelRadius, panelPaint)

        panelBorderPaint.color = PANEL_BORDER
        canvas.drawRoundRect(panelRect, panelRadius, panelRadius, panelBorderPaint)

        drawPanelDetails(canvas)
        drawEyes(canvas)
        drawStatus(canvas)
        canvas.restore()
        drawDiagnosticButton(canvas)

        if (animator.isAnimating(now)) scheduleFrame()
    }

    private fun drawDiagnosticButton(canvas: Canvas) {
        val radius = dp(19f)
        diagnosticButtonPaint.color = 0xCC0B1721.toInt()
        canvas.drawRoundRect(diagnosticButtonRect, radius, radius, diagnosticButtonPaint)
        diagnosticButtonBorderPaint.color = 0x553FAFD0
        canvas.drawRoundRect(diagnosticButtonRect, radius, radius, diagnosticButtonBorderPaint)

        textPaint.style = Paint.Style.FILL
        textPaint.color = 0xBB9BC9DA.toInt()
        textPaint.textSize = dp(9f)
        textPaint.letterSpacing = 0.12f
        canvas.drawText("SES TESTİ", diagnosticButtonRect.centerX(), diagnosticButtonRect.centerY() + dp(3f), textPaint)
        textPaint.letterSpacing = 0.13f
    }

    private fun drawPanelDetails(canvas: Canvas) {
        detailPaint.style = Paint.Style.FILL
        detailPaint.color = DETAIL_DOT
        val dotY = panelRect.top + dp(23f)
        val dotRadius = dp(1.6f)
        canvas.drawCircle(panelRect.left + dp(24f), dotY, dotRadius, detailPaint)
        canvas.drawCircle(panelRect.right - dp(24f), dotY, dotRadius, detailPaint)

        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeWidth = dp(1f)
        detailPaint.color = DETAIL_LINE
        val inset = dp(23f)
        canvas.drawLine(panelRect.left + inset, panelRect.bottom - dp(27f), panelRect.left + inset + dp(22f), panelRect.bottom - dp(27f), detailPaint)
        canvas.drawLine(panelRect.right - inset - dp(22f), panelRect.bottom - dp(27f), panelRect.right - inset, panelRect.bottom - dp(27f), detailPaint)

        detailPaint.style = Paint.Style.FILL
        detailPaint.color = BREATHING_DOT
        val pulse = if (animator.expression == FaceExpression.SLEEPING) animator.breathing(SystemClock.uptimeMillis()) else 0.35f
        detailPaint.alpha = (48 + pulse * 75f).toInt()
        canvas.drawCircle(width * 0.5f, panelRect.bottom - dp(27f), dp(2.3f), detailPaint)
        detailPaint.alpha = 255
    }

    private fun drawEyes(canvas: Canvas) {
        val gap = eyeWidth * 0.50f
        val leftCenter = width * 0.5f - gap * 0.5f - eyeWidth * 0.5f
        val rightCenter = width * 0.5f + gap * 0.5f + eyeWidth * 0.5f
        drawEye(canvas, leftCenter, -animator.tilt)
        drawEye(canvas, rightCenter, animator.tilt)
    }

    private fun drawEye(canvas: Canvas, centerX: Float, tilt: Float) {
        val currentWidth = eyeWidth * animator.eyeScaleX
        val openAmount = (1f - maxOf(animator.lidClosure, animator.blinkAmount)).coerceAtLeast(0.025f)
        val currentHeight = maxOf(dp(3f), eyeHeight * animator.eyeScaleY * openAmount)
        val corner = min(currentWidth * 0.28f, currentHeight * 0.47f)
        eyeRect.set(
            centerX - currentWidth * 0.5f,
            faceCenterY - currentHeight * 0.5f,
            centerX + currentWidth * 0.5f,
            faceCenterY + currentHeight * 0.5f
        )

        canvas.save()
        if (tilt != 0f) canvas.rotate(tilt * 57.29578f, centerX, faceCenterY)

        // Two translucent outlines create a restrained bloom without software shadows.
        eyeGlowPaint.color = GLOW_SOFT
        eyeGlowPaint.strokeWidth = dp(10f)
        canvas.drawRoundRect(eyeRect, corner, corner, eyeGlowPaint)
        eyeGlowPaint.color = GLOW_EDGE
        eyeGlowPaint.strokeWidth = dp(4f)
        canvas.drawRoundRect(eyeRect, corner, corner, eyeGlowPaint)

        eyePaint.shader = eyeGradient
        eyePaint.style = Paint.Style.FILL
        canvas.drawRoundRect(eyeRect, corner, corner, eyePaint)

        // A cool reflection gives the eye a glassy, physical surface.
        highlightRect.set(
            eyeRect.left + currentWidth * 0.13f,
            eyeRect.top + currentHeight * 0.10f,
            eyeRect.right - currentWidth * 0.13f,
            eyeRect.top + currentHeight * 0.31f
        )
        glintPaint.color = EYE_REFLECTION
        canvas.drawRoundRect(highlightRect, currentHeight * 0.12f, currentHeight * 0.12f, glintPaint)

        if (openAmount > 0.22f) {
            val maxPupilRadius = min(currentWidth * 0.145f, currentHeight * 0.28f)
            val pupilX = centerX + animator.gazeX * currentWidth * 0.22f
            val pupilY = faceCenterY + animator.gazeY * currentHeight * 0.26f
            val irisRadius = maxPupilRadius

            irisPaint.color = IRIS_CYAN
            canvas.drawCircle(pupilX, pupilY, irisRadius * 1.13f, irisPaint)

            irisRingPaint.color = IRIS_RING
            irisRingPaint.strokeWidth = maxOf(dp(1.2f), irisRadius * 0.12f)
            canvas.drawCircle(pupilX, pupilY, irisRadius * 0.96f, irisRingPaint)

            pupilPaint.color = PUPIL
            canvas.drawCircle(pupilX, pupilY, irisRadius * 0.72f, pupilPaint)

            glintPaint.color = Color.WHITE
            canvas.drawCircle(
                pupilX - irisRadius * 0.28f,
                pupilY - irisRadius * 0.30f,
                irisRadius * 0.20f,
                glintPaint
            )
        }

        if (openAmount < 0.12f) {
            detailPaint.style = Paint.Style.STROKE
            detailPaint.strokeWidth = dp(1.4f)
            detailPaint.color = CLOSED_EYE_LINE
            canvas.drawLine(
                eyeRect.left + currentWidth * 0.14f,
                faceCenterY,
                eyeRect.right - currentWidth * 0.14f,
                faceCenterY,
                detailPaint
            )
            detailPaint.style = Paint.Style.FILL
        }

        canvas.restore()
    }

    private fun drawStatus(canvas: Canvas) {
        textPaint.style = Paint.Style.FILL
        textPaint.color = STATUS_COLOR
        textPaint.textSize = dp(13f)
        canvas.drawText(displayStatus, width * 0.5f, panelRect.bottom - dp(54f), textPaint)

        if (displayCaption.isNotEmpty()) {
            textPaint.color = CAPTION_COLOR
            textPaint.textSize = dp(11f)
            textPaint.letterSpacing = 0f
            canvas.drawText(displayCaption, width * 0.5f, panelRect.bottom - dp(27f), textPaint)
            textPaint.letterSpacing = 0.13f
        }

        textPaint.color = SUBTITLE_COLOR
        textPaint.textSize = dp(9f)
        textPaint.letterSpacing = 0.21f
        canvas.drawText("MASA ARKADAŞIN", width * 0.5f, height * 0.91f, textPaint)
        textPaint.letterSpacing = 0.13f
    }

    private fun updateDisplayText() {
        val availableWidth = (panelRect.width() - dp(56f)).coerceAtLeast(0f)
        textPaint.letterSpacing = 0.13f
        textPaint.textSize = dp(13f)
        displayStatus = TextUtils.ellipsize(status, textPaint, availableWidth, TextUtils.TruncateAt.END).toString()
        textPaint.letterSpacing = 0f
        textPaint.textSize = dp(11f)
        displayCaption = if (caption.isEmpty() || availableWidth <= 0f) "" else
            TextUtils.ellipsize(caption, textPaint, availableWidth, TextUtils.TruncateAt.END).toString()
        textPaint.letterSpacing = 0.13f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val gazeX = ((event.x / width.coerceAtLeast(1)) * 2f - 1f).coerceIn(-1f, 1f)
        val gazeY = ((event.y / height.coerceAtLeast(1)) * 2f - 1f).coerceIn(-0.8f, 0.8f)

        if (diagnosticTouch) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    diagnosticTouch = false
                    if (diagnosticButtonRect.contains(event.x, event.y)) {
                        onDiagnosticTapListener?.invoke()
                        performClick()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    diagnosticTouch = false
                    return true
                }
                else -> return true
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (diagnosticButtonRect.contains(event.x, event.y)) {
                    diagnosticTouch = true
                    return true
                }
                touchDownAt = SystemClock.uptimeMillis()
                behavior.onTouchDown(gazeX, gazeY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                behavior.onTouchMove(gazeX, gazeY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val elapsedMs = SystemClock.uptimeMillis() - touchDownAt
                behavior.onTouchUp(elapsedMs)
                if (elapsedMs < 650L) onFaceTapListener?.invoke()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                behavior.onTouchCancel()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        val PANEL_BORDER = 0x442b4359
        val DETAIL_DOT = 0x665b829d
        val DETAIL_LINE = 0x333c627b
        val BREATHING_DOT = 0xFF66DFFF.toInt()
        val GLOW_SOFT = 0x1235BFFF
        val GLOW_EDGE = 0x263BCFFF
        val EYE_REFLECTION = 0x467CFAFF
        val IRIS_CYAN = 0xFF0C6C8D.toInt()
        val IRIS_RING = 0xCCB5F6FF.toInt()
        val PUPIL = 0xFF082A43.toInt()
        val CLOSED_EYE_LINE = 0xAA70E8FF.toInt()
        val STATUS_COLOR = 0xCCB4D8E8.toInt()
        val CAPTION_COLOR = 0xFF85CBE1.toInt()
        val SUBTITLE_COLOR = 0x665B8198
        const val MAX_CAPTION_CHARS = 240
    }
}
