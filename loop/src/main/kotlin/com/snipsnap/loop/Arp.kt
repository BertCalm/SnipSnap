package com.snipsnap.loop

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * A chord walked into a run: turn a handful of notes into an arpeggio and
 * lay it onto a ring's steps, the way [OrbitPatterns] lays any other fill
 * onto one.
 *
 * There is no new player and no new clock here, on purpose. A ring's
 * steps already are the arpeggio's rate — a 16-step free ring runs 16ths,
 * an 8-step one runs 8ths, [OrbitSpan] already sets a ring against the
 * bar — so an arpeggio is not a new kind of timing, it is a new way to
 * *fill* the timing that exists. [run] hands back an ordinary
 * [PatternOrbit] ring that [OrbitEngine] plays exactly as it plays any
 * other, with every one of [OrbitClock]'s own machinery — swing, chance,
 * ratchet — still available on the hits it wrote, because they are still
 * just [OrbitHit]s.
 *
 * [walk] is the half of this worth testing on its own: the note order an
 * arpeggio visits, independent of the ring it will end up on.
 */
object Arp {

    /**
     * The shape a run takes through its notes.
     *
     * [AS_PLAYED] is deliberately the one shape that does not sort: it is
     * the order the notes were picked in, which is the one order a chord
     * picker cannot recover once the notes are sorted low to high. Every
     * other shape sorts first, because "up" meaning anything other than
     * "low to high" would be the shape lying about its own name.
     */
    enum class Shape(
        /** The chip's word for it. */
        val label: String,
    ) {
        UP("UP"),
        DOWN("DOWN"),
        UP_DOWN("UP-DOWN"),
        AS_PLAYED("AS PLAYED"),
        RANDOM("RANDOM"),
        CHORD("CHORD");

        /** The shape after this one, wrapping: what one tap on the chip does. */
        val next: Shape get() = entries[(ordinal + 1) % entries.size]

        companion object {
            /** The stored name back to a shape; anything unknown is [UP], never a refusal. */
            fun fromName(name: String?): Shape = entries.firstOrNull { it.name == name } ?: UP
        }
    }

    /**
     * [notes] walked in [shape]'s order, then stacked [octaves] copies
     * [octaveStep] slots apart — low copy first, each copy in the same
     * order as the first.
     *
     * [octaveStep] defaults to a bank's width because that is what a bank
     * *is* (`PadBanks.SIZE`) — "banks are octaves" is already the app's
     * own convention (`docs/SYNTH_ROADMAP.md`), so stacking a run means
     * reaching into the bank above, not a unit this module invents. It is
     * a parameter rather than that constant itself because `:loop` has no
     * dependency on `:shell`, where `PadBanks` lives, and duplicating one
     * number here is cheaper than a new cross-module edge just to fetch
     * it.
     *
     * [Shape.CHORD] does not stack: every note already sounds together,
     * so a second octave would only double every note rather than add a
     * new one to hear.
     */
    fun walk(
        notes: List<Int>,
        shape: Shape,
        octaves: Int = 1,
        octaveStep: Int = DEFAULT_OCTAVE_STEP,
        seed: Int = 0,
    ): List<Int> {
        require(notes.isNotEmpty()) { "an arpeggio needs at least one note" }
        require(octaves >= 1) { "octaves must be at least 1, got $octaves" }
        val sorted = notes.sorted()
        val order = when (shape) {
            Shape.UP, Shape.CHORD -> sorted
            Shape.DOWN -> sorted.reversed()
            // Endpoints are not repeated at the turn: a 4-note up-down is
            // low..high..low's *interior*, not the peak twice in a row.
            Shape.UP_DOWN -> sorted + sorted.reversed().drop(1).dropLast(1)
            Shape.AS_PLAYED -> notes
            Shape.RANDOM -> sorted.shuffled(Random(seed))
        }
        if (shape == Shape.CHORD) return order
        return (0 until octaves).flatMap { octave -> order.map { it + octave * octaveStep } }
    }

    /**
     * [ring] turned into an arpeggio of [notes]: every step gets the next
     * note of [walk]'s run, wrapping when the ring has more steps than the
     * run has notes and cut short when it has fewer to spare — neither is
     * an error, both are what a player hears when a 5-note run meets a
     * 4-step ring.
     *
     * [Shape.CHORD] fills every step with every note at once rather than
     * one note per step, since a chord is not a sequence to walk.
     *
     * [gate] is the fraction of a step a note holds before the next one
     * cuts it off — 1 leaves [OrbitHit.WHOLE_SAMPLE], the honest "let the
     * sample finish" default every other ring hit already has; below
     * that, [OrbitHit.length] is set from [OrbitClock.ringStepPulses] so a
     * plucked run and a held pad are the same [gate] on rings of
     * different lengths.
     *
     * Replaces [ring]'s hits outright — an arpeggio owns its ring the way
     * [OrbitPatterns.scramble] owns the pads in its voice, not a fill
     * layered beside other hits — and sets [Orbit.voice] to the notes
     * actually used, low to high, so the ring's own validation and its
     * unrolled strip agree with what just got written.
     */
    fun run(
        set: OrbitSet,
        ring: Orbit,
        notes: List<Int>,
        shape: Shape,
        octaves: Int = 1,
        gate: Float = 1f,
        octaveStep: Int = DEFAULT_OCTAVE_STEP,
        seed: Int = 0,
    ): Orbit {
        val content = ring.content as? PatternOrbit
            ?: throw IllegalArgumentException("ring '${ring.name}' is a snip ring, not a pattern ring")
        require(gate in MIN_GATE..1f) { "gate wants $MIN_GATE..1, got $gate" }
        val sequence = walk(notes, shape, octaves, octaveStep, seed)
        val length = gatedLength(set, ring, gate)
        val hits = if (shape == Shape.CHORD) {
            (0 until ring.steps).flatMap { step -> sequence.map { slot -> OrbitHit(step, slot, length = length) } }
        } else {
            (0 until ring.steps).map { step -> OrbitHit(step, sequence[step % sequence.size], length = length) }
        }
        return ring.copy(
            voice = sequence.distinct().sorted(),
            content = content.copy(hits = hits.sortedWith(compareBy({ it.step }, { it.slot }))),
        )
    }

    /** How long a gated hit holds, in pulses: [OrbitHit.WHOLE_SAMPLE] at full [gate], else that fraction of one step. */
    private fun gatedLength(set: OrbitSet, ring: Orbit, gate: Float): Long {
        if (gate >= 1f) return OrbitHit.WHOLE_SAMPLE
        val stepPulses = OrbitClock.ringStepPulses(set, ring)
        return (stepPulses * gate).roundToLong().coerceIn(1L, OrbitHit.MAX_LENGTH)
    }

    /** The octave stack step: a bank's width (`PadBanks.SIZE`), duplicated here so `:loop` need not depend on `:shell`. */
    const val DEFAULT_OCTAVE_STEP = 16

    /** Below this a gate is inaudible rather than short, the same kind of floor a knob's own range would refuse to go under. */
    const val MIN_GATE = 0.05f

    /** Octaves worth a chip: none stacked, then up to three more. */
    val OCTAVE_CHOICES: List<Int> = listOf(1, 2, 3, 4)
}
