package dev.trechos.settings.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trechos.settings.ui.theme.Spacing
import icons.PhIcons
import icons.bold.CheckBold
import icons.regular.CaretRight

/** Título de seção, como o "Preview" da Home do OneDepth. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = Spacing.mdLg, vertical = Spacing.smMd)
    )
}

/**
 * Linha de configuração no formato do ContactButton do OneDepth:
 * ícone, título em SemiBold, subtítulo em onSurfaceVariant e algo à direita.
 */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm)
            .clip(RoundedCornerShape(48.dp))
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.smMd, vertical = Spacing.smMd)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.smMd),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (selected) colors.onPrimaryContainer else colors.onSurface
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Linha que abre algo (com a seta à direita). */
@Composable
fun NavigationRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) =
    SettingRow(icon, title, subtitle, onClick = onClick) {
        Icon(PhIcons.Regular.CaretRight, contentDescription = null, modifier = Modifier.size(24.dp))
    }

/** Linha com interruptor. */
@Composable
fun SwitchRow(icon: ImageVector, title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(icon, title, subtitle, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }

/** Linha de escolha única (marcada quando selecionada). */
@Composable
fun OptionRow(icon: ImageVector, title: String, subtitle: String? = null, selected: Boolean, onSelect: () -> Unit) =
    SettingRow(icon, title, subtitle, selected = selected, onClick = onSelect) {
        if (selected) {
            Icon(
                PhIcons.Bold.CheckBold,
                contentDescription = "Selecionado",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

/** Linha com controle deslizante e o valor atual à direita. */
@Composable
fun SliderRow(
    icon: ImageVector,
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SettingRow(icon, title) {
            Text(valueLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.padding(horizontal = Spacing.lg)
        )
    }
}
