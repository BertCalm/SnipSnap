package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.loop.DroneFit
import com.snipsnap.loop.Session
import com.snipsnap.synth.Siren
import com.snipsnap.synth.SirenDrone
import com.snipsnap.synth.SirenVoice
import kotlin.math.abs

/**
 * What the SYNTH screen's `DRONE TO LOOP ▸` needs for SIREN
 * (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md, door 4) —
 * [DroneMaker]'s own shape, simplified by SIREN already being a movement:
 * RATE and DEPTH are the patch's own swing and speed, so there is no MOTION
 * or BREATHS to invent.
 *
 * [DroneFit]'s own span/nudge math is not reused here (review finding on
 * PR #368): it assumes RESIN's even-only sub-octave carrier snap and pure
 * pitch arithmetic, while SIREN's own carrier allows any whole cycle count
 * and depends on DEPTH through the LFO's own phase integral
 * ([SirenDrone.fitCarrier]). Reusing RESIN's model would still hold the
 * tuning promise — SIREN's own achievable nudge is provably no worse,
 * since the LFO's phase integral is always at least the loop's plain
 * duration (Jensen's inequality on a zero-mean swing) and RESIN's
 * even-only constraint is strictly more restrictive than SIREN's own — but
 * it could pick a longer span than SIREN needs, and its displayed nudge
 * would not be the number SIREN's own render actually lands on. [span] and
 * [label] read SIREN's own model instead, so the sheet's readout is the
 * number that plays and the span sent is the shortest one that is.
 */
object SirenDroneMaker {

    /** Every voice shares SIREN's own TUNE range and root ([Siren.ROOT_MIDI]'s own KDoc) — one register, not one per voice as [DroneMaker.roots] has. */
    fun roots(voice: SirenVoice): IntRange = Siren.ROOT_MIDI..(Siren.ROOT_MIDI + Siren.TUNE_SEMITONES)

    /** The lowest note of [key]'s root in the register, else its own root — [DroneMaker.defaultRoot]'s own rule. */
    fun defaultRoot(voice: SirenVoice, key: KeySpec?): Int {
        val range = roots(voice)
        if (key == null) return range.first
        return range.first { ((it % 12) + 12) % 12 == key.rootSemitone }
    }

    /** A drone recipe from a patch's macros, keeping only what sounds. */
    fun spec(voice: SirenVoice, macros: Map<String, Float>): SirenDrone.Spec =
        SirenDrone.Spec(voice, macros.filterKeys { it in SirenDrone.SOUNDING_MACROS })

    /**
     * Intervals the drone spans in [session]: the shortest of [DroneFit.SPANS]
     * whose nudge, read off [SirenDrone.nudgeCents], is within
     * [DroneFit.MAX_NUDGE_CENTS] — [DroneFit.spanFor]'s own search, over
     * SIREN's own model rather than RESIN's.
     */
    fun span(spec: SirenDrone.Spec, rootMidi: Int, session: Session): Int =
        DroneFit.SPANS.firstOrNull { span ->
            val frames = span.toLong() * session.intervalFrames
            abs(SirenDrone.nudgeCents(spec.voice, spec.macros, rootMidi, frames, session.sampleRate)) <= DroneFit.MAX_NUDGE_CENTS
        } ?: DroneFit.SPANS.last()

    /** The whole drone for [session], the way [DroneMaker.render] renders RESIN's. */
    fun render(
        spec: SirenDrone.Spec,
        rootMidi: Int,
        session: Session,
        cancelled: () -> Boolean = { false },
    ): FloatArray =
        SirenDrone.render(spec, rootMidi, span(spec, rootMidi, session).toLong() * session.intervalFrames, session.sampleRate, cancelled)

    /** "A1 · 4 BARS · -1.97¢" — [DroneMaker.label]'s own readout, over SIREN's own span and nudge. */
    fun label(spec: SirenDrone.Spec, rootMidi: Int, session: Session): String {
        val span = span(spec, rootMidi, session)
        val bars = span * session.barsPerInterval
        val frames = span.toLong() * session.intervalFrames
        val cents = SirenDrone.nudgeCents(spec.voice, spec.macros, rootMidi, frames, session.sampleRate)
        return "%s · %d %s · %+.2f¢".format(java.util.Locale.ROOT, Scales.nameOf(rootMidi), bars, if (bars == 1) "BAR" else "BARS", cents)
    }
}
