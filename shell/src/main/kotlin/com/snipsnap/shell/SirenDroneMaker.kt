package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.loop.Session
import com.snipsnap.synth.Siren
import com.snipsnap.synth.SirenDrone
import com.snipsnap.synth.SirenVoice

/**
 * What the SYNTH screen's `DRONE TO LOOP ▸` needs for SIREN
 * (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md, door 4) —
 * [DroneMaker]'s own shape, simplified by SIREN already being a movement:
 * RATE and DEPTH are the patch's own swing and speed, so there is no MOTION
 * or BREATHS to invent, and [DroneFit]'s span/nudge/label math is pitch
 * arithmetic that already reads any voice's register, [DroneMaker.label]
 * included.
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

    /** Intervals the drone spans in [session] — [DroneMaker.span]'s own call. */
    fun span(rootMidi: Int, session: Session): Int = DroneMaker.span(rootMidi, session)

    /** The whole drone for [session], the way [DroneMaker.render] renders RESIN's. */
    fun render(
        spec: SirenDrone.Spec,
        rootMidi: Int,
        session: Session,
        cancelled: () -> Boolean = { false },
    ): FloatArray =
        SirenDrone.render(spec, rootMidi, span(rootMidi, session).toLong() * session.intervalFrames, session.sampleRate, cancelled)

    /** "A1 · 4 BARS · -1.97¢" — [DroneMaker.label]'s own readout; the math never reads an engine. */
    fun label(rootMidi: Int, session: Session): String = DroneMaker.label(rootMidi, session)
}
