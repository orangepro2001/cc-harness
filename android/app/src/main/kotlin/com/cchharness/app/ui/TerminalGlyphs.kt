package com.cchharness.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Terminal characters that must be drawn as geometry rather than taken from a font: a font's
 * line glyphs never meet the cell edges exactly, which leaves gaps in box borders (Claude Code's
 * input box, tmux) and between Powerline segments.
 *
 *  - box drawing U+2500..257F (light / heavy / double, rounded corners, diagonals)
 *  - block elements U+2580..259F, braille U+2800..28FF (spinners)
 *  - Powerline separators U+E0B0..E0BF
 */
class TerminalGlyphs {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    fun draw(canvas: Canvas, cp: Int, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        when (cp) {
            in 0x2500..0x257F -> box(canvas, cp, l, t, r, b)
            in 0x2580..0x259F -> block(canvas, cp, l, t, r, b)
            in 0x2800..0x28FF -> braille(canvas, cp - 0x2800, l, t, r, b)
            in 0xE0B0..0xE0BF -> powerline(canvas, cp, l, t, r, b)
        }
    }

    // ---- box drawing -------------------------------------------------------------------

    private fun box(canvas: Canvas, cp: Int, l: Float, t: Float, r: Float, b: Float) {
        val w = r - l
        val h = b - t
        val light = max(1f, (h / 14f).roundToInt().toFloat())
        val heavy = light * 2
        // odd-width strokes sit on a half pixel so they stay crisp
        fun snap(center: Float) = if (light.toInt() % 2 == 1) floor(center) + 0.5f else center.roundToInt().toFloat()
        val cx = snap(l + w / 2f)
        val cy = snap(t + h / 2f)
        when (cp) {
            in 0x256D..0x2570 -> { arc(canvas, cp, l, t, r, b, cx, cy, light); return }
            in 0x2571..0x2573 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = light
                if (cp != 0x2572) canvas.drawLine(r, t, l, b, paint)
                if (cp != 0x2571) canvas.drawLine(l, t, r, b, paint)
                paint.style = Paint.Style.FILL
                return
            }
        }
        val v = BOX[cp - 0x2500]
        val wl = v and 3
        val wr = v shr 2 and 3
        val wu = v shr 4 and 3
        val wd = v shr 6 and 3
        val g = light // double strokes: two light lines, g either side of the centre line
        fun tk(weight: Int) = when (weight) { 0 -> 0f; 2 -> heavy; else -> light }
        fun fillRect(x0: Float, y0: Float, x1: Float, y1: Float) =
            canvas.drawRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), paint)
        // the arms reach past the centre by half the thickness of the crossing arm, so corners are square
        val crossH = max(tk(wu), tk(wd)) / 2
        val crossV = max(tk(wl), tk(wr)) / 2

        for (toRight in booleanArrayOf(false, true)) {
            val weight = if (toRight) wr else wl
            if (weight == 0) continue
            val opposite = if (toRight) wl else wr
            val edge = if (toRight) r else l
            val dir = if (toRight) 1f else -1f
            if (weight == 3) {
                for (side in intArrayOf(-1, 1)) { // -1 upper line, +1 lower line
                    val crossing = (if (side < 0) wu else wd) == 3
                    val end = when {
                        crossing -> cx + dir * g // stops at the crossing double line
                        opposite != 0 -> cx // runs straight through
                        else -> cx - dir * g // outer corner
                    }
                    val y = cy + side * g
                    fillRect(edge, y - light / 2, end, y + light / 2)
                }
            } else {
                val th = tk(weight)
                fillRect(edge, cy - th / 2, cx - dir * crossH, cy + th / 2)
            }
        }
        for (down in booleanArrayOf(false, true)) {
            val weight = if (down) wd else wu
            if (weight == 0) continue
            val opposite = if (down) wu else wd
            val edge = if (down) b else t
            val dir = if (down) 1f else -1f
            if (weight == 3) {
                for (side in intArrayOf(-1, 1)) { // -1 left line, +1 right line
                    val crossing = (if (side < 0) wl else wr) == 3
                    val end = when {
                        crossing -> cy + dir * g
                        opposite != 0 -> cy
                        else -> cy - dir * g
                    }
                    val x = cx + side * g
                    fillRect(x - light / 2, edge, x + light / 2, end)
                }
            } else {
                val th = tk(weight)
                fillRect(cx - th / 2, edge, cx + th / 2, cy - dir * crossV)
            }
        }
    }

    /** Rounded corners: a straight stub to the cell edge on each side and a quarter curve between them. */
    private fun arc(canvas: Canvas, cp: Int, l: Float, t: Float, r: Float, b: Float, cx: Float, cy: Float, light: Float) {
        val rad = min(r - l, b - t) / 2f
        // which edges this corner connects: 256D down+right, 256E down+left, 256F up+left, 2570 up+right
        val down = cp == 0x256D || cp == 0x256E
        val right = cp == 0x256D || cp == 0x2570
        val vy = if (down) b else t
        val hx = if (right) r else l
        val sy = if (down) 1f else -1f
        val sx = if (right) 1f else -1f
        path.reset()
        path.moveTo(cx, vy)
        path.lineTo(cx, cy + sy * rad)
        path.quadTo(cx, cy, cx + sx * rad, cy)
        path.lineTo(hx, cy)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = light
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    // ---- block elements ----------------------------------------------------------------

    private fun block(canvas: Canvas, cp: Int, l: Float, t: Float, r: Float, b: Float) {
        val w = r - l
        val h = b - t
        fun box(x0: Float, y0: Float, x1: Float, y1: Float) = canvas.drawRect(l + x0 * w, t + y0 * h, l + x1 * w, t + y1 * h, paint)
        when (cp) {
            0x2580 -> box(0f, 0f, 1f, 0.5f)
            in 0x2581..0x2588 -> box(0f, 1f - (cp - 0x2580) / 8f, 1f, 1f)
            in 0x2589..0x258F -> box(0f, 0f, (0x2590 - cp) / 8f, 1f)
            0x2590 -> box(0.5f, 0f, 1f, 1f)
            in 0x2591..0x2593 -> {
                paint.alpha = (paint.alpha * (cp - 0x2590) / 4f).toInt()
                box(0f, 0f, 1f, 1f)
            }
            0x2594 -> box(0f, 0f, 1f, 0.125f)
            0x2595 -> box(0.875f, 0f, 1f, 1f)
            in 0x2596..0x259F -> {
                val q = QUADRANTS[cp - 0x2596]
                if (q and 1 != 0) box(0f, 0f, 0.5f, 0.5f)
                if (q and 2 != 0) box(0.5f, 0f, 1f, 0.5f)
                if (q and 4 != 0) box(0f, 0.5f, 0.5f, 1f)
                if (q and 8 != 0) box(0.5f, 0.5f, 1f, 1f)
            }
        }
    }

    // ---- braille -----------------------------------------------------------------------

    private fun braille(canvas: Canvas, bits: Int, l: Float, t: Float, r: Float, b: Float) {
        val w = r - l
        val h = b - t
        val rad = max(1f, min(w / 5.5f, h / 9f))
        // bit -> (column, row)
        val cols = intArrayOf(0, 0, 0, 1, 1, 1, 0, 1)
        val rows = intArrayOf(0, 1, 2, 0, 1, 2, 3, 3)
        for (i in 0 until 8) {
            if (bits and (1 shl i) == 0) continue
            canvas.drawCircle(l + w * (0.3f + 0.4f * cols[i]), t + h * (0.125f + 0.25f * rows[i]), rad, paint)
        }
    }

    // ---- Powerline ---------------------------------------------------------------------

    private fun powerline(canvas: Canvas, cp: Int, l: Float, t: Float, r: Float, b: Float) {
        val w = r - l
        val h = b - t
        val stroke = max(1f, (h / 14f).roundToInt().toFloat())
        path.reset()
        var outline = false
        when (cp) {
            0xE0B0 -> { path.moveTo(l, t); path.lineTo(r, t + h / 2); path.lineTo(l, b); path.close() }
            0xE0B1 -> { outline = true; path.moveTo(l, t); path.lineTo(r, t + h / 2); path.lineTo(l, b) }
            0xE0B2 -> { path.moveTo(r, t); path.lineTo(l, t + h / 2); path.lineTo(r, b); path.close() }
            0xE0B3 -> { outline = true; path.moveTo(r, t); path.lineTo(l, t + h / 2); path.lineTo(r, b) }
            0xE0B4, 0xE0B5 -> { // right half circle
                outline = cp == 0xE0B5
                rect.set(l - w, t, r, b)
                if (outline) path.arcTo(rect, -90f, 180f, true) else { path.arcTo(rect, -90f, 180f, true); path.close() }
            }
            0xE0B6, 0xE0B7 -> { // left half circle
                outline = cp == 0xE0B7
                rect.set(l, t, r + w, b)
                if (outline) path.arcTo(rect, 90f, 180f, true) else { path.arcTo(rect, 90f, 180f, true); path.close() }
            }
            0xE0B8 -> { path.moveTo(l, t); path.lineTo(l, b); path.lineTo(r, b); path.close() }
            0xE0B9 -> { outline = true; path.moveTo(l, t); path.lineTo(r, b) }
            0xE0BA -> { path.moveTo(r, t); path.lineTo(r, b); path.lineTo(l, b); path.close() }
            0xE0BB -> { outline = true; path.moveTo(r, t); path.lineTo(l, b) }
            0xE0BC -> { path.moveTo(l, t); path.lineTo(r, t); path.lineTo(l, b); path.close() }
            0xE0BD -> { outline = true; path.moveTo(r, t); path.lineTo(l, b) }
            0xE0BE -> { path.moveTo(l, t); path.lineTo(r, t); path.lineTo(r, b); path.close() }
            0xE0BF -> { outline = true; path.moveTo(l, t); path.lineTo(r, b) }
        }
        if (outline) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
        }
        canvas.save()
        canvas.clipRect(l, t, r, b)
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

    companion object {
        fun isCustom(cp: Int) = cp in 0x2500..0x259F || cp in 0x2800..0x28FF || cp in 0xE0B0..0xE0BF

        /** Private-use code points: Nerd Font icons, drawn with the bundled symbols font. */
        fun isPrivateUse(cp: Int) = cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD

        // quadrant bits: 1 upper-left, 2 upper-right, 4 lower-left, 8 lower-right
        private val QUADRANTS = intArrayOf(4, 8, 1, 1 or 4 or 8, 1 or 8, 1 or 2 or 4, 1 or 2 or 8, 2, 2 or 4, 2 or 4 or 8)

        /** Per U+2500..257F: line weights (0 none, 1 light, 2 heavy, 3 double) packed as left | right<<2 | up<<4 | down<<6. */
        private val BOX = intArrayOf(
        0x05, 0x0A, 0x50, 0xA0, 0x05, 0x0A, 0x50, 0xA0, 0x05, 0x0A, 0x50, 0xA0, 0x44, 0x48, 0x84,
        0x88, 0x41, 0x42, 0x81, 0x82, 0x14, 0x18, 0x24, 0x28, 0x11, 0x12, 0x21, 0x22, 0x54, 0x58,
        0x64, 0x94, 0xA4, 0x68, 0x98, 0xA8, 0x51, 0x52, 0x61, 0x91, 0xA1, 0x62, 0x92, 0xA2, 0x45,
        0x46, 0x49, 0x4A, 0x85, 0x86, 0x89, 0x8A, 0x15, 0x16, 0x19, 0x1A, 0x25, 0x26, 0x29, 0x2A,
        0x55, 0x56, 0x59, 0x5A, 0x65, 0x95, 0xA5, 0x66, 0x69, 0x96, 0x99, 0x6A, 0x9A, 0xA6, 0xA9,
        0xAA, 0x05, 0x0A, 0x50, 0xA0, 0x0F, 0xF0, 0x4C, 0xC4, 0xCC, 0x43, 0xC1, 0xC3, 0x1C, 0x34,
        0x3C, 0x13, 0x31, 0x33, 0x5C, 0xF4, 0xFC, 0x53, 0xF1, 0xF3, 0x4F, 0xC5, 0xCF, 0x1F, 0x35,
        0x3F, 0x5F, 0xF5, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x10, 0x04, 0x40,
        0x02, 0x20, 0x08, 0x80, 0x09, 0x90, 0x06, 0x60
        )
    }
}
