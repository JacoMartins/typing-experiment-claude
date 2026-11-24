package dev.trechos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.trechos.predict.UserStore

/**
 * Memória pessoal guardada em SQLite (banco que já vem no Android).
 *
 * Tabela ngram:
 *   ctx   - as até 3 palavras anteriores ("" = contagem geral da palavra)
 *   word  - a palavra escrita depois desse contexto
 *   count - contagem, já descontada pelo tempo até [last]
 *   last  - quando foi usada pela última vez (ms)
 *
 * A chave (ctx, word) faz a busca "o que vem depois de X?" ser direta, sem ler o banco inteiro.
 */
class SqliteUserStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, 1), UserStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ngram (ctx TEXT NOT NULL, word TEXT NOT NULL, count REAL NOT NULL, last INTEGER NOT NULL, " +
                "PRIMARY KEY (ctx, word)) WITHOUT ROWID"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun followers(context: String, limit: Int): Map<String, Float> {
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, Float>()
        readableDatabase.rawQuery(
            "SELECT word, count, last FROM ngram WHERE ctx = ? ORDER BY count DESC LIMIT ?",
            arrayOf(context, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = c.getFloat(1) * UserStore.decay(now - c.getLong(2))
            }
        }
        return out
    }

    override fun learn(history: List<String>, word: String, now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (ctx in UserStore.contexts(history)) {
                var count = 0f
                db.rawQuery("SELECT count, last FROM ngram WHERE ctx = ? AND word = ?", arrayOf(ctx, word)).use { c ->
                    if (c.moveToFirst()) count = c.getFloat(0) * UserStore.decay(now - c.getLong(1))
                }
                db.insertWithOnConflict("ngram", null, ContentValues().apply {
                    put("ctx", ctx)
                    put("word", word)
                    put("count", count + 1f)
                    put("last", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Faxina: tira o que já foi praticamente esquecido (pouco usado e antigo). */
    fun prune(now: Long = System.currentTimeMillis()) {
        val cutoff = now - 4 * UserStore.HALF_LIFE_MS
        writableDatabase.delete("ngram", "last < ? AND count < 2", arrayOf(cutoff.toString()))
    }

    companion object {
        const val DB_NAME = "memoria.db"
    }
}
