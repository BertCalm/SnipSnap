package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note

/**
 * Mechanical variations of a captured groove — the four clips a chopped
 * kit ships (the container's own slot count): the break as captured,
 * snapped tight, at half time, and thinned to its strong hits. Load the
 * kit, flip patterns.
 *
 * Every transform is pure note-list arithmetic — provably derived, no
 * taste injected.
 */
object GrooveVariations {

    /**
     * The standard four, in slot order: Captured / Tight / Half / Sparse —
     * or, when a [swingPercent] is asked for, the Tight slot becomes the
     * swung clip (the container's four-slot budget is the budget).
     */
    fun standard(base: Mpc3Clip, swingPercent: Int? = null): List<Mpc3Clip> = listOf(
        base,
        swingPercent?.let { swing(base, it) }
            ?: quantize(base, Mpc3Clip.PULSES_PER_16TH, suffix = "Tight"),
        halfTime(base),
        sparse(base),
    )

    /**
     * Swing, the way the hardware applies it: quantize to the 16th grid,
     * then push every even ("and") 16th late by `(percent−50)/50` of a
     * 16th. 50 is straight, 66 is triplet feel, and the classic MPC panel
     * runs 50–75. Velocities are untouched — swing is time, not dynamics.
     */
    fun swing(clip: Mpc3Clip, percent: Int): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        // Mpc3Clip.swingPush, not the arithmetic inline: a ring swung to
        // the same percent has to land on the same pulse, and `Long`
        // division here used to truncate 76.8 to 76 while the ring's
        // frame maths rounded it to 77.
        val push = Mpc3Clip.swingPush(percent)
        val limit = clip.lengthPulses
        val tight = quantize(clip, s16, suffix = "Swing $percent")
        return tight.copy(
            notes = tight.notes.map { n ->
                if ((n.timePulses / s16) % 2 == 1L) {
                    n.copy(timePulses = (n.timePulses + push).coerceAtMost(limit - 1))
                } else {
                    n
                }
            },
        )
    }

    /**
     * Snap to [grid], wrapping past the loop point and resolving collisions
     * louder-wins — the same rule as [GrooveEdit.quantized], which this used
     * to contradict.
     *
     * It previously ended `.coerceAtMost(limit - 1)`. In a 240-pulse grid
     * `limit - 1` is never itself a multiple of 240, so a hit rounding past
     * the loop end landed OFF-GRID inside the clip named "Tight" — and
     * because [swing] calls this and [standard] ships its result, that
     * off-grid ghost reached every MIDI export and every arrangement. These
     * clips loop: the loop end IS the next pass's downbeat, so wrapping is
     * both correct and the only answer that stays on the grid.
     *
     * Wrapping (and plain snapping, for two hits a half-step apart) can put
     * two notes on one (note, pulse) address. One survives: the louder.
     */
    fun quantize(clip: Mpc3Clip, grid: Long, suffix: String = "Tight"): Mpc3Clip {
        require(grid > 0) { "grid must be positive: $grid" }
        // Made explicit rather than assumed: every caller passes
        // PULSES_PER_16TH, and `step * grid` is only provably in range when
        // the grid divides a bar evenly.
        require(clip.pulsesPerBar % grid == 0L) { "grid must divide a bar evenly: $grid" }
        val stepsInClip = clip.lengthPulses / grid
        return clip.copy(
            name = variantName(clip.name, suffix),
            notes = GrooveEdit.dedupeLouder(
                clip.notes.map { n ->
                    val step = ((n.timePulses + grid / 2) / grid) % stepsInClip
                    n.copy(timePulses = step * grid)
                },
            ),
        )
    }

    /** Times and lengths doubled — the same feel at half speed. */
    fun halfTime(clip: Mpc3Clip): Mpc3Clip {
        val bars = (clip.bars * 2).coerceAtMost(64)
        val limit = bars * clip.pulsesPerBar
        return Mpc3Clip(
            name = variantName(clip.name, "Half"),
            bars = bars,
            pulsesPerBar = clip.pulsesPerBar,
            notes = clip.notes.mapNotNull { n ->
                val time = n.timePulses * 2
                if (time >= limit) null
                else n.copy(timePulses = time, lengthPulses = (n.lengthPulses * 2).coerceAtMost(limit - time))
            },
        )
    }

    /**
     * Only the strong hits: notes at or above the median velocity. The
     * ghost notes drop away and the skeleton of the beat remains.
     */
    fun sparse(clip: Mpc3Clip): Mpc3Clip {
        // An empty bar (STEPS from scratch) has no median; its skeleton is itself.
        if (clip.notes.isEmpty()) return clip.copy(name = variantName(clip.name, "Sparse"))
        val median = clip.notes.map { it.velocity }.sorted()[clip.notes.size / 2]
        val kept = clip.notes.filter { it.velocity >= median }
        return clip.copy(
            name = variantName(clip.name, "Sparse"),
            notes = kept.ifEmpty { clip.notes },
        )
    }

    /**
     * The fill: the last bar of every four (or the last bar of a shorter
     * groove) densifies into the turn — the first half of the fill bar
     * stays as played, then beat 3 rolls 16ths and beat 4 rolls 32nds on
     * the kit's own snare (clap standing in when there's none), hat
     * eighths underneath, velocities ramping into the next downbeat.
     * Seeded jitter keeps it human; same seed, same fill. Non-fill bars
     * are untouched — provably.
     */
    fun fill(base: Mpc3Clip, kit: Kit, seed: Int = 1): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val rollSlot = kit.pads.firstOrNull { it.drumClass == com.snipsnap.audio.DrumClass.SNARE }?.slot
            ?: kit.pads.firstOrNull { it.drumClass == com.snipsnap.audio.DrumClass.CLAP }?.slot
            ?: kit.pads.firstOrNull { it.drumClass == com.snipsnap.audio.DrumClass.PERC }?.slot
        require(rollSlot != null) { "a fill needs a snare, clap, or perc to roll on - this kit has none" }
        val hatSlot = kit.pads.firstOrNull { it.drumClass == com.snipsnap.audio.DrumClass.HAT_CLOSED }?.slot
        val rnd = kotlin.random.Random(seed)

        val fillBars = if (base.bars >= 4) (3 until base.bars step 4).toList() else listOf(base.bars - 1)
        val notes = mutableListOf<Mpc3Note>()
        for (n in base.notes) {
            val bar = (n.timePulses / Mpc3Clip.PULSES_PER_BAR).toInt()
            val inBar = n.timePulses % Mpc3Clip.PULSES_PER_BAR
            // The fill owns its bar's second half; the played notes yield it.
            if (bar in fillBars && inBar >= 8 * s16) continue
            notes += n
        }
        for (bar in fillBars) {
            val b = bar * Mpc3Clip.PULSES_PER_BAR
            fun jitter() = ((rnd.nextFloat() - 0.5f) * 0.06f)
            // Beat 3: 16th roll finding its feet.
            for (step in 8..11) {
                val ramp = (step - 8) / 8f
                notes += Mpc3Note(
                    Mpc3Note.noteFor(rollSlot), b + step * s16,
                    (0.5f + 0.45f * ramp + jitter()).coerceIn(0.3f, 1f),
                )
            }
            // Beat 4: 32nds pouring into the turn.
            val s32 = s16 / 2
            for (k in 0..7) {
                val ramp = 0.5f + (k / 14f)
                notes += Mpc3Note(
                    Mpc3Note.noteFor(rollSlot), b + 12 * s16 + k * s32,
                    (0.5f + 0.45f * ramp + jitter()).coerceIn(0.3f, 1f),
                    lengthPulses = s32,
                )
            }
            // Hat eighths keep the time under the roll.
            hatSlot?.let { hs ->
                for (e in 4..7) {
                    notes += Mpc3Note(Mpc3Note.noteFor(hs), b + e * 2L * s16, 0.4f)
                }
            }
        }
        return base.copy(
            name = variantName(base.name, "Fill"),
            notes = notes.sortedBy { it.timePulses },
        )
    }

    /** Ghosts stay whispers: the loudest one sits at this velocity. */
    const val GHOST_VELOCITY_CEILING = 0.32f

    /**
     * Ghost-note grammar — the classic funk vocabulary: the "e" before
     * and the "a" after the backbeats (beats 2 and 4) whisper on the
     * kit's own snare (clap standing in), velocities in ghost territory
     * so `--ghosts` soft zones actually voice them. A candidate 16th
     * already carrying a note is left alone — ghosts fill silence, they
     * never pile on. Seeded; the backbone is untouched, provably.
     */
    fun ghosted(base: Mpc3Clip, kit: Kit, seed: Int = 1): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val ghostSlot = kit.pads.firstOrNull { it.drumClass == com.snipsnap.audio.DrumClass.SNARE }?.slot
            ?: kit.pads.firstOrNull { it.drumClass == com.snipsnap.audio.DrumClass.CLAP }?.slot
        require(ghostSlot != null) { "ghosts whisper on a snare or clap - this kit has none" }
        val rnd = kotlin.random.Random(seed)

        val occupied = base.notes.map { (it.timePulses + s16 / 2) / s16 }.toSet()
        val ghosts = mutableListOf<Mpc3Note>()
        for (bar in 0 until base.bars) {
            val barStep = bar * 16L
            for (backbeat in listOf(4, 12)) {
                for (cand in listOf(backbeat - 1, backbeat + 1)) {
                    val step = barStep + cand
                    if (step in occupied) continue
                    if (rnd.nextFloat() < 0.6f) {
                        ghosts += Mpc3Note(
                            Mpc3Note.noteFor(ghostSlot), step * s16,
                            velocity = 0.18f + rnd.nextFloat() * (GHOST_VELOCITY_CEILING - 0.18f),
                            lengthPulses = s16 / 2,
                        )
                    }
                }
            }
        }
        return base.copy(
            name = variantName(base.name, "Ghosted"),
            notes = (base.notes + ghosts).sortedBy { it.timePulses },
        )
    }

    /** "X Groove" → "X Tight"; anything else just gains the suffix. */
    internal fun variantName(base: String, suffix: String): String =
        if (base.endsWith(" Groove")) base.removeSuffix(" Groove") + " " + suffix
        else "$base $suffix"
}
