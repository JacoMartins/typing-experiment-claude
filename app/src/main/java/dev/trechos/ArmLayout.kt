package dev.trechos

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Envolve o QWERTY do modo híbrido. Quando está "armado" (a palavra já tem letras
 * suficientes), um toque numa letra não digita a letra: é entregue a [onArmedTouch],
 * que faz a grade nascer sob o dedo. O gesto inteiro (descer, deslizar, soltar)
 * continua chegando aqui, mesmo depois que a grade aparece.
 */
class ArmLayout(context: Context) : FrameLayout(context) {

    var shouldIntercept: (MotionEvent) -> Boolean = { false }
    var onArmedTouch: (MotionEvent) -> Unit = {}
    private var capturing = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) capturing = shouldIntercept(ev)
        return capturing
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!capturing) return false
        onArmedTouch(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) capturing = false
        return true
    }
}
