package dev.trechos.predict

import java.text.Normalizer

/**
 * Motor de previsão sem dependências do Android.
 *
 * Combina três fontes:
 *  - memória pessoal ([UserStore]): o que VOCÊ escreveu depois das mesmas
 *    3, 2 ou 1 palavras anteriores (backoff), com peso maior para o recente;
 *  - vocabulário geral (listas de frequência + bigramas de um corpus pequeno);
 *  - n-gramas de caracteres: cobre palavras que não estão no vocabulário.
 * Por cima, um cache da conversa dá um bônus às palavras já usadas no texto atual.
 *
 * Tudo é calculado como probabilidade de PALAVRAS; os trechos (tokens) da grade
 * saem daí, somando a probabilidade das palavras que começam com cada trecho.
 *
 * Cada idioma tem o SEU Predictor (vocabulário e modelo de letras separados);
 * a memória pessoal ([userStore]) é compartilhada entre eles.
 */
class Predictor(val language: String = "pt") {

    private class Node {
        var total = 0f
        val next = HashMap<Char, Float>(8)
    }

    private val maxCtx = 4
    private val charNodes = HashMap<String, Node>(200_000)

    /** Memória pessoal. Sem ela, o motor usa só o vocabulário geral. */
    var userStore: UserStore? = null

    /**
     * Palavras do documento que está sendo editado (texto antes E depois do cursor),
     * com quantas vezes aparecem. Só fica em memória; nunca é salvo.
     */
    @Volatile var documentWords: Map<String, Int> = emptyMap()

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

    /**
     * Aprende com o texto que acabou de ganhar um separador (espaço, pontuação):
     * a última palavra concluída e as até 3 palavras antes dela.
     */
    fun learnCompleted(text: String, weight: Float) {
        val t = text.lowercase()
        // exatamente um separador depois da palavra (evita aprender duas vezes com "  ")
        if (t.length < 2 || t.last().isLetter() || !t[t.length - 2].isLetter()) return
        val ctx = parse(t.substring(0, t.length - 1))
        val word = ctx.prefix
        if (word.isEmpty()) return
        addWord(word, weight * WORD_WEIGHT)
        userStore?.learn(ctx.history, word)
        // vocabulário pessoal POR IDIOMA: na próxima carga, a palavra volta só para este idioma
        userStore?.learnExact(vocabKey(language), word)
    }

    /** Coloca no vocabulário uma palavra da memória pessoal (na carga do teclado). */
    fun addUserWord(word: String, count: Float, weight: Float) {
        if (word.isNotEmpty() && word.all { it.isLetter() }) addWord(word, count * weight * WORD_WEIGHT)
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

    /**
     * O que o motor enxerga do texto:
     *  - [prefix]: o pedaço da palavra que está sendo escrita;
     *  - [history]: as até 3 palavras anteriores na mesma frase ([START] marca o começo da frase);
     *  - [cache]: quantas vezes cada palavra já apareceu no texto atual.
     */
    data class Context(
        val prev: String,
        val prefix: String,
        val history: List<String> = listOf(prev),
        val cache: Map<String, Int> = emptyMap(),
    )

    fun parse(textBefore: String): Context {
        val t = textBefore.lowercase()
        var i = t.length
        while (i > 0 && t[i - 1].isLetter()) i--
        val prefix = t.substring(i)
        val body = t.substring(0, i)

        // janela fixa: só as 3 últimas palavras da frase atual
        val sentence = body.substring(body.indexOfLast { it in ".!?\n" } + 1)
        val words = WORD.findAll(sentence.takeLast(200)).map { it.value }.toList()
        var history = words.takeLast(UserStore.MAX_ORDER)
        if (words.size < UserStore.MAX_ORDER) history = (listOf(START) + history).takeLast(UserStore.MAX_ORDER)

        // cache da conversa: palavras usadas no texto recente
        val cache = WORD.findAll(body.takeLast(CACHE_CHARS)).map { it.value }.toList()
            .takeLast(CACHE_WORDS).groupingBy { it }.eachCount()

        return Context(history.lastOrNull() ?: START, prefix, history, cache)
    }

    data class Prediction(val pieces: List<String>, val letters: List<String>)

    /**
     * Trechos para a grade (2+ caracteres; os que fecham palavra terminam em espaço)
     * e as letras soltas mais prováveis, para a barra de letras.
     */
    fun predict(textBefore: String, nPieces: Int = 18, alphabet: String = "abcdefghijklmnopqrstuvwxyz"): Prediction {
        val mixed = mixedDistribution(textBefore)
        val multi = mixed.filterKeys { it.length >= 2 || it == " " }
        val pieces = rank(if (multi.isEmpty()) mixed else multi, nPieces)

        // Letras complementam a grade: quanto da chance de cada letra os trechos
        // visíveis (primeira página) já cobrem? A faixa prioriza o que sobra.
        // Letras sem chance nenhuma vão para o fim, na ordem da frequência geral.
        val visible = pieces.take(9)
        val base = charNodes[""]
        val letters = alphabet.map { it.toString() }.map { l ->
            val p = mixed[l] ?: 0f
            val covered = visible
                .filter { t -> t.startsWith(l) && visible.none { u -> u != t && t.startsWith(u) } }
                .sumOf { t -> (mixed[t] ?: 0f).toDouble() }.toFloat()
            val general = base?.let { (it.next[l[0]] ?: 0f) / it.total } ?: 0f
            l to (p - 0.8f * minOf(covered, p) + 1e-4f * general)
        }.sortedByDescending { it.second }.map { it.first }
        return Prediction(pieces, letters)
    }

    /**
     * Aprende com a escolha de um token (ou letra) na grade: "neste ponto da palavra,
     * você escolheu este pedaço". Da próxima vez, esse pedaço sobe na grade.
     */
    fun learnPiece(textBefore: String, piece: String) {
        val p = piece.lowercase()
        if (p.isEmpty()) return
        userStore?.learnExact(pieceKey(parse(textBefore)), p)
    }

    /** No começo da palavra, a escolha depende da palavra anterior; no meio, do pedaço já escrito. */
    private fun pieceKey(ctx: Context) =
        if (ctx.prefix.isEmpty()) "$PIECE_MARK${ctx.prev}|" else "$PIECE_MARK|${ctx.prefix}"

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

        // tokens que você costuma escolher neste mesmo ponto ganham peso
        val chosen = userStore?.followers(pieceKey(ctx), 60).orEmpty()
        val total = chosen.values.sum()
        if (total > 0f) {
            val beta = total / (total + 2f) * 0.5f
            for (k in mixed.keys.toList()) mixed[k] = (1 - beta) * mixed[k]!!
            for ((s, c) in chosen) mixed[s] = (mixed[s] ?: 0f) + beta * c / total
        }
        return mixed
    }

    private fun rank(mixed: Map<String, Float>, n: Int): List<String> {
        val ranked = mixed.entries
            .filter { it.value > 0f }
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

    /**
     * P(palavra | prefixo, contexto) para as palavras candidatas.
     *
     * 1. Memória pessoal com backoff: procura o que você escreveu depois das últimas
     *    3 palavras; depois de 2; depois de 1. Os níveis mais específicos pesam mais.
     * 2. Vocabulário geral: frequência da palavra + bigramas do corpus.
     * 3. A confiança na memória pessoal cresce com quantas vezes o contexto já foi visto.
     * 4. Cache da conversa: pequeno bônus para palavras já usadas neste texto.
     */
    private fun wordCandidates(ctx: Context, accentInsensitive: Boolean): Map<String, Float> {
        rebuildIfDirty()
        val strippedPrefix = strip(ctx.prefix)
        fun matches(w: String) = ctx.prefix.isEmpty() ||
            (if (accentInsensitive) strip(w).startsWith(strippedPrefix) else w.startsWith(ctx.prefix))

        val cands = HashSet<String>()
        val follow = bigrams[ctx.prev]
        if (ctx.prefix.isEmpty()) {
            cands.addAll(topWords)
            follow?.keys?.let { cands.addAll(it) }
        } else {
            var i = lowerBound(strippedKeys, strippedPrefix)
            var count = 0
            while (i < strippedKeys.size && strippedKeys[i].startsWith(strippedPrefix) && count < 20_000) {
                val w = byStripped[i]
                if (matches(w)) cands.add(w)
                i++; count++
            }
        }

        // memória pessoal: um mapa por nível de contexto (3, 2, 1 palavras)
        val levels = ArrayList<Pair<Int, Map<String, Float>>>()
        userStore?.let { store ->
            for (k in ctx.history.size downTo 1) {
                val m = store.followers(ctx.history.takeLast(k).joinToString(" ")).filterKeys { matches(it) }
                if (m.isNotEmpty()) { levels.add(k to m); cands.addAll(m.keys) }
            }
        }
        // contexto do documento: o texto recente + o documento inteiro (nomes, termos do assunto).
        // Palavras da própria janela quase nunca se repetem logo em seguida: ficam sem bônus.
        // A comparação ignora acentos: "Aná" encontra "Anaxágoras".
        fun matchesLoose(w: String) = ctx.prefix.isEmpty() || strip(w).startsWith(strippedPrefix)
        val cache = HashMap<String, Int>()
        for ((w, c) in ctx.cache) cache[w] = c
        for ((w, c) in documentWords) cache[w] = (cache[w] ?: 0) + c
        cache.keys.retainAll { it != ctx.prefix && it !in ctx.history && it.length > ctx.prefix.length && matchesLoose(it) }
        cands.addAll(cache.keys)
        if (cands.isEmpty()) return emptyMap()

        // geral
        var uniSum = 0f
        var biSum = 0f
        for (w in cands) {
            uniSum += freq[w] ?: 0f
            biSum += follow?.get(w) ?: 0f
        }
        val lambda = if (biSum > 0f) 0.4f else 0f

        // pessoal: mistura os níveis, normalizando os pesos dos níveis que existem
        val levelWeight = levels.sumOf { ORDER_WEIGHT[it.first].toDouble() }.toFloat()
        val levelTotals = levels.map { (_, m) -> m.values.sum() }
        val confidence = levels.firstOrNull()?.let { (k, _) ->
            val total = levelTotals[0]
            total / (total + 1.5f) * (0.55f + 0.1f * k)
        } ?: 0f

        val cacheSum = cache.values.sum().toFloat()

        val out = HashMap<String, Float>(cands.size)
        for (w in cands) {
            val uni = if (uniSum > 0f) (freq[w] ?: 0f) / uniSum else 0f
            val bi = if (biSum > 0f) (follow?.get(w) ?: 0f) / biSum else 0f
            val general = lambda * bi + (1 - lambda) * uni
            var personal = 0f
            levels.forEachIndexed { idx, (k, m) ->
                personal += ORDER_WEIGHT[k] / levelWeight * ((m[w] ?: 0f) / levelTotals[idx])
            }
            var p = confidence * personal + (1 - confidence) * general
            // no meio de uma palavra, uma palavra do documento que bate com o começo é muito provável
            val cw = if (ctx.prefix.isEmpty()) CACHE_WEIGHT else CACHE_WEIGHT_PREFIX
            if (cacheSum > 0f) p = (1 - cw) * p + cw * (cache[w] ?: 0) / cacheSum
            out[w] = p
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
        // Uma letra solta só conta como palavra se for comum sozinha ("a", "e", "o", "é"...):
        // sem isso, digitar "d" fazia a grade oferecer "␣send".
        val endMass = words[ctx.prefix] ?: 0f
        val canEnd = ctx.prefix.length >= 2 || ctx.prefix in SINGLE_LETTER_WORDS
        if (ctx.prefix.isNotEmpty() && endMass > 0f && canEnd) {
            out[" "] = (out[" "] ?: 0f) + endMass
            val next = wordPieceDistribution(
                Context(ctx.prefix, "", (ctx.history + ctx.prefix).takeLast(UserStore.MAX_ORDER), ctx.cache)
            )
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

        /** Contexto da memória pessoal que guarda o vocabulário de cada idioma. */
        fun vocabKey(language: String) = "@$language"

        private val SINGLE_LETTER_WORDS = setOf("a", "e", "o", "é", "à", "i", "y", "u")
        /** Prefixo dos contextos de "token escolhido" na memória pessoal. */
        private const val PIECE_MARK = "§"
        /** Peso de cada nível do backoff: 3 palavras de contexto valem mais que 1. */
        private val ORDER_WEIGHT = floatArrayOf(0f, 0.2f, 0.3f, 0.5f)
        private const val CACHE_WEIGHT = 0.07f
        private const val CACHE_WEIGHT_PREFIX = 0.3f
        private const val CACHE_CHARS = 2000
        private const val CACHE_WORDS = 300
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
