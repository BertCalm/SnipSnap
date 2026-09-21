package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.sqrt

/**
 * PATH — a photo walked in time. A drawn polyline over the picture is
 * resampled by arc length to a fixed step count, so a fast stroke and a
 * slow one over the same line give the same walk, and each step is the
 * `PhotoKit` cell under it: a melody from where the line goes, a rhythm
 * from where it lingers.
 *
 * [render] is the standalone landing — the walked cells as one gapless
 * loop, `Snap.cut` end to end at the tempo's sixteenth grid. The other
 * landing, an ORBIT ring naming a photo kit's own pads directly, needs
 * `PhotoKit.slotFor` and the `:loop` module's own `PatternOrbit`/`OrbitHit`
 * — built at the app layer, where a kit folder name exists to name.
 */
object PhotoPath {

    /**
     * [path] resampled by arc length to exactly [steps] points, 0..1 both
     * ways: a fast stroke and a slow one over the same line give the same
     * walk, and a path that lingers over one stretch gives it no more
     * points than a path that crossed it in a blink — arc length, not
     * time, is what's being resampled. A path with fewer than two points
     * (a tap, or nothing drawn) repeats its one point, or is empty if
     * nothing was drawn at all.
     */
    fun sample(path: List<Pair<Float, Float>>, steps: Int): List<Pair<Float, Float>> {
        require(steps > 0) { "steps must be positive, got $steps" }
        if (path.isEmpty()) return emptyList()
        if (path.size == 1) return List(steps) { path[0] }

        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) {
            val (x0, y0) = path[i - 1]
            val (x1, y1) = path[i]
            val dx = (x1 - x0).toDouble()
            val dy = (y1 - y0).toDouble()
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val total = cum[cum.size - 1]
        if (total <= 0.0) return List(steps) { path[0] }

        val out = ArrayList<Pair<Float, Float>>(steps)
        var seg = 0
        for (s in 0 until steps) {
            val target = if (steps == 1) 0.0 else total * s / (steps - 1)
            while (seg < path.size - 2 && cum[seg + 1] < target) seg++
            val segLen = cum[seg + 1] - cum[seg]
            val t = if (segLen <= 0.0) 0.0 else ((target - cum[seg]) / segLen).coerceIn(0.0, 1.0)
            val (x0, y0) = path[seg]
            val (x1, y1) = path[seg + 1]
            out.add((x0 + (x1 - x0) * t).toFloat() to (y0 + (y1 - y0) * t).toFloat())
        }
        return out
    }

    /**
     * Each of [points] (0..1 both ways) as the grid cell under it, a
     * (column, row) pair clamped into [columns]x[rows] — `PhotoKit`'s own
     * grid by default, so a walked path names the same cells a photo kit
     * built from the same picture would.
     */
    fun cellsFor(
        points: List<Pair<Float, Float>>,
        columns: Int = PhotoKit.COLUMNS,
        rows: Int = PhotoKit.ROWS,
    ): List<Pair<Int, Int>> = points.map { (x, y) ->
        val column = (x * columns).toInt().coerceIn(0, columns - 1)
        val row = (y * rows).toInt().coerceIn(0, rows - 1)
        column to row
    }

    /**
     * The walked [cells] as one gapless loop: each step [Snap.cut] of its
     * cell's table and macros, exactly `stepFrames` long at [bpm]'s
     * sixteenth grid, placed end to end. The render is `cells.size ×
     * stepFrames` long, and every step's pitch is its own cell's TUNE.
     */
    fun render(photo: Photo, cells: List<Pair<Int, Int>>, bpm: Float, name: String = "Path"): Snip {
        require(cells.isNotEmpty()) { "a path needs at least one cell to walk" }
        require(bpm > 0f) { "bpm must be positive, got $bpm" }
        val stepFrames = (Dsp.RATE * 60f / bpm / 4f).toInt().coerceAtLeast(1)
        val out = FloatArray(stepFrames * cells.size)
        for ((i, cell) in cells.withIndex()) {
            val (column, row) = cell
            val patch = PhotoKit.cellAt(photo, column, row, name).patch
            val step = Snap.cut(patch.table, patch.macros, stepFrames)
            System.arraycopy(step, 0, out, i * stepFrames, stepFrames)
        }
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
