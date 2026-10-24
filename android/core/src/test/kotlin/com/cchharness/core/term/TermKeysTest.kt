package com.cchharness.core.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TermKeysTest {
    private val E = "\u001b"

    @Test
    fun arrowsFollowTheCursorKeyMode() {
        assertEquals("$E[A", TermKeys.key(TermKey.Up))
        assertEquals("${E}OA", TermKeys.key(TermKey.Up, appCursor = true))
        assertEquals("$E[1;5C", TermKeys.key(TermKey.Right, appCursor = true, ctrl = true))
        assertEquals("$E[1;3D", TermKeys.key(TermKey.Left, alt = true))
        assertEquals("$E[1;2H", TermKeys.key(TermKey.Home, shift = true))
    }

    @Test
    fun editingAndFunctionKeys() {
        assertEquals("$E[3~", TermKeys.key(TermKey.Delete))
        assertEquals("$E[5~", TermKeys.key(TermKey.PageUp))
        assertEquals("$E[6;5~", TermKeys.key(TermKey.PageDown, ctrl = true))
        assertEquals("${E}OP", TermKeys.key(TermKey.F1))
        assertEquals("$E[1;2P", TermKeys.key(TermKey.F1, shift = true))
        assertEquals("$E[15~", TermKeys.key(TermKey.F5))
        assertEquals("$E[24~", TermKeys.key(TermKey.F12))
        assertEquals("$E[Z", TermKeys.key(TermKey.Tab, shift = true))
        assertEquals("\r", TermKeys.key(TermKey.Enter))
        assertEquals("\u007f", TermKeys.key(TermKey.Backspace))
        assertEquals(E, TermKeys.key(TermKey.Escape))
    }

    @Test
    fun controlCodes() {
        assertEquals(3.toChar(), TermKeys.ctrl('c'))
        assertEquals(3.toChar(), TermKeys.ctrl('C'))
        assertEquals(0x1B.toChar(), TermKeys.ctrl('['))
        assertEquals(0x1F.toChar(), TermKeys.ctrl('/'))
        assertNull(TermKeys.ctrl('-'))
    }

    @Test
    fun stickyModifiersApplyToTheFirstCharacter() {
        assertEquals("\u0003", TermKeys.text("c", ctrl = true))
        assertEquals("${E}x", TermKeys.text("x", alt = true))
        assertEquals("${E}\u0004", TermKeys.text("d", ctrl = true, alt = true))
        assertEquals("你好", TermKeys.text("你好"))
        assertEquals("-", TermKeys.text("-", ctrl = true))
    }

    @Test
    fun pasteIsBracketedAndSanitised() {
        assertEquals("$E[200~a\rb$E[201~", TermKeys.paste("a\nb", bracketed = true))
        assertEquals("a\rb", TermKeys.paste("a\r\nb", bracketed = false))
        assertEquals("$E[200~x[201~y$E[201~", TermKeys.paste("x${E}[201~y", bracketed = true))
    }

    @Test
    fun mouseReports() {
        assertEquals("$E[<64;5;3M", TermKeys.mouse(TermKeys.MouseButton.WheelUp, 4, 2))
        assertEquals("$E[<0;1;1m", TermKeys.mouse(TermKeys.MouseButton.Left, 0, 0, press = false))
        assertEquals("$E[<32;2;2M", TermKeys.mouse(TermKeys.MouseButton.Left, 1, 1, motion = true))
        assertEquals("$E[M${(64 + 32).toChar()}${(5 + 32).toChar()}${(3 + 32).toChar()}", TermKeys.mouse(TermKeys.MouseButton.WheelUp, 4, 2, sgr = false))
        assertNull(TermKeys.mouse(TermKeys.MouseButton.Left, 300, 1, sgr = false))
    }
}
