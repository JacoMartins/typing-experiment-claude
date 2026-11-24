package dev.trechos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Tecla desenhada à mão: fundo arredondado, rótulo central (ou ícone)
 * e um símbolo pequeno no canto, como no teclado Samsung.
 */
class KeyView(context: Context) : View(context) {

    var label: String = ""
        set(v) { field = v; invalidate() }
    var hint: String? = null
        set(v) { field = v; invalidate() }
    var icon: Drawable? = null
        set(v) { field = v?.mutate(); invalidate() }

    var bg: Int = 0
    var pressedBg: Int = 0
    var fg: Int = 0xFFFFFFFF.toInt()
    var hintFg: Int = 0xFFBDBDC2.toInt()
    var textSizePx: Float = 0f
    var bold = false
    var radiusPx: Float = 0f
    var iconSizePx: Int = 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val rect = RectF()

    init {
        isClickable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        bgPaint.color = if (isPressed && pressedBg != 0) pressedBg else bg
        if (bgPaint.color != 0) canvas.drawRoundRect(rect, radiusPx, radiusPx, bgPaint)

        val ic = icon
        if (ic != null) {
            val s = if (iconSizePx > 0) iconSizePx else (minOf(w, h) * 0.5f).toInt()
            val l = ((w - s) / 2).toInt()
            val t = ((h - s) / 2).toInt()
            ic.setBounds(l, t, l + s, t + s)
            ic.setTint(fg)
            ic.draw(canvas)
        } else if (label.isNotEmpty()) {
            textPaint.color = fg
            textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.textSize = textSizePx
            // encolhe textos longos para caber na tecla
            val maxW = w - 2 * (h * 0.12f)
            val tw = textPaint.measureText(label)
            if (tw > maxW && tw > 0) textPaint.textSize = textSizePx * maxW / tw
            val fm = textPaint.fontMetrics
            val y = h / 2 - (fm.ascent + fm.descent) / 2 + (if (hint != null) h * 0.04f else 0f)
            canvas.drawText(label, w / 2, y, textPaint)
        }

        val hn = hint
        if (!hn.isNullOrEmpty()) {
            hintPaint.color = hintFg
            hintPaint.textSize = h * 0.21f
            val pad = h * 0.11f
            canvas.drawText(hn, w - pad, pad + hintPaint.textSize * 0.8f, hintPaint)
        }
    }
}
