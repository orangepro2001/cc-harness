package com.cchharness.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.cchharness.app.ui.TerminalPalette
import com.cchharness.app.ui.TerminalView
import com.cchharness.core.term.TermColor
import com.cchharness.core.term.TerminalEmulator
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewTest {
    private val E = "\u001b"
    private val typed = ArrayList<String>()

    private fun view(emu: TerminalEmulator? = null, w: Int = 1080, h: Int = 1600): TerminalView {
        val v = TerminalView(ApplicationProvider.getApplicationContext())
        v.onInput = { typed += it }
        v.emulator = emu
        v.layout(0, 0, w, h)
        return v
    }

    private fun bitmap(v: TerminalView): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    @Test
    fun sizeFollowsTheViewAndFont() {
        val sizes = ArrayList<Pair<Int, Int>>()
        val v = TerminalView(ApplicationProvider.getApplicationContext())
        v.onResize = { c, r -> sizes += c to r }
        v.layout(0, 0, 1080, 1600)
        val (c1, r1) = sizes.last()
        assertTrue(c1 in 40..120 && r1 in 20..90, "grid ${c1}x$r1")
        v.fontSizeSp = 20
        val (c2, r2) = sizes.last()
        assertTrue(c2 < c1 && r2 < r1, "a larger font gives a smaller grid: ${c1}x$r1 -> ${c2}x$r2")
    }

    @Test
    fun softKeyboardTextAndDeleteReachTheTerminal() {
        val v = view(TerminalEmulator(40, 10))
        val ic = v.onCreateInputConnection(EditorInfo())
        ic.commitText("ls -la", 1)
        ic.deleteSurroundingText(1, 0)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf("ls -la", "\u007f", "\r"), typed)
    }

    @Test
    fun compositionIsSentOnceItIsCommitted() {
        val v = view(TerminalEmulator(40, 10))
        val ic = v.onCreateInputConnection(EditorInfo())
        ic.setComposingText("nihao", 1) // pinyin still being typed
        assertTrue(typed.isEmpty())
        ic.commitText("你好", 1)
        assertEquals(listOf("你好"), typed)
    }

    @Test
    fun stickyCtrlAppliesToTheNextCharacterOnly() {
        val v = view(TerminalEmulator(40, 10))
        var ctrl = true
        v.modifiers = { ctrl to false }
        v.onModifiersUsed = { ctrl = false }
        val ic = v.onCreateInputConnection(EditorInfo())
        ic.commitText("c", 1)
        ic.commitText("c", 1)
        assertEquals(listOf("\u0003", "c"), typed)
    }

    @Test
    fun arrowsFollowApplicationCursorMode() {
        val emu = TerminalEmulator(40, 10)
        val v = view(emu)
        val ic = v.onCreateInputConnection(EditorInfo())
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        emu.feed("$E[?1h")
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(listOf("$E[A", "${E}OA"), typed)
    }

    @Test
    fun hardwareKeysWithCtrl() {
        val v = view(TerminalEmulator(40, 10))
        v.onKeyDown(KeyEvent.KEYCODE_D, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        assertEquals(listOf("\u0004"), typed)
    }

    @Test
    fun drawsColoursCursorAndWideCharacters() {
        val emu = TerminalEmulator(30, 6)
        emu.feed("${E}[31mred$E[0m plain\r\n$E[48;2;0;120;0mgreen bg$E[0m\r\n中文 ok")
        val v = view(emu)
        val b = bitmap(v)
        // the screen is not just the background, and uses at least the red and green cells
        val colors = HashSet<Int>()
        for (x in 0 until b.width step 3) for (y in 0 until b.height step 3) colors += b.getPixel(x, y)
        assertTrue(TerminalPalette.BACKGROUND in colors)
        assertTrue(colors.any { Color(it).let { c -> c.g > 100 && c.r < 40 && c.b < 40 } }, "green background cells")
        assertTrue(colors.any { it == TerminalPalette.CURSOR }, "cursor block")
        assertTrue(colors.size > 5)
        File("build/screenshots").mkdirs()
        File("build/screenshots/terminal-view.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun drawsPowerlineIconsBoxBordersAndBraille() {
        val emu = TerminalEmulator(44, 14)
        val bg1 = "$E[48;5;238;38;5;252m"
        val sep1 = "$E[38;5;238;48;5;31m"
        val seg2 = "$E[48;5;31;38;5;255m"
        val sep2 = "$E[38;5;31;49m"
        emu.feed("$bg1 \uF31B ${sep1}\uE0B0${seg2} ~/d/cc-harness ${sep2}\uE0B0$E[0m\r\n")
        emu.feed("$E[48;5;34;38;5;16m \uE0A0 main ${"$E[38;5;34;49m"}\uE0B4$E[0m  $E[38;5;34m\uE0B6$E[48;5;34;38;5;16m  3s $E[0m\r\n")
        emu.feed("\uF115 android  \uF15B api.go  \uF023 secret  \uF489 term  \uDB80\uDC0A\r\n\r\n")
        emu.feed("╭──────────────────────────────────╮\r\n")
        emu.feed("│ > 帮我看一下这个错误               │\r\n")
        emu.feed("╰──────────────────────────────────╯\r\n")
        emu.feed("┌─┬─┐ ╔═╦═╗ ┏━┳━┓ ▀▄█▌▐ ░▒▓ ⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏\r\n")
        emu.feed("├─┼─┤ ╠═╬═╣ ┣━╋━┫ ▖▗▘▙▚▛▜▝▞▟\r\n")
        emu.feed("└─┴─┘ ╚═╩═╝ ┗━┻━┛ ╱╲╳\r\n")
        val v = view(emu, w = 1080, h = 900)
        val b = bitmap(v)
        File("build/screenshots").mkdirs()
        File("build/screenshots/terminal-glyphs.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val colors = HashSet<Int>()
        for (x in 0 until b.width step 2) for (y in 0 until b.height step 2) colors += b.getPixel(x, y)
        assertTrue(colors.size > 8)
    }

    private class Color(argb: Int) {
        val r = argb shr 16 and 255
        val g = argb shr 8 and 255
        val b = argb and 255
    }

    @Test
    fun scrollingTheHistoryAndMouseWheel() {
        val emu = TerminalEmulator(20, 5)
        for (i in 1..30) emu.feed("line $i\r\n")
        val v = view(emu)
        assertEquals(0, v.scrollOffset)
        v.scrollToBottom()
        assertTrue(emu.scrollbackSize > 20)
        // without mouse mode a drag scrolls the local history
        drag(v, 200f, 1000f)
        assertTrue(v.scrollOffset > 0)
        val before = v.scrollOffset
        v.scrollToBottom()
        assertEquals(0, v.scrollOffset)
        assertTrue(before > 0)

        // with mouse reporting (tmux) the drag becomes wheel events instead
        emu.feed("$E[?1002h$E[?1006h")
        typed.clear()
        drag(v, 200f, 1000f)
        assertTrue(typed.isNotEmpty() && typed.joinToString("").contains("$E[<64;"), typed.toString())
    }

    /** A finger drag from [fromY] to [toY]; moving down (toY > fromY) reveals older content. */
    private fun drag(v: TerminalView, fromY: Float, toY: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, y: Float, at: Long) = android.view.MotionEvent.obtain(t, at, action, 300f, y, 0)
        v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, fromY, t))
        val steps = 20
        for (i in 1..steps) v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * i / steps, t + i * 10L))
        v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, toY, t + 600))
    }
}
