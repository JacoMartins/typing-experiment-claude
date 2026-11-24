package dev.trechos.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.trechos.ClipStore
import dev.trechos.CrashLog
import dev.trechos.Prefs
import dev.trechos.SqliteUserStore
import dev.trechos.TrechosKeyboardService
import java.io.File

/**
 * Estado da tela de configurações. Cada mudança é gravada nas preferências
 * na hora; o teclado escuta essas preferências e se ajusta sozinho.
 */
class SettingsState(private val context: Context) {
    private val prefs = Prefs.of(context)

    var height by mutableFloatStateOf(prefs.getFloat(Prefs.HEIGHT, 1f))
        private set
    var layout by mutableStateOf(prefs.getString(Prefs.LAYOUT, Prefs.LAYOUT_NORMAL) ?: Prefs.LAYOUT_NORMAL)
        private set
    var floatWidth by mutableFloatStateOf(prefs.getFloat(Prefs.FLOAT_WIDTH, 0.75f))
        private set
    var haptic by mutableStateOf(prefs.getBoolean(Prefs.HAPTIC, true))
        private set
    var preview by mutableStateOf(prefs.getBoolean(Prefs.PREVIEW, true))
        private set
    var learn by mutableStateOf(prefs.getBoolean(Prefs.LEARN, true))
        private set

    /** Idiomas ativados; segurar o espaço alterna entre eles. */
    var languages by mutableStateOf(
        (prefs.getString(Prefs.LANGUAGES, "pt,en") ?: "pt,en").split(',').filter { it.isNotBlank() }
    )
        private set

    /** Liga/desliga um idioma (sempre sobra pelo menos um). */
    fun toggleLanguage(code: String) {
        val next = if (code in languages) languages - code else languages + code
        if (next.isEmpty()) return
        // mantém a ordem fixa da lista de idiomas
        languages = TrechosKeyboardService.LANGUAGE_FILES.keys.filter { it in next }
        prefs.edit().putString(Prefs.LANGUAGES, languages.joinToString(",")).apply()
    }

    /** Aumenta sempre que a tela volta ao primeiro plano (para reler o status do teclado). */
    var resumeTick by mutableIntStateOf(0)

    fun updateHeight(v: Float) { height = v; prefs.edit().putFloat(Prefs.HEIGHT, v).apply() }
    fun updateLayout(v: String) { layout = v; prefs.edit().putString(Prefs.LAYOUT, v).apply() }
    fun updateFloatWidth(v: Float) { floatWidth = v; prefs.edit().putFloat(Prefs.FLOAT_WIDTH, v).apply() }
    fun updateHaptic(v: Boolean) { haptic = v; prefs.edit().putBoolean(Prefs.HAPTIC, v).apply() }
    fun updatePreview(v: Boolean) { preview = v; prefs.edit().putBoolean(Prefs.PREVIEW, v).apply() }
    fun updateLearn(v: Boolean) { learn = v; prefs.edit().putBoolean(Prefs.LEARN, v).apply() }

    /** O teclado está ativado nas configurações do Android? */
    fun isEnabled(): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == context.packageName }
    }

    /** O teclado é o que está em uso agora? */
    fun isSelected(): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(context.packageName) == true

    fun openEnableSettings() {
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openPicker() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    /** Apaga a memória pessoal (banco + histórico antigo); o teclado recarrega sozinho. */
    fun clearLearning() {
        File(context.filesDir, TrechosKeyboardService.HISTORY).delete()
        context.deleteDatabase(SqliteUserStore.DB_NAME)
        prefs.edit().putLong(Prefs.RESET, System.currentTimeMillis()).apply()
    }

    /** Relatório do último fechamento inesperado, se houver. */
    var crashReport by mutableStateOf(CrashLog.read(context))
        private set

    fun refreshCrash() { crashReport = CrashLog.read(context) }

    fun copyCrashReport() {
        val report = crashReport ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Relatório de erro do Trechos", report))
    }

    fun dismissCrash() { CrashLog.clear(context); crashReport = null }

    fun clearClipboard() {
        File(context.filesDir, ClipStore.FILE).delete()
        prefs.edit().putLong(Prefs.CLIP_RESET, System.currentTimeMillis()).apply()
    }
}
