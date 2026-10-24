package com.cchharness.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Scroller
import com.cchharness.core.term.TermAttr
import com.cchharness.core.term.TermColor
import com.cchharness.core.term.TermKey
import com.cchharness.core.term.TermKeys
import com.cchharness.core.term.TermLine
import com.cchharness.core.term.TerminalEmulator
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws a [TerminalEmulator] and turns touch and keyboard input into terminal input. It is a plain
 * View (not Compose) because soft-keyboard text input needs its own InputConnection: a terminal
 * needs every key, including Backspace on an empty line, and gets no editable text field.
 */
class TerminalView(context: Context) : View(context) {
    var emulator: TerminalEmulator? = null
        set(value) {
            field = value
            scrollOffset = 0
            invalidate()
            notifyResize(force = true)
        }

    /** Receives everything the user types, already encoded as terminal input. */
    var onInput: (String) -> Unit = {}
    var onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    var onFontSize: (Int) -> Unit = {}

    /** The on-screen Ctrl / Alt toggles; consumed after one character. */
    var modifiers: () -> Pair<Boolean, Boolean> = { false to false }
    var onModifiersUsed: () -> Unit = {}

    var fontSizeSp: Int = 14
        set(value) {
            val v = value.coerceIn(MIN_FONT, MAX_FONT)
            if (v == field && cellW > 0f) return
            field = v
            measureCells()
            notifyResize(force = true)
            invalidate()
        }

    /** Lines scrolled back into the history (0 = following the live screen). */
    var scrollOffset = 0
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fill = Paint()
    private val glyphs = TerminalGlyphs()

    /** Nerd Font icons (private-use code points) come from a bundled font: Android has none of them. */
    private val symbols: Typeface? = try {
        Typeface.createFromAsset(context.assets, "fonts/SymbolsNerdFontMono-Regular.ttf")
    } catch (e: Exception) {
        null
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = symbols }
    private var cellW = 0f
    private var cellH = 0
    private var baseline = 0f
    private val padX = dp(6f)
    private val padY = dp(4f)
    private var lastCols = 0
    private var lastRows = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        setBackgroundColor(TerminalPalette.BACKGROUND)
        measureCells()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun measureCells() {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat(), resources.displayMetrics)
        cellW = paint.measureText("M")
        val fm = paint.fontMetrics
        cellH = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        baseline = -fm.ascent
        // icons are one cell wide in the Mono variant; a little larger reads better and spills into the space that usually follows
        symbolPaint.textSize = 100f
        val adv = symbolPaint.measureText("\uF015").takeIf { it > 0f } ?: 100f
        symbolPaint.textSize = 100f * cellW * SYMBOL_SCALE / adv
    }

    val cellCols: Int get() = (((width - 2 * padX) / cellW).toInt()).coerceAtLeast(MIN_COLS)
    val cellRows: Int get() = (((height - 2 * padY) / cellH).toInt()).coerceAtLeast(MIN_ROWS)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        notifyResize(force = false)
    }

    private fun notifyResize(force: Boolean) {
        if (width == 0 || height == 0 || cellW <= 0f) return
        val c = cellCols
        val r = cellRows
        if (!force && c == lastCols && r == lastRows) return
        lastCols = c
        lastRows = r
        onResize(c, r)
    }

    // ---- drawing -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val emu = emulator ?: return // the view's own background colour already paints the rest
        synchronized(emu.lock) {
            val back = emu.scrollbackSize
            val offset = scrollOffset.coerceIn(0, back)
            for (r in 0 until emu.rows) {
                val idx = r - offset
                if (idx < -back) continue
                drawLine(canvas, emu.line(idx), r, emu.cols)
            }
            if (offset == 0 && emu.cursorVisible) drawCursor(canvas, emu)
        }
    }

    private fun drawLine(canvas: Canvas, line: TermLine, row: Int, cols: Int) {
        val n = minOf(cols, line.cols)
        val top = padY + row * cellH
        // pass 1: backgrounds, merged per run
        var x = 0
        while (x < n) {
            val bg = cellBg(line, x)
            var e = x + 1
            while (e < n && cellBg(line, e) == bg) e++
            if (bg != TerminalPalette.BACKGROUND) {
                fill.color = bg
                canvas.drawRect(padX + x * cellW, top.toFloat(), padX + e * cellW, (top + cellH).toFloat(), fill)
            }
            x = e
        }
        // pass 2: glyphs; plain ASCII with equal rendition is drawn as one string
        x = 0
        val sb = StringBuilder()
        while (x < n) {
            val a = line.attr[x]
            if (a and TermAttr.WIDE_TAIL != 0) { x++; continue }
            val cp = line.cp[x]
            if (cp == 0 || a and TermAttr.HIDDEN != 0) {
                if (a and (TermAttr.UNDERLINE or TermAttr.STRIKE) != 0) decorate(canvas, line, x, x + 1, top)
                x++
                continue
            }
            if (cp < 0x80 && a and TermAttr.WIDE == 0) {
                var e = x
                sb.setLength(0)
                while (e < n && line.cp[e] in 1..0x7F && line.attr[e] and TermAttr.WIDE_TAIL == 0 && sameStyle(line, e, x)) {
                    sb.append(line.cp[e].toChar())
                    e++
                    // a gap (empty cell) ends the run, keeping later glyphs on their cell grid
                    if (e < n && line.cp[e] == 0) break
                }
                style(line, x)
                canvas.drawText(sb, 0, sb.length, padX + x * cellW, top + baseline, paint)
                decorate(canvas, line, x, e, top)
                x = e
            } else if (TerminalGlyphs.isCustom(cp) && a and TermAttr.WIDE == 0) {
                // drawn as geometry on integer cell edges, so neighbouring cells meet without seams
                glyphs.draw(
                    canvas, cp,
                    Math.round(padX + x * cellW).toFloat(), Math.round(top).toFloat(),
                    Math.round(padX + (x + 1) * cellW).toFloat(), Math.round(top + cellH).toFloat(),
                    effective(line, x).first.let { c -> if (a and TermAttr.DIM != 0) (c and 0x00FFFFFF) or (140 shl 24) else c },
                )
                x++
            } else if (symbols != null && TerminalGlyphs.isPrivateUse(cp) && a and TermAttr.WIDE == 0) {
                symbolPaint.color = effective(line, x).first
                if (a and TermAttr.DIM != 0) symbolPaint.alpha = 140
                val s = String(Character.toChars(cp))
                val fm = symbolPaint.fontMetrics
                val gx = padX + x * cellW + (cellW - symbolPaint.measureText(s)) / 2f
                val gy = top + (cellH - (fm.descent - fm.ascent)) / 2f - fm.ascent
                canvas.drawText(s, gx, gy, symbolPaint)
                x++
            } else {
                val wide = a and TermAttr.WIDE != 0
                style(line, x)
                val s = String(Character.toChars(cp))
                val span = if (wide) 2 else 1
                val gx = padX + x * cellW + (span * cellW - paint.measureText(s)).coerceAtLeast(0f) / 2f
                canvas.drawText(s, gx, top + baseline, paint)
                decorate(canvas, line, x, x + span, top)
                x += span
            }
        }
    }

    private fun sameStyle(l: TermLine, i: Int, j: Int) = l.fg[i] == l.fg[j] && l.bg[i] == l.bg[j] && (l.attr[i] and STYLE_MASK) == (l.attr[j] and STYLE_MASK)

    private fun effective(line: TermLine, x: Int): Pair<Int, Int> {
        val a = line.attr[x]
        val bold = a and TermAttr.BOLD != 0
        var fg = TerminalPalette.resolve(line.fg[x], TerminalPalette.FOREGROUND, bold)
        var bg = TerminalPalette.resolve(line.bg[x], TerminalPalette.BACKGROUND)
        if (a and TermAttr.INVERSE != 0) { val t = fg; fg = bg; bg = t }
        return fg to bg
    }

    private fun cellBg(line: TermLine, x: Int) = effective(line, x).second

    private fun style(line: TermLine, x: Int) {
        val a = line.attr[x]
        paint.color = effective(line, x).first
        if (a and TermAttr.DIM != 0) paint.alpha = 140
        paint.isFakeBoldText = a and TermAttr.BOLD != 0
        paint.textSkewX = if (a and TermAttr.ITALIC != 0) -0.2f else 0f
    }

    private fun decorate(canvas: Canvas, line: TermLine, from: Int, to: Int, top: Float) {
        val a = line.attr[from]
        if (a and (TermAttr.UNDERLINE or TermAttr.STRIKE) == 0) return
        fill.color = effective(line, from).first
        val x0 = padX + from * cellW
        val x1 = padX + to * cellW
        val t = dp(1f)
        if (a and TermAttr.UNDERLINE != 0) canvas.drawRect(x0, top + baseline + t, x1, top + baseline + 2 * t, fill)
        if (a and TermAttr.STRIKE != 0) canvas.drawRect(x0, top + cellH / 2f, x1, top + cellH / 2f + t, fill)
    }

    private fun drawCursor(canvas: Canvas, emu: TerminalEmulator) {
        val x = emu.cursorX
        val y = emu.cursorY
        if (y >= emu.rows) return
        val line = emu.line(y)
        val span = if (x < line.cols && line.attr[x] and TermAttr.WIDE != 0) 2 else 1
        val left = padX + x * cellW
        val top = (padY + y * cellH).toFloat()
        fill.color = TerminalPalette.CURSOR
        if (hasFocus()) {
            fill.style = Paint.Style.FILL
            canvas.drawRect(left, top, left + span * cellW, top + cellH, fill)
            val cp = if (x < line.cols) line.cp[x] else 0
            if (cp != 0) {
                paint.color = TerminalPalette.BACKGROUND
                paint.isFakeBoldText = false
                paint.textSkewX = 0f
                canvas.drawText(String(Character.toChars(cp)), left, top + baseline, paint)
            }
        } else {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = dp(1.5f)
            canvas.drawRect(left, top, left + span * cellW, top + cellH, fill)
            fill.style = Paint.Style.FILL
        }
    }

    // ---- touch ---------------------------------------------------------------------------

    private var scrollAcc = 0f
    private val scroller = Scroller(context)
    private var flingLastY = 0
    private val fling = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val y = scroller.currY
            scrollByPixels(-(y - flingLastY).toFloat(), width / 2f, height / 2f)
            flingLastY = y
            postOnAnimation(this)
        }
    }

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                scrollAcc = 0f
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                requestFocus()
                showKeyboard()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                scrollByPixels(dy, e2.x, e2.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                flingLastY = 0
                scroller.fling(0, 0, 0, vy.toInt(), 0, 0, -1_000_000, 1_000_000)
                postOnAnimation(fling)
                return true
            }
        },
    )

    private var scaleAcc = 1f
    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaleAcc = 1f; return true }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                scaleAcc *= d.scaleFactor
                val next = (fontSizeSp * scaleAcc).roundToInt().coerceIn(MIN_FONT, MAX_FONT)
                if (next != fontSizeSp) {
                    scaleAcc = 1f
                    fontSizeSp = next
                    onFontSize(next)
                }
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    /** [distance] > 0: the finger moved up. Programs that track the mouse get wheel events, others scroll the history. */
    private fun scrollByPixels(distance: Float, x: Float, y: Float) {
        val emu = emulator ?: return
        scrollAcc += distance
        val lines = (scrollAcc / cellH).toInt()
        if (lines == 0) return
        scrollAcc -= lines * cellH
        when {
            emu.mouseMode != 0 -> {
                val col = ((x - padX) / cellW).toInt().coerceIn(0, emu.cols - 1)
                val row = ((y - padY) / cellH).toInt().coerceIn(0, emu.rows - 1)
                val btn = if (lines < 0) TermKeys.MouseButton.WheelUp else TermKeys.MouseButton.WheelDown
                val report = TermKeys.mouse(btn, col, row, sgr = emu.mouseSgr) ?: return
                onInput(report.repeat(kotlin.math.abs(lines).coerceAtMost(30)))
            }
            emu.altScreen -> {
                val k = TermKeys.key(if (lines < 0) TermKey.Up else TermKey.Down, appCursor = emu.appCursorKeys)
                onInput(k.repeat(kotlin.math.abs(lines).coerceAtMost(30)))
            }
            else -> {
                scrollOffset = (scrollOffset - lines).coerceIn(0, emu.scrollbackSize)
                invalidate()
            }
        }
    }

    fun scrollToBottom() {
        if (scrollOffset != 0) { scrollOffset = 0; invalidate() }
    }

    // ---- keyboard ------------------------------------------------------------------------

    fun showKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private fun typed(text: String) {
        if (text.isEmpty()) return
        val (ctrl, alt) = modifiers()
        onInput(TermKeys.text(text, ctrl, alt))
        if (ctrl || alt) onModifiersUsed()
        scrollToBottom()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // visible-password + no-suggestions keeps keyboards from composing/autocorrecting what is typed into a shell
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            // Composition (pinyin, kana, ...) stays inside the keyboard until it is committed.
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                editable?.clear()
                typed(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean {
                val pending = editable?.toString().orEmpty()
                editable?.clear()
                typed(pending)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                editable?.clear()
                typed("\u007f".repeat(beforeLength.coerceAtLeast(1).coerceAtMost(20)))
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                typed("\r")
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKey(event.keyCode, event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = keyFor(keyCode) != null || super.onKeyUp(keyCode, event)

    private fun keyFor(code: Int): TermKey? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> TermKey.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> TermKey.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> TermKey.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> TermKey.Right
        KeyEvent.KEYCODE_MOVE_HOME -> TermKey.Home
        KeyEvent.KEYCODE_MOVE_END -> TermKey.End
        KeyEvent.KEYCODE_PAGE_UP -> TermKey.PageUp
        KeyEvent.KEYCODE_PAGE_DOWN -> TermKey.PageDown
        KeyEvent.KEYCODE_INSERT -> TermKey.Insert
        KeyEvent.KEYCODE_FORWARD_DEL -> TermKey.Delete
        KeyEvent.KEYCODE_DEL -> TermKey.Backspace
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TermKey.Enter
        KeyEvent.KEYCODE_TAB -> TermKey.Tab
        KeyEvent.KEYCODE_ESCAPE -> TermKey.Escape
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> TermKey.entries[TermKey.F1.ordinal + (code - KeyEvent.KEYCODE_F1)]
        else -> null
    }

    private fun handleKey(code: Int, event: KeyEvent): Boolean {
        val (stickyCtrl, stickyAlt) = modifiers()
        val ctrl = event.isCtrlPressed || stickyCtrl
        val alt = event.isAltPressed || stickyAlt
        val key = keyFor(code)
        val emu = emulator
        if (key != null) {
            onInput(TermKeys.key(key, appCursor = emu?.appCursorKeys == true, shift = event.isShiftPressed, alt = alt, ctrl = ctrl))
            if (stickyCtrl || stickyAlt) onModifiersUsed()
            scrollToBottom()
            return true
        }
        if (event.isSystem || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (unicode == 0 || unicode and KeyCharacterMapCombining != 0) return false
        val text = String(Character.toChars(unicode))
        onInput(TermKeys.text(text, ctrl, alt))
        if (stickyCtrl || stickyAlt) onModifiersUsed()
        scrollToBottom()
        return true
    }

    companion object {
        const val MIN_FONT = 8
        const val MAX_FONT = 28
        private const val SYMBOL_SCALE = 1.2f
        private const val MIN_COLS = 10
        private const val MIN_ROWS = 3
        private const val STYLE_MASK = TermAttr.BOLD or TermAttr.DIM or TermAttr.ITALIC or TermAttr.UNDERLINE or TermAttr.INVERSE or TermAttr.STRIKE or TermAttr.HIDDEN
        private const val KeyCharacterMapCombining = 0x80000000.toInt()
    }
}
