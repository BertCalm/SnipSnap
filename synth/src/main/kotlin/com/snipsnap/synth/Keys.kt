package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One rendered key-patch note: the audio, and — for sustaining instruments —
 * the frame the sustain loop starts at. The loop always runs to the end of
 * the sample, which is the one shape both MPC generations' loop idioms
 * share; `0` means the note simply decays.
 */
data class KeyNote(val snip: Snip, val loopStartFrame: Long = 0)

/**
 * KEYS — the S5 key patches: engines played at exact MIDI pitch.
 *
 * The drum engines render approximate, characterful pitches; an instrument
 * multisampled across a keyboard has to land each zone's root *exactly*, or
 * the MPC's per-zone transposition compounds the error across the range.
 * These renderers bypass the macro pitch maps and drive the engine cores at
 * true equal-temperament frequencies.
 *
 * Where an instrument sustains (the organ), the note is rendered long, cut
 * at an **exact whole number of waveform periods**, and handed back with a
 * loop start — a luxury sample-based vendors don't have: we know the
 * fundamental to the sample, so the seam is mathematics, not scissors.
 */
object Keys {

    fun midiHz(midi: Int): Float {
        require(midi in 0..127) { "midi out of range: $midi" }
        return 440f * 2f.pow((midi - 69) / 12f)
    }

    /**
     * Electric piano — the TINES key patch the roadmap promised. Two
     * strikes on the shared FM core: a ratio-1 body whose modulation index
     * *is* the velocity (soft playing is darker, not just quieter — the
     * physics of a softer hammer), and a ratio-14 "tine" ping that gives
     * the attack its bell. Lower notes ring longer, like the real thing.
     */
    fun ep(midi: Int, bright: Float): Snip {
        val hz = midiHz(midi)
        val t60 = (3.4f * (110f / hz)).coerceIn(1.0f, 3.4f)
        val out = FloatArray(((t60 * 1.25f) * RATE).toInt())
        Tines.strike(out, hz, ratio = 1f, index = Dsp.lin(bright.coerceIn(0f, 1f), 0.9f, 3.2f), t60 = t60, bite = 2.0f)
        Tines.strike(out, hz, ratio = 14f, index = 0.5f, t60 = t60 * 0.12f, bite = 1.5f, gain = 0.22f)
        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /**
     * Organ — TONEWHEEL held down. Rendered with vibrato off so every
     * partial is an exact harmonic of half the base frequency (the 16'
     * sub bar), then cut at a whole number of fundamental periods chosen
     * to land as close to an integer frame as the range allows. The loop
     * starts after the percussion register has fully decayed and runs to
     * the cut — the MPC holds it as long as the key is down. Organs are
     * not velocity-sensitive, and neither is this.
     */
    fun organ(midi: Int): KeyNote {
        val semis = midi - ORGAN_LOW_MIDI
        require(semis in 0..Tonewheel.TUNE_SEMITONES) { "organ range is MIDI $ORGAN_LOW_MIDI..${ORGAN_LOW_MIDI + Tonewheel.TUNE_SEMITONES}, got $midi" }
        val snip = Tonewheel.render(
            TonewheelVoice.SOUL,
            mapOf(
                "TUNE" to semis / Tonewheel.TUNE_SEMITONES.toFloat(),
                "WARBLE" to 0f,
                "PERC" to 0.18f,
                "DIRT" to 0.12f,
            ),
            gateSeconds = 2.6f,
        )
        val base = 110f * 2f.pow(semis / 12f)
        // The waveform's true period is the 16' sub bar's: 2/base seconds.
        val periodFrames = 2.0 * RATE / base
        val loopStart = (1.2f * RATE).toInt()
        // Pick the period count near half a second whose length lands
        // closest to a whole frame — the seam error is the leftover phase.
        var bestN = (0.5 * RATE / periodFrames).roundToLong()
        var bestErr = Double.MAX_VALUE
        for (n in bestN - 40..bestN + 40) {
            if (n < 8) continue
            val len = n * periodFrames
            val err = Math.abs(len - Math.round(len))
            if (err < bestErr) { bestErr = err; bestN = n }
        }
        val loopLen = Math.round(bestN * periodFrames).toInt()
        val end = loopStart + loopLen
        check(end <= snip.samples.size) { "loop end $end beyond render ${snip.samples.size}" }
        return KeyNote(
            Snip(snip.samples.copyOf(end), channels = 1, sampleRate = RATE),
            loopStartFrame = loopStart.toLong(),
        )
    }

    const val ORGAN_LOW_MIDI = 45

    /**
     * Harp — PLUCK's HARP body at snapped semitones. The engine's 165 Hz
     * root sits two cents above true E3; the offset is constant across the
     * range, far below the just-noticeable difference, and the kalimba-koto
     * family has never been equal-tempered anyway.
     */
    fun harp(midi: Int): Snip {
        val semis = midi - HARP_LOW_MIDI
        require(semis in 0..Pluck.TUNE_SEMITONES) { "harp range is MIDI $HARP_LOW_MIDI..${HARP_LOW_MIDI + Pluck.TUNE_SEMITONES}, got $midi" }
        return Pluck.render(
            PluckVoice.HARP,
            mapOf("TUNE" to semis / Pluck.TUNE_SEMITONES.toFloat()),
        )
    }

    const val HARP_LOW_MIDI = 52

    /**
     * Music box — the TINES chime recipe held to exact pitch: an
     * inharmonic 3.5-ratio strike and a barely-detuned twin beating
     * against it. Not velocity-sensitive; a music box has one dynamic,
     * wistful.
     */
    fun musicBox(midi: Int): Snip {
        val hz = midiHz(midi)
        val t60 = (1.6f * (261.6f / hz).pow(0.5f)).coerceIn(0.7f, 1.8f)
        val out = FloatArray(((t60 * 1.3f) * RATE).toInt())
        Tines.strike(out, hz, ratio = 3.5f, index = 2.0f, t60 = t60, bite = 3f, gain = 0.8f)
        Tines.strike(out, hz * 1.003f, ratio = 3.5f, index = 1.7f, t60 = t60 * 0.9f, bite = 3f, gain = 0.5f)
        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
