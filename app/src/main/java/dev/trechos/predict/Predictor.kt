package dev.trechos.predict

import java.text.Normalizer

/**
 * Motor de previsão sem dependências do Android.
 *
 * Combina dois modelos:
 *  - vocabulário de palavras (unigramas + bigramas): calcula quais trechos
 *    continuam a palavra atual, somando a probabilidade de todas as palavras
 *    que começam com aquele trecho;
 *  - n-gramas de caracteres: cobre palavras que não estão no vocabulário
 *    (nomes, gírias, mistura de línguas).
 */
class Predictor {

    private class Node {
        var total = 0f
        val next = HashMap<Char, Float>(8)
    }

    private val maxCtx = 4
    private val charNodes = HashMap<String, Node>(200_000)

    private val freq = HashMap<String, Float>(60_000)
    private val bigrams = HashMap<String, HashMap<String, Float>>()

    // Índices reconstruídos sob demanda quando entram palavras novas.
    private var dirty = true
    private var byStripped: Array<String> = emptyArray()   // ordenado por forma sem acento
    private var strippedKeys: Array<String> = emptyArray()
    private var topWords: List<String> = emptyList()

    // ------------------------------------------------------------------ treino

    /** Linhas no formato "palavra<TAB>contagem". */
    fun loadWordList(lines: Sequence<String>, weight: Float) {
        for (line in lines) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val w = line.substring(0, tab).lowercase()
            val c = line.substring(tab + 1).trim().toFloatOrNull() ?: continue
            addWord(w, c * weight)
        }
    }

    /** Texto corrido: alimenta bigramas, vocabulário e modelo de caracteres. */
    fun learnText(text: String, weight: Float) {
        for (sentence in text.split('.', '!', '?', '\n')) {
            var prev = START
            for (w in WORD.findAll(sentence.lowercase())) {
                learnPair(prev, w.value, weight)
                prev = w.value
            }
        }
    }

    /** Registra uma palavra escrita depois de [prev] (ou de [START]). */
    fun learnPair(prev: String, word: String, weight: Float) {
        val w = word.lowercase()
        if (w.isEmpty() || !w.all { it.isLetter() }) return
        addWord(w, weight * WORD_WEIGHT)
        val m = bigrams.getOrPut(prev.lowercase()) { HashMap() }
        m[w] = (m[w] ?: 0f) + weight
        // contexto entre palavras para o modelo de caracteres
        if (prev != START) trainChars(" ${prev.takeLast(maxCtx)} $w ", weight * WORD_WEIGHT, from = prev.takeLast(maxCtx).length + 2)
    }

    private fun addWord(w: String, c: Float) {
        if (w.isEmpty()) return
        val old = freq[w]
        if (old == null) dirty = true
        freq[w] = (old ?: 0f) + c
        trainChars(" $w ", c, from = 1)
    }

    private fun trainChars(seq: String, c: Float, from: Int) {
        for (i in from until seq.length) {
            val ch = seq[i]
            for (k in 0..maxCtx) {
                if (i - k < 0) break
                val ctx = seq.substring(i - k, i)
                val n = charNodes.getOrPut(ctx) { Node() }
                n.total += c
                n.next[ch] = (n.next[ch] ?: 0f) + c
            }
        }
    }

    // --------------------------------------------------------------- consulta

    data class Context(val prev: String, val prefix: String)

    fun parse(textBefore: String): Context {
        val t = textBefore.lowercase()
        var i = t.length
        while (i > 0 && t[i - 1].isLetter()) i--
        val prefix = t.substring(i)
        var j = i
        var sentenceBreak = false
        while (j > 0 && !t[j - 1].isLetter()) {
            if (t[j - 1] in ".!?\n") sentenceBreak = true
            j--
        }
        if (j == 0 || sentenceBreak) return Context(START, prefix)
        var k = j
        while (k > 0 && t[k - 1].isLetter()) k--
        return Context(t.substring(k, j), prefix)
    }

    data class Prediction(val pieces: List<String>, val letters: List<String>)

    /**
     * Trechos para a grade (2+ caracteres; os que fecham palavra terminam em espaço)
     * e as letras soltas mais prováveis, para a barra de letras.
     */
    fun predict(textBefore: String, nPieces: Int = 18, nLetters: Int = 8): Prediction {
        val mixed = mixedDistribution(textBefore)
        val letters = mixed.entries
            .filter { it.key.length == 1 && it.key != " " }
            .sortedByDescending { it.value }
            .take(nLetters)
            .map { it.key }
        val multi = mixed.filterKeys { it.length >= 2 || it == " " }
        return Prediction(rank(if (multi.isEmpty()) mixed else multi, nPieces), letters)
    }

    /** Até [n] trechos prováveis para continuar o texto (em minúsculas). */
    fun pieces(textBefore: String, n: Int = 18): List<String> = rank(mixedDistribution(textBefore), n)

    private fun mixedDistribution(textBefore: String): Map<String, Float> {
        val ctx = parse(textBefore)
        val pw = wordPieceDistribution(ctx)
        val pc = charPieceDistribution(normalize(textBefore).takeLast(maxCtx))

        val alpha = if (pw.isEmpty()) 0f else 0.8f
        val mixed = HashMap<String, Float>()
        for ((s, p) in pw) mixed[s] = alpha * p
        for ((s, p) in pc) mixed[s] = (mixed[s] ?: 0f) + (1 - alpha) * p
        return mixed
    }

    private fun rank(mixed: Map<String, Float>, n: Int): List<String> {
        val ranked = mixed.entries
            .filter { it.value > 1e-4f }
            .sortedByDescending { it.value * gain(it.key) }

        val chosen = ArrayList<String>(n)
        for ((s, p) in ranked) {
            // Se um trecho mais longo já escolhido cobre quase toda a massa deste, ele é redundante.
            val redundant = chosen.any { it.length > s.length && it.startsWith(s) && (mixed[it] ?: 0f) > 0.55f * p }
            if (redundant) continue
            chosen.add(s)
            if (chosen.size >= n) break
        }
        return chosen
    }

    /** Até [n] palavras completas para a palavra atual (ou a próxima). */
    fun completions(textBefore: String, n: Int = 3): List<String> {
        val ctx = parse(textBefore)
        val scored = wordCandidates(ctx, accentInsensitive = true)
            .filter { it.key != ctx.prefix }
        return scored.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    // ------------------------------------------------------------ internos

    private fun gain(s: String): Float = if (s == " ") 2f else lengthGain(s.length)

    private fun lengthGain(len: Int): Float = when (len) {
        1 -> 1f
        2 -> 2f
        3 -> 2.8f
        4 -> 3.4f
        else -> 3.8f
    }

    /** Monta os índices de busca já na carga, para a primeira consulta não travar. */
    fun warmUp() = rebuildIfDirty()

    /** P(palavra | prefixo, palavra anterior) para as palavras candidatas. */
    private fun wordCandidates(ctx: Context, accentInsensitive: Boolean): Map<String, Float> {
        rebuildIfDirty()
        val cands = HashSet<String>()
        val follow = bigrams[ctx.prev]
        if (ctx.prefix.isEmpty()) {
            cands.addAll(topWords)
            follow?.keys?.let { cands.addAll(it) }
        } else {
            val key = strip(ctx.prefix)
            var i = lowerBound(strippedKeys, key)
            var count = 0
            while (i < strippedKeys.size && strippedKeys[i].startsWith(key) && count < 20_000) {
                val w = byStripped[i]
                if (accentInsensitive || w.startsWith(ctx.prefix)) cands.add(w)
                i++; count++
            }
        }
        if (cands.isEmpty()) return emptyMap()

        var uniSum = 0f
        var biSum = 0f
        for (w in cands) {
            uniSum += freq[w] ?: 0f
            biSum += follow?.get(w) ?: 0f
        }
        val lambda = if (biSum > 0f) 0.4f else 0f
        val out = HashMap<String, Float>(cands.size)
        for (w in cands) {
            val uni = (freq[w] ?: 0f) / uniSum
            val bi = if (biSum > 0f) (follow?.get(w) ?: 0f) / biSum else 0f
            out[w] = lambda * bi + (1 - lambda) * uni
        }
        return out
    }

    private fun wordPieceDistribution(ctx: Context): Map<String, Float> {
        val words = wordCandidates(ctx, accentInsensitive = false)
        val out = HashMap<String, Float>()
        for ((w, p) in words) {
            val rest = w.substring(ctx.prefix.length)
            for (len in 1..minOf(MAX_PIECE, rest.length)) {
                val s = rest.substring(0, len)
                out[s] = (out[s] ?: 0f) + p
            }
            // Um final curto de palavra entra inteiro ("aind" -> "ainda").
            if (rest.length in MAX_PIECE + 1..MAX_WHOLE) out[rest] = (out[rest] ?: 0f) + p
            // E também com o espaço, como um token que fecha a palavra ("ção ").
            if (rest.isNotEmpty() && rest.length <= MAX_WHOLE) {
                val end = "$rest "
                out[end] = (out[end] ?: 0f) + p
            }
        }
        // O prefixo já é uma palavra completa: o espaço vira um token, sozinho ("␣")
        // ou já puxando o começo da próxima palavra ("␣de", "␣a").
        val endMass = words[ctx.prefix] ?: 0f
        if (ctx.prefix.isNotEmpty() && endMass > 0f) {
            out[" "] = (out[" "] ?: 0f) + endMass
            val next = wordPieceDistribution(Context(ctx.prefix, ""))
                .filterKeys { it.length >= 2 && !it.startsWith(" ") }
                .entries.sortedByDescending { it.value }.take(12)
            for ((piece, q) in next) {
                val k = " $piece"
                out[k] = (out[k] ?: 0f) + endMass * q
            }
        }
        return out
    }

    private fun charPieceDistribution(start: String): Map<String, Float> {
        val out = HashMap<String, Float>()
        var beams = listOf("" to 1f)
        repeat(MAX_PIECE) {
            val next = ArrayList<Pair<String, Float>>()
            for ((s, p) in beams) {
                val c = start + s
                val node = longestNode(c) ?: continue
                for (ch in node.next.keys) {
                    if (!ch.isLetter()) continue
                    next.add(s + ch to p * charProb(c, ch))
                }
            }
            beams = next.sortedByDescending { it.second }.take(BEAM)
            for ((s, p) in beams) out[s] = p
        }
        return out
    }

    private fun longestNode(c: String): Node? {
        for (k in minOf(maxCtx, c.length) downTo 1) {
            charNodes[c.takeLast(k)]?.let { return it }
        }
        return charNodes[""]
    }

    private fun charProb(c: String, ch: Char): Float {
        var p = 1f / 40f
        for (k in 0..minOf(maxCtx, c.length)) {
            val node = charNodes[c.takeLast(k)] ?: break
            p = 0.8f * ((node.next[ch] ?: 0f) / node.total) + 0.2f * p
        }
        return p
    }

    private fun rebuildIfDirty() {
        if (!dirty) return
        val words = freq.keys.toTypedArray()
        val keyed = words.map { strip(it) to it }.sortedBy { it.first }
        strippedKeys = Array(keyed.size) { keyed[it].first }
        byStripped = Array(keyed.size) { keyed[it].second }
        topWords = freq.entries.sortedByDescending { it.value }.take(TOP_WORDS).map { it.key }
        dirty = false
    }

    private fun lowerBound(arr: Array<String>, key: String): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val START = "<s>"
        private const val MAX_PIECE = 4
        private const val MAX_WHOLE = 6
        private const val BEAM = 40
        private const val TOP_WORDS = 2000
        /** Quanto uma palavra escrita pelo usuário pesa no vocabulário (escala das listas de frequência). */
        private const val WORD_WEIGHT = 2000f
        private val WORD = Regex("\\p{L}+")
        private val MARKS = Regex("\\p{Mn}+")

        fun strip(s: String): String =
            MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")

        /** Minúsculas, só letras, qualquer outra coisa vira um espaço. */
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s.lowercase()) {
                if (ch.isLetter()) sb.append(ch)
                else if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
            }
            if (s.isEmpty() || !s.last().isLetter()) {
                if (sb.isEmpty() || sb[sb.length - 1] != ' ') sb.append(' ')
            }
            return sb.toString()
        }
    }
}
