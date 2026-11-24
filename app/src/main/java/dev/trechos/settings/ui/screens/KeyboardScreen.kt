package dev.trechos.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.trechos.Prefs
import dev.trechos.settings.SettingsState
import dev.trechos.settings.ui.components.Header
import dev.trechos.settings.ui.components.KeyboardPreview
import dev.trechos.settings.ui.components.OptionRow
import dev.trechos.settings.ui.components.ScreenComponent
import dev.trechos.settings.ui.components.SectionTitle
import dev.trechos.settings.ui.components.SliderRow
import dev.trechos.settings.ui.components.SwitchRow
import dev.trechos.settings.ui.theme.Spacing
import icons.PhIcons
import icons.regular.ArrowLineLeft
import icons.regular.ArrowLineRight
import icons.regular.ArrowsOut
import icons.regular.ArrowsVertical
import icons.regular.FrameCorners
import icons.regular.HandTap
import icons.regular.Rows
import icons.regular.Vibrate
import kotlin.math.roundToInt

@Composable
fun KeyboardScreen(state: SettingsState, bottomPadding: Dp, modifier: Modifier = Modifier) {
    ScreenComponent(modifier) {
        Header("Teclado")

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            KeyboardPreview(state.height, state.layout, state.floatWidth)

            SectionTitle("Tamanho")
            SliderRow(
                icon = PhIcons.Regular.ArrowsVertical,
                title = "Altura",
                value = state.height,
                range = 0.8f..1.3f,
                valueLabel = "${(state.height * 100).roundToInt()}%",
                onChange = { state.updateHeight((it * 20).roundToInt() / 20f) }   // passos de 5%
            )

            SectionTitle("Posição")
            OptionRow(PhIcons.Regular.Rows, "Normal", "Ocupa a largura toda da tela.",
                selected = state.layout == Prefs.LAYOUT_NORMAL) { state.updateLayout(Prefs.LAYOUT_NORMAL) }
            OptionRow(PhIcons.Regular.ArrowLineLeft, "Uma mão, à esquerda", "Mais estreito e encostado à esquerda.",
                selected = state.layout == Prefs.LAYOUT_LEFT) { state.updateLayout(Prefs.LAYOUT_LEFT) }
            OptionRow(PhIcons.Regular.ArrowLineRight, "Uma mão, à direita", "Mais estreito e encostado à direita.",
                selected = state.layout == Prefs.LAYOUT_RIGHT) { state.updateLayout(Prefs.LAYOUT_RIGHT) }
            OptionRow(PhIcons.Regular.FrameCorners, "Flutuante", "Solto na tela: arraste pela alça. Ideal para tablet.",
                selected = state.layout == Prefs.LAYOUT_FLOATING) { state.updateLayout(Prefs.LAYOUT_FLOATING) }
            if (state.layout == Prefs.LAYOUT_FLOATING) {
                SliderRow(
                    icon = PhIcons.Regular.ArrowsOut,
                    title = "Largura",
                    value = state.floatWidth,
                    range = 0.45f..1f,
                    valueLabel = "${(state.floatWidth * 100).roundToInt()}%",
                    onChange = { state.updateFloatWidth((it * 20).roundToInt() / 20f) }
                )
            }

            SectionTitle("Toque")
            SwitchRow(PhIcons.Regular.Vibrate, "Vibrar ao tocar", checked = state.haptic, onChange = state::updateHaptic)
            SwitchRow(PhIcons.Regular.HandTap, "Prévia da tecla", "Mostra a letra grande acima do dedo.",
                checked = state.preview, onChange = state::updatePreview)
        }

        Spacer(Modifier.height(bottomPadding))
    }
}
