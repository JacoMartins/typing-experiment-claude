package dev.trechos

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Tecla desenhada à mão: fundo arredondado, rótulo central (ou ícone)
 * e um símbolo pequeno no canto, como no teclado Samsung.
 *
 * A área de toque é a View inteira, mas o desenho fica recuado ([insetX], [insetY]):
 * assim o espaço visual entre as teclas também conta como toque da tecla mais próxima,
 * em vez de ser uma área morta onde o toque se perde.
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

    /** Recuo do desenho em relação à área de toque. */
    var insetX = 0f
    var insetY = 0f

    /** Fração extra de área de toque à esquerda/direita (ex.: o "a" e o "l" da fileira do meio). */
    var extraLeft = 0f
    var extraRight = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val rect = RectF()
    private val edgeRect = RectF()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // animação de toque: 0 = solta, 1 = pressionada
    private var press = 0f
    private var pressTarget = 0f
    private var pressAnim: ValueAnimator? = null

    init {
        isClickable = true
    }

    /**
     * Ao pressionar, a tecla "afunda": encolhe um pouco e clareia (rápido, 60ms).
     * Ao soltar, volta devagar (160ms), o que dá a sensação de mola.
     */
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val target = if (isPressed) 1f else 0f
        if (target == pressTarget) return
        pressTarget = target
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, target).apply {
            duration = if (target > press) 60L else 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { press = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pressAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val fullW = width.toFloat()
        val fullH = height.toFloat()
        rect.set(
            fullW * extraLeft + insetX, insetY,
            fullW * (1f - extraRight) - insetX, fullH - insetY,
        )
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = rect.width()
        val h = rect.height()
        bgPaint.color = if (pressedBg != 0 && press > 0f) EVAL.evaluate(press, bg, pressedBg) as Int else bg
        val scale = 1f - 0.05f * press
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        if (bgPaint.color != 0) {
            // Borda de luz bem sutil para um 3D leve:
            //  - tecla escura: linha clara no TOPO (luz batendo de cima);
            //  - tecla clara: linha escura na BASE (sombra embaixo).
            val edge = resources.displayMetrics.density   // 1dp
            if (isLight(bg)) {
                edgePaint.color = 0x1F000000              // preto ~12%
                edgeRect.set(rect.left, rect.top + edge, rect.right, rect.bottom + edge)
                canvas.drawRoundRect(edgeRect, radiusPx, radiusPx, edgePaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, bgPaint)
            } else {
                edgePaint.color = 0x17FFFFFF              // branco ~9%
                canvas.drawRoundRect(rect, radiusPx, radiusPx, edgePaint)
                edgeRect.set(rect.left, rect.top + edge, rect.right, rect.bottom)
                canvas.drawRoundRect(edgeRect, radiusPx, radiusPx, bgPaint)
            }
        }

        val ic = icon
        if (ic != null) {
            val s = if (iconSizePx > 0) iconSizePx else (minOf(w, h) * 0.5f).toInt()
            val l = (cx - s / 2f).toInt()
            val t = (cy - s / 2f).toInt()
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
            val y = cy - (fm.ascent + fm.descent) / 2 + (if (hint != null) h * 0.04f else 0f)
            canvas.drawText(label, cx, y, textPaint)
        }

        val hn = hint
        if (!hn.isNullOrEmpty()) {
            hintPaint.color = hintFg
            hintPaint.textSize = h * 0.21f
            val pad = h * 0.11f
            canvas.drawText(hn, rect.right - pad, rect.top + pad + hintPaint.textSize * 0.8f, hintPaint)
        }
        canvas.restore()
    }

    private companion object {
        val EVAL = ArgbEvaluator()

        /** A cor é clara? (luminância aproximada) */
        fun isLight(c: Int): Boolean {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return 0.299 * r + 0.587 * g + 0.114 * b > 150
        }
    }
}
