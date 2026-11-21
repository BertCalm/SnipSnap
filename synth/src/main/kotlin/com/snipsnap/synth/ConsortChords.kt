package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Chord-tool voicings for CONSORT: every chord tone kept, the whole chord moved by octaves to the lead
 * voice's two-octave range (its [ConsortKeys.padMidis] span), so its spacing is the chord tool's own
 * ([AccordChords]' rule). A chord played on a section patch stacks the section on every tone: a MIN7 chair
 * under a triad makes a thicker chord, which is what a section playing chords does.
 */
object ConsortChords {
    fun range(voice: ConsortVoice): IntRange = Consort.rootMidi(voice)..Consort.rootMidi(voice) + Consort.TUNE_SEMITONES

    /**
     * [chord]'s notes moved by whole octaves to the place that strays least outside [voice]'s range (a chord
     * already inside is not moved). A voice spans two octaves, so a chord as narrow as 14 semitones can sit
     * where no octave lands it whole inside (B add9 on the FLUTE's C4 to C6): it then reaches a semitone or two
     * past an edge, which the keys' edge zones play (they reach nine semitones past each end). Every tone and
     * the chord tool's spacing are kept.
     */
    fun notes(voice: ConsortVoice, chord: ChordStabs.Stab): List<Int> {
        require(chord.notes.isNotEmpty())
        val span = range(voice)
        fun outside(shift: Int) = chord.notes.sumOf { n ->
            val m = n + shift
            if (m < span.first) span.first - m else if (m > span.last) m - span.last else 0
        }
        val shift = (-10..10).map { it * 12 }.minWith(compareBy<Int>({ outside(it) }, { abs(it) }))
        return chord.notes.map { it + shift }
    }

    /** How far a voiced chord may reach past [range]: the keys' edge zones, nine semitones each way. */
    const val EDGE_SEMITONES = 9

    /**
     * Offline audition: [patch] played on each of the chord's notes and summed at 1 / n. Each tone is the
     * engine's own render at that note, with its own players' takes, so the tones do not breathe together.
     * A tone past the range is its nearest in-range note read faster or slower, as the keys' edge zone plays it.
     */
    fun render(patch: ConsortPatch, chord: ChordStabs.Stab): Snip {
        val root = Consort.rootMidi(patch.voice)
        val span = range(patch.voice)
        val sources = notes(patch.voice, chord).map { midi ->
            val sampled = midi.coerceIn(span)
            val x = patch.copy(macros = patch.macros + ("TUNE" to (sampled - root) / Consort.TUNE_SEMITONES.toFloat())).render()
            if (sampled == midi) x.samples
            else Resampler.resample(x, (Dsp.RATE / 2.0.pow((midi - sampled) / 12.0)).roundToInt()).samples
        }
        val out = FloatArray(sources.maxOf { it.size })
        val gain = 1f / sources.size
        for (source in sources) for (i in source.indices) out[i] += source[i] * gain
        return Snip(out, 1, Dsp.RATE)
    }
}
