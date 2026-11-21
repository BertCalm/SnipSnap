package com.snipsnap.synth

import com.snipsnap.audio.Scales
import java.util.concurrent.CancellationException

/**
 * CONSORT, held: a CONSORT patch (a lead voice and its section) as a keys instrument that sustains
 * while a key is down, the owner's choice of 2026-11-20 ("Sustain while held").
 *
 * Each sample is one held note played from its first sample: the note's own attack, then its held
 * middle as a sustain loop. It is cut exactly as BORE's held keys are ([BoreKeys.cutHeld]): the loop
 * starts no sooner than [Bore.LOOP_START_SECONDS], is about [Consort.KEYS_PERIOD_SECONDS] long (the held
 * pads' wanders on a shorter cycle, the owner's middle ground, 2026-11-21), and closes by blending its last [Bore.LOOP_BLEND_SECONDS] into the audio
 * just before its start, so the first pass from the attack into the loop is the note's own audio. A
 * join whose two waves correlate under [Bore.LOOP_MIN_CORRELATION], or whose level dips more than
 * [Bore.LOOP_MAX_DIP_DB] in the blend, is refused by name, not shipped. The start is then shaped
 * by [HeldStart.SHORT], the owner's pick for BORE (2026-11-06).
 *
 * The held note is the one-shot's own section ([Consort.heldNote]): the same players, chairs, takes,
 * humanization and room. The room's wash of a steady note is itself steady, so it loops with the
 * note. A held note never overblows: a key plays its own note.
 *
 * Velocity: three touches ([TOUCHES]), each levelled to the same loudness on its loop, so they differ
 * by timbre (breath effort) and the player applies the key's velocity as gain once, BORE's rule.
 * Round robin: two engine takes a touch ([TAKES]), dealt in turn by the keygroup's cycle (HALOBAR's
 * and ACCORD's matching-window layout): take 0 (centred) and take 2 (bitten), the two ways of playing
 * furthest apart in attack and vibrato onset.
 */
object ConsortKeys {
    /** Soft, medium and hard: medium is the engine's default velocity. Effort is capped under the overblow line. */
    val TOUCHES: List<Float> = listOf(0.3f, Consort.DEFAULT_VELOCITY, 1f)

    /** The touches' MIDI windows, soft first, as ACCORD's keys have them. */
    val VELOCITY_WINDOWS: List<IntRange> = listOf(0..42, 43..84, 85..127)

    /** The engine takes each touch deals, in order. */
    val TAKES: List<Int> = listOf(0, 2)

    const val LAYERS = 6

    /** The nine zones of [voice]: every minor third across its two-octave TUNE range, both ends included. */
    fun padMidis(voice: ConsortVoice): List<Int> = (0..Consort.TUNE_SEMITONES step 3).map { Consort.rootMidi(voice) + it }

    /** One sample: [touch] and [take] index [TOUCHES] and [TAKES]. */
    data class Cell(val midi: Int, val touch: Int, val take: Int)

    /** Every cell of [patch]'s keys, zone by zone, soft to hard, each touch's takes in order: the keygroup's layer order. */
    fun cells(patch: ConsortPatch): List<Cell> = padMidis(patch.voice).flatMap { midi ->
        TOUCHES.indices.flatMap { touch -> TAKES.indices.map { take -> Cell(midi, touch, take) } }
    }

    /** The macros a zone at [midi] plays: the patch's, with TUNE set to the key. */
    internal fun zoneMacros(patch: ConsortPatch, midi: Int): Map<String, Float> {
        val root = Consort.rootMidi(patch.voice)
        require(midi - root in 0..Consort.TUNE_SEMITONES) {
            "CONSORT ${patch.voice} keys are MIDI $root..${root + Consort.TUNE_SEMITONES}, got $midi"
        }
        return Consort.settled(patch.macros, patch.voice) + ("TUNE" to (midi - root) / Consort.TUNE_SEMITONES.toFloat())
    }

    /** [cell] as an attack and a sustain loop, its start shaped by [hit]. Refused, naming the cell, if its join does not hold. */
    fun render(patch: ConsortPatch, cell: Cell, hit: HeldStart.Hit? = HeldStart.SHORT, cancelled: () -> Boolean = { false }): KeyNote =
        HeldStart.shape(measured(patch, cell, cancelled).note, hit)

    /** [render]'s cut and its join, before the start is shaped: for the tests. */
    internal fun measured(patch: ConsortPatch, cell: Cell, cancelled: () -> Boolean = { false }): BoreKeys.Held {
        val name = "CONSORT ${patch.voice}${if (patch.chairs.isEmpty()) "" else " section"} ${Scales.nameOf(cell.midi)} " +
            "touch ${cell.touch + 1} take ${cell.take + 1}"
        stop(cancelled, name)
        // the held pads' deeper wanders on a four-second cycle (Consort.KEYS_HELD), the owner's middle ground
        // between the held pads' sound and the size of an instrument (2026-11-21)
        val held = Consort.KEYS_HELD
        val x = Consort.heldNote(patch.voice, zoneMacros(patch, cell.midi), patch.chairs, TOUCHES[cell.touch], TAKES[cell.take], held)
        stop(cancelled, name)
        return BoreKeys.cutHeld(
            x, minOf(x.size, held.usableFrames), name, loopSeconds = held.period,
            fixedFrom = Consort.HELD_FROM_FRAMES, minCorrelation = minCorrelation(patch),
        )
    }

    /** How alike the two waves a key's loop blends must be: the engine's own floor ([Consort.loopFloor]). */
    internal fun minCorrelation(patch: ConsortPatch): Double =
        Consort.loopFloor(Consort.settled(patch.macros, patch.voice), patch.chairs)

    /** The section floor, [Consort.SECTION_MIN_CORRELATION]. */
    const val SECTION_MIN_CORRELATION = Consort.SECTION_MIN_CORRELATION

    /** One zone's six layers at [midi], in layer order (soft to hard, each touch's takes in turn). */
    fun zone(patch: ConsortPatch, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in padMidis(patch.voice)) { "CONSORT ${patch.voice} zone root must be one of ${padMidis(patch.voice)}" }
        return cells(patch).filter { it.midi == midi }.map { render(patch, it, cancelled = cancelled) }
    }

    private fun stop(cancelled: () -> Boolean, name: String) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("$name no longer wanted")
    }
}
