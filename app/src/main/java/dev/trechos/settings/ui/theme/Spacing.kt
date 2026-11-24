package dev.trechos.settings.ui.theme

import androidx.compose.ui.unit.dp

object Spacing {
    private val base = 16.dp

    // Named steps
    val none = 0.dp
    val xxs  = base / 8        // 2.dp
    val xs   = base / 4        // 4.dp
    val sm   = base / 2        // 8.dp
    val md   = base            // 16.dp
    val lg   = base * 1.5f     // 24.dp
    val xl   = base * 2        // 32.dp
    val xxl  = base * 3        // 48.dp
    val xxxl = base * 4        // 64.dp

    // In-between steps
    val xsSm = xs + xxs
    val smMd = sm + xs         // 12.dp  (between sm and md)
    val mdLg = md + xs         // 20.dp  (between md and lg)
    val lgXl = lg + sm         // 32.dp  (same as xl, see note)
}