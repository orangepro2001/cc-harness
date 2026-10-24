package com.cchharness.core.term

import java.util.Base64

/** Colour encoding shared by the emulator and renderers: DEFAULT, 0..255 palette index, or 24-bit RGB. */
object TermColor {
    const val DEFAULT = -1
    private const val RGB_FLAG = 1 shl 24
    fun rgb(r: Int, g: Int, b: Int) = RGB_FLAG or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
    fun isRgb(c: Int) = c >= 0 && (c and RGB_FLAG) != 0
    fun isIndexed(c: Int) = c in 0..255
    /** 0xRRGGBB of an [isRgb] colour. */
    fun rgbValue(c: Int) = c and 0xFFFFFF
}

object TermAttr {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val HIDDEN = 64
    const val STRIKE = 128
    /** Lead cell of a double-width character; the next cell is its [WIDE_TAIL]. */
    const val WIDE = 256
    const val WIDE_TAIL = 512
}

/** One row of cells. A code point of 0 is an empty cell. */
class TermLine(val cols: Int) {
    val cp = IntArray(cols)
    val fg = IntArray(cols) { TermColor.DEFAULT }
    val bg = IntArray(cols) { TermColor.DEFAULT }
    val attr = IntArray(cols)

    fun clear(from: Int, to: Int, bgColor: Int) {
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(cols)) {
            cp[i] = 0; fg[i] = TermColor.DEFAULT; bg[i] = bgColor; attr[i] = 0
        }
    }

    fun copyCell(from: Int, to: Int) {
        cp[to] = cp[from]; fg[to] = fg[from]; bg[to] = bg[from]; attr[to] = attr[from]
    }

    /** The text of columns [from, to), trailing blanks removed. */
    fun text(from: Int = 0, to: Int = cols): String {
        val sb = StringBuilder()
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(cols)) {
            if (attr[i] and TermAttr.WIDE_TAIL != 0) continue
            if (cp[i] == 0) sb.append(' ') else sb.appendCodePoint(cp[i])
        }
        return sb.toString().trimEnd()
    }

    fun isBlank(): Boolean = cp.all { it == 0 }
}

/**
 * A VT100/xterm-compatible terminal screen: cursor addressing, scroll regions, SGR (16/256/true
 * colour), alternate screen, DEC line drawing, mouse reporting modes, bracketed paste, OSC title
 * and clipboard. Bytes go in through [feed]; renderers read the grid under [lock].
 *
 * It is not a complete xterm: sixel, DECCOLM, soft fonts, and the like are ignored. It is the
 * subset that tmux, shells, vim and Claude Code's interface rely on.
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    private val scrollbackLimit: Int = 5_000,
    private val replySink: (ByteArray) -> Unit = {},
) {
    val lock = Any()

    private fun reply(s: String) = replySink(s.toByteArray(Charsets.UTF_8))

    var cols = cols.coerceAtLeast(1)
        private set
    var rows = rows.coerceAtLeast(1)
        private set

    var onTitle: ((String) -> Unit)? = null
    var onClipboard: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null

    var title: String = ""
        private set

    // ---- screens -------------------------------------------------------------------------
    private var main: Array<TermLine> = Array(this.rows) { TermLine(this.cols) }
    private var alt: Array<TermLine> = Array(this.rows) { TermLine(this.cols) }
    private val scrollback = ArrayDeque<TermLine>()
    var altScreen = false
        private set

    private val screen: Array<TermLine> get() = if (altScreen) alt else main

    // ---- cursor and rendition ------------------------------------------------------------
    var cursorX = 0
        private set
    var cursorY = 0
        private set
    var cursorVisible = true
        private set
    private var wrapPending = false
    private var curFg = TermColor.DEFAULT
    private var curBg = TermColor.DEFAULT
    private var curAttr = 0

    private class Saved(val x: Int, val y: Int, val fg: Int, val bg: Int, val attr: Int, val g0: Boolean, val g1: Boolean, val shift: Boolean, val origin: Boolean, val wrap: Boolean)
    private var saved: Saved? = null
    private var savedMain: Saved? = null // cursor saved by ?1049 on entering the alternate screen

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1
    private var tabs = BooleanArray(this.cols) { it % 8 == 0 && it > 0 }

    // ---- modes ---------------------------------------------------------------------------
    var autoWrap = true
        private set
    var originMode = false
        private set
    var insertMode = false
        private set
    var appCursorKeys = false
        private set
    var appKeypad = false
        private set
    var bracketedPaste = false
        private set
    var focusReporting = false
        private set

    /** 0 = off, 1000 = press/release, 1002 = + drag, 1003 = + all motion. */
    var mouseMode = 0
        private set
    var mouseSgr = false
        private set

    // ---- charsets ------------------------------------------------------------------------
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false

    // ---- parser --------------------------------------------------------------------------
    private enum class S { GROUND, ESC, ESC_INTER, CSI, OSC, OSC_ESC, STRING, STRING_ESC }
    private var state = S.GROUND
    private val params = IntArray(32)
    private val colon = BooleanArray(32) // separator BEFORE param i was ':'
    private var nParams = 0
    private var curParam = -1
    private var curSepColon = false
    private var csiPrefix = 0.toChar()
    private var csiInter = StringBuilder()
    private var escInter = 0.toChar()
    private val osc = StringBuilder()
    private var utf = 0 // pending continuation bytes
    private var utfCp = 0
    private var utfMin = 0
    private var lastPrinted = 0

    val scrollbackSize: Int get() = scrollback.size

    /** Row [r] of the visible screen (0 until [rows]); negative rows index the scrollback, -1 being the newest. */
    fun line(r: Int): TermLine = if (r >= 0) screen[r.coerceAtMost(rows - 1)] else scrollback[scrollback.size + r]

    // =========================================================================================

    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        synchronized(lock) {
            for (i in off until off + len) byte(data[i].toInt() and 0xFF)
        }
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun byte(b: Int) {
        // UTF-8 continuation handling only applies to printable text and string bodies
        if (utf > 0) {
            if (b and 0xC0 == 0x80) {
                utfCp = (utfCp shl 6) or (b and 0x3F)
                if (--utf == 0) {
                    val cp = if (utfCp < utfMin || utfCp > 0x10FFFF || utfCp in 0xD800..0xDFFF) 0xFFFD else utfCp
                    codePoint(cp)
                }
                return
            }
            utf = 0
            codePoint(0xFFFD) // truncated sequence; reprocess this byte below
        }
        when {
            b < 0x80 -> codePoint(b)
            b and 0xE0 == 0xC0 -> { utf = 1; utfCp = b and 0x1F; utfMin = 0x80 }
            b and 0xF0 == 0xE0 -> { utf = 2; utfCp = b and 0x0F; utfMin = 0x800 }
            b and 0xF8 == 0xF0 -> { utf = 3; utfCp = b and 0x07; utfMin = 0x10000 }
            else -> codePoint(0xFFFD)
        }
    }

    private fun codePoint(c: Int) {
        when (state) {
            S.GROUND -> ground(c)
            S.ESC -> escape(c)
            S.ESC_INTER -> {
                if (c in 0x20..0x2F) return
                escInterFinal(c)
                state = S.GROUND
            }
            S.CSI -> csi(c)
            S.OSC -> when {
                c == 0x07 -> { endOsc(); state = S.GROUND }
                c == 0x1B -> state = S.OSC_ESC
                c == 0x18 || c == 0x1A -> state = S.GROUND
                osc.length < 100_000 -> osc.appendCodePoint(c)
            }
            S.OSC_ESC -> {
                if (c == '\\'.code) { endOsc(); state = S.GROUND } else { state = S.ESC; escape(c) } // ESC cancelled the string and starts a new sequence
            }
            S.STRING -> when (c) { // DCS / APC / PM / SOS: ignored until ST
                0x1B -> state = S.STRING_ESC
                0x07, 0x18, 0x1A -> state = S.GROUND
            }
            S.STRING_ESC -> state = if (c == '\\'.code) S.GROUND else S.STRING
        }
    }

    // ---- ground state --------------------------------------------------------------------

    private fun ground(c: Int) {
        when {
            c == 0x1B -> { state = S.ESC }
            c < 0x20 || c == 0x7F -> control(c)
            c in 0x80..0x9F -> c1(c)
            else -> print(c)
        }
    }

    private fun control(c: Int) {
        when (c) {
            0x07 -> onBell?.invoke()
            0x08 -> { if (cursorX > 0) cursorX--; wrapPending = false }
            0x09 -> { tab(1) }
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> { cursorX = 0; wrapPending = false }
            0x0E -> shiftOut = true
            0x0F -> shiftOut = false
            0x18, 0x1A -> state = S.GROUND
        }
    }

    private fun c1(c: Int) {
        when (c) {
            0x84 -> lineFeed()
            0x85 -> { cursorX = 0; lineFeed() }
            0x88 -> if (cursorX < cols) tabs[cursorX] = true
            0x8D -> reverseIndex()
            0x9B -> startCsi()
            0x9D -> { osc.setLength(0); state = S.OSC }
            0x90, 0x98, 0x9E, 0x9F -> state = S.STRING
        }
    }

    private fun tab(n: Int) {
        var k = n
        while (k-- > 0) {
            var x = cursorX + 1
            while (x < cols - 1 && !tabs[x]) x++
            cursorX = x.coerceAtMost(cols - 1)
        }
        wrapPending = false
    }

    // ---- escape sequences ----------------------------------------------------------------

    private fun escape(c: Int) {
        state = S.GROUND
        when (c) {
            '['.code -> startCsi()
            ']'.code -> { osc.setLength(0); state = S.OSC }
            'P'.code, 'X'.code, '^'.code, '_'.code -> state = S.STRING
            in 0x20..0x2F -> { escInter = c.toChar(); state = S.ESC_INTER }
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> lineFeed()
            'E'.code -> { cursorX = 0; lineFeed() }
            'M'.code -> reverseIndex()
            'H'.code -> if (cursorX < cols) tabs[cursorX] = true
            'c'.code -> reset()
            '='.code -> appKeypad = true
            '>'.code -> appKeypad = false
            '\\'.code -> {}
            0x1B -> state = S.ESC
            in 0..0x1F -> control(c)
        }
    }

    private fun escInterFinal(c: Int) {
        when (escInter) {
            '('-> g0Graphics = c == '0'.code
            ')' -> g1Graphics = c == '0'.code
            '#' -> if (c == '8'.code) { // DECALN: fill the screen with E
                for (r in 0 until rows) for (x in 0 until cols) { screen[r].cp[x] = 'E'.code; screen[r].attr[x] = 0 }
            }
        }
    }

    // ---- CSI -----------------------------------------------------------------------------

    private fun startCsi() {
        state = S.CSI
        nParams = 0
        curParam = -1
        curSepColon = false
        csiPrefix = 0.toChar()
        csiInter.setLength(0)
    }

    private fun pushParam() {
        if (nParams < params.size) {
            params[nParams] = curParam
            colon[nParams] = curSepColon
            nParams++
        }
        curParam = -1
    }

    private fun csi(c: Int) {
        when {
            c in '0'.code..'9'.code -> curParam = (if (curParam < 0) 0 else curParam).let { (it * 10 + (c - '0'.code)).coerceAtMost(1_000_000) }
            c == ';'.code -> { pushParam(); curSepColon = false }
            c == ':'.code -> { pushParam(); curSepColon = true }
            c in '<'.code..'?'.code -> if (nParams == 0 && curParam < 0) csiPrefix = c.toChar()
            c in 0x20..0x2F -> csiInter.append(c.toChar())
            c in 0x40..0x7E -> {
                if (curParam >= 0 || nParams > 0) pushParam()
                state = S.GROUND
                dispatchCsi(c.toChar())
            }
            c == 0x1B -> state = S.ESC
            c == 0x18 || c == 0x1A -> state = S.GROUND
            c < 0x20 -> control(c) // C0 controls act even inside a sequence
        }
    }

    private fun p(i: Int, def: Int): Int = if (i < nParams && params[i] > 0) params[i] else def
    private fun p0(i: Int): Int = if (i < nParams && params[i] >= 0) params[i] else 0

    private fun dispatchCsi(f: Char) {
        if (csiInter.isNotEmpty()) { // DECSCUSR ("q" with space), DECSTR ("p" with !), DECRQM ($p) ...
            if (f == 'p' && csiInter.toString() == "!") softReset()
            if (f == 'p' && csiInter.toString() == "$" && csiPrefix == '?') reply("\u001b[?${p0(0)};0\$y")
            return
        }
        when (csiPrefix) {
            '?' -> { privateCsi(f); return }
            '>' -> { if (f == 'c') reply("\u001b[>0;115;0c"); return }
            '=', '<' -> return
        }
        when (f) {
            'A' -> { cursorY = (cursorY - p(0, 1)).coerceAtLeast(if (cursorY >= scrollTop) scrollTop else 0); wrapPending = false }
            'B', 'e' -> { cursorY = (cursorY + p(0, 1)).coerceAtMost(if (cursorY <= scrollBottom) scrollBottom else rows - 1); wrapPending = false }
            'C', 'a' -> { cursorX = (cursorX + p(0, 1)).coerceAtMost(cols - 1); wrapPending = false }
            'D' -> { cursorX = (cursorX - p(0, 1)).coerceAtLeast(0); wrapPending = false }
            'E' -> { cursorX = 0; cursorY = (cursorY + p(0, 1)).coerceAtMost(rows - 1); wrapPending = false }
            'F' -> { cursorX = 0; cursorY = (cursorY - p(0, 1)).coerceAtLeast(0); wrapPending = false }
            'G', '`' -> { cursorX = (p(0, 1) - 1).coerceIn(0, cols - 1); wrapPending = false }
            'H', 'f' -> moveTo(p(0, 1) - 1, p(1, 1) - 1)
            'd' -> moveTo(p(0, 1) - 1, 0, keepX = true)
            'I' -> tab(p(0, 1))
            'Z' -> { var k = p(0, 1); while (k-- > 0) { var x = cursorX - 1; while (x > 0 && !tabs[x]) x--; cursorX = x.coerceAtLeast(0) }; wrapPending = false }
            'J' -> eraseDisplay(p0(0))
            'K' -> eraseLine(p0(0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            'X' -> { screen[cursorY].clear(cursorX, cursorX + p(0, 1), curBg); fixWide(screen[cursorY]) }
            'S' -> repeat(p(0, 1)) { scrollUp() }
            'T' -> repeat(p(0, 1)) { scrollDown() }
            'b' -> if (lastPrinted != 0) repeat(p(0, 1).coerceAtMost(10_000)) { print(lastPrinted) }
            'm' -> sgr()
            'r' -> {
                val top = p(0, 1) - 1
                val bot = (if (nParams > 1 && params[1] > 0) params[1] else rows) - 1
                if (top < bot && bot < rows) { scrollTop = top; scrollBottom = bot }
                moveTo(0, 0)
            }
            's' -> if (nParams == 0) saveCursor()
            'u' -> restoreCursor()
            'h' -> if (p0(0) == 4) insertMode = true
            'l' -> if (p0(0) == 4) insertMode = false
            'n' -> when (p0(0)) {
                5 -> reply("\u001b[0n")
                6 -> reply("\u001b[${(if (originMode) cursorY - scrollTop else cursorY) + 1};${cursorX + 1}R")
            }
            'c' -> if (p0(0) == 0) reply("\u001b[?62;22c")
            'g' -> when (p0(0)) { 0 -> if (cursorX < cols) tabs[cursorX] = false; 3 -> tabs.fill(false) }
            't' -> {} // window manipulation: ignored
        }
    }

    private fun privateCsi(f: Char) {
        if (f != 'h' && f != 'l') return
        val on = f == 'h'
        for (i in 0 until nParams) {
            when (params[i]) {
                1 -> appCursorKeys = on
                6 -> { originMode = on; moveTo(0, 0) }
                7 -> autoWrap = on
                25 -> cursorVisible = on
                47, 1047 -> switchScreen(on, save = false)
                1049 -> switchScreen(on, save = true)
                1000, 1002, 1003 -> mouseMode = if (on) params[i] else if (mouseMode == params[i]) 0 else mouseMode
                1004 -> focusReporting = on
                1006 -> mouseSgr = on
                2004 -> bracketedPaste = on
            }
        }
    }

    private fun sgr() {
        if (nParams == 0) { sgrReset(); return }
        var i = 0
        while (i < nParams) {
            val v = if (params[i] < 0) 0 else params[i]
            when (v) {
                0 -> sgrReset()
                1 -> curAttr = curAttr or TermAttr.BOLD
                2 -> curAttr = curAttr or TermAttr.DIM
                3 -> curAttr = curAttr or TermAttr.ITALIC
                4 -> curAttr = curAttr or TermAttr.UNDERLINE
                5, 6 -> curAttr = curAttr or TermAttr.BLINK
                7 -> curAttr = curAttr or TermAttr.INVERSE
                8 -> curAttr = curAttr or TermAttr.HIDDEN
                9 -> curAttr = curAttr or TermAttr.STRIKE
                21 -> curAttr = curAttr or TermAttr.UNDERLINE
                22 -> curAttr = curAttr and (TermAttr.BOLD or TermAttr.DIM).inv()
                23 -> curAttr = curAttr and TermAttr.ITALIC.inv()
                24 -> curAttr = curAttr and TermAttr.UNDERLINE.inv()
                25 -> curAttr = curAttr and TermAttr.BLINK.inv()
                27 -> curAttr = curAttr and TermAttr.INVERSE.inv()
                28 -> curAttr = curAttr and TermAttr.HIDDEN.inv()
                29 -> curAttr = curAttr and TermAttr.STRIKE.inv()
                in 30..37 -> curFg = v - 30
                39 -> curFg = TermColor.DEFAULT
                in 40..47 -> curBg = v - 40
                49 -> curBg = TermColor.DEFAULT
                in 90..97 -> curFg = v - 90 + 8
                in 100..107 -> curBg = v - 100 + 8
                38, 48 -> {
                    val (color, used) = extendedColor(i)
                    if (color != null) { if (v == 38) curFg = color else curBg = color }
                    i += used
                }
            }
            i++
        }
    }

    /** Parses "5;n" / "2;r;g;b" (also the ':' forms, which carry an optional colour-space slot). Returns the colour and how many extra params it consumed. */
    private fun extendedColor(i: Int): Pair<Int?, Int> {
        if (i + 1 >= nParams) return null to (nParams - i - 1).coerceAtLeast(0)
        val colonForm = colon[i + 1]
        fun at(k: Int) = if (k < nParams) params[k].coerceAtLeast(0) else 0
        return when (params[i + 1]) {
            5 -> if (i + 2 < nParams) (at(i + 2).coerceIn(0, 255)) to 2 else null to 1
            2 -> {
                val skip = if (colonForm && nParams - (i + 2) >= 4) 1 else 0 // colon form: ISO 8613-6 colour-space id
                val base = i + 2 + skip
                if (base + 2 < nParams) TermColor.rgb(at(base), at(base + 1), at(base + 2)) to (base + 2 - i) else null to (nParams - i - 1)
            }
            else -> null to 1
        }
    }

    private fun sgrReset() { curFg = TermColor.DEFAULT; curBg = TermColor.DEFAULT; curAttr = 0 }

    // ---- OSC -----------------------------------------------------------------------------

    private fun endOsc() {
        val s = osc.toString()
        val semi = s.indexOf(';')
        val code = (if (semi < 0) s else s.substring(0, semi)).toIntOrNull() ?: return
        val arg = if (semi < 0) "" else s.substring(semi + 1)
        when (code) {
            0, 2 -> { title = arg; onTitle?.invoke(arg) }
            52 -> {
                val data = arg.substringAfter(';', "")
                if (data.isNotEmpty() && data != "?") {
                    try {
                        onClipboard?.invoke(String(Base64.getDecoder().decode(data), Charsets.UTF_8))
                    } catch (e: IllegalArgumentException) {
                    }
                }
            }
        }
    }

    // ---- printing ------------------------------------------------------------------------

    private val decGraphics = charArrayOf(
        '◆', '▒', '␉', '␌', '␍', '␊', '°', '±', '␤', '␋', '┘', '┐', '┌', '└', '┼', '⎺', '⎻', '─', '⎼', '⎽',
        '├', '┤', '┴', '┬', '│', '≤', '≥', 'π', '≠', '£', '·',
    )

    private fun print(c0: Int) {
        var c = c0
        if (c in 0x60..0x7E && (if (shiftOut) g1Graphics else g0Graphics)) c = decGraphics[c - 0x60].code
        val w = CharWidth.of(c)
        if (w == 0) return // combining marks are dropped: cells hold one code point
        lastPrinted = c
        if (wrapPending && autoWrap) { cursorX = 0; wrapPending = false; lineFeed() }
        wrapPending = false
        if (w == 2 && cursorX == cols - 1) {
            if (!autoWrap) return
            // a wide character never straddles the margin: blank the last cell and wrap
            val l = screen[cursorY]
            l.cp[cursorX] = 0; l.attr[cursorX] = 0; l.bg[cursorX] = curBg
            cursorX = 0
            lineFeed()
        }
        val line = screen[cursorY]
        if (insertMode) insertChars(w)
        breakWideAt(line, cursorX)
        if (w == 2) breakWideAt(line, cursorX + 1)
        line.cp[cursorX] = c; line.fg[cursorX] = curFg; line.bg[cursorX] = curBg
        line.attr[cursorX] = curAttr or if (w == 2) TermAttr.WIDE else 0
        if (w == 2) {
            line.cp[cursorX + 1] = 0; line.fg[cursorX + 1] = curFg; line.bg[cursorX + 1] = curBg
            line.attr[cursorX + 1] = curAttr or TermAttr.WIDE_TAIL
        }
        cursorX += w
        if (cursorX >= cols) { cursorX = cols - 1; wrapPending = true }
    }

    /** Overwriting one half of a double-width character blanks the other half. */
    private fun breakWideAt(line: TermLine, x: Int) {
        if (x >= cols) return
        val a = line.attr[x]
        if (a and TermAttr.WIDE_TAIL != 0 && x > 0) { line.cp[x - 1] = 0; line.attr[x - 1] = line.attr[x - 1] and TermAttr.WIDE.inv() }
        if (a and TermAttr.WIDE != 0 && x + 1 < cols) { line.cp[x + 1] = 0; line.attr[x + 1] = line.attr[x + 1] and TermAttr.WIDE_TAIL.inv() }
    }

    /** After a bulk edit, no half of a wide character may remain without its partner. */
    private fun fixWide(line: TermLine) {
        for (x in 0 until cols) {
            val a = line.attr[x]
            if (a and TermAttr.WIDE != 0 && (x + 1 >= cols || line.attr[x + 1] and TermAttr.WIDE_TAIL == 0)) { line.cp[x] = 0; line.attr[x] = a and TermAttr.WIDE.inv() }
            if (a and TermAttr.WIDE_TAIL != 0 && (x == 0 || line.attr[x - 1] and TermAttr.WIDE == 0)) { line.cp[x] = 0; line.attr[x] = a and TermAttr.WIDE_TAIL.inv() }
        }
    }

    // ---- cursor movement and editing -----------------------------------------------------

    private fun moveTo(row: Int, col: Int, keepX: Boolean = false) {
        val top = if (originMode) scrollTop else 0
        val bot = if (originMode) scrollBottom else rows - 1
        cursorY = (row + top).coerceIn(top, bot)
        if (!keepX) cursorX = col.coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorY == scrollBottom) scrollUp()
        else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorY == scrollTop) scrollDown() else if (cursorY > 0) cursorY--
    }

    private fun blankLine() = TermLine(cols).also { if (curBg != TermColor.DEFAULT) it.clear(0, cols, curBg) }

    private fun scrollUp() {
        val s = screen
        val removed = s[scrollTop]
        for (r in scrollTop until scrollBottom) s[r] = s[r + 1]
        s[scrollBottom] = blankLine()
        if (!altScreen && scrollTop == 0) {
            scrollback.addLast(removed)
            if (scrollback.size > scrollbackLimit) scrollback.removeFirst()
        }
    }

    private fun scrollDown() {
        val s = screen
        for (r in scrollBottom downTo scrollTop + 1) s[r] = s[r - 1]
        s[scrollTop] = blankLine()
    }

    private fun insertLines(n: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val s = screen
        repeat(n.coerceAtMost(scrollBottom - cursorY + 1)) {
            for (r in scrollBottom downTo cursorY + 1) s[r] = s[r - 1]
            s[cursorY] = blankLine()
        }
        cursorX = 0; wrapPending = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val s = screen
        repeat(n.coerceAtMost(scrollBottom - cursorY + 1)) {
            for (r in cursorY until scrollBottom) s[r] = s[r + 1]
            s[scrollBottom] = blankLine()
        }
        cursorX = 0; wrapPending = false
    }

    private fun insertChars(n: Int) {
        val l = screen[cursorY]
        val k = n.coerceAtMost(cols - cursorX)
        for (x in cols - 1 downTo cursorX + k) l.copyCell(x - k, x)
        l.clear(cursorX, cursorX + k, curBg)
        fixWide(l)
    }

    private fun deleteChars(n: Int) {
        val l = screen[cursorY]
        val k = n.coerceAtMost(cols - cursorX)
        for (x in cursorX until cols - k) l.copyCell(x + k, x)
        l.clear(cols - k, cols, curBg)
        fixWide(l)
    }

    private fun eraseLine(mode: Int) {
        val l = screen[cursorY]
        when (mode) {
            0 -> l.clear(cursorX, cols, curBg)
            1 -> l.clear(0, cursorX + 1, curBg)
            2 -> l.clear(0, cols, curBg)
        }
        fixWide(l)
        wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        val s = screen
        when (mode) {
            0 -> { s[cursorY].clear(cursorX, cols, curBg); fixWide(s[cursorY]); for (r in cursorY + 1 until rows) s[r].clear(0, cols, curBg) }
            1 -> { for (r in 0 until cursorY) s[r].clear(0, cols, curBg); s[cursorY].clear(0, cursorX + 1, curBg); fixWide(s[cursorY]) }
            2 -> for (r in 0 until rows) s[r].clear(0, cols, curBg)
            3 -> scrollback.clear()
        }
    }

    private fun saveCursor() {
        saved = Saved(cursorX, cursorY, curFg, curBg, curAttr, g0Graphics, g1Graphics, shiftOut, originMode, wrapPending)
    }

    private fun restoreCursor() {
        val s = saved
        if (s == null) { cursorX = 0; cursorY = 0; sgrReset(); originMode = false; return }
        cursorX = s.x.coerceAtMost(cols - 1); cursorY = s.y.coerceAtMost(rows - 1)
        curFg = s.fg; curBg = s.bg; curAttr = s.attr
        g0Graphics = s.g0; g1Graphics = s.g1; shiftOut = s.shift; originMode = s.origin; wrapPending = s.wrap
    }

    private fun switchScreen(toAlt: Boolean, save: Boolean) {
        if (toAlt == altScreen) return
        if (toAlt) {
            if (save) { saveCursor(); savedMain = saved }
            altScreen = true
            for (r in 0 until rows) alt[r].clear(0, cols, TermColor.DEFAULT)
        } else {
            altScreen = false
            if (save) { savedMain?.let { saved = it; restoreCursor() } }
        }
    }

    private fun softReset() {
        sgrReset(); autoWrap = true; originMode = false; insertMode = false; appCursorKeys = false
        cursorVisible = true; scrollTop = 0; scrollBottom = rows - 1; saved = null
        g0Graphics = false; g1Graphics = false; shiftOut = false
    }

    /** Full reset (RIS): clears both screens and every mode; the scrollback is kept. */
    fun reset() {
        synchronized(lock) {
            softReset()
            mouseMode = 0; mouseSgr = false; bracketedPaste = false; appKeypad = false; focusReporting = false
            altScreen = false
            for (r in 0 until rows) { main[r].clear(0, cols, TermColor.DEFAULT); alt[r].clear(0, cols, TermColor.DEFAULT) }
            cursorX = 0; cursorY = 0; wrapPending = false
            tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }
            state = S.GROUND; utf = 0
        }
    }

    // ---- resize --------------------------------------------------------------------------

    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceAtLeast(1)
        val nr = newRows.coerceAtLeast(1)
        synchronized(lock) {
            if (nc == cols && nr == rows) return
            val (m, droppedMain) = resized(main, nc, nr, pushToScrollback = true)
            val (a, droppedAlt) = resized(alt, nc, nr, pushToScrollback = false)
            main = m
            alt = a
            cols = nc
            rows = nr
            cursorY = (cursorY - if (altScreen) droppedAlt else droppedMain).coerceAtLeast(0) // rows removed above the cursor
            cursorX = cursorX.coerceAtMost(cols - 1)
            cursorY = cursorY.coerceAtMost(rows - 1)
            scrollTop = 0; scrollBottom = rows - 1
            wrapPending = false
            tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }
        }
    }

    /** Returns the new rows and how many were removed from the top (the cursor moves up by that much). */
    private fun resized(old: Array<TermLine>, nc: Int, nr: Int, pushToScrollback: Boolean): Pair<Array<TermLine>, Int> {
        var lines = old.toList()
        var fromTop = 0
        if (nr < lines.size) {
            val drop = lines.size - nr
            // drop blank lines from the bottom first, then the oldest lines from the top
            var fromBottom = 0
            while (fromBottom < drop && lines[lines.size - 1 - fromBottom].isBlank() && (lines.size - 1 - fromBottom) > cursorY) fromBottom++
            fromTop = drop - fromBottom
            if (pushToScrollback) for (i in 0 until fromTop) { scrollback.addLast(lines[i]); if (scrollback.size > scrollbackLimit) scrollback.removeFirst() }
            lines = lines.subList(fromTop, lines.size - fromBottom)
        }
        val out = ArrayList<TermLine>(nr)
        for (l in lines) {
            if (l.cols == nc) out += l else out += TermLine(nc).also { n ->
                for (x in 0 until minOf(nc, l.cols)) n.copyFrom(l, x)
                fixWide(n, nc)
            }
        }
        while (out.size < nr) out += TermLine(nc)
        return out.toTypedArray() to fromTop
    }

    private fun TermLine.copyFrom(o: TermLine, x: Int) { cp[x] = o.cp[x]; fg[x] = o.fg[x]; bg[x] = o.bg[x]; attr[x] = o.attr[x] }

    private fun fixWide(line: TermLine, width: Int) {
        for (x in 0 until width) {
            val a = line.attr[x]
            if (a and TermAttr.WIDE != 0 && (x + 1 >= width || line.attr[x + 1] and TermAttr.WIDE_TAIL == 0)) { line.cp[x] = 0; line.attr[x] = a and TermAttr.WIDE.inv() }
        }
    }

    // ---- reading -------------------------------------------------------------------------

    /** The visible screen as text, one line per row, trailing blank lines removed. */
    fun screenText(includeScrollback: Boolean = false): String = synchronized(lock) {
        val sb = ArrayList<String>()
        if (includeScrollback) scrollback.forEach { sb += it.text() }
        for (r in 0 until rows) sb += screen[r].text()
        while (sb.isNotEmpty() && sb.last().isEmpty()) sb.removeAt(sb.size - 1)
        sb.joinToString("\n")
    }
}
