package dev.trechos.settings.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.trechos.settings.SettingsState
import dev.trechos.settings.ui.screens.HomeScreen
import dev.trechos.settings.ui.screens.KeyboardScreen
import dev.trechos.settings.ui.screens.PrivacyScreen
import dev.trechos.settings.ui.theme.Spacing
import dev.trechos.settings.ui.theme.modifiers.angularBorder
import dev.trechos.settings.ui.theme.smoothlyFrosted
import icons.PhIcons
import icons.filled.HouseFill
import icons.filled.KeyboardFill
import icons.filled.ShieldCheckFill
import icons.regular.House
import icons.regular.Keyboard
import icons.regular.ShieldCheck

val LocalIsDarkTheme = staticCompositionLocalOf { false }

enum class SettingsDestinations(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Início", PhIcons.Regular.House, PhIcons.Filled.HouseFill),
    KEYBOARD("Teclado", PhIcons.Regular.Keyboard, PhIcons.Filled.KeyboardFill),
    PRIVACY("Privacidade", PhIcons.Regular.ShieldCheck, PhIcons.Filled.ShieldCheckFill),
}

/** Mesma estrutura do OneDepthApp: telas por baixo e a pílula de vidro flutuando embaixo. */
@Composable
fun SettingsApp(state: SettingsState) {
    val density = LocalDensity.current
    val isDark = MaterialTheme.colorScheme.surfaceContainer.luminance() < 0.5f
    val hazeState = rememberHazeState()
    val pillShape = RoundedCornerShape(32.dp)
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val glassStyle = remember(surfaceContainer) {
        GlassStyle.smoothlyFrosted.then {
            shape(pillShape)
            tint(surfaceContainer.copy(alpha = 0.35f))
            edgeShadow(Color.Transparent)
            edgeSoftness(0.dp)
        }
    }
    var current by rememberSaveable { mutableStateOf(SettingsDestinations.HOME) }
    var barSpace by remember { mutableStateOf(Spacing.none) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) { innerPadding ->
            val screenModifier = Modifier.padding(top = innerPadding.calculateTopPadding())
            CompositionLocalProvider(LocalIsDarkTheme provides isDark) {
                when (current) {
                    SettingsDestinations.HOME -> HomeScreen(state, barSpace, screenModifier)
                    SettingsDestinations.KEYBOARD -> KeyboardScreen(state, barSpace, screenModifier)
                    SettingsDestinations.PRIVACY -> PrivacyScreen(state, barSpace, screenModifier)
                }
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size -> barSpace = with(density) { size.height.toDp() } }
                .navigationBarsPadding()
                .padding(bottom = Spacing.md)
                .shadow(
                    elevation = Spacing.sm,
                    shape = pillShape,
                    ambientColor = Color.Black.copy(alpha = 0.25f),
                    spotColor = Color.Black.copy(alpha = 0.375f)
                )
                .hazeGlass(input = HazeInput.Sources(hazeState), style = glassStyle)
                .angularBorder(
                    alpha = if (isDark) 0.25f else 1f,
                    blendMode = if (isDark) BlendMode.Hardlight else BlendMode.Overlay,
                    sideAlpha = if (isDark) 0.5f else 0.20f
                ),
            shape = pillShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.padding(all = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsDestinations.entries.forEach { dest ->
                    val selected = dest == current
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { current = dest }
                            .padding(horizontal = Spacing.mdLg, vertical = Spacing.smMd),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (selected) dest.selectedIcon else dest.icon,
                            contentDescription = dest.label,
                            modifier = Modifier.size(24.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
