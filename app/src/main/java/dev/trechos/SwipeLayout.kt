package dev.trechos

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * LinearLayout que reconhece um deslize horizontal por cima dos filhos
 * (ex.: as células da grade) sem atrapalhar os toques normais neles.
 * [onSwipe] recebe +1 (deslizou para a esquerda: próxima) ou -1 (direita: anterior).
 */
class SwipeLayout(context: Context) : LinearLayout(context) {

    var onSwipe: ((Int) -> Unit)? = null

    /** Modo Fluxo: se [capture] aceitar o toque inicial, o gesto inteiro vai para [onCaptured]. */
    var capture: ((MotionEvent) -> Boolean)? = null
    var onCaptured: (MotionEvent) -> Unit = {}
    private var capturing = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; swiping = false
                capturing = capture?.invoke(ev) == true
                if (capturing) return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // só vira deslize se for claramente horizontal; toque normal continua indo para a célula
                if (!swiping && abs(dx) > slop * 3 && abs(dx) > abs(dy) * 1.5f) swiping = true
            }
        }
        return swiping || capturing
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (capturing) {
            onCaptured(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) capturing = false
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                if (swiping) onSwipe?.invoke(if (ev.x < downX) 1 else -1)
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }
}
