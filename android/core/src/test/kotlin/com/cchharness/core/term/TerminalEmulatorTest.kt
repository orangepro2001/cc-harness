package com.cchharness.core.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalEmulatorTest {
    private val replies = ArrayList<String>()
    private fun term(cols: Int = 20, rows: Int = 5) = TerminalEmulator(cols, rows) { replies += String(it, Charsets.UTF_8) }
    private fun TerminalEmulator.row(r: Int) = line(r).text()
    private fun TerminalEmulator.at(r: Int, c: Int) = line(r).cp[c]
    private val E = "\u001b"

    @Test
    fun textAndNewlines() {
        val t = term()
        t.feed("hello\r\nworld")
        assertEquals("hello", t.row(0))
        assertEquals("world", t.row(1))
        assertEquals(5, t.cursorX)
        assertEquals(1, t.cursorY)
    }

    @Test
    fun bareLineFeedKeepsTheColumn() {
        val t = term()
        t.feed("ab\ncd")
        assertEquals("ab", t.row(0))
        assertEquals("  cd", t.row(1))
    }

    @Test
    fun wrapIsDeferredUntilTheNextCharacter() {
        val t = term(cols = 5, rows = 3)
        t.feed("12345")
        assertEquals(4, t.cursorX)
        assertEquals(0, t.cursorY) // still on the first row: the wrap is pending
        t.feed("6")
        assertEquals("12345", t.row(0))
        assertEquals("6", t.row(1))
    }

    @Test
    fun noAutowrapOverwritesTheLastColumn() {
        val t = term(cols = 4, rows = 2)
        t.feed("$E[?7l" + "abcdef")
        assertEquals("abcf", t.row(0))
        assertEquals("", t.row(1))
    }

    @Test
    fun cursorAddressing() {
        val t = term()
        t.feed("$E[3;5Hx$E[1;1Hy$E[2Bz$E[3Dw")
        assertEquals('x'.code, t.at(2, 4))
        assertEquals('y'.code, t.at(0, 0))
        assertEquals('z'.code, t.at(2, 1))
        assertEquals('w'.code, t.at(2, 0))
    }

    @Test
    fun cursorMovesAreClamped() {
        val t = term(cols = 10, rows = 4)
        t.feed("$E[99;99H")
        assertEquals(9, t.cursorX)
        assertEquals(3, t.cursorY)
        t.feed("$E[99A$E[99D")
        assertEquals(0, t.cursorX)
        assertEquals(0, t.cursorY)
    }

    @Test
    fun eraseInLineAndDisplay() {
        val t = term()
        t.feed("abcdef\r\nghijkl$E[1;3H$E[K")
        assertEquals("ab", t.row(0))
        assertEquals("ghijkl", t.row(1))
        t.feed("$E[2;3H$E[1J")
        assertEquals("", t.row(0))
        assertEquals("   jkl", t.row(1).padStart(6))
        t.feed("$E[2J")
        assertEquals("", t.screenText())
    }

    @Test
    fun sgrColorsAndAttributes() {
        val t = term()
        t.feed("$E[1;31mA$E[0mB$E[38;5;200mC$E[48;2;1;2;3mD$E[38:2::10:20:30mE$E[39;49mF")
        val l = t.line(0)
        assertEquals(1, l.fg[0]); assertTrue(l.attr[0] and TermAttr.BOLD != 0)
        assertEquals(TermColor.DEFAULT, l.fg[1]); assertEquals(0, l.attr[1])
        assertEquals(200, l.fg[2])
        assertEquals(TermColor.rgb(1, 2, 3), l.bg[3]); assertEquals(200, l.fg[3])
        assertEquals(TermColor.rgb(10, 20, 30), l.fg[4])
        assertEquals(TermColor.DEFAULT, l.fg[5]); assertEquals(TermColor.DEFAULT, l.bg[5])
        t.feed("$E[91;102mG")
        assertEquals(9, t.line(0).fg[6]); assertEquals(10, t.line(0).bg[6])
    }

    @Test
    fun sgrWithSemicolonRgbDoesNotEatFollowingParams() {
        val t = term()
        t.feed("$E[38;2;5;6;7;1mA")
        assertEquals(TermColor.rgb(5, 6, 7), t.line(0).fg[0])
        assertTrue(t.line(0).attr[0] and TermAttr.BOLD != 0)
    }

    @Test
    fun erasingUsesTheCurrentBackground() {
        val t = term(cols = 4, rows = 2)
        t.feed("$E[44m$E[2J")
        assertEquals(4, t.line(1).bg[3])
    }

    @Test
    fun scrollingFillsTheScrollback() {
        val t = term(cols = 6, rows = 3)
        t.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals("3", t.row(0)); assertEquals("4", t.row(1)); assertEquals("5", t.row(2))
        assertEquals(2, t.scrollbackSize)
        assertEquals("2", t.line(-1).text())
        assertEquals("1", t.line(-2).text())
    }

    @Test
    fun scrollRegionKeepsOutsideRowsAndSkipsTheScrollback() {
        val t = term(cols = 6, rows = 5)
        t.feed("top\r\n1\r\n2\r\n3\r\nbot")
        t.feed("$E[2;4r$E[4;1H\r\n")
        assertEquals("top", t.row(0))
        assertEquals("2", t.row(1))
        assertEquals("3", t.row(2))
        assertEquals("", t.row(3))
        assertEquals("bot", t.row(4))
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun insertAndDeleteLines() {
        val t = term(cols = 6, rows = 4)
        t.feed("a\r\nb\r\nc\r\nd$E[2;1H$E[L")
        assertEquals("a", t.row(0)); assertEquals("", t.row(1)); assertEquals("b", t.row(2)); assertEquals("c", t.row(3))
        t.feed("$E[M")
        assertEquals("b", t.row(1)); assertEquals("c", t.row(2)); assertEquals("", t.row(3))
    }

    @Test
    fun reverseIndexScrollsDownAtTheTop() {
        val t = term(cols = 4, rows = 3)
        t.feed("a\r\nb\r\nc$E[1;1H${E}M")
        assertEquals("", t.row(0)); assertEquals("a", t.row(1)); assertEquals("b", t.row(2))
    }

    @Test
    fun insertAndDeleteCharacters() {
        val t = term(cols = 8, rows = 2)
        t.feed("abcdef$E[1;3H$E[2@")
        assertEquals("ab  cdef", t.row(0))
        t.feed("$E[3P")
        assertEquals("abdef", t.row(0))
        t.feed("$E[1;1H$E[2X")
        assertEquals("  def", t.row(0))
    }

    @Test
    fun alternateScreenRestoresTheMainScreenAndCursor() {
        val t = term()
        t.feed("main$E[?1049h")
        assertTrue(t.altScreen)
        assertEquals("", t.screenText())
        t.feed("alt-text")
        t.feed("$E[?1049l")
        assertFalse(t.altScreen)
        assertEquals("main", t.screenText())
        assertEquals(4, t.cursorX)
        // the alternate screen never feeds the scrollback
        t.feed("$E[?1049h1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7")
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun saveAndRestoreCursor() {
        val t = term()
        t.feed("${E}7ab$E[31m${E}8x")
        assertEquals("xb", t.row(0))
        assertEquals(TermColor.DEFAULT, t.line(0).fg[0])
        t.feed("y") // the rendition was restored too
        assertEquals(TermColor.DEFAULT, t.line(0).fg[1])
    }

    @Test
    fun tabStops() {
        val t = term(cols = 24)
        t.feed("a\tb\tc")
        assertEquals('b'.code, t.at(0, 8))
        assertEquals('c'.code, t.at(0, 16))
        t.feed("$E[Z")
        assertEquals(16, t.cursorX) // CBT moves back to the previous stop
    }

    @Test
    fun wideCharactersTakeTwoCells() {
        val t = term(cols = 8, rows = 2)
        t.feed("a中b")
        assertEquals('中'.code, t.at(0, 1))
        assertTrue(t.line(0).attr[1] and TermAttr.WIDE != 0)
        assertTrue(t.line(0).attr[2] and TermAttr.WIDE_TAIL != 0)
        assertEquals('b'.code, t.at(0, 3))
        assertEquals("a中b", t.row(0))
        assertEquals(4, t.cursorX)
    }

    @Test
    fun overwritingHalfOfAWideCharacterBlanksTheRest() {
        val t = term(cols = 8, rows = 2)
        t.feed("中$E[1;2Hx")
        assertEquals(0, t.at(0, 0))
        assertEquals('x'.code, t.at(0, 1))
        assertEquals(0, t.line(0).attr[0] and TermAttr.WIDE)
    }

    @Test
    fun aWideCharacterNeverStraddlesTheRightMargin() {
        val t = term(cols = 4, rows = 3)
        t.feed("abc中")
        assertEquals("abc", t.row(0))
        assertEquals("中", t.row(1))
    }

    @Test
    fun combiningMarksAreIgnoredAndEmojiAreWide() {
        val t = term(cols = 8, rows = 2)
        t.feed("éx")
        assertEquals('x'.code, t.at(0, 1))
        t.feed("\r\n😀y")
        assertEquals('y'.code, t.at(1, 2))
    }

    @Test
    fun utf8SplitAcrossFeeds() {
        val t = term()
        val bytes = "中é".toByteArray()
        for (b in bytes) t.feed(byteArrayOf(b))
        assertEquals("中é", t.row(0))
    }

    @Test
    fun invalidUtf8BecomesReplacementCharacter() {
        val t = term()
        t.feed(byteArrayOf(0xFF.toByte(), 'a'.code.toByte(), 0xE4.toByte(), 'b'.code.toByte()))
        assertEquals("�a�b", t.row(0))
    }

    @Test
    fun deviceStatusAndAttributeReports() {
        val t = term()
        t.feed("$E[3;4H$E[6n$E[5n$E[c$E[>c")
        assertEquals(listOf("$E[3;4R", "$E[0n", "$E[?62;22c", "$E[>0;115;0c"), replies)
    }

    @Test
    fun oscTitleAndClipboard() {
        val t = term()
        var title = ""
        var clip = ""
        t.onTitle = { title = it }
        t.onClipboard = { clip = it }
        t.feed("$E]0;my title\u0007$E]2;second$E\\$E]52;c;${java.util.Base64.getEncoder().encodeToString("hi 世".toByteArray())}\u0007")
        assertEquals("second", t.title)
        assertEquals("second", title)
        assertEquals("hi 世", clip)
        t.feed("ok")
        assertEquals("ok", t.row(0)) // sequences leave nothing on screen
    }

    @Test
    fun decLineDrawing() {
        val t = term()
        t.feed("$E(0lqk$E(Bq")
        assertEquals("┌─┐q", t.row(0))
    }

    @Test
    fun modesAreTracked() {
        val t = term()
        t.feed("$E[?1h$E[?2004h$E[?1002h$E[?1006h$E[?25l")
        assertTrue(t.appCursorKeys); assertTrue(t.bracketedPaste); assertTrue(t.mouseSgr); assertFalse(t.cursorVisible)
        assertEquals(1002, t.mouseMode)
        t.feed("$E[?1002l$E[?1l$E[?25h")
        assertEquals(0, t.mouseMode); assertFalse(t.appCursorKeys); assertTrue(t.cursorVisible)
    }

    @Test
    fun unknownSequencesAreSwallowed() {
        val t = term()
        t.feed("a$E[?2026h$E[>4;2m$E[ q$E[=1u${E}P+q544e\u001b\\b${E}_Gi=1;OK$E\\c")
        assertEquals("abc", t.row(0))
    }

    @Test
    fun escapeInsideASequenceStartsANewOne() {
        val t = term()
        t.feed("$E[3$E[2;2Hx")
        assertEquals('x'.code, t.at(1, 1))
    }

    @Test
    fun resizeKeepsContentAndClampsTheCursor() {
        val t = term(cols = 10, rows = 4)
        t.feed("abc\r\ndef\r\nghi$E[3;9H")
        t.resize(5, 3)
        assertEquals("abc", t.row(0)); assertEquals("def", t.row(1)); assertEquals("ghi", t.row(2))
        assertEquals(4, t.cursorX)
        assertEquals(2, t.cursorY)
        t.resize(8, 6)
        assertEquals(6, t.rows)
        assertEquals("abc", t.row(0))
    }

    @Test
    fun shrinkingMovesTopLinesToTheScrollbackWhenTheCursorIsLow() {
        val t = term(cols = 6, rows = 4)
        t.feed("1\r\n2\r\n3\r\n4")
        t.resize(6, 2)
        assertEquals("3", t.row(0)); assertEquals("4", t.row(1))
        assertEquals("2", t.line(-1).text())
        assertEquals(1, t.cursorY)
    }

    @Test
    fun resetClearsEverything() {
        val t = term()
        t.feed("abc$E[?1h$E[31m")
        t.reset()
        assertEquals("", t.screenText())
        assertFalse(t.appCursorKeys)
        t.feed("x")
        assertEquals(TermColor.DEFAULT, t.line(0).fg[0])
    }

    @Test
    fun repeatCharacter() {
        val t = term()
        t.feed("ab$E[3b")
        assertEquals("abbbb", t.row(0))
    }

    @Test
    fun screenTextCanIncludeScrollback() {
        val t = term(cols = 6, rows = 2)
        t.feed("1\r\n2\r\n3")
        assertEquals("2\n3", t.screenText())
        assertEquals("1\n2\n3", t.screenText(includeScrollback = true))
    }

    @Test
    fun aRealisticFullScreenRepaint() {
        // roughly what tmux sends when a client attaches
        val t = term(cols = 12, rows = 4)
        t.feed("$E[?1049h$E[22;0;0t$E[1;1H$E[H$E[2J$E[?25l$E[?1000h$E[?1002h$E[?1006h")
        t.feed("$E[1;1Hprompt\$ ls$E[2;1Hfile1  file2$E[4;1H$E[7m status $E[27m$E[1;10H$E[?25h")
        assertEquals("prompt\$ ls", t.row(0))
        assertEquals("file1  file2", t.row(1))
        assertEquals(" status", t.row(3))
        assertTrue(t.line(3).attr[1] and TermAttr.INVERSE != 0)
        assertEquals(9, t.cursorX)
        assertEquals(1002, t.mouseMode)
    }
}
