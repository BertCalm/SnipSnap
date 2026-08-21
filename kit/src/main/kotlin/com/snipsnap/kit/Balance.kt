package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness

/**
 * Kit-level loudness balance — the difference between "sounds like a kit"
 * and "sounds like files".
 *
 * Peak-normalizing each WAV (which rendering does) leaves the hat screaming
 * over the kick, because peaks aren't loudness. This measures each pad's
 * perceived level and sets the *program's* per-pad level fields to a
 * class-relative balance — kick and snare forward, hats tucked — scaled so
 * the hottest pad sits at [CEILING] with headroom to spare. Non-destructive
 * by construction: the WAVs never change, only the mixer the MPC already
 * has.
 */
object Balance {

    /** Where each class should sit, relative to the kick. Taste, but defensible taste. */
    val TARGETS: Map<DrumClass, Float> = mapOf(
        DrumClass.KICK to 1.0f,
        DrumClass.SNARE to 0.92f,
        DrumClass.CLAP to 0.85f,
        DrumClass.HAT_CLOSED to 0.55f,
        DrumClass.HAT_OPEN to 0.58f,
        DrumClass.TOM to 0.82f,
        DrumClass.PERC to 0.7f,
        DrumClass.TONAL to 0.78f,
        DrumClass.LOOP to 0.85f,
        DrumClass.UNKNOWN to 0.75f,
    )

    /** The loudest pad's level after balancing — the rest of the way is headroom. */
    const val CEILING = 0.85f

    /**
     * The arranged kit with per-pad levels set. Pads whose audio measures
     * silent keep the default level rather than being blown up to infinity.
     */
    fun apply(arranged: List<ArrangedPad?>): List<ArrangedPad?> {
        val loudness = arranged.map { it?.let { p -> Loudness.of(p.snip) } }

        // The pad that needs the least turning-down pins the ceiling;
        // everything else scales under it.
        var c = Float.MAX_VALUE
        arranged.forEachIndexed { i, pad ->
            val l = loudness[i] ?: return@forEachIndexed
            if (pad == null || l <= 1e-6f) return@forEachIndexed
            val ratio = l / TARGETS.getValue(pad.drumClass)
            if (ratio < c) c = ratio
        }
        if (c == Float.MAX_VALUE) return arranged

        return arranged.mapIndexed { i, pad ->
            val l = loudness[i]
            if (pad == null || l == null || l <= 1e-6f) return@mapIndexed pad
            val level = (CEILING * c * TARGETS.getValue(pad.drumClass) / l).coerceIn(0.05f, 1f)
            pad.copy(level = level)
        }
    }
}
