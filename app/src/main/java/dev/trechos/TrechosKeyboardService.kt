package dev.trechos

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
    private var letters: List<String> = emptyList()
    private var numeric = false
    private var phone = false
    private val letterViews = ArrayList<TextView>()
    private val pieceStack = ArrayDeque<String>()
    private var lastAutoSpace = false
    private var learningEnabled = true

    // views
    private lateinit var root: LinearLayout
    private lateinit var wordBar: LinearLayout
    private lateinit var body: FrameLayout
    private val wordViews = ArrayList<TextView>()
    private val cellViews = ArrayList<TextView>()
    private var miniOverlay: View? = null
    private val miniKeys = ArrayList<TextView>()
    private var miniHover: TextView? = null
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
            setPadding(dp(5), dp(4), dp(5), dp(10))
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
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        phone = cls == InputType.TYPE_CLASS_PHONE
        numeric = phone || cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME
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
        if (numeric) { showWords(emptyList(), clickable = false); return }
        worker.execute {
            val ws = p.completions(before, 3)
            val pr = if (mode == Mode.TRECHOS) p.predict(before, 18, LETTERS) else null
            main.post {
                if (gen != generation) return@post
                words = ws
                pieces = pr?.pieces ?: emptyList()
                letters = pr?.letters ?: emptyList()
                page = 0
                showWords(ws.map { caseLike(before, it, wholeWord = true) }, clickable = true)
                showPieces(pieces)
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
            // o espaço no fim do token aparece como ␣
            cell.text = shown.replace(' ', '␣')
            cell.tag = shown
            cell.isEnabled = true
        }
        letterViews.forEachIndexed { i, tv ->
            val l = letters.getOrNull(i)
            val shown = l?.let { caseLike(before, it, wholeWord = false) }
            tv.text = shown ?: ""
            tv.tag = shown
            tv.isEnabled = shown != null
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
        val before = textBefore()
        pieceStack.addLast(s)
        commit(s)
        lastAutoSpace = s.endsWith(" ")
        // se o token tem espaço, uma palavra acabou: aprende com ela
        val sp = s.lastIndexOf(' ')
        if (sp >= 0) learnFrom(before + s.substring(0, sp + 1))
    }

    // ---------------------------------------------------------------- mini QWERTY

    private fun showMini() {
        if (miniOverlay != null) return
        val before = textBefore()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            isClickable = true   // não deixa o toque vazar para a grade embaixo
        }
        for (chars in MINI_ROWS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (ch in chars) {
                val label = ch.toString()
                val tv = if (label == "✕") {
                    key(label, FN_BG, 18f) { hideMini() }
                } else {
                    key(caseLike(before, label, wholeWord = false), KEY_BG, 20f) { v -> typeMini(v as TextView) }
                }
                miniKeys.add(tv)
                row.addView(tv, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
            }
            panel.addView(row)
        }
        body.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        miniOverlay = panel
    }

    private fun hideMini() {
        miniOverlay?.let { body.removeView(it) }
        miniOverlay = null
        miniKeys.clear()
        miniHover = null
    }

    private fun typeMini(v: TextView) {
        val l = v.text.toString()
        if (l.isEmpty() || l == "✕") { hideMini(); return }
        lastAutoSpace = false
        pieceStack.addLast(l)
        hideMini()
        commit(l)
    }

    private fun miniKeyAt(rawX: Float, rawY: Float): TextView? {
        val loc = IntArray(2)
        for (k in miniKeys) {
            k.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX < loc[0] + k.width && rawY >= loc[1] && rawY < loc[1] + k.height) return k
        }
        return null
    }

    /** Segurar abre o mini QWERTY; deslizar escolhe; soltar digita. Um toque rápido deixa ele aberto. */
    private fun miniTouch(v: View) {
        var startX = 0f
        var startY = 0f
        var moved = false
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tap(view); view.isPressed = true
                    startX = ev.rawX; startY = ev.rawY; moved = false
                    if (miniOverlay != null) hideMini() else showMini()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.hypot((ev.rawX - startX).toDouble(), (ev.rawY - startY).toDouble()) > dp(14)) moved = true
                    val k = miniKeyAt(ev.rawX, ev.rawY)
                    if (k !== miniHover) {
                        miniHover?.isPressed = false
                        k?.isPressed = true
                        if (k != null) tap(k)
                        miniHover = k
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    val k = miniHover
                    if (moved) { if (k != null) typeMini(k) else hideMini() }
                }
                MotionEvent.ACTION_CANCEL -> { view.isPressed = false; if (moved) hideMini() }
            }
            true
        }
    }

    /** Letra da barra de sugestões da grade. */
    private fun typeLetter(l: String) {
        lastAutoSpace = false
        pieceStack.addLast(l)
        commit(l)
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
        miniOverlay = null
        miniKeys.clear()
        cellViews.clear()
        letterViews.clear()
        pageButton = null
        wordBar.visibility = if (numeric) View.GONE else View.VISIBLE
        body.addView(
            when {
                numeric -> buildNumpad()      // campo de número: teclado numérico, sem mudar o modo salvo
                mode == Mode.TRECHOS -> buildGrid()
                else -> buildQwerty()
            }
        )
        showPieces(pieces)
    }

    /** Teclado numérico grande, como o dos campos de número do sistema. */
    private fun buildNumpad(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val enterLabel = when (action) {
            EditorInfo.IME_ACTION_GO -> "Ir"
            EditorInfo.IME_ACTION_SEARCH -> "Buscar"
            EditorInfo.IME_ACTION_SEND -> "Enviar"
            EditorInfo.IME_ACTION_NEXT -> "Próx."
            EditorInfo.IME_ACTION_DONE -> "OK"
            else -> "↵"
        }
        val rows = listOf(
            listOf("1", "2", "3", "⌫"),
            listOf("4", "5", "6", enterLabel),
            listOf("7", "8", "9", if (phone) "+" else "."),
            listOf(if (phone) "*" else "-", "0", if (phone) "#" else " ", if (phone) " " else ","),
        )
        for ((r, labels) in rows.withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((c, label) in labels.withIndex()) {
                val lp = LinearLayout.LayoutParams(0, dp(58), 1f).margins()
                val v = when {
                    label == "⌫" -> key(label, FN_BG, 22f, onClick = null).also { repeating(it) { backspace() } }
                    r == 1 && c == 3 -> key(label, FN_BG, 18f) { enter() }.also { it.setTextColor(ACCENT) }
                    label == " " -> key(if (phone) "␣" else "", FN_BG, 20f) { commit(" ") }
                    c == 3 || (r == 3 && c != 1) -> key(label, FN_BG, 22f) { commit(label) }
                    else -> key(label, KEY_BG, 26f) { commit(label) }
                }
                row.addView(v, lp)
            }
            col.addView(row)
        }
        return col
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
                row.addView(cell, LinearLayout.LayoutParams(0, dp(60), 1f).margins())
            }
            val (label, setup) = side[r]
            val s = key(label, FN_BG, textSp = 20f, onClick = null)
            setup(s)
            row.addView(s, LinearLayout.LayoutParams(0, dp(60), 0.8f).margins())
            col.addView(row)
        }
        // barra de letras sugeridas: saída para quando o trecho certo não aparece
        val lettersRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(LETTERS) {
            val tv = key("", KEY_BG, 18f) { v -> (v.tag as? String)?.let { typeLetter(it) } }
            letterViews.add(tv)
            lettersRow.addView(tv, LinearLayout.LayoutParams(0, dp(44), 1f).margins())
        }
        // última tecla: segura e desliza para escolher no mini QWERTY (como o menu de acentos)
        val mini = key("⌨", CELL_BG, 20f, onClick = null)
        miniTouch(mini)
        lettersRow.addView(mini, LinearLayout.LayoutParams(0, dp(44), 1.2f).margins())
        col.addView(lettersRow)
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
            Layer.LETTERS -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            Layer.ACCENTS -> listOf("áàâãéêíóôõ", "úüçñ'\"-():", "!?@/&_")
            Layer.SYMBOLS -> listOf("1234567890", "@#$%&-+()/", "*\"':;!?")
        }
        // fileira de números fixa no modo letras, como nos teclados do sistema
        if (layer != Layer.SYMBOLS) {
            val nums = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (d in "1234567890") {
                nums.addView(key(d.toString(), KEY_BG, 23f) { typeChar(d.toString()) }, keyParams(1f))
            }
            col.addView(nums)
        }
        for ((idx, chars) in rows.withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (idx == 2) {
                if (layer == Layer.SYMBOLS) {
                    row.addView(key("áé", FN_BG, 17f) { layer = Layer.ACCENTS; buildBody() }, keyParams(1.45f))
                } else {
                    val label = when (shift) { Shift.OFF -> "⇧"; Shift.ONCE -> "⬆"; Shift.LOCK -> "⇪" }
                    row.addView(key(label, if (shift == Shift.OFF) FN_BG else CELL_BG, 24f) {
                        shift = when (shift) { Shift.OFF -> Shift.ONCE; Shift.ONCE -> Shift.LOCK; Shift.LOCK -> Shift.OFF }
                        buildBody(); refresh()
                    }, keyParams(1.45f))
                }
            }
            // fileira do meio com 9 teclas, recuada meia tecla de cada lado
            val inset = chars.length == 9 && idx == 1
            if (inset) row.addView(View(this), keyParams(0.5f))
            for (ch in chars) {
                val s = ch.toString()
                val shown = if (shift != Shift.OFF) s.uppercase() else s
                val k = key(shown, KEY_BG, 24f) { typeChar(s) }
                // segurar o c digita ç
                if (s == "c") k.setOnLongClickListener { tap(it); typeChar("ç"); true }
                row.addView(k, keyParams(1f))
            }
            if (inset) row.addView(View(this), keyParams(0.5f))
            if (idx == 2) row.addView(key("⌫", FN_BG, 22f, onClick = null).also { v -> repeating(v) { backspace() } }, keyParams(1.45f))
            col.addView(row)
        }
        // última fileira no estilo Samsung: !#1  ,  espaço  .  ↵  (+ ⊞ para a grade)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(key(if (layer == Layer.LETTERS) "!#1" else "ABC", FN_BG, 17f) {
            layer = if (layer == Layer.LETTERS) Layer.SYMBOLS else Layer.LETTERS; buildBody()
        }, keyParams(1.25f))
        bottom.addView(key("⊞", CELL_BG, 22f, bold = true) { setMode(Mode.TRECHOS) }, keyParams(1f))
        bottom.addView(key(",", FN_BG, 22f) { punct(",") }, keyParams(1f))
        bottom.addView(key("Português (BR)", KEY_BG, 14f) { space() }, keyParams(4.2f))
        bottom.addView(key(".", FN_BG, 22f) { punct(".") }, keyParams(1f))
        bottom.addView(key("↵", FN_BG, 24f) { enter() }, keyParams(1.45f))
        col.addView(bottom)
        return col
    }

    private fun keyParams(w: Float, heightDp: Int = 52) =
        LinearLayout.LayoutParams(0, dp(heightDp), w).margins()

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
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(PRESSED_BG))
            addState(intArrayOf(), rounded(color))
        }
        isClickable = true
        if (onClick != null) setOnClickListener { tap(it); onClick(it) }
    }

    private fun rounded(color: Int) = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color) }

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
        setMargins(dp(3), dp(4), dp(3), dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS = "trechos"
        const val KEY_MODE = "modo"
        const val KEY_RESET = "reset"
        const val HISTORY = "historico.tsv"
        private const val USER_WEIGHT = 3f

        /** Ordem de preenchimento: o mais provável no centro, depois as bordas, depois os cantos. */
        private const val LETTERS = 7

        private val MINI_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnmç✕", "áâãàéêíóôõú")

        private val GRID_ORDER = intArrayOf(4, 1, 7, 3, 5, 0, 2, 6, 8)

        private val BG = Color.parseColor("#121212")
        private val KEY_BG = Color.parseColor("#2B2B2D")
        private val FN_BG = Color.parseColor("#2B2B2D")
        private val CELL_BG = Color.parseColor("#2E4A6E")
        private val BAR_BG = Color.parseColor("#121212")
        private val FG = Color.parseColor("#F1F2F4")
        private val PRESSED_BG = Color.parseColor("#4A4A4E")
        private val ACCENT = Color.parseColor("#6FA8FF")
    }
}
