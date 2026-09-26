package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class GlintVoice { REED, BOTTLE, KAZOO }

/**
 * GLINT — phase distortion, where the formant is generated rather than
 * carved. Each cycle of the fundamental runs a sine burst at [k] times f0,
 * multiplied by a window that reaches exactly zero by the cycle's end.
 *
 * That zero is the whole engine. It means the burst always lands on silence
 * at the wrap, so `k` need not be an integer and can slide continuously
 * without a click — the formant sweeps while the pitch does not move at all.
 * Only the window's *slope* jumps across the wrap, and that slope
 * discontinuity is the buzz the engine is made of. Do not smooth it.
 *
 * The bare window doubles as the body waveform: a linear-decay window is a
 * sawtooth, a triangle window is a triangle, a trapezoid is a pulse. Mixing
 * it back in (mean-removed, so it carries no DC) gives "a saw with a
 * resonant peak riding on it" for one macro instead of a second oscillator.
 *
 * Design: `docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md`.
 */
object Glint {

    const val TUNE_SEMITONES = 24

    /** Below two burst cycles inside the window there is no peak, only a dull fragment. */
    const val K_MIN = 2f

    /**
     * The musical ceiling. Aliasing is not what bounds this: the burst is
     * generated at `Dsp.RATE * Dsp.OVERSAMPLE`, so folding starts only above
     * `k·f0 ≈ 88 kHz` — k ≈ 1443 at A1, ≈ 361 at A3, ≈ 76 at C6.
     */
    const val K_MAX = 40f

    /** At or below this, `k` snaps to integers so the peak lands on a harmonic. */
    const val SNAP_CEILING = 12f

    /** KAZOO's trapezoid holds at full for this fraction of the cycle, then ramps out. */
    const val KAZOO_FLAT = 0.7f

    /** BODY's own t60 as a fraction of the amp t60 — the body burns off, the glass rings on. */
    const val BODY_DECAY_RATIO = 0.45f

    fun macrosFor(voice: GlintVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, 0.5f),
        MacroSpec("PEAK", 0.45f),
        MacroSpec("FOLLOW", 0.8f),
        MacroSpec("BODY", 0.4f),
        MacroSpec("BLOOM", 0.35f),
        MacroSpec("DECAY", 0.5f),
    )

    fun defaults(voice: GlintVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /**
     * No preset roster yet — Phase 3 authors one and this gains the
     * preset-seeded form every other engine uses (see `Resin.scramble`).
     */
    fun scramble(
        voice: GlintVoice,
        random: Random,
        temperature: Float = 0.35f,
        near: Patch? = null,
    ): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** The bottom of each voice's own two-octave TUNE range. */
    fun rootHz(voice: GlintVoice): Float = when (voice) {
        GlintVoice.REED -> 110f     // A2
        GlintVoice.BOTTLE -> 220f   // A3
        GlintVoice.KAZOO -> 220f    // A3
    }

    fun frequencyFor(voice: GlintVoice, tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return rootHz(voice) * 2f.pow(semis / 12f)
    }

    /**
     * The window at [phase] in [0, 1). Must reach exactly zero at phase 1 —
     * every voice's definition below is written so that it does.
     */
    fun windowAt(voice: GlintVoice, phase: Float): Float {
        val p = phase.coerceIn(0f, 1f)
        return when (voice) {
            GlintVoice.REED -> 1f - p
            GlintVoice.BOTTLE -> if (p < 0.5f) p * 2f else (1f - p) * 2f
            GlintVoice.KAZOO -> if (p < KAZOO_FLAT) 1f else (1f - p) / (1f - KAZOO_FLAT)
        }
    }

    /**
     * The window's own mean, subtracted before BODY mixes it. A window is
     * unipolar; mixing it raw would push DC into `Dsp.levelTo` and out to
     * the WAV. Pinned against numeric integration by `GlintTest`.
     */
    fun windowMean(voice: GlintVoice): Float = when (voice) {
        GlintVoice.REED -> 0.5f
        GlintVoice.BOTTLE -> 0.5f
        GlintVoice.KAZOO -> KAZOO_FLAT + (1f - KAZOO_FLAT) / 2f
    }

    /**
     * PEAK as a ratio at the voice's own reference note. Exponential,
     * because the ear judges the peak's position by interval, not by Hz.
     */
    fun ratioAtReference(peak: Float): Float = Dsp.expMap(peak, K_MIN, K_MAX)

    /**
     * Integers put the peak exactly on a harmonic — k=2 the octave, k=3 the
     * octave-and-a-fifth, k=5 two octaves and a major third. Snapping matters
     * only where the ear reads the peak as related to the note, so it applies
     * below [SNAP_CEILING] and stops above it, where it would be inaudible.
     *
     * The base ratio snaps; BLOOM modulates continuously on top of it. That
     * is what makes the knob musical and the sweep smooth.
     */
    fun snapRatio(k: Float): Float = if (k <= SNAP_CEILING) Math.round(k).toFloat() else k

    /** The centre of the voice's own TUNE range — where FOLLOW has no work to do. */
    fun referenceHz(voice: GlintVoice): Float = frequencyFor(voice, 0.5f)

    /**
     * The formant ratio actually used, after FOLLOW, the snap and the clamp.
     *
     * FOLLOW rides `Dsp.keyTrack`: at 1 the peak's Hz scales exactly with the
     * note, so the ratio is constant and the timbre is identical across the
     * range; at 0 the peak's Hz is fixed, so the ratio falls as the note
     * rises and the sound turns vocal — a body resonance rather than a
     * filter. In between, `keyTrack` blends in the log domain, so half
     * tracking means half the octaves of movement.
     *
     * The [K_MIN] floor is unconditional. Across a pad's two-octave range it
     * never binds (a 700 Hz peak is k=12.7 at A1 and 3.2 at A3), but across
     * a four-octave keygroup at FOLLOW 0 it would — see the spec's note, and
     * expect FOLLOW's bottom half to collapse toward tracking up there.
     */
    fun ratioFor(voice: GlintVoice, tune: Float, peak: Float, follow: Float): Float {
        val reference = referenceHz(voice)
        val f0 = frequencyFor(voice, tune)
        val peakHzAtReference = ratioAtReference(peak) * reference
        val peakHz = Dsp.keyTrack(peakHzAtReference, f0, reference, follow)
        return snapRatio((peakHz / f0).coerceIn(K_MIN, K_MAX))
    }

    internal fun synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice) + macros
        val f0 = frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)

        val k = ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))

        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = windowAt(voice, phase)
            out[i] = amp.at(t) * w * sin(2.0 * PI * k * phase).toFloat()
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
    }

    fun render(voice: GlintVoice, macros: Map<String, Float> = emptyMap()): Snip {
        // U6: render at 4x RATE so the burst's harmonics fold above 22.05 kHz
        // instead of into the band, then decimate.
        val renderRate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate)
        val out = Dsp.decimate(raw, Dsp.RATE)
        Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
