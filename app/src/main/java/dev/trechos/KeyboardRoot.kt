package dev.trechos

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout

/**
 * Raiz do teclado.
 *
 * Modo normal: o tamanho é SEMPRE o das teclas ([content]). A camada de popups
 * ([overlay]: prévia, acentos, menu) fica por cima, sem fazer o teclado crescer.
 *
 * Modo flutuante: a raiz ocupa a área toda que o Android oferece e o [content]
 * vira um "card" solto, posicionado por [floatX]/[floatY]. O serviço avisa ao
 * Android que só a área do card capta toques (o resto passa para o app embaixo).
 */
class KeyboardRoot(context: Context, private val content: View, private val overlay: View) : FrameLayout(context) {

    var floating = false
    var floatX = -1f
    var floatY = -1f

    init {
        addView(content)
        addView(overlay)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!floating) {
            content.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val w = content.measuredWidth
            val h = content.measuredHeight
            overlay.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
            return
        }
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cardW = content.layoutParams?.width?.takeIf { it > 0 } ?: w
        content.measure(MeasureSpec.makeMeasureSpec(minOf(cardW, w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        overlay.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        overlay.layout(0, 0, overlay.measuredWidth, overlay.measuredHeight)
        if (floating) applyPosition() else { content.translationX = 0f; content.translationY = 0f }
    }

    /** Move o card (em px), sem sair da tela. */
    fun moveBy(dx: Float, dy: Float) {
        floatX = currentX() + dx
        floatY = currentY() + dy
        applyPosition()
    }

    private fun currentX() = if (floatX < 0) (width - content.width) / 2f else floatX
    private fun currentY() = if (floatY < 0) height - content.height - height * 0.04f else floatY

    private fun applyPosition() {
        val maxX = (width - content.width).coerceAtLeast(0).toFloat()
        val maxY = (height - content.height).coerceAtLeast(0).toFloat()
        floatX = currentX().coerceIn(0f, maxX)
        floatY = currentY().coerceIn(0f, maxY)
        content.translationX = floatX
        content.translationY = floatY
    }

    /** Retângulo do card, nas coordenadas da raiz. */
    fun cardRect(): Rect {
        val x = content.translationX.toInt()
        val y = content.translationY.toInt()
        return Rect(x, y, x + content.width, y + content.height)
    }
}
