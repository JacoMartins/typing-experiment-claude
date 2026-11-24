package dev.trechos.predict

import kotlin.math.pow

/**
 * Memória pessoal do teclado: o que o usuário escreveu, contado por contexto.
 *
 * Cada linha é (contexto, palavra) -> contagem. O contexto são as até 3 palavras
 * anteriores, separadas por espaço ("a gente precisa", "gente precisa", "precisa"),
 * e "" é a contagem geral da palavra. Assim a mesma palavra aprendida alimenta
 * os quatro níveis do backoff de uma vez.
 *
 * As contagens "esquecem" devagar: cada uma perde metade do valor a cada
 * [HALF_LIFE_MS] sem uso. O que você escreveu ontem pesa mais do que algo de meses atrás.
 */
interface UserStore {
    /** Palavras que vieram depois de [context], com a contagem já descontada pelo tempo. */
    fun followers(context: String, limit: Int = 300): Map<String, Float>

    /** Registra [word] escrita depois de [history] (as palavras anteriores, da mais antiga à mais recente). */
    fun learn(history: List<String>, word: String, now: Long = System.currentTimeMillis())

    /** Registra [item] só no contexto exato [context] (usado para os tokens escolhidos na grade). */
    fun learnExact(context: String, item: String, now: Long = System.currentTimeMillis())

    companion object {
        const val MAX_ORDER = 3
        const val HALF_LIFE_MS = 60L * 24 * 60 * 60 * 1000   // 60 dias

        /** Fator de esquecimento: 1.0 agora, 0.5 depois de uma meia-vida, 0.25 depois de duas... */
        fun decay(elapsedMs: Long): Float =
            if (elapsedMs <= 0) 1f else 0.5.pow(elapsedMs.toDouble() / HALF_LIFE_MS).toFloat()

        /** Os contextos de ordem 0..3 para um histórico: "", "c", "b c", "a b c". */
        fun contexts(history: List<String>): List<String> {
            val h = history.takeLast(MAX_ORDER)
            return (0..h.size).map { k -> h.takeLast(k).joinToString(" ") }
        }
    }
}

/** Implementação em memória (usada nos testes e como reserva se o banco falhar). */
class MemoryUserStore : UserStore {
    private class Entry(var count: Float, var last: Long)
    private val table = HashMap<String, HashMap<String, Entry>>()

    override fun followers(context: String, limit: Int): Map<String, Float> {
        val now = System.currentTimeMillis()
        val m = table[context] ?: return emptyMap()
        return m.entries
            .map { it.key to it.value.count * UserStore.decay(now - it.value.last) }
            .sortedByDescending { it.second }
            .take(limit)
            .toMap()
    }

    override fun learn(history: List<String>, word: String, now: Long) {
        for (ctx in UserStore.contexts(history)) learnExact(ctx, word, now)
    }

    override fun learnExact(context: String, item: String, now: Long) {
        val e = table.getOrPut(context) { HashMap() }.getOrPut(item) { Entry(0f, now) }
        e.count = e.count * UserStore.decay(now - e.last) + 1f
        e.last = now
    }
}
