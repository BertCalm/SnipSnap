package com.snipsnap.shell

import kotlin.math.cos
import kotlin.math.sin

/**
 * The tile styles, one function each, all with the same shape: paint the
 * field for [KitArt.draw] inside a [size]-square tile whose ground is
 * already down, then set [label] inside [zone]. Nothing here knows which
 * platform is painting; everything goes through [ArtCanvas].
 */
internal object TileStyles {

    /** How many petals the envelope is wrapped into; a one-shot's decay becomes one petal's falling edge. */
    const val BLOOM_PETALS = 6

    /**
     * Bloom: the seed's loudness envelope wrapped round a circle as
     * [BLOOM_PETALS] petals. Each petal is a cosine body (so it is always a
     * rounded form, never a spike) whose reach is the envelope in decibels
     * at its place round the circle: the hit is the first petal clockwise
     * from the top and the tail the last, so a drum's bloom diminishes and
     * a sustained sound's stays full. Filled with a
     * radial two-tone gradient, a hole of ground in the middle where the
     * name sits in condensed caps, on two lines when it has a break.
     */
    fun bloom(canvas: ArtCanvas, size: Int, palette: Palette, seed: TileSound?, label: String, zone: KitArt.Box) {
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.40f
        val petal = petals(cx, cy, r, seed?.envelope(90) ?: FloatArray(90), BLOOM_PETALS, n = 360, centredAtTop = false)
        canvas.fillPath(petal, ArtFill.Radial(cx, cy, r, palette.field1, palette.field2))
        canvas.strokePath(petal, size * 0.008f, ArtFill.Solid(palette.ink, 0.35f))
        canvas.fillCircle(cx, cy, r * 0.42f, ArtFill.Solid(palette.ground))

        holeName(canvas, palette, label, zone, cx, cy)
    }

    /**
     * A flower outline: [count] petals round ([cx], [cy]), each a cosine
     * body (always a rounded form, never a spike) reaching between half of
     * [r] and all of it by the envelope at that petal's place round the
     * circle, clockwise from the top: the hit is the first petal, the tail
     * the last. Read in decibels and averaged over the petal's own span.
     * [centredAtTop] turns the flower so the first petal's tip points up.
     */
    fun petals(cx: Float, cy: Float, r: Float, env: FloatArray, count: Int, n: Int, centredAtTop: Boolean): ArtPath {
        val bins = env.size
        val perPetal = n / count
        val reachOf = FloatArray(count) { p ->
            val from = bins * p / count
            val to = maxOf(from + 1, bins * (p + 1) / count)
            var s = 0f
            for (b in from until to) s += env[b]
            reach(s / (to - from))
        }
        val turn = if (centredAtTop) -Math.PI / count else 0.0
        val xs = FloatArray(perPetal * count)
        val ys = FloatArray(perPetal * count)
        for (i in xs.indices) {
            val a = (i.toDouble() / xs.size) * 2 * Math.PI - Math.PI / 2 + turn
            // d: 0 at the petal's centre, 1 at its edges.
            val k = i % perPetal
            val d = kotlin.math.abs(k - perPetal / 2f) / (perPetal / 2f)
            val body = 0.5f + 0.5f * cos(Math.PI * d).toFloat()
            val rr = r * (0.50f + 0.50f * body * (0.60f + 0.40f * reachOf[i / perPetal]))
            xs[i] = cx + (rr * cos(a)).toFloat()
            ys[i] = cy + (rr * sin(a)).toFloat()
        }
        return ArtPath.polygon(xs, ys)
    }

    /**
     * The name in Bloom's hole: one line when it fits at a readable size,
     * else two lines split at the space, underscore or hyphen nearest the middle, each fitted to
     * the zone's width and the pair to its height.
     */
    private fun holeName(canvas: ArtCanvas, palette: Palette, label: String, zone: KitArt.Box, cx: Float, cy: Float) {
        val text = label.uppercase()
        val font = ArtFont.OSWALD_BOLD
        val oneLineMax = zone.h * 0.6f
        val single = TileType.fitText(canvas, text, font, zone.w.toFloat(), oneLineMax)
        val split = text.indices.filter { text[it] in " _-" }.minByOrNull { kotlin.math.abs(it - text.length / 2) }
        if (split == null || (single.first == text && single.second >= oneLineMax * 0.6f)) {
            canvas.drawText(single.first, font, single.second, cx, cy + single.second * 0.36f, TextAnchor.MIDDLE, palette.ink)
            return
        }
        val lineMax = zone.h * 0.38f
        val top = text.substring(0, split).trimEnd()
        val bottom = text.substring(split + 1).trimStart()
        val size = minOf(
            TileType.fitText(canvas, top, font, zone.w.toFloat(), lineMax).second,
            TileType.fitText(canvas, bottom, font, zone.w.toFloat(), lineMax).second,
        )
        val topText = TileType.fitText(canvas, top, font, zone.w.toFloat(), size).first
        val bottomText = TileType.fitText(canvas, bottom, font, zone.w.toFloat(), size).first
        canvas.drawText(topText, font, size, cx, cy - size * 0.18f, TextAnchor.MIDDLE, palette.ink)
        canvas.drawText(bottomText, font, size, cx, cy + size * 0.92f, TextAnchor.MIDDLE, palette.ink)
    }

    /** How many ridge lines Ridges stacks, front to back. */
    const val RIDGE_COUNT = 9

    /**
     * Ridges: the seed sliced into [RIDGE_COUNT] equal windows, front to
     * back, each drawn as a ridge line: that window's own peak shape under
     * a hann taper (so every line rises in the middle and settles at the
     * edges), as tall as the window is loud in decibels. The front ridge is the attack, tallest and in
     * the first field tone; later windows stack behind it, smaller and
     * fading into the ground. Each ridge fills ground below itself so a
     * nearer one hides what is behind. The name sits top-left in the
     * italic serif, as typed.
     */
    fun ridges(canvas: ArtCanvas, size: Int, palette: Palette, seed: TileSound?, label: String, zone: KitArt.Box) {
        val m = KitArt.margin(size).toFloat()
        val left = m
        val right = size - m
        val points = 96
        val backBase = size * 0.36f
        val frontBase = size * 0.84f
        for (k in RIDGE_COUNT - 1 downTo 0) {
            // k = 0 is the front (the attack), k = RIDGE_COUNT - 1 the back.
            val depth = k / (RIDGE_COUNT - 1f)
            val base = frontBase - (frontBase - backBase) * depth
            val amp = size * 0.13f * (1f - 0.55f * depth)
            val raw = seed?.slice(k, RIDGE_COUNT, points) ?: FloatArray(points)
            // Each ridge shows its own window's shape (scaled by its own maximum) at a height set
            // by how loud that window is, so a quiet tail still draws a ridge rather than a rule.
            val smooth = FloatArray(points) { i ->
                (kotlin.math.abs(raw[maxOf(0, i - 1)]) + kotlin.math.abs(raw[i]) + kotlin.math.abs(raw[minOf(points - 1, i + 1)])) / 3f
            }
            val top = smooth.max()
            val level = reach(raw.maxOf { kotlin.math.abs(it) })
            val height = amp * (0.35f + 0.65f * level)
            val xs = FloatArray(points + 2)
            val ys = FloatArray(points + 2)
            for (i in 0 until points) {
                val v = if (top > 1e-6f) reach(smooth[i] / top) else 0f
                val taper = 0.5f - 0.5f * cos(2 * Math.PI * i / (points - 1)).toFloat()
                xs[i] = left + (right - left) * i / (points - 1)
                ys[i] = base - height * (0.25f + 0.75f * v) * taper
            }
            // Close down to the next ridge's baseline so this one hides what is behind it.
            xs[points] = right
            ys[points] = base + (frontBase - backBase) / (RIDGE_COUNT - 1)
            xs[points + 1] = left
            ys[points + 1] = ys[points]
            canvas.fillPath(ArtPath.polygon(xs, ys), ArtFill.Solid(palette.ground))
            val line = ArtPath.polyline(xs.copyOf(points), ys.copyOf(points))
            val tone = if (k == 0) palette.field1 else palette.ink
            canvas.strokePath(line, size * 0.007f, ArtFill.Solid(tone, 1f - 0.75f * depth))
        }
        val (text, fitted) = TileType.fitText(canvas, label, ArtFont.PLAYFAIR_BOLD_ITALIC, zone.w.toFloat(), minOf(size * 0.12f, zone.h * 0.75f))
        canvas.drawText(text, ArtFont.PLAYFAIR_BOLD_ITALIC, fitted, zone.x.toFloat(), zone.y + fitted * 0.95f, TextAnchor.START, palette.ink)
    }

    /** The dot grid's side, in dots. */
    const val FIELD_GRID = 12

    /**
     * Field: a [FIELD_GRID]-square dot grid read row by row as the seed's
     * envelope, each dot's size and opacity its bin's loudness in
     * decibels, the field tones blending left to right. A soft band of
     * ground across the middle carries the name in the wide geometric face.
     */
    fun field(canvas: ArtCanvas, size: Int, palette: Palette, seed: TileSound?, label: String, zone: KitArt.Box) {
        val m = KitArt.margin(size).toFloat()
        val n = FIELD_GRID
        val cell = (size - 2 * m) / n
        val env = seed?.envelope(n * n) ?: FloatArray(n * n)
        for (row in 0 until n) for (col in 0 until n) {
            val e = reach(env[row * n + col])
            val cx = m + cell * (col + 0.5f)
            val cy = m + cell * (row + 0.5f)
            val tone = lerp(palette.field1, palette.field2, col / (n - 1f))
            canvas.fillCircle(cx, cy, cell * (0.10f + 0.34f * e), ArtFill.Solid(tone, 0.35f + 0.65f * e))
        }
        val bandTop = size * 0.40f
        val bandH = size * 0.20f
        canvas.fillPath(
            ArtPath.polygon(floatArrayOf(0f, size.toFloat(), size.toFloat(), 0f), floatArrayOf(bandTop, bandTop, bandTop + bandH, bandTop + bandH)),
            ArtFill.Solid(palette.ground, 0.88f),
        )
        val (text, fitted) = TileType.fitText(canvas, label.uppercase(), ArtFont.UNBOUNDED_EXTRABOLD, zone.w.toFloat(), minOf(size * 0.10f, zone.h * 0.7f))
        canvas.drawText(text, ArtFont.UNBOUNDED_EXTRABOLD, fitted, size / 2f, size * 0.50f + fitted * 0.36f, TextAnchor.MIDDLE, palette.ink)
    }

    /**
     * Ribbon: the seed's attack, low-passed so a bell's partials cannot make
     * it jagged ([TileSound.attackSmooth]), as one smooth stroke across the tile, drawn
     * three times — a wide faint glow in the second field tone, a narrower
     * one in the first, then the line itself running from the first tone
     * to the second. The name sits in the bottom strip, wide-tracked.
     */
    fun ribbon(canvas: ArtCanvas, size: Int, palette: Palette, seed: TileSound?, label: String, zone: KitArt.Box) {
        val m = KitArt.margin(size).toFloat()
        val points = 64
        val pts = seed?.attackSmooth(points) ?: FloatArray(points)
        val xs = FloatArray(points) { m + (size - 2 * m) * it / (points - 1) }
        val ys = FloatArray(points) { size * 0.42f - pts[it] * size * 0.24f }
        // Quadratic through the midpoints: each sample is a control point, so the line never kinks.
        val path = ArtPath().moveTo(xs[0], ys[0])
        for (i in 1 until points - 1) path.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2f, (ys[i] + ys[i + 1]) / 2f)
        path.lineTo(xs[points - 1], ys[points - 1])
        canvas.strokePath(path, size * 0.10f, ArtFill.Solid(palette.field2, 0.18f))
        canvas.strokePath(path, size * 0.05f, ArtFill.Solid(palette.field1, 0.35f))
        canvas.strokePath(path, size * 0.022f, ArtFill.Linear(m, 0f, size - m, 0f, palette.field1, palette.field2))
        val tracking = 0.18f
        val (text, fitted) = TileType.fitTrackedText(canvas, label.uppercase(), ArtFont.SPACE_GROTESK_BOLD, zone.w.toFloat(), minOf(size * 0.08f, zone.h * 0.6f), tracking)
        TileType.tracked(canvas, text, ArtFont.SPACE_GROTESK_BOLD, fitted, zone.x.toFloat(), zone.y + zone.h * 0.72f, tracking, palette.ink)
    }

    // ---- the four redrawn: each draws every pad of the seed kit, class-coloured ----

    /** Where the wheel (Slices) and the orbits (Rings) sit, as shares of the tile. */
    const val WHEEL_CY = 0.42f
    const val WHEEL_R = 0.34f

    /**
     * Waveform: every pad's sound end to end as one river. The river's
     * banks are the joined sound's loudness per column (smoothed, so it
     * flows rather than steps), never thinner than a hairline so a gap
     * between hits is a narrows, not a break; each pad's reach is filled
     * with a gradient from its class colour to the next pad's, so the
     * colours blend along the water.
     */
    fun river(canvas: ArtCanvas, size: Int, palette: Palette, pads: List<PadSound>, label: String, zone: KitArt.Box) {
        if (pads.isNotEmpty()) {
            val m = KitArt.margin(size).toFloat()
            val cols = 240
            val total = pads.sumOf { it.sound.frames }
            val joined = FloatArray(total)
            var at = 0
            val starts = IntArray(pads.size)
            pads.forEachIndexed { i, p ->
                starts[i] = at
                p.sound.mono.copyInto(joined, at)
                at += p.sound.frames
            }
            val raw = FloatArray(cols)
            for (c in 0 until cols) {
                val a = (total.toLong() * c / cols).toInt()
                val b = (total.toLong() * (c + 1) / cols).toInt().coerceAtLeast(a + 1).coerceAtMost(total)
                var best = 0f
                for (i in a until b) best = maxOf(best, kotlin.math.abs(joined[i]))
                raw[c] = best
            }
            val top = raw.max().coerceAtLeast(1e-6f)
            val level = FloatArray(cols) { c ->
                var s = 0f
                var n = 0
                for (j in maxOf(0, c - 4)..minOf(cols - 1, c + 4)) { s += reach(raw[j] / top); n++ }
                s / n
            }
            val cy = size * WHEEL_CY
            val amp = size * 0.27f
            val hair = size * 0.006f
            val x = FloatArray(cols + 1) { m + (size - 2 * m) * it / cols }
            fun bank(c: Int) = maxOf(hair, amp * level[c.coerceIn(0, cols - 1)])
            // Each pad's reach of the river, in columns, overlapping its neighbour by one so no seam shows.
            pads.forEachIndexed { i, p ->
                val c0 = (starts[i].toLong() * cols / total).toInt().coerceIn(0, cols - 1)
                val c1 = if (i + 1 < pads.size) (starts[i + 1].toLong() * cols / total).toInt().coerceIn(c0 + 1, cols) else cols
                val path = ArtPath().moveTo(x[c0], cy - bank(c0))
                for (c in c0 + 1..c1) path.lineTo(x[c], cy - bank(c))
                for (c in c1 downTo c0) path.lineTo(x[c], cy + bank(c) * 0.85f)
                path.close()
                val next = pads.getOrNull(i + 1)?.color ?: p.color
                canvas.fillPath(path, ArtFill.Linear(x[c0], 0f, x[c1], 0f, p.color, lerp(p.color, next, 0.5f), alpha = 0.92f))
            }
        }
        stripName(canvas, palette, label, zone, ArtFont.SPACE_GROTESK_BOLD)
    }

    /** Petals on each of Grid's pad flowers. */
    const val GRID_PETALS = 5

    /**
     * Grid: the 4×4 bank, each pad's cell a small bloom of that pad's own
     * envelope ([GRID_PETALS] petals, the hit's petal pointing up) in its
     * class colour, with a hole of ground at its heart; an empty pad is a
     * faint dot.
     */
    fun padBlooms(canvas: ArtCanvas, size: Int, palette: Palette, pads: List<PadSound>, label: String, zone: KitArt.Box) {
        val bySlot = pads.associateBy { it.slot }
        for (slot in 1..16) {
            val cell = KitArt.gridCell(size, slot)
            val cx = cell.x + cell.w / 2f
            val cy = cell.y + cell.h / 2f
            val pad = bySlot[slot]
            if (pad == null) {
                canvas.fillCircle(cx, cy, cell.w * 0.06f, ArtFill.Solid(palette.ink, 0.15f))
                continue
            }
            val flower = petals(cx, cy, cell.w * 0.48f, pad.sound.envelope(40), GRID_PETALS, n = 120, centredAtTop = true)
            canvas.fillPath(flower, ArtFill.Radial(cx, cy, cell.w * 0.48f, lighten(pad.color, 0.25f), pad.color, alpha = 0.92f))
            canvas.fillCircle(cx, cy, cell.w * 0.12f, ArtFill.Solid(palette.ground))
        }
        stripName(canvas, palette, label, zone, ArtFont.OSWALD_BOLD)
    }

    /**
     * Slices: one wedge per pad round a wheel, starting at twelve o'clock,
     * each reaching out by the square root of its length (so a long loop
     * does not flatten every one-shot), round an open hub, rimmed in a
     * faint line of ink.
     */
    fun wheel(canvas: ArtCanvas, size: Int, palette: Palette, pads: List<PadSound>, label: String, zone: KitArt.Box) {
        val shown = pads.take(32)
        if (shown.isNotEmpty()) {
            val cx = size / 2f
            val cy = size * WHEEL_CY
            val r = size * WHEEL_R
            val hub = r * 0.18f
            val maxLen = shown.maxOf { it.sound.frames }.toFloat()
            val step = 360f / shown.size
            shown.forEachIndexed { i, p ->
                val reachOut = hub + (r - hub) * kotlin.math.sqrt(p.sound.frames / maxLen)
                val a0 = -90f + i * step + step * 0.04f
                val a1 = -90f + (i + 1) * step - step * 0.04f
                val arc = 7
                val xs = FloatArray(arc * 2)
                val ys = FloatArray(arc * 2)
                for (k in 0 until arc) {
                    val a = Math.toRadians((a0 + (a1 - a0) * k / (arc - 1)).toDouble())
                    xs[k] = cx + (reachOut * cos(a)).toFloat()
                    ys[k] = cy + (reachOut * sin(a)).toFloat()
                    val b = Math.toRadians((a1 - (a1 - a0) * k / (arc - 1)).toDouble())
                    xs[arc + k] = cx + (hub * cos(b)).toFloat()
                    ys[arc + k] = cy + (hub * sin(b)).toFloat()
                }
                canvas.fillPath(ArtPath.polygon(xs, ys), ArtFill.Solid(p.color, 0.85f))
                canvas.strokePath(ArtPath.polyline(xs.copyOf(arc), ys.copyOf(arc)), size * 0.006f, ArtFill.Solid(palette.ink, 0.25f))
            }
        }
        stripName(canvas, palette, label, zone, ArtFont.SPACE_GROTESK_BOLD)
    }

    /**
     * Rings: one seeded arc per pad round a common centre, its sweep the
     * pad's length and its weight the pad's peak, each laid twice — a wide
     * faint glow, then the line — so they overlap like light, not a chart.
     */
    fun orbits(canvas: ArtCanvas, size: Int, palette: Palette, pads: List<PadSound>, ringsSeed: Int, label: String, zone: KitArt.Box) {
        if (pads.isNotEmpty()) {
            val rnd = kotlin.random.Random(ringsSeed)
            val cx = size / 2f
            val cy = size * WHEEL_CY
            val r = size * 0.36f
            val maxLen = pads.maxOf { it.sound.frames }.toFloat()
            val maxPeak = pads.maxOf { it.sound.peak }.coerceAtLeast(1e-6f)
            pads.forEachIndexed { i, p ->
                val rr = r * (i + 1) / (pads.size + 1)
                val sweep = 30f + 300f * (p.sound.frames / maxLen)
                val start = rnd.nextInt(360).toFloat()
                val n = 48
                val xs = FloatArray(n)
                val ys = FloatArray(n)
                for (k in 0 until n) {
                    val a = Math.toRadians((start + sweep * k / (n - 1)).toDouble())
                    xs[k] = cx + (rr * cos(a)).toFloat()
                    ys[k] = cy + (rr * sin(a)).toFloat()
                }
                val arc = ArtPath.polyline(xs, ys)
                val w = size * 0.006f + (p.sound.peak / maxPeak) * size * 0.014f
                canvas.strokePath(arc, w * 3.2f, ArtFill.Solid(p.color, 0.14f))
                canvas.strokePath(arc, w, ArtFill.Solid(p.color, 0.95f))
            }
        }
        stripName(canvas, palette, label, zone, ArtFont.SPACE_GROTESK_BOLD)
    }

    /** Straight-line distance between two packed colours, 0..441. */
    fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    /** A 0..1 level as reach: decibels over a 40 dB window, so a decaying hit still spreads. */
    fun reach(level: Float): Float {
        val db = 20.0 * Math.log10(maxOf(level, 1e-4f).toDouble())
        return (1.0 + db.coerceIn(-40.0, 0.0) / 40.0).toFloat()
    }

    /** The name in the bottom strip: upper case, START at the zone's left, shrunk to the zone. */
    fun stripName(canvas: ArtCanvas, palette: Palette, label: String, zone: KitArt.Box, font: ArtFont) {
        val (text, fitted) = TileType.fitText(canvas, label.uppercase(), font, zone.w.toFloat(), zone.h * 0.6f)
        canvas.drawText(text, font, fitted, zone.x.toFloat(), zone.y + zone.h * 0.72f, TextAnchor.START, palette.ink)
    }

    // ---- colour helpers (packed 0xRRGGBB in, same out) ----------------------

    fun lighten(rgb: Int, k: Float): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val nr = (r + (255 - r) * k).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * k).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * k).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    fun darken(rgb: Int, k: Float): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val nr = (r * (1 - k)).toInt().coerceIn(0, 255)
        val ng = (g * (1 - k)).toInt().coerceIn(0, 255)
        val nb = (b * (1 - k)).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    /** [a] at t = 0 running to [b] at t = 1. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * k).toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
