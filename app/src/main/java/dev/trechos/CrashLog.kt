package dev.trechos

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda o motivo do último fechamento inesperado do app, para o usuário poder
 * copiar o relatório na tela de configurações. Fica só no aparelho.
 */
object CrashLog {
    private const val FILE = "ultimo-erro.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val file = File(context.applicationContext.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { file.writeText(report(thread, error)) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    /** Registra um erro que foi contido (o teclado continuou funcionando). */
    fun record(context: Context, error: Throwable) {
        try { File(context.filesDir, FILE).writeText(report(Thread.currentThread(), error)) } catch (_: Throwable) {}
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) { File(context.filesDir, FILE).delete() }

    private fun report(thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "Trechos — $date\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Thread: ${thread.name}\n\n$sw"
    }
}
