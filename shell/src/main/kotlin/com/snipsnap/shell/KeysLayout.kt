package com.snipsnap.shell

import com.snipsnap.audio.Scale
import com.snipsnap.audio.Scales

/**
 * Keys on the grid, as data (UI_DESIGN "keys on the grid"): melodic
 * content plays on the same 4×4, root on A01, ascending left to right
 * and bottom to top, the layouts CHROMATIC or a scale, OCTAVE moving the
 * whole grid. The KEYS screen draws this and nothing else.
 */
object KeysLayout {

    /** The layout chips, as drawn. */
    val LAYOUTS: List<String> = listOf("CHROMATIC", "MAJOR", "MINOR", "MIN PENT")

    const val DEFAULT_LAYOUT = "CHROMATIC"

    /** Octaves either side of the instrument's own root the grid may move. */
    const val OCTAVE_MIN = -3
    const val OCTAVE_MAX = 3

    private val SCALE_FOR: Map<String, Scale> = mapOf(
        "CHROMATIC" to Scale.CHROMATIC,
        "MAJOR" to Scale.MAJOR,
        "MINOR" to Scale.MINOR,
        "MIN PENT" to Scale.MINOR_PENTATONIC,
    )

    fun scaleFor(layout: String): Scale =
        SCALE_FOR[layout] ?: throw IllegalArgumentException("unknown layout '$layout' - the screen draws: ${LAYOUTS.joinToString(", ")}")

    /**
     * The MIDI note pad [slot] (1..16) plays: [root] on A01, the layout's
     * offsets ascending, [octave] octaves up or down. Null past MIDI's ends.
     */
    fun noteFor(slot: Int, root: Int, layout: String, octave: Int = 0): Int? {
        require(slot in 1..16) { "slot is 1..16, got $slot" }
        require(octave in OCTAVE_MIN..OCTAVE_MAX) { "octave is $OCTAVE_MIN..$OCTAVE_MAX, got $octave" }
        val note = root + Scales.layout(scaleFor(layout))[slot - 1] + 12 * octave
        return note.takeIf { it in 0..127 }
    }

    /** "A2", "C#4" — the pad's label. */
    fun label(note: Int): String = Scales.nameOf(note)
}
