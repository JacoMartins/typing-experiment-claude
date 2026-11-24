package dev.trechos.settings.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.trechos.settings.ui.navigation.LocalIsDarkTheme
import dev.trechos.settings.ui.theme.Spacing
import dev.trechos.settings.ui.theme.modifiers.AngularLight
import dev.trechos.settings.ui.theme.modifiers.angularBorder

@Composable
fun ButtonComponent(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current

    Box(
        modifier = modifier
            .angularBorder(
                light = AngularLight.Button,
                alpha = if (isDark) 0.6f else 0.75f,
                sideAlpha = 0.4f
            ) // ring on the outer bounds...
    ) {
        // Material enforces a 48dp touch target, which adds invisible space around a 40dp button
        // and makes the ring look loose. Disable it here since the ring supplies the visual boundary.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(0.dp),
                ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xsSm),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = modifier.padding(horizontal=Spacing.mdLg, vertical=Spacing.sm),
                    content = content
                )
            }
        }
    }
}