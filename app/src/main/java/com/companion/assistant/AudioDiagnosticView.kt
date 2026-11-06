package com.companion.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small native control surface for checking Android audio output routes. */
internal class AudioDiagnosticView(
    context: Context,
    private val listener: Listener
) : LinearLayout(context) {

    enum class Output(val label: String) {
        SPEAKER("Hoparlör"),
        EARPIECE("Ahize"),
        HEADPHONES("Kablolu kulaklık")
    }

    interface Listener {
        fun onOutputSelected(output: Output)
        fun onReturnToFace()
    }

    private val density = resources.displayMetrics.density
    private val statusView = TextView(context).apply {
        setTextColor(0xFFB7CFDC.toInt())
        textSize = 15f
        gravity = Gravity.CENTER
        setLineSpacing(dp(5f).toFloat(), 1f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        setPadding(dp(28f), dp(20f), dp(28f), dp(20f))

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val title = TextView(context).apply {
            text = "SES ÇIKIŞI TANILAMA"
            setTextColor(0xFFE7F8FF.toInt())
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            letterSpacing = 0.07f
        }
        content.addView(title, centeredParams())

        val subtitle = TextView(context).apply {
            text = "Her düğme aynı kısa test sesini çalar."
            setTextColor(0xFF7894A4.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val subtitleParams = centeredParams().apply { topMargin = dp(7f) }
        content.addView(subtitle, subtitleParams)

        statusView.text = "Hazırlanıyor…"
        val statusParams = centeredParams().apply {
            topMargin = dp(16f)
            bottomMargin = dp(18f)
        }
        content.addView(statusView, statusParams)

        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(buttons, centeredParams())
        addOutputButton(buttons, Output.SPEAKER)
        addOutputButton(buttons, Output.EARPIECE)
        addOutputButton(buttons, Output.HEADPHONES)

        val back = Button(context).apply {
            text = "YÜZE DÖN"
            isAllCaps = false
            setTextColor(0xFF9BB6C5.toInt())
            backgroundTintList = ColorStateList.valueOf(0xFF18232B.toInt())
            setOnClickListener { listener.onReturnToFace() }
        }
        val backParams = centeredParams().apply { topMargin = dp(22f) }
        content.addView(back, backParams)
    }

    fun showStatus(message: String) {
        statusView.text = message
    }

    private fun addOutputButton(parent: LinearLayout, output: Output) {
        val button = Button(context).apply {
            text = when (output) {
                Output.SPEAKER -> "HOPARLÖRÜ DENE"
                Output.EARPIECE -> "AHİZEYİ DENE"
                Output.HEADPHONES -> "KULAKLIĞI DENE"
            }
            isAllCaps = false
            setTextColor(0xFFE5F8FF.toInt())
            backgroundTintList = ColorStateList.valueOf(0xFF123246.toInt())
            setOnClickListener { listener.onOutputSelected(output) }
        }
        val params = LayoutParams(0, dp(52f), 1f).apply {
            marginStart = dp(6f)
            marginEnd = dp(6f)
        }
        parent.addView(button, params)
    }

    private fun centeredParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()
}
