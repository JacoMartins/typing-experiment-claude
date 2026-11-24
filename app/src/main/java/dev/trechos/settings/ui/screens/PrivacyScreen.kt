package dev.trechos.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.trechos.settings.SettingsState
import dev.trechos.settings.ui.components.Header
import dev.trechos.settings.ui.components.NavigationRow
import dev.trechos.settings.ui.components.ScreenComponent
import dev.trechos.settings.ui.components.SectionTitle
import dev.trechos.settings.ui.components.SwitchRow
import dev.trechos.settings.ui.theme.Spacing
import icons.PhIcons
import icons.regular.Brain
import icons.regular.Eraser
import icons.regular.Trash

private enum class Confirm { LEARNING, CLIPBOARD }

@Composable
fun PrivacyScreen(state: SettingsState, bottomPadding: Dp, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    ScreenComponent(modifier) {
        Header("Privacidade")

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Tudo o que o Trechos aprende fica só neste celular. Nada é enviado para a internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.mdLg, vertical = Spacing.xs)
            )

            SectionTitle("Aprendizado")
            SwitchRow(
                PhIcons.Regular.Brain, "Aprender com o que eu escrevo",
                "Palavras, sequências e tokens que você escolhe. Senhas nunca são registradas.",
                checked = state.learn, onChange = state::updateLearn
            )
            NavigationRow(PhIcons.Regular.Eraser, "Apagar o que o teclado aprendeu", "Volta às sugestões de fábrica.") {
                confirm = Confirm.LEARNING
            }

            SectionTitle("Área de transferência")
            NavigationRow(PhIcons.Regular.Trash, "Limpar área de transferência", "Remove também os itens fixados.") {
                confirm = Confirm.CLIPBOARD
            }
        }

        Spacer(Modifier.height(bottomPadding))
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == Confirm.LEARNING) "Apagar o aprendizado?" else "Limpar a área de transferência?") },
            text = {
                Text(
                    if (what == Confirm.LEARNING) "O teclado esquece tudo o que aprendeu com você. Isso não pode ser desfeito."
                    else "Todos os itens copiados, inclusive os fixados, serão apagados."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == Confirm.LEARNING) state.clearLearning() else state.clearClipboard()
                    confirm = null
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } }
        )
    }
}
