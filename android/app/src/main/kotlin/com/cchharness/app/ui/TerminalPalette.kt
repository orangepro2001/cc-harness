package com.cchharness.app.ui

import com.cchharness.core.term.TermColor

/** The terminal is dark in both app themes, as terminal programs assume; the tones match the app's warm palette. */
object TerminalPalette {
    const val BACKGROUND = 0xFF1F1E1D.toInt()
    const val FOREGROUND = 0xFFECE9E0.toInt()
    const val CURSOR = 0xFFD97757.toInt()

    private val ansi = intArrayOf(
        0xFF3B3A37.toInt(), 0xFFE8756B.toInt(), 0xFF6CBF88.toInt(), 0xFFE0A83C.toInt(),
        0xFF7FB0DC.toInt(), 0xFFC792EA.toInt(), 0xFF5FC5C0.toInt(), 0xFFD8D5CB.toInt(),
        0xFF6B6A65.toInt(), 0xFFF29C93.toInt(), 0xFF8FD6A4.toInt(), 0xFFF0C265.toInt(),
        0xFFA1C8EC.toInt(), 0xFFDDB0F5.toInt(), 0xFF8ADBD6.toInt(), 0xFFFAF9F5.toInt(),
    )

    private val table: IntArray = IntArray(256) { i ->
        when {
            i < 16 -> ansi[i]
            i < 232 -> {
                val n = i - 16
                fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
                0xFF000000.toInt() or (level(n / 36) shl 16) or (level(n / 6 % 6) shl 8) or level(n % 6)
            }
            else -> {
                val g = 8 + (i - 232) * 10
                0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
            }
        }
    }

    /** ARGB for an emulator colour; [bold] brightens the eight base colours like a classic terminal. */
    fun resolve(color: Int, default: Int, bold: Boolean = false): Int = when {
        color == TermColor.DEFAULT -> default
        TermColor.isIndexed(color) -> table[if (bold && color < 8) color + 8 else color]
        TermColor.isRgb(color) -> 0xFF000000.toInt() or TermColor.rgbValue(color)
        else -> default
    }
}
