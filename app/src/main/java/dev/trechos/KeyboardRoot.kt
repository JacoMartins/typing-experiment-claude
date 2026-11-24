package dev.trechos

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Raiz do teclado: o tamanho é SEMPRE o das teclas ([content]).
 * A camada de popups ([overlay]: prévia, acentos, menu) fica por cima,
 * com o mesmo tamanho, sem nunca fazer o teclado crescer.
 */
class KeyboardRoot(context: Context, private val content: View, private val overlay: View) : FrameLayout(context) {

    init {
        addView(content)
        addView(overlay)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val w = content.measuredWidth
        val h = content.measuredHeight
        overlay.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        overlay.layout(0, 0, overlay.measuredWidth, overlay.measuredHeight)
    }
}
