package com.snipsnap.shell

import com.snipsnap.kit.KitPreview

/**
 * ECHO's time on SURFACE: free, or a division of the kit's bar, so the
 * repeats land on the grid the way RATE on MOD and BARS on PRINT already
 * do. The engine never learns a tempo - it takes a time in seconds
 * (`SurfaceEngine.setEchoTime`) and this is where the seconds come from,
 * through [PrintLength]'s one bar so the three cannot disagree about how
 * long a bar is. A kit with no tempo runs at [KitPreview.DEFAULT_BPM],
 * the same stand-in GROOVE and the modulators use.
 *
 * FREE is the engine's own fixed time, what ECHO always was, and the
 * first choice so a kit from before this existed sounds as it did. The
 * time is the only thing here: the wet MIX is still the `echo` macro,
 * corner-blended and modulated like the others.
 */
object EchoTime {

    /** A note value as a fraction of a bar - [num] over [den] of it. */
    data class Division(val num: Int, val den: Int) {
        init {
            require(num > 0 && den > 0) { "a division is a positive fraction, got $num/$den" }
        }

        val fraction: Float get() = num.toFloat() / den.toFloat()

        /** How the button prints it: the fraction alone, the way a delay pedal marks its notes. */
        val label: String get() = "$num/$den"
    }

    /**
     * The choices, in the order the button cycles them; null is FREE.
     * Sixteenth to half a bar, with the dotted eighth every echo pedal
     * has a mark for. Half a bar at GROOVE's slowest tempo is exactly the
     * engine's ceiling (`SurfaceEngine::kMaxEchoSeconds`), so every choice
     * here is one it honours; `EchoTimeTest` holds that.
     */
    val DIVISIONS: List<Division?> = listOf(
        null,
        Division(1, 16),
        Division(1, 8),
        Division(3, 16),
        Division(1, 4),
        Division(1, 2),
    )

    /** Where a fresh kit starts: FREE. */
    const val FREE_INDEX = 0

    /** The time at [index] in seconds at [bpm], or null for FREE - the engine's own time. */
    fun seconds(index: Int, bpm: Float?): Float? {
        require(index in DIVISIONS.indices) { "ECHO's time is an index into DIVISIONS (0..${DIVISIONS.lastIndex}), got $index" }
        val d = DIVISIONS[index] ?: return null
        return PrintLength.seconds(1, bpm ?: KitPreview.DEFAULT_BPM) * d.fraction
    }

    /** The button's word for [index]: FREE, or the note value. */
    fun label(index: Int): String {
        require(index in DIVISIONS.indices) { "ECHO's time is an index into DIVISIONS (0..${DIVISIONS.lastIndex}), got $index" }
        return DIVISIONS[index]?.label ?: "FREE"
    }

    /** The next choice after [index], round the loop - the button's own step. */
    fun next(index: Int): Int = (index + 1).mod(DIVISIONS.size)
}
