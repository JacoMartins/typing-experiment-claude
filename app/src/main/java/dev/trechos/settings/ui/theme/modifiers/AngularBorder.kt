package dev.trechos.settings.ui.theme.modifiers

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

private const val STEPS = 32

/**
 * Vertical light. [key] hits the top edge, [fill] bounces off the bottom.
 * Because the gradient is measured against the object's own height, it scales to any size.
 */
data class AngularLight(
    val key: Float = 0.9f,
    val fill: Float = 0.35f,
    val spread: Float = 0.6f,
    val softness: Float = 0.65f
) {
    companion object {
        /** Wide, long surfaces such as the nav pill. */
        val Nav = AngularLight(key = 0.9f, fill = 0.35f, spread = 0.6f)

        /** Short surfaces such as buttons: keep the glow tighter. */
        val Button = AngularLight(key = 0.8f, fill = 0.25f, spread = 0.5f)
    }
}

// 1 at x = 0, easing to 0 at x >= 1
private fun glow(x: Float, softness: Float): Float =
    if (x >= 1f) 0f else (0.5f + 0.5f * cos(PI.toFloat() * x)).pow(softness)

private fun AngularLight.brush(height: Float): Brush {
    val s = spread.coerceAtLeast(0.01f)
    return Brush.verticalGradient(
        colorStops = Array(STEPS + 1) { i ->
            val t = i / STEPS.toFloat()
            val a = maxOf(key * glow(t / s, softness), fill * glow((1f - t) / s, softness))
            t to Color.White.copy(alpha = a)
        },
        startY = 0f,
        endY = height
    )
}

fun Modifier.angularBorder(
    width: Dp = 1.dp,
    alpha: Float = 0.333f,
    blendMode: BlendMode = BlendMode.Hardlight,
    light: AngularLight = AngularLight(),
    sideWidth: Dp = 1.dp,
    sideAlpha: Float = 0.20f,
    sideColor: Color = Color.Black
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()

        val w = width.toPx()
        val s = sideWidth.toPx()
        val h = size.height
        val r = h / 2f

        // 1) Main ring: full height, but narrower by the side stroke on both ends.
        //    Same radius as the outer pill, just shifted inward, so a crescent opens up at each tip.
        drawRoundRect(
            brush = light.brush(h),
            topLeft = Offset(s + w / 2f, w / 2f),
            size = Size((size.width - 2f * s - w).coerceAtLeast(0f), h - w),
            cornerRadius = CornerRadius(r - w / 2f),
            style = Stroke(w),
            alpha = alpha,
            blendMode = blendMode
        )

        // 2) Side ring: the full outer pill, dark at the tips and eased to transparent by the tangent points.
        if (s > 0.01f) {
            val fade = (r / size.width).coerceAtMost(0.5f)
            val ease = listOf(0f to 1f, .25f to .84f, .5f to .5f, .75f to .16f, 1f to 0f)
            val stops = ease.map { (t, a) -> fade * t to sideColor.copy(alpha = a) } +
                    ease.reversed().map { (t, a) -> 1f - fade * t to sideColor.copy(alpha = a) }

            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colorStops = stops.toTypedArray(),
                    startX = 0f,
                    endX = size.width
                ),
                topLeft = Offset(s / 2f, s / 2f),
                size = Size(size.width - s, h - s),
                cornerRadius = CornerRadius(r - s / 2f),
                style = Stroke(s),
                alpha = sideAlpha
            )
        }
    }