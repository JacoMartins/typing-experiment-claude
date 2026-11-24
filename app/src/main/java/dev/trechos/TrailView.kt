package dev.trechos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Rastro do dedo durante o swipe (desenhado na camada de popups, por cima do teclado). */
class TrailView(context: Context, color: Int, widthPx: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 170
    }
    private val path = Path()
    private var empty = true

    /** Ponto já nas coordenadas desta View. */
    fun add(x: Float, y: Float) {
        if (empty) { path.moveTo(x, y); empty = false } else path.lineTo(x, y)
        invalidate()
    }

    fun clear() { path.reset(); empty = true; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (!empty) canvas.drawPath(path, paint)
    }
}
