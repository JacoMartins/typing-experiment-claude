package dev.trechos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Métricas de digitação, só no aparelho. NUNCA guarda o texto: só que tipo de ação foi
 * feita (letra, token, palavra, swipe...), quantos caracteres ela escreveu e quanto
 * tempo passou desde a ação anterior (o "tempo de decisão").
 *
 * Com isso dá para responder perguntas como "a grade me faz escrever mais rápido ou
 * eu passo mais tempo pensando?": compara-se caracteres por segundo de cada fonte.
 */
class Telemetry(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ev (ts INTEGER NOT NULL, day TEXT NOT NULL, mode TEXT NOT NULL, " +
                "source TEXT NOT NULL, chars INTEGER NOT NULL, gap INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX ev_day ON ev(day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** [gapMs] = tempo desde a ação anterior; -1 se foi uma pausa longa (não conta como decisão). */
    fun record(ts: Long, day: String, mode: String, source: String, chars: Int, gapMs: Long) {
        writableDatabase.insert("ev", null, ContentValues().apply {
            put("ts", ts); put("day", day); put("mode", mode)
            put("source", source); put("chars", chars); put("gap", gapMs)
        })
    }

    /** Resumo por fonte (e modo) desde [sinceDay] (yyyy-MM-dd). */
    data class Row(val mode: String, val source: String, val count: Int, val chars: Int, val gapSum: Long, val gapCount: Int)

    fun summary(sinceDay: String): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(
            "SELECT mode, source, COUNT(*), SUM(chars), SUM(CASE WHEN gap >= 0 THEN gap ELSE 0 END), " +
                "SUM(CASE WHEN gap >= 0 THEN 1 ELSE 0 END) FROM ev WHERE day >= ? GROUP BY mode, source",
            arrayOf(sinceDay)
        ).use { c ->
            while (c.moveToNext()) out.add(Row(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getInt(5)))
        }
        return out
    }

    /** Todas as ações em CSV (para analisar numa planilha). */
    fun csv(): String {
        val sb = StringBuilder("timestamp,dia,modo,fonte,caracteres,intervalo_ms\n")
        readableDatabase.rawQuery("SELECT ts, day, mode, source, chars, gap FROM ev ORDER BY ts", null).use { c ->
            while (c.moveToNext()) sb.append("${c.getLong(0)},${c.getString(1)},${c.getString(2)},${c.getString(3)},${c.getInt(4)},${c.getLong(5)}\n")
        }
        return sb.toString()
    }

    companion object {
        const val DB_NAME = "metricas.db"

        // fontes de ação
        const val LETTER = "letra"          // tecla do QWERTY
        const val STRIP = "faixa"           // faixa de letras sugeridas
        const val TOKEN = "token"           // célula da grade
        const val FLOW = "traco"            // token escolhido no gesto do modo Traço
        const val WORD = "palavra"          // barra de cima
        const val SWIPE = "swipe"           // palavra por gesto
        const val ACCENT = "acento"         // popup de acentos/símbolos
        const val SPACE = "espaco"
        const val PUNCT = "pontuacao"
        const val BACKSPACE = "apagar"

        /** Intervalos maiores que isto são pausa (parou de digitar), não tempo de decisão. */
        const val PAUSE_MS = 8_000L
    }
}
