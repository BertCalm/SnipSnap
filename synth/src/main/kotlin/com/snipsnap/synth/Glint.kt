package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.ln
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
 * BODY is a second burst under the same window, two and a half octaves below
 * the main formant and on its own envelope — a second source, not a copy of
 * the first. (An earlier version mixed the bare window back in instead; that
 * added the same harmonic series the burst already carries and was
 * inaudible however loud it was mixed.)
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

    /**
     * The second formant's t60 as a fraction of the amp t60. Single-
     * enveloped, unlike the body term this replaced: that one was scaled by
     * its own envelope and then again by the amp envelope, so two
     * exponentials composed and its real decay was ~0.31x t60 rather than
     * the 0.45 the constant claimed.
     */
    const val BODY_DECAY_RATIO = 0.8f

    /**
     * How far below the main formant the second one sits — k/5.6, about two
     * and a half octaves. The old BODY mixed the mean-removed window back
     * in, which is the same harmonic series the burst already carries, so it
     * was inaudible however loud it was mixed. A second burst at its own
     * ratio is a second *source*: "two formants, two speeds, is a vowel and
     * the room it sits in" (design 2026-09-26).
     */
    const val BODY_RATIO_DIVISOR = 5.6f

    /** The second formant's level relative to the main burst at BODY 1. */
    const val BODY_MIX = 0.7f

    /**
     * BLOOM's ceiling: the peak opens to 1 + this many times its settled
     * ratio — 4x at full BLOOM.
     *
     * That 4x is clamped against [K_MAX] (40) in `synthesize`, and the two
     * interact: full BLOOM starts clipping against the ceiling once
     * `kBase > 10` (PEAK ≈ 0.54, since `kBase = 2 * 20^PEAK`), and is
     * entirely inert at PEAK 1, where `kBase` is already 40 and has nowhere
     * left to open. Not a bug — undocumented behaviour someone auditioning
     * BLOOM at high PEAK would otherwise read as the knob being broken.
     */
    const val BLOOM_MAX = 3f

    /**
     * The formant sweep's t60, fixed. It used to run from 0.30 s at low
     * BLOOM down to 0.06 s at full — depth and rate on one knob, so a big
     * sweep was always a fast one and a wide, unhurried one could not be
     * dialled at all. Measured at the shipped coupling the centroid fell to
     * 0.92x of its opening value and then sat flat for the rest of the note;
     * at 0.45 s it travels to 0.35x over 300 ms, which is the single change
     * the 2026-09-26 audition marked KEEP on both voices tested.
     *
     * BLOOM now means how far the formant travels. Each voice supplies how
     * it travels — D2's PLATE ties it to loudness and RATCHET steps it.
     */
    const val BLOOM_T60 = 0.45f

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
            .let { it + ("PEAK" to snapPeak(it.getValue("PEAK"))) }

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
            .let { it + ("PEAK" to snapPeak(it.getValue("PEAK"))) }
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

    /**
     * [peak] moved to the nearest macro value whose ratio is an exact
     * harmonic — the snap, applied where PEAK is *authored* rather than
     * where it is rendered.
     *
     * It used to live inside [ratioFor], which meant velocity's
     * floor-scaled macro quantised onto the same integer as the full one:
     * below about PEAK 0.06 the soft and hard layers rendered byte-
     * identically and a preset there had no velocity response at all.
     * Snapping the stored value instead leaves the render path continuous,
     * so scaling it always moves the formant. Quantisation for the ear,
     * full resolution for the difference.
     *
     * Identity above [SNAP_CEILING], where the ratio runs free.
     */
    fun snapPeak(peak: Float): Float {
        val k = ratioAtReference(peak)
        if (k > SNAP_CEILING) return peak
        val target = Math.round(k).toFloat().coerceIn(K_MIN, K_MAX)
        return (ln(target / K_MIN) / ln(K_MAX / K_MIN)).coerceIn(0f, 1f)
    }

    /** The centre of the voice's own TUNE range — where FOLLOW has no work to do. */
    fun referenceHz(voice: GlintVoice): Float = frequencyFor(voice, 0.5f)

    /**
     * The formant ratio actually used, after FOLLOW and the clamp.
     *
     * The snap now lives in [snapPeak] and is applied when PEAK is set
     * (by [defaults] and [scramble]), not when it is rendered — this stays
     * continuous so a scaled macro (as `Velocity.atVelocity` produces)
     * always moves the formant rather than quantising onto the same
     * integer as the unscaled value.
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
        return (peakHz / f0).coerceIn(K_MIN, K_MAX)
    }

    internal fun synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice) + macros
        val f0 = frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)

        val kBase = ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))
        val bloomAmount = Dsp.lin(m.getValue("BLOOM"), 0f, BLOOM_MAX)
        val bloomT60 = BLOOM_T60

        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val bodyMix = m.getValue("BODY") * BODY_MIX
        val bodyT60 = t60 * BODY_DECAY_RATIO
        // Its own envelope, not the amp envelope times another one. The term
        // this replaced was scaled twice — by its own decay and then by the
        // amp's — so two exponentials composed and its real decay was ~0.31x
        // t60 rather than the constant it claimed. One envelope now, with
        // the same 2 ms attack so the onset does not click.
        val bodyEnv = Dsp.Env(attackSeconds = 0.002f, decay2T60 = bodyT60)
        // The second formant is pinned to the *base* ratio, not the
        // bloom-modulated one: it is a separate resonance, not a shadow of
        // the first. Clamped to K_MIN so it never falls below two burst
        // cycles per window and stops being a formant at all.
        val k2 = (kBase / BODY_RATIO_DIVISOR).coerceAtLeast(K_MIN)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = windowAt(voice, phase)
            // kBase is snapped; BLOOM modulates continuously on top of it, so
            // the knob is musical and the sweep is smooth. k moves on the
            // envelope's timescale, far slower than one cycle, so the inner
            // sine stays effectively periodic while restarting at each wrap.
            val k = (kBase * (1f + bloomAmount * Dsp.envAt(t, bloomT60))).coerceIn(K_MIN, K_MAX)
            val burst = w * sin(2.0 * PI * k * phase).toFloat()
            // A windowed sine carries no DC, so unlike the window copy this
            // replaced, nothing here needs mean-removing.
            val body = bodyMix * w * sin(2.0 * PI * k2 * phase).toFloat()
            out[i] = amp.at(t) * burst + bodyEnv.at(t) * body
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
