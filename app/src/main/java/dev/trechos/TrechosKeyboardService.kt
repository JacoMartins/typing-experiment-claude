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
import dev.trechos.predict.MemoryUserStore
import dev.trechos.predict.Predictor
import dev.trechos.predict.UserStore
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

    /** Um modelo por idioma, carregado só quando é usado. A memória pessoal é compartilhada. */
    private val predictors = java.util.concurrent.ConcurrentHashMap<String, Predictor>()
    @Volatile private var userStore: UserStore? = null

    /** Idioma ativo (o espaço mostra o nome; segurar o espaço troca). */
    @Volatile private var language = "pt"
    private var languages = listOf("pt", "en")

    private val predictor: Predictor? get() = predictors[language]
    private var sqliteStore: SqliteUserStore? = null
    private var loadedVersion = -1L

    private lateinit var prefs: android.content.SharedPreferences

    // configurações (vêm da tela de configurações; ver Prefs)
    private var heightScale = 1f
    private var layoutMode = Prefs.LAYOUT_NORMAL
    private var floatWidth = 0.75f
    private var haptic = true
    private var previewOn = true
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        main.post { onPrefChanged(key) }
    }
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
    private lateinit var root: KeyboardRoot
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
    private var lettersScroll: HorizontalScrollView? = null
    private var pageButton: KeyView? = null
    private var shiftView: KeyView? = null
    private val letterKeys = ArrayList<Pair<KeyView, String>>()
    private val spaceViews = ArrayList<KeyView>()

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
        CrashLog.install(this)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = if (prefs.getString(KEY_MODE, Mode.QWERTY.name) == Mode.TRECHOS.name) Mode.TRECHOS else Mode.QWERTY
        clipStore = ClipStore(File(filesDir, ClipStore.FILE)).also { it.load() }
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)
        readSettings()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        loadModel()
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        worker.shutdownNow()
        sqliteStore?.close()
        super.onDestroy()
    }

    /** Recomeça do zero (na criação e quando o aprendizado é apagado): reabre a memória e o idioma ativo. */
    private fun loadModel() {
        loadedVersion = prefs.getLong(KEY_RESET, 0L)
        predictors.clear()
        bg {
            userStore = openUserStore()
        }
        ensureLanguage(language)
    }

    /** Carrega o modelo do idioma [lang] em segundo plano, se ainda não estiver carregado. */
    private fun ensureLanguage(lang: String) {
        if (predictors.containsKey(lang)) { main.post { refresh() }; return }
        bg {
            if (predictors.containsKey(lang)) return@bg
            val p = Predictor(lang)
            val file = LANGUAGE_FILES[lang] ?: return@bg
            assets.open(file).bufferedReader().useLines { p.loadWordList(it, 1f) }
            if (lang == "pt") p.learnText(assets.open("frases.txt").bufferedReader().readText(), 1f)
            val store = userStore ?: openUserStore().also { userStore = it }
            p.userStore = store
            // vocabulário pessoal deste idioma (e, no português, o histórico de antes da separação)
            for ((w, c) in store.followers(Predictor.vocabKey(lang), 5000)) p.addUserWord(w, c, USER_WEIGHT)
            if (lang == "pt") for ((w, c) in store.followers("", 5000)) p.addUserWord(w, c, USER_WEIGHT)
            p.warmUp()
            predictors[lang] = p
            main.post { refresh() }
        }
    }

    /** Troca o idioma ativo (segurar o espaço), mostrando o novo nome na barra de espaço. */
    private fun switchLanguage() {
        if (languages.size < 2) return
        val next = languages[(languages.indexOf(language) + 1).mod(languages.size)]
        prefs.edit().putString(Prefs.LANGUAGE, next).apply()   // o listener de preferências aplica (setLanguage)
    }

    private fun setLanguage(lang: String) {
        language = lang
        spaceViews.forEach { k ->
            k.label = LANGUAGE_NAMES[lang] ?: lang
            pop(k, k.width / 2f, k.height / 2f, from = 0.92f, duration = 160)
        }
        ensureLanguage(lang)
    }

    private fun historyFile() = File(filesDir, HISTORY)

    /**
     * Roda em segundo plano. Se algo falhar, o erro é registrado (CrashLog) e o teclado
     * segue funcionando, em vez de o Android fechar o app inteiro.
     */
    private fun bg(task: () -> Unit) {
        try {
            worker.execute {
                try { task() } catch (t: Throwable) { CrashLog.record(this, t) }
            }
        } catch (_: Exception) {
            // executor já encerrado (serviço sendo destruído)
        }
    }

    /**
     * Abre a memória pessoal (SQLite), faz a faxina, migra o histórico antigo
     * (arquivo de pares de palavras) e coloca as palavras aprendidas no vocabulário.
     * Se o banco falhar por algum motivo, o teclado segue com uma memória temporária.
     */
    private fun openUserStore(): UserStore = try {
        sqliteStore?.close()
        val store = SqliteUserStore(this)
        sqliteStore = store
        store.prune()
        historyFile().takeIf { it.exists() }?.let { f ->
            f.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val prev = line.substring(0, tab)
                    store.learn(if (prev == Predictor.START) emptyList() else listOf(prev), line.substring(tab + 1))
                }
            }
            f.delete()
        }
        store
    } catch (_: Exception) {
        MemoryUserStore()
    }

    private fun readSettings() {
        heightScale = prefs.getFloat(Prefs.HEIGHT, 1f).coerceIn(0.8f, 1.3f)
        layoutMode = prefs.getString(Prefs.LAYOUT, Prefs.LAYOUT_NORMAL) ?: Prefs.LAYOUT_NORMAL
        floatWidth = prefs.getFloat(Prefs.FLOAT_WIDTH, 0.75f).coerceIn(0.45f, 1f)
        haptic = prefs.getBoolean(Prefs.HAPTIC, true)
        previewOn = prefs.getBoolean(Prefs.PREVIEW, true)
        languages = (prefs.getString(Prefs.LANGUAGES, "pt,en") ?: "pt,en")
            .split(',').filter { it in LANGUAGE_FILES }.ifEmpty { listOf("pt") }
        val saved = prefs.getString(Prefs.LANGUAGE, languages.first()) ?: languages.first()
        language = if (saved in languages) saved else languages.first()
    }

    /** A tela de configurações mudou algo: o teclado se ajusta na hora. */
    private fun onPrefChanged(key: String?) {
        when {
            key != null && key in Prefs.LAYOUT_KEYS -> {
                readSettings()
                if (::root.isInitialized) setInputView(onCreateInputView())
            }
            key == Prefs.HAPTIC -> haptic = prefs.getBoolean(Prefs.HAPTIC, true)
            key == Prefs.LANGUAGES || key == Prefs.LANGUAGE -> {
                readSettings()
                setLanguage(language)
            }
            key == Prefs.RESET -> loadModel()
            key == Prefs.CLIP_RESET -> {
                clipStore.clearAll()
                lastClipText = null
                updateBar()
                if (clipOpen) showClipPanel()
            }
        }
    }

    /** Largura útil do teclado em px (menor no modo de uma mão e no flutuante). */
    private fun boardWidth(): Int {
        val screen = resources.displayMetrics.widthPixels
        return when (layoutMode) {
            Prefs.LAYOUT_LEFT, Prefs.LAYOUT_RIGHT -> (screen * Prefs.ONE_HAND_WIDTH).toInt()
            Prefs.LAYOUT_FLOATING -> (screen * floatWidth).toInt()
            else -> screen
        }
    }

    override fun onCreateInputView(): View {
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
        preview = null

        val content: View = when (layoutMode) {
            Prefs.LAYOUT_LEFT, Prefs.LAYOUT_RIGHT -> oneHanded(column)
            Prefs.LAYOUT_FLOATING -> floatingCard(column)
            else -> column
        }
        // o tamanho do teclado vem só das teclas; popups ficam por cima sem empurrar nada
        // (fora do apply: lá dentro, "layoutMode" seria o getLayoutMode() da própria View)
        val isFloating = layoutMode == Prefs.LAYOUT_FLOATING
        val savedX = prefs.getFloat(Prefs.FLOAT_X, -1f)
        val savedY = prefs.getFloat(Prefs.FLOAT_Y, -1f)
        root = KeyboardRoot(this, content, overlay)
        root.floating = isFloating
        root.floatX = savedX
        root.floatY = savedY
        clipOpen = false
        buildBody()
        return root
    }

    /** Uma mão: o teclado mais estreito de um lado e, do outro, botões para trocar de lado ou expandir. */
    private fun oneHanded(board: View): View {
        val left = layoutMode == Prefs.LAYOUT_LEFT
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG) }
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        side.addView(kv(bg = FN_BG, iconRes = if (left) R.drawable.ic_arrow_line_right else R.drawable.ic_arrow_line_left) {
            prefs.edit().putString(Prefs.LAYOUT, if (left) Prefs.LAYOUT_RIGHT else Prefs.LAYOUT_LEFT).apply()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
        side.addView(kv(bg = FN_BG, iconRes = R.drawable.ic_arrows_out) {
            prefs.edit().putString(Prefs.LAYOUT, Prefs.LAYOUT_NORMAL).apply()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
        val boardLp = LinearLayout.LayoutParams(boardWidth(), LinearLayout.LayoutParams.WRAP_CONTENT)
        val sideLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        if (left) { row.addView(board, boardLp); row.addView(side, sideLp) }
        else { row.addView(side, sideLp); row.addView(board, boardLp) }
        return row
    }

    /**
     * Flutuante: um card solto com uma alça em cima.
     * Arrastar a alça move o card; o botão da direita muda a largura arrastando;
     * o da esquerda devolve o teclado para baixo (modo normal).
     */
    private fun floatingCard(board: View): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BG, dp(18).toFloat())
            clipToOutline = true
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(boardWidth(), FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        val handle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        handle.addView(kv(bg = 0, iconRes = R.drawable.ic_rows) {
            prefs.edit().putString(Prefs.LAYOUT, Prefs.LAYOUT_NORMAL).apply()
        }, LinearLayout.LayoutParams(dp(48), dp(30)))
        val grip = kv(bg = 0, iconRes = R.drawable.ic_dots_six)
        var lastX = 0f
        var lastY = 0f
        grip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = ev.rawX; lastY = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    root.moveBy(ev.rawX - lastX, ev.rawY - lastY)
                    lastX = ev.rawX; lastY = ev.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putFloat(Prefs.FLOAT_X, root.floatX).putFloat(Prefs.FLOAT_Y, root.floatY).apply()
                    root.requestLayout()   // recalcula a área de toque do card
                }
            }
            true
        }
        handle.addView(grip, LinearLayout.LayoutParams(0, dp(30), 1f))
        val resize = kv(bg = 0, iconRes = R.drawable.ic_arrows_out)
        var startX = 0f
        var startW = 0
        resize.setOnTouchListener { _, ev ->
            val screen = resources.displayMetrics.widthPixels
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = ev.rawX; startW = card.width }
                MotionEvent.ACTION_MOVE -> {
                    val w = (startW + (ev.rawX - startX)).toInt().coerceIn((screen * 0.45f).toInt(), screen)
                    card.layoutParams = card.layoutParams.apply { width = w }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putFloat(Prefs.FLOAT_WIDTH, card.width.toFloat() / screen).apply()
                }
            }
            true
        }
        handle.addView(resize, LinearLayout.LayoutParams(dp(48), dp(30)))
        card.addView(handle)
        card.addView(board)
        return card
    }

    /** No modo flutuante, só o card capta toques; o app embaixo continua usável e não é empurrado. */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        if (!::root.isInitialized || !root.floating || !root.isAttachedToWindow) return
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        val bottom = loc[1] + root.height
        outInsets.contentTopInsets = bottom
        outInsets.visibleTopInsets = bottom
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        val r = if (menuViews.isNotEmpty()) android.graphics.Rect(0, 0, root.width, root.height) else root.cardRect()
        r.offset(loc[0], loc[1])
        outInsets.touchableRegion.set(r)
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
        currentInputConnection?.getTextBeforeCursor(160, 0)?.toString() ?: ""

    /** Texto mais longo, só para a previsão: alimenta o cache da conversa. */
    private fun contextText(): String =
        currentInputConnection?.getTextBeforeCursor(2000, 0)?.toString() ?: ""

    private fun refresh() {
        if (!::root.isInitialized) return
        val p = predictor
        if (p == null) {
            showWords(listOf("carregando…"), clickable = false)
            showPieces(emptyList())
            return
        }
        val before = contextText()
        val gen = ++generation
        updateBar()
        if (numeric) { showWords(emptyList(), clickable = false); return }
        bg {
            val ws = p.completions(before, 3)
            val pr = if (mode == Mode.TRECHOS) p.predict(before, 18, ALL_LETTERS) else null
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
        lettersScroll?.scrollTo(0, 0)
        letterViews.forEachIndexed { i, tv ->
            val shown = letters.getOrNull(i)?.let { caseLike(before, it, wholeWord = false) }
            tv.label = shown ?: ""
            tv.tag = shown
            tv.visibility = if (shown == null) View.GONE else View.VISIBLE
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
            refreshShiftUi()   // só troca os rótulos: reconstruir aqui perderia toques de outros dedos
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
        learnChoice(before, s)
        pieceStack.addLast(s)
        commit(s)
        lastAutoSpace = s.endsWith(" ")
        // se o token tem espaço, uma palavra acabou: aprende com ela
        val sp = s.lastIndexOf(' ')
        if (sp >= 0) learnFrom(before + s.substring(0, sp + 1))
    }

    /** Letra da barra de sugestões da grade. */
    private fun typeLetter(l: String) {
        learnChoice(textBefore(), l)
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

    /**
     * A ação que o app pediu para o Enter (Enviar, Buscar, Ir, Próximo, Concluir),
     * ou null se o Enter deve pular linha. Campos de várias linhas comuns já vêm com
     * IME_FLAG_NO_ENTER_ACTION; quando o app NÃO põe essa marca (ex.: chat com Enviar),
     * o Enter envia mesmo em campo de várias linhas.
     */
    private fun enterAction(): Int? {
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        return if (noEnterAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) null else action
    }

    private fun enter() {
        pieceStack.clear()
        lastAutoSpace = false
        val action = enterAction()
        if (action != null) {
            learnFrom(textBefore() + "\n")   // a mensagem enviada também ensina a última palavra
            currentInputConnection?.performEditorAction(action)
        } else {
            newline()
        }
    }

    private fun newline() {
        pieceStack.clear()
        lastAutoSpace = false
        learnFrom(textBefore() + "\n")
        commit("\n")
    }

    /** Tecla Enter com o ícone da ação (avião = Enviar, lupa = Buscar...). Segurar sempre pula linha. */
    private fun enterKey(): KeyView {
        val action = enterAction()
        val icon = when (action) {
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_arrow_right
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_check
            else -> R.drawable.ic_enter
        }
        val k = kv(bg = if (action != null) ACCENT_BG else FN_BG, iconRes = icon) { enter() }
        if (action != null) k.pressedBg = ACCENT_PRESSED
        k.setOnLongClickListener { v -> tap(v); newline(); true }
        return k
    }

    private fun setMode(m: Mode) {
        mode = m
        prefs.edit().putString(KEY_MODE, m.name).apply()   // fica salvo para a próxima vez
        layer = Layer.LETTERS
        hideOverlay()
        buildBody()
        body.getChildAt(0)?.let { animateIn(it, dy = dp(14).toFloat(), duration = 180) }
        refresh()
    }

    /** Aprende qual token/letra você escolheu neste ponto da palavra (vale para a grade e a faixa). */
    private fun learnChoice(before: String, choice: String) {
        if (!learningEnabled) return
        val p = predictor ?: return
        bg { try { p.learnPiece(before, choice) } catch (_: Exception) {} }
    }

    /** Aprende a última palavra concluída (com a anterior como contexto). */
    private fun learnFrom(text: String) {
        if (!learningEnabled) return
        val p = predictor ?: return
        bg {
            try {
                p.learnCompleted(text, USER_WEIGHT)   // vai para o banco com as 3 palavras anteriores
                p.warmUp()
            } catch (_: Exception) {}
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

    /**
     * Texto do que foi copiado, ou "" se não for texto. Nunca lança erro: a área de
     * transferência pode estar vazia, ter uma imagem (ex.: um print) ou pertencer a
     * outro app que não deixa ler.
     */
    private fun clipText(clip: android.content.ClipData): String = try {
        if (clip.itemCount == 0) "" else {
            val item = clip.getItemAt(0)
            val direct = item?.text?.toString()
            val isText = clip.description?.let { it.hasMimeType("text/*") } == true
            (direct ?: if (isText) item?.coerceToText(this)?.toString() else null).orEmpty().trim()
        }
    } catch (_: Exception) {
        ""
    }

    private fun onClipChanged() {
        val clip = try { clipboard.primaryClip } catch (_: Exception) { null } ?: return
        val text = clipText(clip)
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
        val stamp = try { clip.description?.timestamp } catch (_: Exception) { null } ?: return
        if (stamp == lastClipStamp) return
        lastClipStamp = stamp
        if (System.currentTimeMillis() - stamp > CHIP_MS) return
        val text = clipText(clip)
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
        animateIn(barClip, dy = 0f, duration = 160)
        showClipPanel(animated = true)
    }

    private fun closeClipboard() {
        clipOpen = false
        barClip.visibility = View.GONE
        barNormal.visibility = View.VISIBLE
        animateIn(barNormal, dy = 0f, duration = 160)
        buildBody()
        body.getChildAt(0)?.let { animateIn(it, dy = dp(16).toFloat(), duration = 180) }
        refresh()
    }

    private fun showClipPanel(animated: Boolean = false) {
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
                for (item in rowItems) row.addView(clipCard(item), LinearLayout.LayoutParams(0, dp(92), 1f).cardMargins())
                if (rowItems.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(92), 1f).cardMargins())
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
        if (animated) {
            animateIn(scroll, dy = dp(24).toFloat(), duration = 220)
            // cartões aparecem em cascata (só os primeiros, os de baixo já estão fora da tela)
            for (i in 0 until minOf(list.childCount, 6)) {
                animateIn(list.getChildAt(i), dy = dp(10).toFloat(), duration = 200, delay = 40L * i)
            }
        }
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
        val scrim = View(this).apply {
            isClickable = true
            setBackgroundColor(Color.parseColor("#33000000"))
            alpha = 0f
            setOnClickListener { closeMenuAnimated() }
        }
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
                setOnClickListener { v -> tap(v); closeMenuAnimated(); action() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
        }
        item("Área de transferência", R.drawable.ic_clipboard) { openClipboard() }
        if (mode == Mode.TRECHOS) item("Teclado QWERTY", R.drawable.ic_keyboard) { setMode(Mode.QWERTY) }
        else item("Grade de trechos", R.drawable.ic_grid) { setMode(Mode.TRECHOS) }
        item("Configurações", R.drawable.ic_settings) {
            startActivity(Intent(this, dev.trechos.settings.SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = posInOverlay(barNormal)
        overlay.addView(panel, FrameLayout.LayoutParams(dp(250), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = bar[1] + barNormal.height + dp(2)
            rightMargin = maxOf(dp(8), overlay.width - (bar[0] + barNormal.width) + dp(8))
        })
        menuViews = listOf(scrim, panel)
        if (root.floating) root.requestLayout()   // menu aberto: a tela toda passa a captar o toque de fechar
        scrim.animate().alpha(1f).setDuration(160).start()
        panel.alpha = 0f
        panel.translationY = -dp(10).toFloat()
        panel.scaleY = 0.94f
        panel.pivotY = 0f
        panel.animate().alpha(1f).translationY(0f).scaleY(1f).setDuration(180).setInterpolator(decel).start()
    }

    // ---------------------------------------------------------------- prévia e popup de acentos

    private fun posInOverlay(v: View): IntArray {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationOnScreen(a)
        overlay.getLocationOnScreen(b)
        return intArrayOf(a[0] - b[0], a[1] - b[1])
    }

    /**
     * Balão de prévia: uma única View, criada uma vez e só reposicionada
     * (translation) a cada toque. Assim tocar uma tecla não refaz o layout do teclado.
     */
    private fun showPreview(k: KeyView, text: String) {
        if (!previewOn) return
        val pos = posInOverlay(k)
        val keyW = (k.width * (1f - k.extraLeft - k.extraRight)).toInt()
        val keyLeft = pos[0] + (k.width * k.extraLeft).toInt()
        val w = keyW + dp(8)
        val h = (k.height * 1.4f).toInt()
        val tv = preview ?: TextView(this).apply {
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            background = rounded(PREVIEW_BG, dp(14).toFloat())
            elevation = dp(4).toFloat()
        }.also {
            // fora do apply: lá dentro, "overlay" seria a camada interna da própria TextView
            overlay.addView(it, FrameLayout.LayoutParams(w, h))
            preview = it
        }
        val lp = tv.layoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; tv.layoutParams = lp }
        tv.text = text
        tv.translationX = (keyLeft + keyW / 2 - w / 2).coerceIn(0, maxOf(0, overlay.width - w)).toFloat()
        tv.translationY = maxOf(0, pos[1] - h + (k.height * 0.2f).toInt()).toFloat()
        tv.animate().cancel()
        tv.visibility = View.VISIBLE
        pop(tv, w / 2f, h.toFloat(), from = 0.8f, duration = 70)
    }

    private fun hidePreview() {
        preview?.visibility = View.INVISIBLE
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
        pop(panel, (pos[0] - left + k.width / 2).toFloat(), panelH.toFloat(), from = 0.9f, duration = 120)
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
            if (haptic) accentCells.getOrNull(best)?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun hideAccents() {
        accentPanel?.let { overlay.removeView(it) }
        accentPanel = null
        accentCells = emptyList()
        accentCenters = emptyList()
        accentOptions = emptyList()
    }

    /** Fecha o menu ⋯ com animação (o painel sobe e some) e só então o remove. */
    private fun closeMenuAnimated() {
        val views = menuViews
        if (views.isEmpty()) return
        menuViews = emptyList()
        if (root.floating) root.requestLayout()
        val (scrim, panel) = views
        scrim.animate().alpha(0f).setDuration(120).start()
        panel.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(120)
            .withEndAction { overlay.removeView(scrim); overlay.removeView(panel) }.start()
    }

    private fun hideOverlay() {
        if (!::overlay.isInitialized) return
        hidePreview()
        hideAccents()
        if (menuViews.isNotEmpty()) {
            menuViews.forEach { overlay.removeView(it) }
            menuViews = emptyList()
            if (root.floating) root.requestLayout()
        }
    }

    /** Troca a página da grade (+1 próxima, -1 anterior), com as células deslizando. */
    private fun changePage(dir: Int) {
        val pages = maxOf(1, (pieces.size + 8) / 9)
        if (pages < 2) return
        page = (page + dir + pages) % pages
        showPieces(pieces)
        val dx = dp(36).toFloat() * dir
        for (c in cellViews) {
            c.translationX = dx
            c.alpha = 0f
            c.animate().translationX(0f).alpha(1f).setDuration(170).setInterpolator(decel).start()
        }
    }

    // ---------------------------------------------------------------- animações

    private val decel = android.view.animation.DecelerateInterpolator(1.6f)

    /** Entra com fade, subindo [dy] px; [delay] permite efeito cascata. */
    private fun animateIn(v: View, dy: Float = dp(12).toFloat(), duration: Long = 200, delay: Long = 0) {
        v.alpha = 0f
        v.translationY = dy
        v.animate().alpha(1f).translationY(0f).setDuration(duration).setStartDelay(delay)
            .setInterpolator(decel).start()
    }

    /** "Pop": cresce a partir de [pivotX],[pivotY] (em px dentro da View). */
    private fun pop(v: View, pivotX: Float, pivotY: Float, from: Float = 0.85f, duration: Long = 110) {
        v.pivotX = pivotX
        v.pivotY = pivotY
        v.scaleX = from; v.scaleY = from; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(duration).setInterpolator(decel).start()
    }

    // ---------------------------------------------------------------- layout

    private fun buildBody() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        lettersScroll = null
        cellViews.clear()
        letterViews.clear()
        letterKeys.clear()
        spaceViews.clear()
        shiftView = null
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
                row.addView(v, LinearLayout.LayoutParams(0, kh(66), 1f).margins())
            }
            col.addView(row)
        }
        return col
    }

    private fun buildGrid(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // as 3 fileiras da grade reconhecem deslize horizontal: troca de página (além do botão ⟳)
        val rows = SwipeLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            onSwipe = { dir -> changePage(dir) }
        }
        col.addView(rows)
        for (r in 0 until 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val cell = kv(bg = CELL_BG, sp = 22f, bold = true) { v -> pickPiece(v) }
                cell.pressedBg = CELL_PRESSED
                cellViews.add(cell)
                row.addView(cell, LinearLayout.LayoutParams(0, kh(68), 1f).margins())
            }
            val side = when (r) {
                0 -> kv(bg = FN_BG, iconRes = R.drawable.ic_backspace).also { repeating(it) { backspace() } }
                1 -> kv("⟳", FN_BG, 20f) { changePage(1) }.also { pageButton = it }
                else -> enterKey()
            }
            row.addView(side, LinearLayout.LayoutParams(0, kh(68), 0.8f).margins())
            rows.addView(row)
        }
        // faixa de letras: TODAS as letras, da mais provável para a menos provável.
        // Desliza para o lado; as bordas esmaecem e a barrinha embaixo mostra onde você está.
        val cellW = (boardWidth() / 7.5f).toInt()
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(ALL_LETTERS.length) {
            val k = kv(bg = KEY_BG, sp = 19f) { v -> (v.tag as? String)?.let { typeLetter(it) } }
            letterViews.add(k)
            strip.addView(k, LinearLayout.LayoutParams(cellW, kh(52)))
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(36))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip)
        }
        lettersScroll = scroll
        col.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, kh(52)))

        // indicador de rolagem: trilho fino com um "polegar" que acompanha a faixa
        val track = FrameLayout(this).apply { background = rounded(FN_BG, dp(2).toFloat()) }
        val thumb = View(this).apply { background = rounded(HINT_FG, dp(2).toFloat()) }
        track.addView(thumb, FrameLayout.LayoutParams(dp(40), dp(3)))
        col.addView(track, LinearLayout.LayoutParams(dp(120), dp(3)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(2), 0, dp(4))
        })
        fun syncThumb() {
            val content = strip.width.toFloat()
            val view = scroll.width.toFloat()
            if (content <= 0f || view <= 0f) return
            val tw = (track.width * (view / content)).coerceIn(dp(16).toFloat(), track.width.toFloat())
            val lp = thumb.layoutParams
            if (lp.width != tw.toInt()) { lp.width = tw.toInt(); thumb.layoutParams = lp }
            val maxScroll = (content - view).coerceAtLeast(1f)
            thumb.translationX = (track.width - tw) * (scroll.scrollX / maxScroll)
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> syncThumb() }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncThumb() }

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
                    for ((ci, ch) in chars.withIndex()) {
                        val base = ch.toString()
                        val k = charKey(base, 24f, HINTS[ch], accentOptions(ch))
                        letterKeys.add(k to base)
                        // fileira do meio recuada: o "a" e o "l" ganham meia tecla de área de toque na borda
                        var weight = 1f
                        if (idx == 1 && ci == 0) { weight = 1.5f; k.extraLeft = 1f / 3f }
                        if (idx == 1 && ci == chars.length - 1) { weight = 1.5f; k.extraRight = 1f / 3f }
                        row.addView(k, keyParams(weight))
                    }
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
        bottom.addView(enterKey(), keyParams(1.45f))
        col.addView(bottom)
        return col
    }

    private fun shiftKey(): KeyView =
        kv(bg = FN_BG, iconRes = R.drawable.ic_shift) {
            shift = when (shift) { Shift.OFF -> Shift.ONCE; Shift.ONCE -> Shift.LOCK; Shift.LOCK -> Shift.OFF }
            refreshShiftUi(); refresh()
        }.also { shiftView = it }

    /** Atualiza o ícone do ⇧ e os rótulos das letras, sem recriar as teclas. */
    private fun refreshShiftUi() {
        shiftView?.let { v ->
            v.icon = getDrawable(when (shift) {
                Shift.OFF -> R.drawable.ic_shift
                Shift.ONCE -> R.drawable.ic_shift_on
                Shift.LOCK -> R.drawable.ic_shift_lock
            })
            v.fg = if (shift == Shift.OFF) FG else ACCENT
            v.invalidate()
        }
        for ((k, base) in letterKeys) k.label = if (shift != Shift.OFF) base.uppercase() else base
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
        insetX = dp(3).toFloat()
        insetY = dp(4).toFloat()
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
    private fun charKey(base: String, sp: Float, hint: String?, options: List<String>): KeyView {
        fun shown() = if (shift != Shift.OFF) base.uppercase() else base
        val k = kv(shown(), KEY_BG, sp, hint = hint)
        val longPress = Runnable {
            hidePreview()
            if (haptic) k.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showAccents(k, options)
        }
        k.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    tap(v)
                    showPreview(k, shown())
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
        val k = kv(LANGUAGE_NAMES[language] ?: language, KEY_BG, 14f)
        spaceViews.add(k)
        var startX = 0f
        var dragging = false
        var switched = false
        var steps = 0
        // segurar ~1 s sem arrastar troca o idioma
        val hold = Runnable {
            switched = true
            if (haptic) k.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            switchLanguage()
        }
        k.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true; tap(v)
                    startX = ev.rawX; dragging = false; switched = false; steps = 0
                    if (languages.size > 1) main.postDelayed(hold, LANGUAGE_HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    if (!dragging && !switched && abs(dx) > dp(14)) { dragging = true; main.removeCallbacks(hold) }
                    if (dragging) {
                        val target = (dx / dp(11)).toInt()
                        while (steps < target) { moveCursor(1); steps++ }
                        while (steps > target) { moveCursor(-1); steps-- }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    main.removeCallbacks(hold)
                    if (!dragging && !switched) space()
                }
                MotionEvent.ACTION_CANCEL -> { v.isPressed = false; main.removeCallbacks(hold) }
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

    private fun tap(v: View) {
        if (haptic) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun rounded(color: Int, radius: Float = dp(9).toFloat()) =
        GradientDrawable().apply { cornerRadius = radius; setColor(color) }

    private fun pressable(color: Int, radius: Float = dp(12).toFloat()) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(PRESSED_BG, radius))
        addState(intArrayOf(), rounded(color, radius))
    }

    private fun keyParams(w: Float, heightDp: Int = KEY_H) =
        LinearLayout.LayoutParams(0, kh(heightDp), w).margins()

    /** Altura de tecla em px, já multiplicada pela altura escolhida nas configurações. */
    private fun kh(v: Int) = (v * heightScale * resources.displayMetrics.density).toInt()

    /** Teclas não têm margem: o espaçamento é desenhado dentro delas (ver KeyView.insetX/insetY). */
    private fun LinearLayout.LayoutParams.margins(): LinearLayout.LayoutParams = this

    private fun LinearLayout.LayoutParams.cardMargins(): LinearLayout.LayoutParams = apply {
        setMargins(dp(3), dp(4), dp(3), dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS = Prefs.FILE
        const val KEY_MODE = Prefs.MODE
        const val KEY_RESET = Prefs.RESET
        const val KEY_LEARN = Prefs.LEARN
        const val HISTORY = "historico.tsv"
        private const val USER_WEIGHT = 3f
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        private const val BAR_H = 48
        private const val KEY_H = 60
        private const val NUM_H = 54
        private const val LONG_PRESS_MS = 350L
        private const val CHIP_MS = 60_000L
        private const val LANGUAGE_HOLD_MS = 900L

        /** Idiomas disponíveis: lista de frequência de cada um e o nome mostrado no espaço. */
        val LANGUAGE_FILES = linkedMapOf("pt" to "words_pt.tsv", "en" to "words_en.tsv", "es" to "words_es.tsv")
        val LANGUAGE_NAMES = mapOf("pt" to "Português (BR)", "en" to "English (US)", "es" to "Español")

        /** Letras da faixa rolável da grade (a ordem na tela vem da previsão). */
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
        private val ACCENT_PRESSED = Color.parseColor("#6C9BFA")
        private val ACCENT = Color.parseColor("#7FA8FF")
        private val FG = Color.parseColor("#F2F2F4")
        private val HINT_FG = Color.parseColor("#B8B8BE")
        private val DIVIDER = Color.parseColor("#3A3A3C")
    }
}
