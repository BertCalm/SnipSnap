package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.loop.DroneFit
import com.snipsnap.loop.Session
import com.snipsnap.synth.Resin
import com.snipsnap.synth.ResinDrone
import com.snipsnap.synth.ResinVoice
import java.util.Locale

/**
 * What the CLI's `synth --drone` and the SYNTH screen's `DRONE TO LOOP ▸`
 * share: the recipe, the root range, the span, the render and the readout
 * (docs/superpowers/specs/2026-09-25-resin-drone-design.md). Neither door
 * computes any of this itself, so the two can't drift.
 */
object DroneMaker {

    /** The macros a drone hears. CONTOUR and DECAY have no note-on to act on; ROOT replaces TUNE. */
    val SOUNDING_MACROS = listOf("STACK", "CUTOFF", "CREAM")

    const val DEFAULT_MOTION = 0.5f
    const val DEFAULT_RATE = 1

    /**
     * A drone recipe from a patch's macros, keeping only what sounds, so two
     * drones that differ only in a knob the drone ignores are one recipe
     * (and one render).
     */
    fun spec(voice: ResinVoice, macros: Map<String, Float>, motion: Float, rate: Int): ResinDrone.Spec =
        ResinDrone.Spec(voice, macros.filterKeys { it in SOUNDING_MACROS }, motion, rate)

    /** The voice's own register: TUNE's 25 semitones up from its root, as MIDI notes. */
    fun roots(voice: ResinVoice): IntRange {
        val low = Scales.hzToMidi(Resin.frequencyFor(voice, 0f)).let { Math.round(it) }
        return low..low + Resin.TUNE_SEMITONES
    }

    /** The lowest note of [key]'s root in the voice's register, else the voice's own root. */
    fun defaultRoot(voice: ResinVoice, key: KeySpec?): Int {
        val range = roots(voice)
        if (key == null) return range.first
        return range.first { ((it % 12) + 12) % 12 == key.rootSemitone }
    }

    /** Intervals the drone spans in [session]. */
    fun span(rootMidi: Int, session: Session): Int = DroneFit.spanFor(rootMidi, session)

    /** The whole drone for [session]: its span of intervals, mono, at the session's rate. */
    fun render(spec: ResinDrone.Spec, rootMidi: Int, session: Session): FloatArray =
        ResinDrone.render(spec, rootMidi, span(rootMidi, session).toLong() * session.intervalFrames, session.sampleRate)

    fun motionLabel(motion: Float): String =
        "±%.1f OCT".format(Locale.ROOT, motion * ResinDrone.MOTION_MAX_OCTAVES)

    fun breathsLabel(rate: Int): String = if (rate == 1) "1 BREATH" else "$rate BREATHS"

    /** "A1 · 4 BARS · -1.97¢": the note, how long before it repeats, and how far the loop nudged it. */
    fun label(rootMidi: Int, session: Session): String {
        val span = span(rootMidi, session)
        val bars = span * session.barsPerInterval
        val cents = DroneFit.nudgeCents(rootMidi, span, session)
        return "%s · %d %s · %+.2f¢".format(Locale.ROOT, Scales.nameOf(rootMidi), bars, if (bars == 1) "BAR" else "BARS", cents)
    }
}
