package dev.trechos

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Envolve o QWERTY e reconhece o swipe de palavras: se o dedo desce numa letra e
 * anda mais que [threshold] px sem levantar, o gesto deixa de ser um toque e passa a
 * ser um traço (as teclas recebem CANCEL; o traço inteiro vem para cá).
 */
class GestureLayer(context: Context) : FrameLayout(context) {

    /** O ponto (tela) está sobre uma letra? Só traços que começam numa letra viram swipe. */
    var startsOnLetter: (Float, Float) -> Boolean = { _, _ -> false }
    var onGestureStart: (List<FloatArray>) -> Unit = {}
    var onGestureMove: (Float, Float) -> Unit = { _, _ -> }
    var onGestureEnd: () -> Unit = {}
    var swipeEnabled = true
    var threshold = 40f

    private var downX = 0f
    private var downY = 0f
    private var candidate = false
    private var gesturing = false
    private val early = ArrayList<FloatArray>()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                gesturing = false
                candidate = swipeEnabled && startsOnLetter(ev.rawX, ev.rawY)
                early.clear()
                if (candidate) early.add(floatArrayOf(ev.rawX, ev.rawY))
            }
            MotionEvent.ACTION_MOVE -> {
                if (!candidate || ev.pointerCount > 1) return false
                early.add(floatArrayOf(ev.rawX, ev.rawY))
                if (hypot(ev.rawX - downX, ev.rawY - downY) > threshold) {
                    gesturing = true
                    onGestureStart(early.toList())
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> candidate = false   // segundo dedo: digitação normal
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onGestureMove(ev.rawX, ev.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gesturing = false
                onGestureEnd()
            }
        }
        return true
    }
}
