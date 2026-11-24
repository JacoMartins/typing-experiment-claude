package dev.trechos

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.trechos.predict.Predictor
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Teclado com dois modos:
 *  - QWERTY no padrão Samsung (símbolo no canto, popup de acentos, prévia da tecla);
 *  - grade 3x3 de trechos previstos (tokens).
 * Barra de cima: área de transferência, palavras sugeridas (ou o chip "Colar") e menu ⋯.
 * O modo escolhido fica salvo e volta igual na próxima vez que o teclado abrir.
 */
class TrechosKeyboardService : InputMethodService() {

    private enum class Mode { QWERTY, TRECHOS }
    private enum class Layer { LETTERS, SYM1, SYM2 }
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
    private val pieceStack = ArrayDeque<String>()
    private var lastAutoSpace = false
    private var learningEnabled = true

    // área de transferência
    private lateinit var clipboard: ClipboardManager
    private lateinit var clipStore: ClipStore
    private var lastClipText: String? = null
    private var lastClipAt = 0L
    private var lastClipStamp = 0L
    private var chipUsed = true
    private var clipOpen = false
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    // views
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var overlay: FrameLayout
    private lateinit var barNormal: LinearLayout
    private lateinit var barClip: LinearLayout
    private lateinit var suggestionsRow: LinearLayout
    private lateinit var chip: TextView
    private lateinit var body: FrameLayout
    private val wordViews = ArrayList<KeyView>()
    private val cellViews = ArrayList<KeyView>()
    private val letterViews = ArrayList<KeyView>()
    private val overflowViews = ArrayList<KeyView>()
    private var lettersScroll: HorizontalScrollView? = null
    private var pageButton: KeyView? = null

    // popups (prévia da tecla, acentos, menu)
    private var preview: TextView? = null
    private var accentPanel: View? = null
    private var accentCells: List<TextView> = emptyList()
    private var accentCenters: List<Pair<Float, Float>> = emptyList()
    private var accentOptions: List<String> = emptyList()
    private var accentSel = 0
    private var menuViews: List<View> = emptyList()

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = if (prefs.getString(KEY_MODE, Mode.QWERTY.name) == Mode.TRECHOS.name) Mode.TRECHOS else Mode.QWERTY
        clipStore = ClipStore(File(filesDir, ClipStore.FILE)).also { it.load() }
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)
        loadModel()
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
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
        root = FrameLayout(this)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        val barFrame = FrameLayout(this).apply { setBackgroundColor(BAR_BG) }
        barNormal = buildBar()
        barClip = buildClipBar()
        barFrame.addView(barNormal, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(BAR_H)))
        barFrame.addView(barClip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(BAR_H)))
        barClip.visibility = View.GONE
        column.addView(barFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(BAR_H)).apply {
            setMargins(-dp(4), 0, -dp(4), dp(4))
        })
        body = FrameLayout(this)
        column.addView(body)
        overlay = FrameLayout(this)
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        clipOpen = false
        buildBody()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (prefs.getLong(KEY_RESET, 0L) != loadedVersion) loadModel()
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        learningEnabled = prefs.getBoolean(KEY_LEARN, true) &&
            variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            cls != InputType.TYPE_CLASS_NUMBER
        phone = cls == InputType.TYPE_CLASS_PHONE
        numeric = phone || cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME
        layer = Layer.LETTERS
        shift = Shift.OFF
        pieceStack.clear()
        lastAutoSpace = false
        checkRecentClip()
        if (clipOpen) closeClipboard() else { hideOverlay(); buildBody() }
        refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        hideOverlay()
        super.onFinishInputView(finishingInput)
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
        updateBar()
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
            tv.label = list.getOrNull(i) ?: ""
            tv.isEnabled = clickable && i < list.size
        }
    }

    private fun showPieces(list: List<String>) {
        if (mode != Mode.TRECHOS) return
        val before = textBefore()
        val pageItems = list.drop(page * 9).take(9)
        cellViews.forEach { it.label = ""; it.isEnabled = false; it.tag = null }
        pageItems.forEachIndexed { rank, s ->
            val cell = cellViews.getOrNull(GRID_ORDER[rank]) ?: return@forEachIndexed
            val shown = caseLike(before, s, wholeWord = false)
            // o espaço no fim do token aparece como ␣
            cell.label = shown.replace(' ', '␣')
            cell.tag = shown
            cell.isEnabled = true
        }
        val rest = ALL_LETTERS.filter { it.toString() !in letters }
        overflowViews.forEachIndexed { i, tv ->
            val l = rest.getOrNull(i)?.toString()
            val shown = l?.let { caseLike(before, it, wholeWord = false) }
            tv.label = shown ?: ""
            tv.tag = shown
            tv.visibility = if (shown == null) View.GONE else View.VISIBLE
        }
        lettersScroll?.scrollTo(0, 0)
        letterViews.forEachIndexed { i, tv ->
            val l = letters.getOrNull(i)
            val shown = l?.let { caseLike(before, it, wholeWord = false) }
            tv.label = shown ?: ""
            tv.tag = shown
            tv.isEnabled = shown != null
        }
        val pages = maxOf(1, (list.size + 8) / 9)
        pageButton?.label = if (pages > 1) "⟳ ${page + 1}/$pages" else "⟳"
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
        if (text.isNotEmpty() && !chipUsed) { chipUsed = true; updateBar() }
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

    private fun pickPiece(cell: View) {
        val s = cell.tag as? String ?: return
        val before = textBefore()
        pieceStack.addLast(s)
        commit(s)
        lastAutoSpace = s.endsWith(" ")
        // se o token tem espaço, uma palavra acabou: aprende com ela
        val sp = s.lastIndexOf(' ')
        if (sp >= 0) learnFrom(before + s.substring(0, sp + 1))
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
        hideOverlay()
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

    // ---------------------------------------------------------------- barra de cima

    private fun buildBar(): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // botão que abre a área de transferência
        bar.addView(kv(bg = 0, iconRes = R.drawable.ic_clipboard) { openClipboard() },
            LinearLayout.LayoutParams(dp(52), dp(BAR_H - 6)).apply { setMargins(dp(4), dp(3), 0, dp(3)) })

        val center = FrameLayout(this)
        suggestionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wordViews.clear()
        repeat(3) { i ->
            val w = kv(bg = 0, sp = 18f) { pickWord(i) }
            wordViews.add(w)
            suggestionsRow.addView(w, LinearLayout.LayoutParams(0, dp(BAR_H - 8), 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        }
        center.addView(suggestionsRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // chip "Colar" para o que acabou de ser copiado
        chip = TextView(this).apply {
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(16), 0)
            compoundDrawablePadding = dp(8)
            background = rounded(CHIP_BG, dp(18).toFloat())
            val ic = getDrawable(R.drawable.ic_clipboard)!!.mutate().apply { setTint(ACCENT); setBounds(0, 0, dp(18), dp(18)) }
            setCompoundDrawables(ic, null, null, null)
            visibility = View.GONE
            setOnClickListener { v ->
                tap(v)
                lastClipText?.let { paste(it) }
            }
        }
        center.addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(38), Gravity.CENTER))
        bar.addView(center, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        bar.addView(kv(bg = 0, iconRes = R.drawable.ic_more) { showMenu() },
            LinearLayout.LayoutParams(dp(52), dp(BAR_H - 6)).apply { setMargins(0, dp(3), dp(4), dp(3)) })
        return bar
    }

    private fun buildClipBar(): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(kv(bg = 0, iconRes = R.drawable.ic_keyboard) { closeClipboard() },
            LinearLayout.LayoutParams(dp(52), dp(BAR_H - 6)).apply { setMargins(dp(4), dp(3), 0, dp(3)) })
        bar.addView(View(this).apply { setBackgroundColor(DIVIDER) },
            LinearLayout.LayoutParams(dp(1), dp(22)).apply { setMargins(dp(6), 0, dp(14), 0) })
        bar.addView(TextView(this).apply {
            text = "Área de transferência"
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // lixeira: apaga tudo o que não está fixado
        bar.addView(kv(bg = 0, iconRes = R.drawable.ic_delete) {
            clipStore.clearUnpinned()
            showClipPanel()
        }, LinearLayout.LayoutParams(dp(52), dp(BAR_H - 6)).apply { setMargins(0, dp(3), dp(4), dp(3)) })
        return bar
    }

    /** Mostra o chip "Colar" (se houver algo recém-copiado) ou as palavras sugeridas. */
    private fun updateBar() {
        if (!::chip.isInitialized) return
        val text = lastClipText
        val fresh = text != null && !chipUsed && System.currentTimeMillis() - lastClipAt < CHIP_MS && !numeric
        if (fresh) {
            chip.text = text!!.replace('\n', ' ')
            chip.visibility = View.VISIBLE
            suggestionsRow.visibility = View.INVISIBLE
        } else {
            chip.visibility = View.GONE
            suggestionsRow.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- área de transferência

    private fun onClipChanged() {
        val clip = try { clipboard.primaryClip } catch (_: Exception) { null } ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        // conteúdo marcado como sensível (ex.: senha copiada de um gerenciador) não vai para o histórico
        val sensitive = clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true
        if (!sensitive) clipStore.add(text)
        lastClipText = text
        lastClipAt = System.currentTimeMillis()
        chipUsed = false
        main.post { updateBar(); if (clipOpen) showClipPanel() }
        main.postDelayed({ updateBar() }, CHIP_MS + 500)
    }

    /** Ao abrir o teclado, oferece "Colar" se algo foi copiado há pouco (mesmo com o teclado fechado). */
    private fun checkRecentClip() {
        if (Build.VERSION.SDK_INT < 26) return
        val clip = try { clipboard.primaryClip } catch (_: Exception) { null } ?: return
        val stamp = clip.description?.timestamp ?: return
        if (stamp == lastClipStamp) return
        lastClipStamp = stamp
        if (System.currentTimeMillis() - stamp > CHIP_MS) return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val sensitive = clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true
        if (!sensitive) clipStore.add(text)
        lastClipText = text
        lastClipAt = stamp
        chipUsed = false
        main.postDelayed({ updateBar() }, CHIP_MS + 500)
    }

    private fun paste(text: String) {
        currentInputConnection?.commitText(text, 1)
        chipUsed = true
        pieceStack.clear()
        lastAutoSpace = false
        updateBar()
    }

    private fun openClipboard() {
        hideOverlay()
        clipOpen = true
        barNormal.visibility = View.GONE
        barClip.visibility = View.VISIBLE
        showClipPanel()
    }

    private fun closeClipboard() {
        clipOpen = false
        barClip.visibility = View.GONE
        barNormal.visibility = View.VISIBLE
        buildBody()
        refresh()
    }

    private fun showClipPanel() {
        if (!clipOpen) return
        clipStore.prune()
        // mantém a altura do teclado para nada pular na tela
        val h = if (body.height > 0) body.height else dp(300)
        body.removeAllViews()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
        val items = clipStore.items
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nada copiado ainda.\nO que você copiar aparece aqui por 1 hora."
                setTextColor(HINT_FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h - dp(8)))
        } else {
            for (rowItems in items.chunked(2)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (item in rowItems) row.addView(clipCard(item), LinearLayout.LayoutParams(0, dp(92), 1f).margins())
                if (rowItems.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(92), 1f).margins())
                list.addView(row)
            }
            list.addView(TextView(this).apply {
                text = "Toque para colar · segure para fixar"
                setTextColor(HINT_FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        body.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h))
    }

    private fun clipCard(item: ClipStore.Item): TextView = TextView(this).apply {
        text = item.text
        setTextColor(FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), dp(10), dp(10), dp(10))
        background = pressable(KEY_BG)
        if (item.pinned) {
            val pin = getDrawable(R.drawable.ic_pin)!!.mutate().apply { setTint(ACCENT); setBounds(0, 0, dp(16), dp(16)) }
            setCompoundDrawables(null, null, pin, null)
            compoundDrawablePadding = dp(6)
        }
        setOnClickListener { v ->
            tap(v)
            paste(item.text)
            closeClipboard()
        }
        setOnLongClickListener { v ->
            tap(v)
            clipStore.togglePin(item)
            showClipPanel()
            true
        }
    }

    // ---------------------------------------------------------------- menu ⋯

    private fun showMenu() {
        hideOverlay()
        val scrim = View(this).apply { isClickable = true; setOnClickListener { hideOverlay() } }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(MENU_BG, dp(16).toFloat())
            elevation = dp(6).toFloat()
            setPadding(0, dp(6), 0, dp(6))
        }
        fun item(label: String, iconRes: Int, action: () -> Unit) {
            panel.addView(TextView(this).apply {
                text = label
                setTextColor(FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                compoundDrawablePadding = dp(14)
                val ic = getDrawable(iconRes)!!.mutate().apply { setTint(FG); setBounds(0, 0, dp(22), dp(22)) }
                setCompoundDrawables(ic, null, null, null)
                background = pressable(0, 0f)
                setOnClickListener { v -> tap(v); hideOverlay(); action() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
        }
        item("Área de transferência", R.drawable.ic_clipboard) { openClipboard() }
        if (mode == Mode.TRECHOS) item("Teclado QWERTY", R.drawable.ic_keyboard) { setMode(Mode.QWERTY) }
        else item("Grade de trechos", R.drawable.ic_grid) { setMode(Mode.TRECHOS) }
        item("Configurações", R.drawable.ic_settings) {
            startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.addView(panel, FrameLayout.LayoutParams(dp(250), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(BAR_H + 2)
            rightMargin = dp(8)
        })
        menuViews = listOf(scrim, panel)
    }

    // ---------------------------------------------------------------- prévia e popup de acentos

    private fun posInOverlay(v: View): IntArray {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationOnScreen(a)
        overlay.getLocationOnScreen(b)
        return intArrayOf(a[0] - b[0], a[1] - b[1])
    }

    private fun showPreview(k: KeyView, text: String) {
        hidePreview()
        val pos = posInOverlay(k)
        val w = k.width + dp(14)
        val h = (k.height * 1.5f).toInt()
        val left = (pos[0] + k.width / 2 - w / 2).coerceIn(0, maxOf(0, overlay.width - w))
        val top = maxOf(0, pos[1] - h + (k.height * 0.15f).toInt())
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            background = rounded(PREVIEW_BG, dp(14).toFloat())
            elevation = dp(4).toFloat()
        }
        overlay.addView(tv, FrameLayout.LayoutParams(w, h).apply { leftMargin = left; topMargin = top })
        preview = tv
    }

    private fun hidePreview() {
        preview?.let { overlay.removeView(it) }
        preview = null
    }

    /** Popup como o do Samsung: a opção padrão (destacada) fica na fileira de baixo, à esquerda. */
    private fun showAccents(k: KeyView, options: List<String>) {
        hideAccents()
        if (options.isEmpty()) return
        val shown = options.map { if (shift != Shift.OFF) it.uppercase() else it }
        val perRow = if (shown.size <= 6) shown.size else (shown.size + 1) / 2
        val rows = if (shown.size <= 6) 1 else 2
        val cellW = k.width
        val cellH = (k.height * 0.95f).toInt()
        val pad = dp(5)
        val panelW = perRow * cellW + 2 * pad
        val panelH = rows * cellH + 2 * pad
        val pos = posInOverlay(k)
        val left = (pos[0] - pad).coerceIn(0, maxOf(0, overlay.width - panelW))
        val top = maxOf(0, pos[1] - panelH - dp(2))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(MENU_BG, dp(14).toFloat())
            elevation = dp(6).toFloat()
            setPadding(pad, pad, pad, pad)
        }
        val cells = ArrayList<TextView>()
        val centers = ArrayList<Pair<Float, Float>>()
        val rowLists = if (rows == 1) listOf(shown) else listOf(shown.drop(perRow), shown.take(perRow))
        val cellOf = HashMap<Int, TextView>()
        for ((r, list) in rowLists.withIndex()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((c, opt) in list.withIndex()) {
                val tv = TextView(this).apply {
                    text = opt
                    setTextColor(FG)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    gravity = Gravity.CENTER
                }
                row.addView(tv, LinearLayout.LayoutParams(cellW, cellH))
                // índice da opção: a fileira de baixo guarda as primeiras
                val index = if (rows == 1 || r == 1) c else perRow + c
                cellOf[index] = tv
            }
            panel.addView(row)
        }
        for (i in shown.indices) {
            val tv = cellOf[i] ?: continue
            cells.add(tv)
            val r = if (rows == 1 || i < perRow) rows - 1 else 0
            val c = if (rows == 1 || i < perRow) i else i - perRow
            centers.add((left + pad + c * cellW + cellW / 2f) to (top + pad + r * cellH + cellH / 2f))
        }
        overlay.addView(panel, FrameLayout.LayoutParams(panelW, panelH).apply { leftMargin = left; topMargin = top })
        accentPanel = panel
        accentCells = cells
        accentCenters = centers
        accentOptions = shown
        selectAccent(0)
    }

    private fun selectAccent(i: Int) {
        accentSel = i
        accentCells.forEachIndexed { j, tv ->
            tv.background = if (j == i) rounded(ACCENT_BG, dp(10).toFloat()) else null
        }
    }

    private fun moveAccent(rawX: Float, rawY: Float) {
        if (accentCenters.isEmpty()) return
        val b = IntArray(2)
        overlay.getLocationOnScreen(b)
        val x = rawX - b[0]
        val y = rawY - b[1]
        var best = 0
        var bestD = Float.MAX_VALUE
        accentCenters.forEachIndexed { i, (cx, cy) ->
            val d = (x - cx) * (x - cx) + 0.5f * (y - cy) * (y - cy)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best != accentSel) {
            selectAccent(best)
            accentCells.getOrNull(best)?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun hideAccents() {
        accentPanel?.let { overlay.removeView(it) }
        accentPanel = null
        accentCells = emptyList()
        accentCenters = emptyList()
        accentOptions = emptyList()
    }

    private fun hideOverlay() {
        if (!::overlay.isInitialized) return
        hidePreview()
        hideAccents()
        menuViews.forEach { overlay.removeView(it) }
        menuViews = emptyList()
    }

    // ---------------------------------------------------------------- layout

    private fun buildBody() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        overflowViews.clear()
        lettersScroll = null
        cellViews.clear()
        letterViews.clear()
        pageButton = null
        barNormal.visibility = if (numeric || clipOpen) View.GONE else View.VISIBLE
        body.addView(
            when {
                numeric -> buildNumpad()      // campo de número: teclado numérico, sem mudar o modo salvo
                mode == Mode.TRECHOS -> buildGrid()
                else -> buildQwerty()
            }
        )
        showPieces(pieces)
        updateBar()
    }

    /** Teclado numérico grande, como o dos campos de número do sistema. */
    private fun buildNumpad(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val enterLabel = when (action) {
            EditorInfo.IME_ACTION_GO -> "Ir"
            EditorInfo.IME_ACTION_SEARCH -> "Buscar"
            EditorInfo.IME_ACTION_SEND -> "Enviar"
            EditorInfo.IME_ACTION_NEXT -> "Próx."
            EditorInfo.IME_ACTION_DONE -> "OK"
            else -> ""
        }
        val rows = listOf(
            listOf("1", "2", "3", "⌫"),
            listOf("4", "5", "6", "↵"),
            listOf("7", "8", "9", if (phone) "+" else "."),
            listOf(if (phone) "*" else "-", "0", if (phone) "#" else " ", if (phone) " " else ","),
        )
        for (labels in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (label in labels) {
                val v = when (label) {
                    "⌫" -> kv(bg = FN_BG, iconRes = R.drawable.ic_backspace).also { repeating(it) { backspace() } }
                    "↵" -> (if (enterLabel.isEmpty()) kv(bg = FN_BG, iconRes = R.drawable.ic_enter) { enter() }
                            else kv(enterLabel, FN_BG, 18f) { enter() }).also { it.fg = ACCENT }
                    " " -> kv(if (phone) "␣" else "", FN_BG, 20f) { commit(" ") }
                    else -> kv(label, if (label[0].isDigit()) KEY_BG else FN_BG, 26f) { commit(label) }
                }
                row.addView(v, LinearLayout.LayoutParams(0, dp(58), 1f).margins())
            }
            col.addView(row)
        }
        return col
    }

    private fun buildGrid(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val cell = kv(bg = CELL_BG, sp = 22f, bold = true) { v -> pickPiece(v) }
                cell.pressedBg = CELL_PRESSED
                cellViews.add(cell)
                row.addView(cell, LinearLayout.LayoutParams(0, dp(60), 1f).margins())
            }
            val side = when (r) {
                0 -> kv(bg = FN_BG, iconRes = R.drawable.ic_backspace).also { repeating(it) { backspace() } }
                1 -> kv("⟳", FN_BG, 20f) {
                    val pages = maxOf(1, (pieces.size + 8) / 9)
                    page = (page + 1) % pages
                    showPieces(pieces)
                }.also { pageButton = it }
                else -> kv(bg = FN_BG, iconRes = R.drawable.ic_enter) { enter() }
            }
            row.addView(side, LinearLayout.LayoutParams(0, dp(60), 0.8f).margins())
            col.addView(row)
        }
        // barra de letras: as sugeridas primeiro; deslizando para o lado vem o resto do alfabeto
        val lettersRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dotsW = dp(58)
        val cellW = (resources.displayMetrics.widthPixels - dp(8) - dotsW) / LETTERS
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stripKey(): KeyView {
            val k = kv(bg = KEY_BG, sp = 19f) { v -> (v.tag as? String)?.let { typeLetter(it) } }
            strip.addView(k, LinearLayout.LayoutParams(cellW - dp(6), dp(44)).margins())
            return k
        }
        repeat(LETTERS) { letterViews.add(stripKey()) }
        // divisória discreta entre as sugeridas e o resto
        strip.addView(View(this).apply { setBackgroundColor(DIVIDER) },
            LinearLayout.LayoutParams(dp(2), dp(28)).apply { gravity = Gravity.CENTER_VERTICAL; setMargins(dp(4), 0, dp(4), 0) })
        repeat(ALL_LETTERS.length) { overflowViews.add(stripKey()) }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        lettersScroll = scroll
        lettersRow.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // ⋯ pula para o resto das letras (ou volta para as sugeridas)
        lettersRow.addView(kv(bg = FN_BG, iconRes = R.drawable.ic_more) {
            val target = overflowViews.firstOrNull()?.left?.minus(dp(6)) ?: 0
            scroll.smoothScrollTo(if (scroll.scrollX < target / 2) target else 0, 0)
        }, LinearLayout.LayoutParams(dotsW - dp(6), dp(44)).margins())
        col.addView(lettersRow)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(kv("ABC", FN_BG, 17f) { setMode(Mode.QWERTY) }, keyParams(1.25f))
        bottom.addView(kv(",", FN_BG, 22f) { punct(",") }, keyParams(0.9f))
        bottom.addView(spaceKey(), keyParams(3.2f))
        bottom.addView(kv(".", FN_BG, 22f) { punct(".") }, keyParams(0.9f))
        bottom.addView(kv("?", FN_BG, 22f) { punct("?") }, keyParams(0.9f))
        col.addView(bottom)
        return col
    }

    private fun buildQwerty(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // fileira de números, como no Samsung (inclusive na tela de símbolos)
        val nums = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (d in "1234567890") nums.addView(charKey(d.toString(), 22f, null, emptyList()), keyParams(1f, NUM_H))
        col.addView(nums)

        when (layer) {
            Layer.LETTERS -> {
                val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
                for ((idx, chars) in rows.withIndex()) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    if (idx == 2) row.addView(shiftKey(), keyParams(1.45f))
                    if (idx == 1) row.addView(View(this), keyParams(0.5f))   // fileira do meio recuada
                    for (ch in chars) {
                        val base = ch.toString()
                        val shown = if (shift != Shift.OFF) base.uppercase() else base
                        row.addView(charKey(base, 24f, HINTS[ch], accentOptions(ch), shown), keyParams(1f))
                    }
                    if (idx == 1) row.addView(View(this), keyParams(0.5f))
                    if (idx == 2) row.addView(backspaceKey(), keyParams(1.45f))
                    col.addView(row)
                }
            }
            Layer.SYM1, Layer.SYM2 -> {
                val rows = if (layer == Layer.SYM1) SYM1_ROWS else SYM2_ROWS
                for ((idx, chars) in rows.withIndex()) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    if (idx == 2) row.addView(kv(if (layer == Layer.SYM1) "1/2" else "2/2", FN_BG, 17f) {
                        layer = if (layer == Layer.SYM1) Layer.SYM2 else Layer.SYM1; buildBody()
                    }, keyParams(1.45f))
                    for (ch in chars) row.addView(charKey(ch.toString(), 22f, null, emptyList()), keyParams(1f))
                    if (idx == 2) row.addView(backspaceKey(), keyParams(1.45f))
                    col.addView(row)
                }
            }
        }

        // última fileira no estilo Samsung: !#1  ⊞  ,  espaço  .  ↵
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(kv(if (layer == Layer.LETTERS) "!#1" else "ABC", FN_BG, 17f) {
            layer = if (layer == Layer.LETTERS) Layer.SYM1 else Layer.LETTERS; buildBody()
        }, keyParams(1.3f))
        // no lugar do globo do Samsung: troca para a grade de trechos
        bottom.addView(kv(bg = FN_BG, iconRes = R.drawable.ic_grid) { setMode(Mode.TRECHOS) }.also { it.fg = ACCENT }, keyParams(1f))
        bottom.addView(kv(",", KEY_BG, 22f) { punct(",") }, keyParams(1f))
        bottom.addView(spaceKey(), keyParams(4f))
        bottom.addView(kv(".", KEY_BG, 22f) { punct(".") }, keyParams(1f))
        bottom.addView(kv(bg = FN_BG, iconRes = R.drawable.ic_enter) { enter() }, keyParams(1.45f))
        col.addView(bottom)
        return col
    }

    private fun shiftKey(): KeyView {
        val icon = when (shift) {
            Shift.OFF -> R.drawable.ic_shift
            Shift.ONCE -> R.drawable.ic_shift_on
            Shift.LOCK -> R.drawable.ic_shift_lock
        }
        return kv(bg = FN_BG, iconRes = icon) {
            shift = when (shift) { Shift.OFF -> Shift.ONCE; Shift.ONCE -> Shift.LOCK; Shift.LOCK -> Shift.OFF }
            buildBody(); refresh()
        }.also { if (shift != Shift.OFF) it.fg = ACCENT }
    }

    private fun backspaceKey(): KeyView =
        kv(bg = FN_BG, iconRes = R.drawable.ic_backspace).also { repeating(it) { backspace() } }

    // ---------------------------------------------------------------- teclas e gestos

    private fun kv(
        label: String = "",
        bg: Int = KEY_BG,
        sp: Float = 22f,
        iconRes: Int = 0,
        hint: String? = null,
        bold: Boolean = false,
        onClick: ((View) -> Unit)? = null,
    ): KeyView = KeyView(this).apply {
        this.label = label
        this.bg = bg
        pressedBg = if (bg == 0) PRESSED_FLAT else PRESSED_BG
        fg = FG
        hintFg = HINT_FG
        textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        radiusPx = dp(9).toFloat()
        this.bold = bold
        this.hint = hint
        if (iconRes != 0) {
            icon = getDrawable(iconRes)
            iconSizePx = dp(24)
        }
        if (onClick != null) setOnClickListener { tap(it); onClick(it) }
    }

    /**
     * Tecla de caractere: mostra a prévia ao tocar e digita ao soltar.
     * Segurando, abre o popup (símbolo + acentos); desliza para escolher e solta para digitar.
     */
    private fun charKey(base: String, sp: Float, hint: String?, options: List<String>, shown: String = base): KeyView {
        val k = kv(shown, KEY_BG, sp, hint = hint)
        val longPress = Runnable {
            hidePreview()
            k.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showAccents(k, options)
        }
        k.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    tap(v)
                    showPreview(k, shown)
                    if (options.isNotEmpty()) main.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> if (accentPanel != null) moveAccent(ev.rawX, ev.rawY)
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    main.removeCallbacks(longPress)
                    hidePreview()
                    if (accentPanel != null) {
                        val choice = accentOptions.getOrNull(accentSel)
                        hideAccents()
                        if (choice != null) typeRaw(choice)
                    } else {
                        typeChar(base)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(longPress)
                    hidePreview()
                    hideAccents()
                }
            }
            true
        }
        return k
    }

    /** Digita exatamente o texto (já com maiúscula aplicada, vindo do popup). */
    private fun typeRaw(s: String) {
        lastAutoSpace = false
        pieceStack.clear()
        commit(s)
    }

    /** Espaço: toque digita; arrastar para os lados move o cursor (como no Samsung). */
    private fun spaceKey(): KeyView {
        val k = kv("Português (BR)", KEY_BG, 14f)
        var startX = 0f
        var dragging = false
        var steps = 0
        k.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true; tap(v)
                    startX = ev.rawX; dragging = false; steps = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    if (!dragging && abs(dx) > dp(14)) dragging = true
                    if (dragging) {
                        val target = (dx / dp(11)).toInt()
                        while (steps < target) { moveCursor(1); steps++ }
                        while (steps > target) { moveCursor(-1); steps-- }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!dragging) space()
                }
                MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            true
        }
        return k
    }

    private fun moveCursor(d: Int) {
        sendDownUpKeyEvents(if (d > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        pieceStack.clear()
        lastAutoSpace = false
    }

    private fun accentOptions(ch: Char): List<String> {
        val hint = HINTS[ch]
        val acc = ACCENTS[ch].orEmpty().map { it.toString() }
        // no c, o ç vem primeiro (é o que se quer quase sempre em português)
        return if (ch == 'c') acc + listOfNotNull(hint) else listOfNotNull(hint) + acc
    }

    /** Segurar repete a ação (usado no apagar). */
    private fun repeating(v: View, action: () -> Unit) {
        val r = object : Runnable {
            override fun run() { action(); main.postDelayed(this, 55) }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { tap(view); view.isPressed = true; action(); main.postDelayed(r, 420) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; main.removeCallbacks(r) }
            }
            true
        }
    }

    private fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private fun rounded(color: Int, radius: Float = dp(9).toFloat()) =
        GradientDrawable().apply { cornerRadius = radius; setColor(color) }

    private fun pressable(color: Int, radius: Float = dp(12).toFloat()) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(PRESSED_BG, radius))
        addState(intArrayOf(), rounded(color, radius))
    }

    private fun keyParams(w: Float, heightDp: Int = KEY_H) =
        LinearLayout.LayoutParams(0, dp(heightDp), w).margins()

    private fun LinearLayout.LayoutParams.margins(): LinearLayout.LayoutParams = apply {
        setMargins(dp(3), dp(4), dp(3), dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS = "trechos"
        const val KEY_MODE = "modo"
        const val KEY_RESET = "reset"
        const val KEY_LEARN = "aprender"
        const val HISTORY = "historico.tsv"
        private const val USER_WEIGHT = 3f
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        private const val BAR_H = 48
        private const val KEY_H = 52
        private const val NUM_H = 46
        private const val LONG_PRESS_MS = 350L
        private const val CHIP_MS = 60_000L

        /** Quantas letras sugeridas aparecem antes do resto do alfabeto. */
        private const val LETTERS = 7

        /** Letras do "resto" da faixa, depois das sugeridas. */
        private const val ALL_LETTERS = "abcdefghijklmnopqrstuvwxyzçáâãàéêíóôõú"

        /** Ordem de preenchimento: o mais provável no centro, depois as bordas, depois os cantos. */
        private val GRID_ORDER = intArrayOf(4, 1, 7, 3, 5, 0, 2, 6, 8)

        /** Símbolo do canto de cada letra, igual ao Samsung. */
        private val HINTS: Map<Char, String> = run {
            val m = HashMap<Char, String>()
            "qwertyuiop".zip("+×÷=/_<>[]").forEach { (k, v) -> m[k] = v.toString() }
            "asdfghjkl".zip("!@#$%&*()").forEach { (k, v) -> m[k] = v.toString() }
            "zxcvbnm".zip("-'\":;,?").forEach { (k, v) -> m[k] = v.toString() }
            m
        }

        /** Acentos do popup de cada letra. */
        private val ACCENTS = mapOf(
            'a' to "áàâãäå", 'e' to "éêèë", 'i' to "íîìï", 'o' to "óôõòöø", 'u' to "úüùû",
            'c' to "çćč", 'n' to "ñń", 's' to "ßśš", 'y' to "ýÿ", 'z' to "žźż", 'g' to "ğ", 'l' to "ł",
        )

        private val SYM1_ROWS = listOf("+×÷=/_<>[]", "!@#$%^&*()", "-'\":;,?")
        private val SYM2_ROWS = listOf("`~\\|{}€£¥¢", "°•§¶©®™✓…·", "«»¡¿¬‰¤")

        // paleta (inspirada no Samsung escuro / Material 3)
        private val BG = Color.parseColor("#0E0E0F")
        private val BAR_BG = Color.parseColor("#161617")
        private val KEY_BG = Color.parseColor("#3A3A3C")
        private val FN_BG = Color.parseColor("#232325")
        private val PRESSED_BG = Color.parseColor("#55555A")
        private val PRESSED_FLAT = Color.parseColor("#2C2C2F")
        private val CELL_BG = Color.parseColor("#2B4166")
        private val CELL_PRESSED = Color.parseColor("#3D5A8A")
        private val PREVIEW_BG = Color.parseColor("#4A4A4E")
        private val MENU_BG = Color.parseColor("#2A2A2D")
        private val CHIP_BG = Color.parseColor("#26324A")
        private val ACCENT_BG = Color.parseColor("#4D86F7")
        private val ACCENT = Color.parseColor("#7FA8FF")
        private val FG = Color.parseColor("#F2F2F4")
        private val HINT_FG = Color.parseColor("#B8B8BE")
        private val DIVIDER = Color.parseColor("#3A3A3C")
    }
}
