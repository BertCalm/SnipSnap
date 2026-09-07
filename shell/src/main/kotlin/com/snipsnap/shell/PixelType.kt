package com.snipsnap.shell

import java.awt.Color
import java.awt.Graphics2D
import kotlin.math.max
import kotlin.math.min

/**
 * A 5×7 pixel typeface drawn as filled rectangles — no `java.awt.Font`,
 * so rendering is byte-identical on every machine (fontconfig-less
 * containers included), and chunky pixel type is TapeOS's accent anyway.
 *
 * Covers A–Z, 0–9 and the punctuation kit names can legally carry
 * (`Names.isMpcSafe` allows letters, digits, space, `-` `_` `.` `&` `'`);
 * anything else renders as the box glyph, visibly rather than silently.
 */
object PixelType {

    const val COLS = 5
    const val ROWS = 7

    /**
     * Draw [text] with its glyph-pixel size chosen so the line is
     * [heightPx] tall, shrunk if needed to fit the caller's width when
     * [maxWidthPx] is given. ([x],[y]) is the top-left, or the top-centre
     * when [centered].
     */
    fun draw(
        g: Graphics2D,
        text: String,
        x: Int,
        y: Int,
        heightPx: Int,
        color: Color,
        centered: Boolean = false,
        maxWidthPx: Int = Int.MAX_VALUE,
    ) {
        if (text.isEmpty()) return
        var px = max(1, heightPx / ROWS)
        // A glyph advance is COLS + 1 gap columns.
        val advance = { p: Int -> text.length * (COLS + 1) * p - p }
        while (px > 1 && advance(px) > maxWidthPx) px--
        val width = advance(px)
        var cx = if (centered) x - width / 2 else x
        g.color = color
        for (ch in text) {
            val glyph = GLYPHS[ch.uppercaseChar()] ?: BOX
            for (r in 0 until ROWS) {
                val row = glyph[r]
                for (c in 0 until COLS) {
                    if (row[c] == '#') g.fillRect(cx + c * px, y + r * px, px, px)
                }
            }
            cx += (COLS + 1) * px
        }
    }

    /** Advance width in pixels for [text] at glyph-pixel size [px]. */
    fun width(text: String, px: Int): Int =
        if (text.isEmpty()) 0 else text.length * (COLS + 1) * px - px

    /** Pick the largest glyph-pixel size that fits both bounds. */
    fun fit(text: String, maxWidthPx: Int, maxHeightPx: Int): Int {
        var px = max(1, maxHeightPx / ROWS)
        while (px > 1 && width(text, px) > maxWidthPx) px--
        return px
    }

    private val BOX = arrayOf(
        "#####",
        "#...#",
        "#...#",
        "#...#",
        "#...#",
        "#...#",
        "#####",
    )

    private val GLYPHS: Map<Char, Array<String>> = mapOf(
        'A' to arrayOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to arrayOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to arrayOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to arrayOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
        'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
        'J' to arrayOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to arrayOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
        'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to arrayOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to arrayOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to arrayOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
        'X' to arrayOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to arrayOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", "#####"),
        '2' to arrayOf(".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####"),
        '3' to arrayOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
        '4' to arrayOf("...##", "..#.#", ".#..#", "#...#", "#####", "....#", "....#"),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
        ' ' to arrayOf(".....", ".....", ".....", ".....", ".....", ".....", "....."),
        '-' to arrayOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '_' to arrayOf(".....", ".....", ".....", ".....", ".....", ".....", "#####"),
        '.' to arrayOf(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
        '\'' to arrayOf("..#..", "..#..", ".....", ".....", ".....", ".....", "....."),
        '&' to arrayOf(".##..", "#..#.", "#.#..", ".#...", "#.#.#", "#..#.", ".##.#"),
    )
}
