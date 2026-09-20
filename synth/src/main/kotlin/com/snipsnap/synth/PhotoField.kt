package com.snipsnap.synth

import com.snipsnap.audio.GrainField
import com.snipsnap.audio.Snip
import kotlin.math.max
import kotlin.math.min

/**
 * PHOTO FIELD — the whole picture, under a finger.
 *
 * SNAP reads one line through a photo; a photo has two dimensions and a
 * million pixels. The GRAIN FIELD screen already plays a scatter of short
 * grains from wherever a finger is, off nothing more than a sample and a
 * list of positions ([GrainField.GrainMap]). So the picture is cut into a
 * grid of cells, each cell is rendered as its own short SNAP grain — its
 * line as the cycle, its colours as the knobs, so sky and brick sound as
 * different as they look — and the grains are laid out where their cells
 * are. Drag across the photo and hear what is under the finger. No new
 * real-time code: the field's own voice plays this map as it plays any.
 *
 * A cell with no swing in its line (a patch of clear sky) is a pure tone
 * at the cell's hue rather than a refusal: the field must sound everywhere.
 * Brightness is level as well as BRIGHT — a dark cell is quieter, never
 * silent — so a picture's light and shade can be heard as well as its
 * colour.
 *
 * [cloud] hands the field's whole source to GRAINS, so the picture also
 * lands on a pad as a texture — a LOOP by the classifier's measure, which
 * is what it is.
 */
object PhotoField {

    const val COLUMNS = 16
    const val ROWS = 12

    /** How much quieter the darkest cell is than the brightest: a floor, so a night shot still speaks. */
    internal const val DARKEST_LEVEL = 0.25f

    /** One cell of the grid: where it is, what it looked like, and the knobs that made its grain. */
    data class Cell(
        val column: Int,
        val row: Int,
        val reading: Snap.Reading,
        val macros: Map<String, Float>,
        /** True when the cell's own line had no swing and a sine stood in. */
        val flat: Boolean,
    )

    /**
     * The built field: every cell's grain end to end in [source], the
     * [map] that places each at its cell, and the cells themselves for a
     * screen that wants to say what a finger is over.
     */
    class Field(
        val source: Snip,
        val map: GrainField.GrainMap,
        val columns: Int,
        val rows: Int,
        val cells: List<Cell>,
    ) {
        val grainFrames: Int get() = map.grainFrames

        /** The frames of the grain for the cell at ([column], [row]). */
        fun grainOf(column: Int, row: Int): FloatArray {
            require(column in 0 until columns && row in 0 until rows) { "no cell at ($column, $row) in a ${columns}x$rows field" }
            val start = (row * columns + column) * grainFrames
            return source.samples.copyOfRange(start, start + grainFrames)
        }
    }

    fun build(
        photo: Photo,
        columns: Int = COLUMNS,
        rows: Int = ROWS,
        grainFrames: Int = GrainField.GRAIN_FRAMES,
    ): Field {
        require(columns > 0 && rows > 0) { "a field needs at least one cell: ${columns}x$rows" }
        require(grainFrames > 0) { "a grain needs at least one frame" }
        val w = photo.width
        val h = photo.height
        val source = FloatArray(columns * rows * grainFrames)
        val grains = ArrayList<GrainField.Grain>(columns * rows)
        val cells = ArrayList<Cell>(columns * rows)
        for (r in 0 until rows) {
            // A cell is at least one pixel each way, so a photo smaller than
            // the grid is read in overlapping cells rather than refused.
            val y0 = min(r * h / rows, h - 1)
            val y1 = max(y0 + 1, min((r + 1) * h / rows, h))
            for (c in 0 until columns) {
                val x0 = min(c * w / columns, w - 1)
                val x1 = max(x0 + 1, min((c + 1) * w / columns, w))
                val patch = photo.crop(x0, y0, x1 - x0, y1 - y0)
                val reading = Snap.look(patch)
                val macros = Snap.macrosFrom(reading)
                val line = Snap.table(patch, SnapVoice.HORIZON)
                val flat = Snap.isFlat(line)
                val table = if (flat) Draw.wave(Draw.Wave.SINE) else line
                val grain = Snap.grain(table, macros, grainFrames)
                val level = Dsp.lin(reading.luminance, DARKEST_LEVEL, 1f)
                val index = r * columns + c
                val start = index * grainFrames
                for (i in 0 until grainFrames) source[start + i] = grain[i] * level
                grains.add(GrainField.Grain(start, (c + 0.5f) / columns, (r + 0.5f) / rows))
                cells.add(Cell(c, r, reading, macros, flat))
            }
        }
        return Field(
            source = Snip(source, channels = 1, sampleRate = Dsp.RATE),
            map = GrainField.GrainMap(grains, grainFrames, projector = null),
            columns = columns,
            rows = rows,
            cells = cells,
        )
    }

    /** The whole picture as one GRAINS cloud: [field]'s source through [Grains.render], for a pad. */
    fun cloud(field: Field, macros: Map<String, Float> = emptyMap(), seconds: Float = 2.5f, seed: Int = 1): Snip =
        Grains.render(field.source, macros, seconds, seed)
}
