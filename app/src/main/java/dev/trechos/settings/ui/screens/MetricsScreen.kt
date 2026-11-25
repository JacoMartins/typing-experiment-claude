package dev.trechos.settings.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.trechos.Telemetry
import dev.trechos.settings.SettingsState
import dev.trechos.settings.ui.components.Header
import dev.trechos.settings.ui.components.NavigationRow
import dev.trechos.settings.ui.components.ScreenComponent
import dev.trechos.settings.ui.components.SectionTitle
import dev.trechos.settings.ui.theme.Spacing
import icons.PhIcons
import icons.regular.Copy
import icons.regular.Trash
import java.util.Locale

private val MODE_NAMES = mapOf("QWERTY" to "QWERTY", "TRECHOS" to "Grade", "HIBRIDO" to "Híbrido", "FLUXO" to "Fluxo", "TRACO" to "Traço")
private val SOURCE_NAMES = mapOf(
    Telemetry.LETTER to "Letras do QWERTY", Telemetry.STRIP to "Faixa de letras", Telemetry.TOKEN to "Tokens da grade",
    Telemetry.FLOW to "Tokens do Traço", "fluxo" to "Tokens do Fluxo (antigo)", Telemetry.WORD to "Palavras da barra", Telemetry.SWIPE to "Swipe de palavras",
    Telemetry.ACCENT to "Acentos e símbolos", Telemetry.SPACE to "Espaço", Telemetry.PUNCT to "Pontuação",
    Telemetry.BACKSPACE to "Apagar",
)

private fun fmt(v: Double, digits: Int = 1) = String.format(Locale("pt", "BR"), "%.${digits}f", v)

/**
 * Métricas de digitação: velocidade por modo e, principalmente, quanto tempo cada tipo
 * de ação custa ("tempo antes" = tempo de decisão) e quantos caracteres ela rende.
 */
@Composable
fun MetricsScreen(state: SettingsState, bottomPadding: Dp, modifier: Modifier = Modifier) {
    var period by remember { mutableIntStateOf(1) }   // 0 hoje, 1 7 dias, 2 tudo
    var reload by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val rows = remember(period, reload, state.resumeTick) { state.metrics(period) }

    ScreenComponent(modifier) {
        Header("Métricas")
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Text(
                "Medido só neste celular, sem guardar o que você escreve: o tipo de cada ação, " +
                    "quantas letras ela rendeu e o tempo desde a ação anterior.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.mdLg, vertical = Spacing.xs)
            )
            Row(Modifier.padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf("Hoje", "7 dias", "Tudo").forEachIndexed { i, label ->
                    FilterChip(selected = period == i, onClick = { period = i }, label = { Text(label) })
                }
            }

            if (rows.isEmpty()) {
                Text(
                    "Ainda não há dados neste período. Digite um pouco com o Trechos e volte aqui.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Spacing.mdLg)
                )
            } else {
                SectionTitle("Velocidade por modo")
                for ((mode, list) in rows.groupBy { it.mode }) ModeCard(MODE_NAMES[mode] ?: mode, list)

                SectionTitle("Quanto cada ação custa")
                Text(
                    "\"Tempo antes\" é quanto você leva para decidir e tocar. Se um token rende 3 letras mas " +
                        "custa mais que 3 toques de letra, a grade não está compensando.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.mdLg, vertical = Spacing.xs)
                )
                val bySource = rows.groupBy { it.source }.mapValues { (_, l) ->
                    Telemetry.Row("", l[0].source, l.sumOf { it.count }, l.sumOf { it.chars },
                        l.sumOf { it.gapSum }, l.sumOf { it.gapCount })
                }
                val totalChars = bySource.values.filter { it.chars > 0 }.sumOf { it.chars }.coerceAtLeast(1)
                for (r in bySource.values.sortedByDescending { it.chars }) {
                    if (r.source == Telemetry.BACKSPACE) continue
                    val avgGap = if (r.gapCount > 0) r.gapSum.toDouble() / r.gapCount / 1000.0 else 0.0
                    val perAction = r.chars.toDouble() / r.count
                    val cps = if (avgGap > 0) perAction / avgGap else 0.0
                    SourceRow(
                        name = SOURCE_NAMES[r.source] ?: r.source,
                        share = 100.0 * r.chars / totalChars,
                        detail = "${fmt(perAction)} letras por ação · ${fmt(avgGap, 2)} s antes · ${fmt(cps)} letras/s"
                    )
                }
            }

            SectionTitle("Dados")
            NavigationRow(PhIcons.Regular.Copy, if (copied) "Copiado" else "Copiar tudo como CSV",
                "Para analisar numa planilha (uma linha por ação).") { state.copyMetricsCsv(); copied = true }
            NavigationRow(PhIcons.Regular.Trash, "Zerar métricas", "Começa uma medição nova.") { confirm = true }
            Spacer(Modifier.height(Spacing.md + bottomPadding))
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Zerar as métricas?") },
            text = { Text("Todas as medições de digitação serão apagadas.") },
            confirmButton = { TextButton(onClick = { state.clearMetrics(); confirm = false; reload++ }) { Text("Zerar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ModeCard(name: String, rows: List<Telemetry.Row>) {
    val chars = rows.filter { it.chars > 0 }.sumOf { it.chars }
    val deletes = rows.filter { it.source == Telemetry.BACKSPACE }.sumOf { it.count }
    val activeMs = rows.sumOf { it.gapSum }
    val wpm = if (activeMs > 0) (chars / 5.0) / (activeMs / 60_000.0) else 0.0
    val corr = if (chars > 0) 100.0 * deletes / chars else 0.0
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Spacing.mdLg)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text("${fmt(wpm)} ppm", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "$chars letras · ${fmt(corr)}% de correções · ${fmt(activeMs / 60_000.0)} min digitando",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SourceRow(name: String, share: Double, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.mdLg, vertical = Spacing.sm)) {
        Row {
            Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text("${fmt(share, 0)}% do texto", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
