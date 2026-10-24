package com.cchharness.core.term

enum class TermKey { Up, Down, Right, Left, Home, End, PageUp, PageDown, Insert, Delete, Enter, Tab, Escape, Backspace, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 }

/** What a key press sends to the program: xterm-compatible escape sequences. */
object TermKeys {
    private const val ESC = "\u001b"

    /** xterm modifier parameter: 1 + shift(1) + alt(2) + ctrl(4). */
    private fun mod(shift: Boolean, alt: Boolean, ctrl: Boolean) = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    fun key(k: TermKey, appCursor: Boolean = false, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        val m = mod(shift, alt, ctrl)
        fun cursor(final: Char): String = when {
            m > 1 -> "$ESC[1;$m$final"
            appCursor -> "${ESC}O$final"
            else -> "$ESC[$final"
        }
        fun tilde(n: Int): String = if (m > 1) "$ESC[$n;$m~" else "$ESC[$n~"
        fun fn(low: Char, n: Int): String = if (m > 1) "$ESC[1;$m$low" else "${ESC}O$low".takeIf { n <= 4 } ?: "$ESC[$n~"
        return when (k) {
            TermKey.Up -> cursor('A')
            TermKey.Down -> cursor('B')
            TermKey.Right -> cursor('C')
            TermKey.Left -> cursor('D')
            TermKey.Home -> cursor('H')
            TermKey.End -> cursor('F')
            TermKey.PageUp -> tilde(5)
            TermKey.PageDown -> tilde(6)
            TermKey.Insert -> tilde(2)
            TermKey.Delete -> tilde(3)
            TermKey.Enter -> if (alt) "$ESC\r" else "\r"
            TermKey.Tab -> if (shift) "$ESC[Z" else if (alt) "$ESC\t" else "\t"
            TermKey.Escape -> ESC
            TermKey.Backspace -> (if (ctrl) "\u0008" else "\u007f").let { if (alt) ESC + it else it }
            TermKey.F1 -> fn('P', 1)
            TermKey.F2 -> fn('Q', 2)
            TermKey.F3 -> fn('R', 3)
            TermKey.F4 -> fn('S', 4)
            TermKey.F5 -> if (m > 1) "$ESC[15;$m~" else "$ESC[15~"
            TermKey.F6 -> if (m > 1) "$ESC[17;$m~" else "$ESC[17~"
            TermKey.F7 -> if (m > 1) "$ESC[18;$m~" else "$ESC[18~"
            TermKey.F8 -> if (m > 1) "$ESC[19;$m~" else "$ESC[19~"
            TermKey.F9 -> if (m > 1) "$ESC[20;$m~" else "$ESC[20~"
            TermKey.F10 -> if (m > 1) "$ESC[21;$m~" else "$ESC[21~"
            TermKey.F11 -> if (m > 1) "$ESC[23;$m~" else "$ESC[23~"
            TermKey.F12 -> if (m > 1) "$ESC[24;$m~" else "$ESC[24~"
        }
    }

    /** Control code for Ctrl+[c], or null when the combination has none. */
    fun ctrl(c: Char): Char? = when (c) {
        in 'a'..'z' -> (c - 'a' + 1).toChar()
        in 'A'..'Z' -> (c - 'A' + 1).toChar()
        '@', ' ', '2' -> 0.toChar()
        '[', '3' -> 0x1B.toChar()
        '\\', '4' -> 0x1C.toChar()
        ']', '5' -> 0x1D.toChar()
        '^', '6' -> 0x1E.toChar()
        '_', '7', '/' -> 0x1F.toChar()
        '8', '?' -> 0x7F.toChar()
        else -> null
    }

    /** Typed text with the on-screen Ctrl / Alt modifiers applied to its first character. */
    fun text(text: String, ctrl: Boolean = false, alt: Boolean = false): String {
        if (text.isEmpty() || (!ctrl && !alt)) return text
        val first = text[0]
        val head = if (ctrl) (ctrl(first)?.toString() ?: first.toString()) else first.toString()
        return (if (alt) ESC else "") + head + text.substring(1)
    }

    fun paste(text: String, bracketed: Boolean): String {
        // a pasted ESC would let the clipboard end the bracket early
        val clean = text.replace("\u001b", "").replace("\r\n", "\r").replace('\n', '\r')
        return if (bracketed) "$ESC[200~$clean$ESC[201~" else clean
    }

    const val FOCUS_IN = "$ESC[I"
    const val FOCUS_OUT = "$ESC[O"

    enum class MouseButton(val code: Int) { Left(0), Middle(1), Right(2), None(3), WheelUp(64), WheelDown(65) }

    /**
     * Mouse report for a 0-based cell. [press] false = release; [motion] adds the drag flag.
     * SGR (1006) encoding when [sgr], otherwise legacy X10 which cannot address cells past 222.
     */
    fun mouse(button: MouseButton, col: Int, row: Int, press: Boolean = true, motion: Boolean = false, sgr: Boolean = true): String? {
        val cb = button.code + (if (motion) 32 else 0)
        val x = col + 1
        val y = row + 1
        return if (sgr) {
            "$ESC[<$cb;$x;$y${if (press) 'M' else 'm'}"
        } else {
            if (x > 222 || y > 222) return null
            val code = if (press) cb else 3
            "$ESC[M${(code + 32).toChar()}${(x + 32).toChar()}${(y + 32).toChar()}"
        }
    }
}
