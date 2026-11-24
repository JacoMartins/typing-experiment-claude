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

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // só vira deslize se for claramente horizontal; toque normal continua indo para a célula
                if (!swiping && abs(dx) > slop * 3 && abs(dx) > abs(dy) * 1.5f) swiping = true
            }
        }
        return swiping
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
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
