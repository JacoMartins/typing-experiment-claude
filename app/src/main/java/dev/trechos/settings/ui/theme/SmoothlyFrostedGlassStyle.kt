package dev.trechos.settings.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.RefractionProfile

// Built once, so it keeps a stable identity (styles compare by identity).
@OptIn(ExperimentalHazeApi::class)
private val SmoothlyFrostedBase: GlassStyle = GlassStyle.regular.then {
    edgeShadow(Color.Transparent)
    edgeSoftness(0.dp)
    optics(
        refractionStrength = 0.7f,
        refractionHeightFraction = 0.15f,
        refractionDisplacement = 48.dp,
        refractionDetailIntensity = 0f,
        refractionProfile = RefractionProfile.Edge(20.dp),
        depth = 0.9f,        // Regular uses 0.65 at 64dp; higher = more of the blurred layer
        blurRadius = 8.dp    // Regular uses ~20dp at 64dp
    )
}

/**
 * Base style: Regular's look with a lighter frost. Shape and tint are left to the call site.
 * Fixed optics, so it is tuned for ~64dp tall surfaces.
 */
val GlassStyle.Companion.smoothlyFrosted: GlassStyle
    get() = SmoothlyFrostedBase

/*
 * Usage (editable at the call site, like before):
 *
 * val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
 * val pillShape = RoundedCornerShape(32.dp)
 *
 * val glassStyle = remember(surfaceContainer) {
 *     GlassStyle.smoothlyFrosted.then {
 *         shape(pillShape)
 *         tint(surfaceContainer.copy(alpha = 0.35f))
 *     }
 * }
 *
 * Modifier.hazeGlass(input = HazeInput.Sources(hazeState), style = glassStyle)
 */