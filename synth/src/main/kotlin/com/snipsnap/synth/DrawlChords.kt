package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * Chord-tool voicings for DRAWL's keys: every chord tone kept, the whole chord moved by octaves into
 * the voice's playable range (its root an octave either side, the reach of TUNE) so its spacing is
 * the chord tool's own ([AccordChords]' rule). A chord wider than two octaves folds each tone to its
 * nearest octave in range. A strummed chord on one pad is [DrawlStrum.fromChord]; this is for keys.
 */
object DrawlChords {
    /** The keys [voice] plays: its root an octave either side. */
    fun range(voice: DrawlVoice): IntRange {
        val root = Drawl.rootMidi(voice)
        return root - Drawl.TUNE_SEMITONES / 2..root + Drawl.TUNE_SEMITONES / 2
    }

    /** [chord]'s notes inside [range]; a chord already inside is not moved. */
    fun notes(voice: DrawlVoice, chord: ChordStabs.Stab): List<Int> {
        require(chord.notes.isNotEmpty())
        val keys = range(voice)
        val shifts = (-10..10).map { it * 12 }.filter { shift -> chord.notes.all { it + shift in keys } }
        if (shifts.isNotEmpty()) {
            val shift = shifts.minBy { abs(it) }
            return chord.notes.map { it + shift }
        }
        return chord.notes.map { note -> keys.filter { (it - note) % 12 == 0 }.minBy { abs(it - note) } }.distinct().sorted()
    }

    /** TUNE that plays [midi] on [voice]. */
    fun tuneFor(voice: DrawlVoice, midi: Int): Float {
        require(midi in range(voice)) { "$midi is outside ${voice.name}'s keys ${range(voice)}" }
        return .5f + (midi - Drawl.rootMidi(voice)).toFloat() / Drawl.TUNE_SEMITONES
    }

    /**
     * Offline audition: the chord's notes picked together and summed at 1 / n, each the engine's own
     * render of [patch] at that note (no strum). Keys made by MAKE INSTRUMENT play tones between their
     * sampled roots a semitone fast or slow instead; that is the instrument's, not this.
     */
    fun render(patch: DrawlPatch, chord: ChordStabs.Stab, velocity: Float = patch.velocity): Snip {
        require(patch.strum == null) { "a strummed DRAWL patch is already a chord" }
        val sources = notes(patch.voice, chord).map { midi ->
            patch.copy(macros = patch.macros + ("TUNE" to tuneFor(patch.voice, midi)), velocity = velocity).render()
        }
        val out = FloatArray(sources.maxOf { it.frameCount })
        val gain = 1f / sources.size
        for (source in sources) for (i in source.samples.indices) out[i] += source.samples[i] * gain
        return Snip(out, 1, Dsp.RATE)
    }
}
