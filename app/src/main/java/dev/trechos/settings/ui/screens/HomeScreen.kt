package dev.trechos.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.trechos.settings.SettingsState
import dev.trechos.settings.ui.components.ButtonComponent
import dev.trechos.settings.ui.components.Header
import dev.trechos.settings.ui.components.ScreenComponent
import dev.trechos.settings.ui.components.SectionTitle
import dev.trechos.settings.ui.components.SettingRow
import dev.trechos.settings.ui.theme.Spacing
import icons.PhIcons
import icons.bold.CheckBold
import icons.bold.KeyboardBold
import icons.regular.ArrowsHorizontal
import icons.regular.Clipboard
import icons.regular.HandTap
import icons.regular.SquaresFour

@Composable
fun HomeScreen(state: SettingsState, bottomPadding: Dp, modifier: Modifier = Modifier) {
    // relido sempre que a tela volta (o usuário pode ter acabado de ativar o teclado)
    val enabled = remember(state.resumeTick) { state.isEnabled() }
    val selected = remember(state.resumeTick) { state.isSelected() }
    var test by rememberSaveable { mutableStateOf("") }

    ScreenComponent(modifier) {
        Header("Trechos")

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            // cartão de boas-vindas, como o WelcomeCard do OneDepth
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.mdLg, vertical = Spacing.xs)) {
                Text(
                    text = when {
                        !enabled -> "Vamos ativar o Trechos"
                        !selected -> "Quase lá"
                        else -> "Tudo pronto"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(Spacing.smMd))
                Text(
                    text = when {
                        !enabled -> "Primeiro, ative o Trechos na lista de teclados do Android."
                        !selected -> "O Trechos está ativado. Agora escolha ele como o teclado em uso."
                        else -> "O Trechos é o seu teclado. Escreva no campo abaixo para testar."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.md))
                ButtonComponent(onClick = { if (!enabled) state.openEnableSettings() else state.openPicker() }) {
                    Text(
                        text = when {
                            !enabled -> "Ativar"
                            !selected -> "Escolher"
                            else -> "Trocar teclado"
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Icon(
                        imageVector = if (enabled) PhIcons.Bold.CheckBold else PhIcons.Bold.KeyboardBold,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            SectionTitle("Teste")
            OutlinedTextField(
                value = test,
                onValueChange = { test = it },
                placeholder = { Text("Escreva aqui…") },
                shape = RoundedCornerShape(24.dp),
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.mdLg)
            )

            SectionTitle("Como usar")
            SettingRow(PhIcons.Regular.SquaresFour, "Grade de tokens", "Toque em ⊞ para escrever por pedaços de palavra.")
            SettingRow(PhIcons.Regular.HandTap, "Acentos e símbolos", "Segure uma tecla, deslize até o que quer e solte.")
            SettingRow(PhIcons.Regular.ArrowsHorizontal, "Mover o cursor", "Arraste o dedo sobre a barra de espaço.")
            SettingRow(PhIcons.Regular.Clipboard, "Área de transferência", "O botão à esquerda das sugestões guarda o que você copiou.")
        }

        Spacer(Modifier.height(bottomPadding))
    }
}
