package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.pow
import kotlin.math.roundToInt
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
            // No scanner and no rotor on this one path. Both are
            // time-varying, and the loop cut below depends on the steady
            // region being exactly periodic - a rotor a third of the way
            // through a turn at the seam is a click. The rate could instead
            // be fitted so a whole number of rotor turns lands inside the
            // chosen loop length; that is worth doing, but it is a change to
            // the arithmetic below rather than to the engine.
            motion = false,
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

    // ---------- RESIN, held ----------
    // docs/superpowers/specs/2026-09-25-resin-held-pad-design.md

    /** The nine RESIN pad zones: every minor third across the voice's TUNE range (A1–A3, A2–A4, A3–A5). */
    fun resinPadMidis(voice: ResinVoice): List<Int> {
        val root = Scales.hzToMidi(Resin.frequencyFor(voice, 0f)).roundToInt()
        return (0..Resin.TUNE_SEMITONES step 3).map { root + it }
    }

    /**
     * Seconds the ladder is given to settle after the attack before the loop
     * may start. The spec's worst measured seam at this settle was 7.1e-5,
     * fourteen times under the bar.
     */
    const val RESIN_PAD_SETTLE_SECONDS = 1.5f

    /** The Organ's bar: difference energy across the wrap over signal energy. */
    const val MAX_SEAM_ERROR = 1e-3

    internal data class LoopPlan(val loopFrames: Int, val baseHz: Double, val squareRatio: Double?, val k: Int)

    /**
     * The loop's length, the exact pitch that fills it, and the square's
     * ratio if it sounds.
     *
     * The loop is K periods of the sub-octave saw. With the square silent, K
     * is whatever fits in about half a second (the Organ's length). With it
     * sounding, K comes straight from the detune STACK asks for, and the
     * square's ratio is exactly 1 + 1/(2K): it completes 2K + 1 cycles, one
     * whole beat per loop.
     *
     * K periods of the true pitch almost never land on a whole frame, and a
     * fraction of a frame left over is audible in the seam of a bright zone
     * (measured: residues of 0.03-0.07 frames put LEAD at CUTOFF 1 at
     * 2e-4..6.5e-4 against 1e-10 where the residue was zero). So the loop is
     * rounded to whole frames and the pitch moves to fit it - by at most half
     * a frame over the loop, 0.14 cents at the shortest (A5), far below
     * hearing and the keygroup's own tuning.
     */
    internal fun planLoop(noteHz: Float, stack: Float): LoopPlan {
        val g3 = Resin.stackGains(stack).second
        val k = if (g3 <= 0f) {
            (0.5 * noteHz / 2.0).roundToInt().coerceAtLeast(10)
        } else {
            val cents = Dsp.lin(stack, 3f, 14f)
            (1.0 / (2.0 * (2.0.pow(cents / 1200.0) - 1.0))).roundToInt()
        }
        val loopFrames = Math.round(k * 2.0 * RATE / noteHz).toInt()
        val baseHz = k * 2.0 * RATE / loopFrames
        return LoopPlan(loopFrames, baseHz, if (g3 <= 0f) null else 1.0 + 1.0 / (2.0 * k), k)
    }

    /**
     * InstrumentSuiteTest's seam metric: over the 256 frames before
     * [loopStart], the energy of the difference between what plays before the
     * wrap and what plays after it, over the signal's own energy. The loop
     * runs from [loopStart] to the end of [s].
     */
    fun seamError(s: FloatArray, loopStart: Int): Double {
        val loopLen = s.size - loopStart
        require(loopStart >= 256 && loopLen > 0) { "no room to measure a seam: start $loopStart, loop $loopLen" }
        var diff = 0.0
        var level = 0.0
        for (i in loopStart - 256 until loopStart) {
            val d = s[i + loopLen] - s[i].toDouble()
            diff += d * d
            level += s[i].toDouble() * s[i]
        }
        return diff / level
    }

    internal fun requireSeam(label: String, s: FloatArray, loopStart: Int) {
        val e = seamError(s, loopStart)
        require(e < MAX_SEAM_ERROR) {
            "$label: the loop does not close (seam %.2e, bar %.0e)".format(java.util.Locale.ROOT, e, MAX_SEAM_ERROR)
        }
    }

    /**
     * RESIN held down - one pad zone at exact MIDI pitch, cut at a seam that
     * is a whole number of every waveform's cycles. TUNE is set by [midi];
     * the rest of [macros] plays as it does in the one-shot, except CREAM,
     * which stops at the self-oscillation threshold ([Resin.HELD_MAX_RESONANCE]).
     */
    fun resinPad(voice: ResinVoice, macros: Map<String, Float>, midi: Int, attackSeconds: Float): KeyNote {
        val low = resinPadMidis(voice).first()
        val semis = midi - low
        require(semis in 0..Resin.TUNE_SEMITONES) {
            "$voice pads are MIDI $low..${low + Resin.TUNE_SEMITONES}, got $midi"
        }
        val defaults = Resin.defaults(voice)
        val m = defaults + macros.filterKeys { it in defaults } + ("TUNE" to semis / Resin.TUNE_SEMITONES.toFloat())
        val base = Resin.frequencyFor(voice, m.getValue("TUNE"))
        val plan = planLoop(base, m.getValue("STACK"))
        val label = "$voice ${Scales.nameOf(midi)}"

        fun cut(settle: Float): KeyNote {
            val loopStart = ((attackSeconds + settle) * RATE).roundToInt()
            val end = loopStart + plan.loopFrames
            // A quarter second past the cut, so the decimator's edge never reaches it.
            val held = Resin.Held(attackSeconds, end.toFloat() / RATE + 0.25f, plan.squareRatio, plan.baseHz)
            val s = Resin.renderHeld(voice, m, held).samples.copyOf(end)
            requireSeam(label, s, loopStart)
            // Loud where it is held: the loop, not the attack, sets the level
            // (spec decision 10). RESIN's per-voice loudness offsets are all
            // zero today; if they move, this target should carry them too.
            val loud = Loudness.of(Snip(s.copyOfRange(loopStart, end), channels = 1, sampleRate = RATE))
            if (loud > 1e-6f) {
                val g = Dsp.MELODIC_LOUDNESS_TARGET / loud
                for (i in s.indices) s[i] *= g
            }
            Dsp.limitPeak(s, 0.99f)
            return KeyNote(Snip(s, channels = 1, sampleRate = RATE), loopStartFrame = loopStart.toLong())
        }
        // One retry with twice the settle; the measurements say it never fires.
        // A second failure propagates, naming the zone - never a crossfade.
        return try {
            cut(RESIN_PAD_SETTLE_SECONDS)
        } catch (e: IllegalArgumentException) {
            cut(RESIN_PAD_SETTLE_SECONDS * 2f)
        }
    }

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
