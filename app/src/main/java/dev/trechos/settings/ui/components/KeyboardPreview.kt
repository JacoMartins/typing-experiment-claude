package dev.trechos.settings.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.trechos.Prefs
import dev.trechos.settings.ui.theme.Spacing

/**
 * Miniatura do teclado que reage na hora às configurações:
 * altura, uma mão (esquerda/direita) e flutuante (com largura).
 */
@Composable
fun KeyboardPreview(height: Float, layout: String, floatWidth: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val h by animateFloatAsState(height, label = "altura")
    val widthFrac by animateFloatAsState(
        when (layout) {
            Prefs.LAYOUT_LEFT, Prefs.LAYOUT_RIGHT -> Prefs.ONE_HAND_WIDTH
            Prefs.LAYOUT_FLOATING -> floatWidth
            else -> 1f
        },
        label = "largura"
    )
    val align by animateFloatAsState(
        when (layout) {
            Prefs.LAYOUT_LEFT -> 0f
            Prefs.LAYOUT_RIGHT -> 1f
            else -> 0.5f
        },
        label = "posição"
    )
    val floating = layout == Prefs.LAYOUT_FLOATING
    val keyColor = colors.onSurface.copy(alpha = 0.22f)
    val fnColor = colors.onSurface.copy(alpha = 0.12f)
    val accent = colors.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.mdLg)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainer)
            .padding(Spacing.md)
    ) {
        Canvas(Modifier.fillMaxWidth().height(210.dp)) {
            val boardW = size.width * widthFrac
            val boardH = size.height * 0.78f * h / 1.3f
            val left = (size.width - boardW) * align
            val top = if (floating) (size.height - boardH) * 0.45f else size.height - boardH
            // fundo do teclado
            drawRoundRect(
                color = colors.surfaceContainerHighest,
                topLeft = Offset(left, top),
                size = Size(boardW, boardH),
                cornerRadius = CornerRadius(if (floating) 18f else 10f)
            )
            if (floating) {
                // alça de arrastar do modo flutuante
                drawRoundRect(
                    color = colors.onSurfaceVariant,
                    topLeft = Offset(left + boardW / 2 - 24f, top + 8f),
                    size = Size(48f, 6f),
                    cornerRadius = CornerRadius(3f)
                )
            }
            // fileiras: números, 3 de letras, inferior
            val rows = listOf(10, 10, 9, 9, 6)
            val pad = 8f
            val innerTop = top + (if (floating) 22f else pad)
            val rowH = (boardH - (innerTop - top) - pad) / rows.size
            rows.forEachIndexed { r, n ->
                val y = innerTop + r * rowH
                val inset = if (r == 2) boardW / 20f else 0f
                val usable = boardW - 2 * pad - 2 * inset
                val kw = usable / n
                for (i in 0 until n) {
                    val isFn = (r == 3 && (i == 0 || i == n - 1)) || (r == 4 && i != 3)
                    val wide = if (r == 4 && i == 3) 2.5f else 1f
                    val x = left + pad + inset + i * kw
                    val w = if (r == 4 && i == 3) kw * wide else kw
                    val shift = if (r == 4 && i > 3) kw * (wide - 1f) else 0f
                    if (r == 4 && i > 3 && x + shift + kw > left + boardW) continue
                    drawRoundRect(
                        color = when {
                            r == 4 && i == 1 -> accent.copy(alpha = 0.5f)
                            isFn -> fnColor
                            else -> keyColor
                        },
                        topLeft = Offset(x + shift + 2f, y + 3f),
                        size = Size(w - 4f, rowH - 6f),
                        cornerRadius = CornerRadius(6f)
                    )
                }
            }
        }
    }
}
