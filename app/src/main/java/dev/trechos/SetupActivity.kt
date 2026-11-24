package dev.trechos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Tela de configuração: ativar o teclado, escolher, testar e apagar o aprendizado. */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            textSize = 16f
            text = "Trechos: teclado QWERTY com uma grade de trechos de palavra previstos.\n\n" +
                "• A tecla ⊞ troca para a grade; ABC volta para o QWERTY.\n" +
                "• O modo escolhido continua igual quando o teclado abre de novo.\n" +
                "• A barra de cima completa a palavra nos dois modos.\n" +
                "• ⟳ mostra mais 9 trechos. ⌫ na grade apaga o último trecho inteiro.\n"
        })
        col.addView(Button(this).apply {
            text = "1. Ativar o teclado nas configurações"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "2. Escolher o Trechos como teclado"
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        col.addView(EditText(this).apply {
            hint = "Teste aqui…"
            minLines = 4
        })
        col.addView(Button(this).apply {
            text = "Apagar o que o teclado aprendeu"
            setOnClickListener {
                File(filesDir, TrechosKeyboardService.HISTORY).delete()
                deleteDatabase(SqliteUserStore.DB_NAME)
                getSharedPreferences(TrechosKeyboardService.PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(TrechosKeyboardService.KEY_RESET, System.currentTimeMillis()).apply()
                Toast.makeText(this@SetupActivity, "Aprendizado apagado", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
