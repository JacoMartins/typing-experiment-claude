package dev.trechos

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.trechos.predict.Predictor
import java.io.File
import java.util.concurrent.Executors

/**
 * Teclado com dois modos:
 *  - QWERTY normal;
 *  - grade 3x3 de trechos previstos (pedaços de palavra).
 * Nos dois modos a barra de cima completa a palavra atual.
 * O modo escolhido fica salvo e volta igual na próxima vez que o teclado abrir.
 */
class TrechosKeyboardService : InputMethodService() {

    private enum class Mode { QWERTY, TRECHOS }
    private enum class Layer { LETTERS, ACCENTS, SYMBOLS }
    private enum class Shift { OFF, ONCE, LOCK }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var predictor: Predictor? = null
    private var loadedVersion = -1L

    private lateinit var prefs: android.content.SharedPreferences
    private var mode = Mode.QWERTY
    private var layer = Layer.LETTERS
    private var shift = Shift.OFF

    // estado de edição
    private var generation = 0
    private var pieces: List<String> = emptyList()
    private var page = 0
    private var words: List<String> = emptyList()
    private val pieceStack = ArrayDeque<String>()
    private var lastAutoSpace = false
    private var learningEnabled = true

    // views
    private lateinit var root: LinearLayout
    private lateinit var wordBar: LinearLayout
    private lateinit var body: FrameLayout
    private val wordViews = ArrayList<TextView>()
    private val cellViews = ArrayList<TextView>()
    private var pageButton: TextView? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = if (prefs.getString(KEY_MODE, Mode.QWERTY.name) == Mode.TRECHOS.name) Mode.TRECHOS else Mode.QWERTY
        loadModel()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun loadModel() {
        val version = prefs.getLong(KEY_RESET, 0L)
        loadedVersion = version
        predictor = null
        worker.execute {
            val p = Predictor()
            for ((file, w) in listOf("words_pt.tsv" to 1f, "words_en.tsv" to 0.35f, "words_es.tsv" to 0.35f)) {
                assets.open(file).bufferedReader().useLines { p.loadWordList(it, w) }
            }
            p.learnText(assets.open("frases.txt").bufferedReader().readText(), 1f)
            historyFile().takeIf { it.exists() }?.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) p.learnPair(line.substring(0, tab), line.substring(tab + 1), USER_WEIGHT)
            }
            p.warmUp()
            predictor = p
            main.post { refresh() }
        }
    }

    private fun historyFile() = File(filesDir, HISTORY)

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(3), dp(4), dp(3), dp(6))
        }
        wordBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wordViews.clear()
        repeat(3) { i ->
            val tv = key("", BAR_BG, textSp = 17f) { pickWord(i) }
            wordViews.add(tv)
            wordBar.addView(tv, LinearLayout.LayoutParams(0, dp(44), 1f).margins())
        }
        root.addView(wordBar)
        body = FrameLayout(this)
        root.addView(body)
        buildBody()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (prefs.getLong(KEY_RESET, 0L) != loadedVersion) loadModel()
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        learningEnabled = variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            (info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_NUMBER
        layer = Layer.LETTERS
        shift = Shift.OFF
        pieceStack.clear()
        lastAutoSpace = false
        buildBody()
        refresh()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refresh()
    }

    // ---------------------------------------------------------------- previsão

    private fun textBefore(): String =
        currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: ""

    private fun refresh() {
        if (!::root.isInitialized) return
        val p = predictor
        if (p == null) {
            showWords(listOf("carregando…"), clickable = false)
            showPieces(emptyList())
            return
        }
        val before = textBefore()
        val gen = ++generation
        worker.execute {
            val ws = p.completions(before, 3)
            val ps = if (mode == Mode.TRECHOS) p.pieces(before, 18) else emptyList()
            main.post {
                if (gen != generation) return@post
                words = ws
                pieces = ps
                page = 0
                showWords(ws.map { caseLike(before, it, wholeWord = true) }, clickable = true)
                showPieces(ps)
            }
        }
    }

    private fun showWords(list: List<String>, clickable: Boolean) {
        wordViews.forEachIndexed { i, tv ->
            tv.text = list.getOrNull(i) ?: ""
            tv.isEnabled = clickable && i < list.size
        }
    }

    private fun showPieces(list: List<String>) {
        if (mode != Mode.TRECHOS) return
        val before = textBefore()
        val pageItems = list.drop(page * 9).take(9)
        cellViews.forEach { it.text = ""; it.isEnabled = false; it.tag = null }
        pageItems.forEachIndexed { rank, s ->
            val cell = cellViews.getOrNull(GRID_ORDER[rank]) ?: return@forEachIndexed
            val shown = caseLike(before, s, wholeWord = false)
            cell.text = shown
            cell.tag = shown
            cell.isEnabled = true
        }
        val pages = maxOf(1, (list.size + 8) / 9)
        pageButton?.text = if (pages > 1) "⟳ ${page + 1}/$pages" else "⟳"
    }

    /** Ajusta maiúsculas do trecho/palavra conforme o que já foi digitado. */
    private fun caseLike(before: String, s: String, wholeWord: Boolean): String {
        if (s.isEmpty()) return s
        var i = before.length
        while (i > 0 && before[i - 1].isLetter()) i--
        val prefix = before.substring(i)
        val atStart = prefix.isEmpty()
        return when {
            prefix.length >= 2 && prefix.all { it.isUpperCase() } -> s.uppercase()
            shift == Shift.LOCK -> s.uppercase()
            wholeWord && prefix.firstOrNull()?.isUpperCase() == true -> s.replaceFirstChar { it.uppercaseChar() }
            atStart && (shift == Shift.ONCE || autoCaps()) -> s.replaceFirstChar { it.uppercaseChar() }
            else -> s
        }
    }

    private fun autoCaps(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val ic = currentInputConnection ?: return false
        return ic.getCursorCapsMode(info.inputType) != 0
    }

    // ---------------------------------------------------------------- ações

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (shift == Shift.ONCE && text.any { it.isLetter() }) {
            shift = Shift.OFF
            if (mode == Mode.QWERTY) buildBody()
        }
    }

    private fun typeChar(c: String) {
        val out = if (shift != Shift.OFF) c.uppercase() else c
        lastAutoSpace = false
        pieceStack.clear()
        commit(out)
    }

    private fun pickPiece(cell: TextView) {
        val s = cell.tag as? String ?: return
        lastAutoSpace = false
        pieceStack.addLast(s)
        commit(s)
    }

    private fun pickWord(i: Int) {
        val w = words.getOrNull(i) ?: return
        val ic = currentInputConnection ?: return
        val before = textBefore()
        var start = before.length
        while (start > 0 && before[start - 1].isLetter()) start--
        val prefix = before.substring(start)
        val shown = caseLike(before, w, wholeWord = true)
        ic.beginBatchEdit()
        if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
        commit("$shown ")
        ic.endBatchEdit()
        learnFrom(before.substring(0, start) + shown + " ")
        pieceStack.clear()
        lastAutoSpace = true
    }

    private fun space() {
        val before = textBefore()
        commit(" ")
        learnFrom("$before ")
        pieceStack.clear()
        lastAutoSpace = false
    }

    /** Vírgula, ponto etc.: cola na palavra se o espaço anterior foi automático. */
    private fun punct(c: String) {
        val ic = currentInputConnection ?: return
        val before = textBefore()
        ic.beginBatchEdit()
        if ((lastAutoSpace || mode == Mode.TRECHOS) && before.endsWith(" ")) {
            ic.deleteSurroundingText(1, 0)
            commit("$c ")
            lastAutoSpace = true
        } else {
            commit(c)
            lastAutoSpace = false
        }
        ic.endBatchEdit()
        learnFrom(before.trimEnd() + c)
        pieceStack.clear()
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        lastAutoSpace = false
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = textBefore()
        val last = pieceStack.removeLastOrNull()
        if (mode == Mode.TRECHOS && last != null && before.endsWith(last)) {
            ic.deleteSurroundingText(last.length, 0)
        } else {
            pieceStack.clear()
            if (before.isEmpty()) {
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
            } else {
                // não quebra emojis e outros pares substitutos
                val n = if (before.length >= 2 && Character.isSurrogatePair(before[before.length - 2], before.last())) 2 else 1
                ic.deleteSurroundingText(n, 0)
            }
        }
    }

    private fun enter() {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        pieceStack.clear()
        lastAutoSpace = false
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            learnFrom(textBefore() + "\n")
            commit("\n")
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        prefs.edit().putString(KEY_MODE, m.name).apply()   // fica salvo para a próxima vez
        layer = Layer.LETTERS
        buildBody()
        refresh()
    }

    /** Aprende a última palavra concluída (com a anterior como contexto). */
    private fun learnFrom(text: String) {
        if (!learningEnabled) return
        val p = predictor ?: return
        val m = Regex("(?:(\\p{L}+)[^\\p{L}.!?\\n]+)?(\\p{L}+)[^\\p{L}]$").find(text) ?: return
        val word = m.groupValues[2].lowercase()
        val prev = m.groupValues[1].lowercase().ifEmpty { Predictor.START }
        worker.execute {
            p.learnPair(prev, word, USER_WEIGHT)
            p.warmUp()
            try { historyFile().appendText("$prev\t$word\n") } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildBody() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        cellViews.clear()
        pageButton = null
        body.addView(if (mode == Mode.TRECHOS) buildGrid() else buildQwerty())
        showPieces(pieces)
    }

    private fun buildGrid(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val side = listOf<Pair<String, (TextView) -> Unit>>(
            "⌫" to { v -> repeating(v) { backspace() } },
            "⟳" to { v ->
                pageButton = v
                v.setOnClickListener {
                    val pages = maxOf(1, (pieces.size + 8) / 9)
                    page = (page + 1) % pages
                    tap(it); showPieces(pieces)
                }
            },
            "↵" to { v -> v.setOnClickListener { tap(it); enter() } },
        )
        for (r in 0 until 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val cell = key("", CELL_BG, textSp = 22f, bold = true) { v -> pickPiece(v as TextView) }
                cellViews.add(cell)
                row.addView(cell, LinearLayout.LayoutParams(0, dp(62), 1f).margins())
            }
            val (label, setup) = side[r]
            val s = key(label, FN_BG, textSp = 20f, onClick = null)
            setup(s)
            row.addView(s, LinearLayout.LayoutParams(0, dp(62), 0.8f).margins())
            col.addView(row)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(key("ABC", FN_BG, 15f) { setMode(Mode.QWERTY) }, weight(1.1f))
        bottom.addView(key(",", KEY_BG) { punct(",") }, weight(0.7f))
        bottom.addView(key("espaço", KEY_BG, 14f) { space() }, weight(2.2f))
        bottom.addView(key(".", KEY_BG) { punct(".") }, weight(0.7f))
        bottom.addView(key("?", KEY_BG) { punct("?") }, weight(0.7f))
        col.addView(bottom)
        return col
    }

    private fun buildQwerty(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = when (layer) {
            Layer.LETTERS -> listOf("qwertyuiop", "asdfghjklç", "zxcvbnm")
            Layer.ACCENTS -> listOf("áàâãéêíóôõ", "úüñ'\"-():;", "!?@/&_")
            Layer.SYMBOLS -> listOf("1234567890", "@#$%&-+()/", "*\"':;!?")
        }
        for ((idx, chars) in rows.withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (idx == 2) {
                if (layer == Layer.SYMBOLS) {
                    row.addView(key("áé", FN_BG, 15f) { layer = Layer.ACCENTS; buildBody() }, weight(1.5f))
                } else {
                    val label = when (shift) { Shift.OFF -> "⇧"; Shift.ONCE -> "⬆"; Shift.LOCK -> "⇪" }
                    row.addView(key(label, if (shift == Shift.OFF) FN_BG else CELL_BG, 18f) {
                        shift = when (shift) { Shift.OFF -> Shift.ONCE; Shift.ONCE -> Shift.LOCK; Shift.LOCK -> Shift.OFF }
                        buildBody(); refresh()
                    }, weight(1.5f))
                }
            }
            for (ch in chars) {
                val s = ch.toString()
                val shown = if (shift != Shift.OFF) s.uppercase() else s
                row.addView(key(shown, KEY_BG, 19f) { typeChar(s) }, weight(1f))
            }
            if (idx == 2) row.addView(key("⌫", FN_BG, 18f, onClick = null).also { v -> repeating(v) { backspace() } }, weight(1.5f))
            col.addView(row)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(key(if (layer == Layer.LETTERS) "?123" else "ABC", FN_BG, 14f) {
            layer = if (layer == Layer.LETTERS) Layer.SYMBOLS else Layer.LETTERS; buildBody()
        }, weight(1.2f))
        bottom.addView(key(if (layer == Layer.ACCENTS) "ABC" else "áé", FN_BG, 15f) {
            layer = if (layer == Layer.ACCENTS) Layer.LETTERS else Layer.ACCENTS; buildBody()
        }, weight(1f))
        // tecla que troca para a grade de trechos
        bottom.addView(key("⊞", CELL_BG, 20f, bold = true) { setMode(Mode.TRECHOS) }, weight(1f))
        bottom.addView(key(",", KEY_BG) { punct(",") }, weight(0.8f))
        bottom.addView(key("espaço", KEY_BG, 14f) { space() }, weight(3f))
        bottom.addView(key(".", KEY_BG) { punct(".") }, weight(0.8f))
        bottom.addView(key("↵", FN_BG, 18f) { enter() }, weight(1.2f))
        col.addView(bottom)
        return col
    }

    // ---------------------------------------------------------------- utilitários de view

    private fun key(
        label: String,
        color: Int,
        textSp: Float = 18f,
        bold: Boolean = false,
        onClick: ((View) -> Unit)?,
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        background = GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(color) }
        isClickable = true
        if (onClick != null) setOnClickListener { tap(it); onClick(it) }
    }

    /** Segurar repete a ação (usado no apagar). */
    private fun repeating(v: View, action: () -> Unit) {
        val r = object : Runnable {
            override fun run() { action(); main.postDelayed(this, 55) }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { tap(view); view.isPressed = true; action(); main.postDelayed(r, 420) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; main.removeCallbacks(r) }
            }
            true
        }
    }

    private fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private fun weight(w: Float) = LinearLayout.LayoutParams(0, dp(52), w).margins()

    private fun LinearLayout.LayoutParams.margins(): LinearLayout.LayoutParams = apply {
        setMargins(dp(2), dp(3), dp(2), dp(3))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS = "trechos"
        const val KEY_MODE = "modo"
        const val KEY_RESET = "reset"
        const val HISTORY = "historico.tsv"
        private const val USER_WEIGHT = 3f

        /** Ordem de preenchimento: o mais provável no centro, depois as bordas, depois os cantos. */
        private val GRID_ORDER = intArrayOf(4, 1, 7, 3, 5, 0, 2, 6, 8)

        private val BG = Color.parseColor("#17181B")
        private val KEY_BG = Color.parseColor("#3A3C42")
        private val FN_BG = Color.parseColor("#2A2C31")
        private val CELL_BG = Color.parseColor("#2E4A6E")
        private val BAR_BG = Color.parseColor("#24262B")
        private val FG = Color.parseColor("#F1F2F4")
    }
}
