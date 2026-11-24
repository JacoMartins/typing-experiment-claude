package dev.trechos.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.trechos.settings.ui.navigation.SettingsApp
import dev.trechos.settings.ui.theme.TrechosTheme

/** Tela de configurações (Compose, no estilo do OneDepth). O teclado em si continua em Views. */
class SettingsActivity : ComponentActivity() {

    private lateinit var state: SettingsState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = SettingsState(this)
        setContent {
            TrechosTheme {
                SettingsApp(state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        state.resumeTick++
    }
}
