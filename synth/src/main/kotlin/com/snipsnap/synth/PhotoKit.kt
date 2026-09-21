package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.kit.ArrangedPad
import kotlin.math.max
import kotlin.math.min

/**
 * A KIT FROM ONE PHOTO — the MPC's own 4×4, not [PhotoField]'s 16×12 of
 * grains. Each cell becomes a full SNAP **pad** (a note, rendered by
 * [Snap.render], not a grain windowed by a field's voice) and lands at
 * the cell's own place on the grid: the photo's top-left cell is A13,
 * its bottom-left A01 — `PadBanks`'s own numbering, the same geometry
 * `KitArt`'s GRID style already draws. Every pad's colour is its cell's
 * mean RGB ([Snap.Reading.meanRgb]), so the MPC lights up as the picture
 * did.
 *
 * `AutoPlace` never runs here: a photo kit's layout is the picture's own,
 * not the drum convention's — the whole point of a photo kit is that the
 * grid IS the photo. No re-roll either, unlike [Shuffle.kit]: a cell is
 * whatever it sounds like, and [PhotoField.cellTable]'s own soft blend
 * toward a sine keeps a flat cell (a patch of clear sky) from ever
 * refusing — the kit must fill every pad, the way the field must sound
 * everywhere.
 */
object PhotoKit {

    /** The MPC's own bank: four across, four down. */
    const val COLUMNS = 4
    const val ROWS = 4

    /**
     * Sixteen pads off [photo], named [name]: cell (0, 0) — the photo's
     * top-left — at slot 13 (A13), cell (0, 3) — the bottom-left — at
     * slot 1 (A01), `PadBanks.tag`'s own top-row-first numbering. Every
     * cell is read and rendered exactly as [PhotoField.build] reads one
     * of its own — [Snap.look] for the macros, [Snap.table] plus
     * [PhotoField.cellTable]'s blend for the line — so a photo kit and a
     * photo field agree about what a patch of the same picture sounds
     * like. The recipe is the patch's, so the kit regenerates from its
     * `kit.json` sidecar like every other synth kit.
     */
    fun build(photo: Photo, name: String): List<ArrangedPad?> {
        val w = photo.width
        val h = photo.height
        val pads = arrayOfNulls<ArrangedPad>(COLUMNS * ROWS)
        for (row in 0 until ROWS) {
            // A cell is at least one pixel each way, so a photo smaller
            // than the grid is read in overlapping cells rather than
            // refused — the same rule PhotoField.build follows.
            val y0 = min(row * h / ROWS, h - 1)
            val y1 = max(y0 + 1, min((row + 1) * h / ROWS, h))
            for (column in 0 until COLUMNS) {
                val x0 = min(column * w / COLUMNS, w - 1)
                val x1 = max(x0 + 1, min((column + 1) * w / COLUMNS, w))
                val cell = photo.crop(x0, y0, x1 - x0, y1 - y0)
                val reading = Snap.look(cell)
                val macros = Snap.macrosFrom(reading)
                val rawLine = Snap.table(cell, SnapVoice.HORIZON)
                val (table, _) = PhotoField.cellTable(rawLine)
                val patch = SnapPatch(name, SnapVoice.HORIZON, macros, table)
                val snip = patch.render()
                val cls = Classifier.classify(snip).drumClass
                val slot = 13 - 4 * row + column
                pads[slot - 1] = ArrangedPad(
                    snip = snip,
                    drumClass = cls,
                    recipe = PadRecipe(patch = patch).toJsonValue(),
                    colorHex = colorHex(reading.meanRgb),
                )
            }
        }
        return pads.toList()
    }

    /** [Snap.Reading.meanRgb] as the `#rrggbb` `KitPad.colorHex` wants. */
    private fun colorHex(rgb: Int): String = "#%06x".format(rgb and 0xFFFFFF)
}
