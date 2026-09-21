package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.exp

/**
 * PUNCH — U3 of [docs/SYNTH_UPGRADE.md](../../../../../../../docs/SYNTH_UPGRADE.md).
 *
 * Peak normalisation says nothing about how hard a hit feels: a commercial
 * kick hits because of transient shaping and saturation, not because its
 * peak sample happens to be high. U3's own framing (docs/SYNTH_UPGRADE.md)
 * is to *swap* the peak target for a perceived-level one, not add a second
 * target alongside it - so an engine calls [Dsp.normalize] **before** this
 * stage, to fix the reference level Punch then preserves, and
 * [Dsp.limitPeak] **after**, as a true safety net that only steps in if the
 * shaping below pushed a sample past what's safe, instead of unconditionally
 * overwriting the level this stage just chose (which a second
 * [Dsp.normalize] afterward would have done silently, cancelling the "more
 * PUNCH reshapes the hit, it doesn't just get louder" guarantee below). The
 * actual work, in order:
 *
 * 1. **Soft saturation, transient-only** — glue and harmonics, reusing
 *    [Dsp.drive], scoped to the same short onset window as the boost below
 *    rather than the whole buffer. It wasn't always: saturating the whole
 *    signal broke [Thump]'s KICK the moment it also went through [Dub]'s own
 *    saturating generations - `lowRatio` (the bass-dominance test that reads
 *    KICK vs. PERC) turned out to be on such a knife's edge already
 *    (0.574 vs. a 0.55 cutoff) that even a barely-perceptible whole-buffer
 *    saturation amount (0.005, far below what's audible) tipped it, while
 *    the *identical* amount confined to a handful of onset samples left
 *    `lowRatio` untouched, because it never reaches the sustained
 *    low-frequency body a bass-heavy voice's identity actually lives in.
 *    Order matters: [Dsp.drive] is a compressor (peak-calibrated, so it
 *    inherently narrows dynamic range - the same lesson [Dsp.TptSvf]'s own
 *    `saturate` parameter learned the hard way), and running it *after* the
 *    boost would squash the just-boosted attack hardest of anything in the
 *    buffer, fighting the transient shaping rather than gluing it.
 * 2. **Transient shaping** — a boost that's strongest at t=0 and decays
 *    smoothly over the same short, fixed window. A signal-adaptive envelope-
 *    follower difference (the textbook transient-designer circuit) was
 *    tried first and discarded: getting a fast/slow follower pair to
 *    track cleanly across THUMP's full range of decay times (RIM's ~30ms
 *    to KICK's ~850ms) without either lagging behind a short hit's real
 *    onset or staying elevated for most of the hit turned into exactly
 *    the kind of per-voice tuning fragility [Dsp.TptSvf]'s `saturate`
 *    parameter also ran into. Every one-shot voice here starts its
 *    attack at exactly t=0 by construction, so a fixed early window is
 *    both simpler and more reliable than trying to detect what's already
 *    known. Applied last, so nothing downstream re-compresses the
 *    result.
 * 3. **Loudness-targeted normalise** — [Loudness.of] (`:audio`, already
 *    used for kit balance) measures perceived level before and after, and
 *    the result is rescaled back to the pre-PUNCH loudness. Without this,
 *    "more PUNCH" would really just mean "louder", which is the exact
 *    loudness-war confound a transient/saturation stage should not be
 *    testable against. A uniform rescale can't touch crest factor, so it
 *    doesn't undo the shaping above.
 */
internal object Punch {

    /**
     * How long the transient boost (and, with it, the saturation above) lasts
     * before it's tapered away to nothing. Deliberately tight: a wider window
     * (tried first, at 8ms) spread the boost across most of a typical short
     * one-shot's entire decay, which fights the transient shaping instead of
     * accenting it - and, for saturation, reaches into the sustained body a
     * bass-heavy voice's classifier identity depends on (see the KICK/[Dub]
     * story above).
     */
    private const val ATTACK_WINDOW_SECONDS = 0.0005f

    /**
     * The onset window's own geometry, shared by [saturate] and [boost] so
     * both taper identically across whichever rate they're called at.
     *
     * [frameCount] - not a raw sample count - so the window covers the same
     * real-time duration whether [saturate]/[boostEnvelope] are shaping a
     * mono buffer or an interleaved stereo one; a raw sample count would
     * halve the window's real duration on stereo, the same class of bug
     * Task 3a fixed in [Dsp.fadeTail]/[Dsp.decimate]/[Dsp.levelTo].
     */
    private class Window(rate: Int, frameCount: Int) {
        val samples = (ATTACK_WINDOW_SECONDS * rate).toInt().coerceAtLeast(1)
        val cutoffSamples = samples * 4
        val lastShaped = minOf(cutoffSamples, frameCount) - 1

        // exp() never actually reaches zero, so a raw exp(-i/window) cut off
        // at the last shaped sample leaves a small but real step in the gain
        // right at the boundary - subtracting the taper's own value AT that
        // last sample (which, for a buffer shorter than the window's own
        // cutoff, is the buffer's own last sample, not the window's) pulls
        // the whole taper down to exactly zero there instead, so it meets
        // the first untouched sample at the same gain.
        private val cutoffTaper = if (lastShaped >= 0) exp(-lastShaped.toFloat() / samples) else 0f
        fun taperAt(i: Int): Float = exp(-i.toFloat() / samples) - cutoffTaper
    }

    /**
     * The nonlinear stage of Punch's transient shaping: soft saturation
     * (reusing [Dsp.drive]), confined to the onset window. Split out so a
     * U6-oversampling engine (docs/SYNTH_UPGRADE.md) can call this BEFORE
     * [Dsp.decimate], at its own renderRate: [Dsp.drive] is a static
     * nonlinearity and mints new harmonics wherever it runs, so applying it
     * after decimation would hand U6's whole anti-aliasing story right back
     * to a raw oscillator's problem, with nothing left downstream to
     * band-limit what it creates (measured: PunchTest's aliasing
     * regression). [apply] calls this for the ordinary (non-oversampled)
     * case.
     *
     * On an interleaved stereo [buf] ([channels] == 2), [Dsp.drive] is
     * shaped into the FOLD (L+R) at each onset frame, not each channel
     * independently, then redistributed back across L/R in their original
     * proportion. `drive` is a nonlinearity, and a nonlinearity does not
     * commute with panning: `drive(L) + drive(R) != drive(L + R)` in
     * general, so saturating each channel on its own would leave a wide
     * SNARE's onset a scaled-and-reshaped version of the mono render rather
     * than the same signal at one gain - exactly the comb-notching-shaped
     * hazard this task's own fold-down test exists to catch (measured: an
     * earlier version that skipped shaping entirely on the stereo path
     * instead left an 8% relative residual there, concentrated at the loud
     * onset). Reshaping the fold and preserving each channel's share of it
     * keeps the image's pan ratio untouched while still applying PUNCH's
     * curve to the same total energy the mono path shapes.
     */
    fun saturate(buf: FloatArray, amount: Float, rate: Int = Dsp.RATE, channels: Int = 1) {
        if (amount <= 0f || buf.isEmpty()) return
        val punch = amount.coerceIn(0f, 1f)
        // Cubic: see the doc comment on [apply] for why.
        val satAmount = punch * punch * punch * 0.3f
        val frames = buf.size / channels
        val window = Window(rate, frames)
        for (f in 0..window.lastShaped) {
            val taper = satAmount * window.taperAt(f)
            if (channels <= 1) {
                buf[f] = Dsp.drive(buf[f], taper)
                continue
            }
            var sum = 0f
            for (c in 0 until channels) sum += buf[f * channels + c]
            if (kotlin.math.abs(sum) <= 1e-9f) continue
            val shaped = Dsp.drive(sum, taper)
            val scale = shaped / sum
            for (c in 0 until channels) buf[f * channels + c] *= scale
        }
    }

    /**
     * The transient boost itself: a smooth per-sample gain envelope,
     * strongest at t=0. No loudness-matching here - that's
     * [rescaleToLoudness], deliberately separate. Like [saturate], this
     * belongs BEFORE [Dsp.decimate] too: the envelope changes fast enough
     * over its own short window, and is cut off sharply enough at the end
     * of it, that multiplying by it is not perfectly spectrally
     * transparent either - a bright voice's high-frequency content can
     * pick up sidebands from it that land above the final rate's Nyquist
     * with nothing downstream left to remove them. [rescaleToLoudness]'s
     * own uniform gain, by contrast, genuinely is transparent (a constant
     * multiply can't mint new frequency content), which is exactly why
     * it's the one piece safe to keep after decimation.
     *
     * Unlike [saturate], this is a plain multiply - linear, so applying the
     * SAME per-frame gain to every channel already preserves each channel's
     * share of the signal exactly; no fold/redistribute trick needed here.
     */
    fun boostEnvelope(buf: FloatArray, amount: Float, rate: Int = Dsp.RATE, channels: Int = 1) {
        if (amount <= 0f || buf.isEmpty()) return
        val punch = amount.coerceIn(0f, 1f)
        // Cubic: see the doc comment on [apply] for why.
        val boostGain = punch * punch * punch * 6f
        val frames = buf.size / channels
        val window = Window(rate, frames)
        for (f in 0..window.lastShaped) {
            val g = 1f + boostGain * window.taperAt(f)
            for (c in 0 until channels) buf[f * channels + c] *= g
        }
    }

    /**
     * Rescales [buf] so its current loudness matches [target] - the
     * loudness-targeted normalise U3 is named for. Takes [amount] purely as
     * a no-op guard, matching [saturate] and [boostEnvelope]: an engine
     * that skips those two at PUNCH 0 must skip this one too, or a buffer
     * decimation left quieter than [target] would get audibly rescaled
     * even though PUNCH never touched it.
     */
    fun rescaleToLoudness(buf: FloatArray, amount: Float, target: Float, rate: Int = Dsp.RATE, channels: Int = 1) {
        if (amount <= 0f || buf.isEmpty()) return
        val current = Loudness.of(Snip(buf.copyOf(), channels = channels, sampleRate = rate))
        if (current > 1e-6f && target > 1e-6f) {
            val gain = target / current
            for (i in buf.indices) buf[i] *= gain
        }
    }

    /**
     * All three stages together, for an engine that doesn't oversample:
     * [saturate] and [boostEnvelope], then [rescaleToLoudness] back to
     * [buf]'s own loudness from right before this call - the combination
     * PunchTest proves standalone.
     *
     * A multi-onset voice like THUMP's CLAP (several equal-height bursts a
     * few ms apart) only has its very first burst inside the onset window,
     * so any shaping there inflates burst 1 relative to the others - and
     * the loudness-match rescale then shrinks every other burst by the same
     * factor. Classifier.kt's attackBurstCount needs each burst above 40%
     * of the take's peak, so burst 1 can't end up more than 2.5x the rest.
     * Cubic scaling (above, on both amounts) keeps full strength at
     * punch=1 - where PunchTest's single-onset crest-factor proof lives -
     * while keeping PUNCH's default (0.5) mild enough that CLAP still reads
     * as four bursts, not one.
     */
    fun apply(buf: FloatArray, amount: Float, rate: Int = Dsp.RATE) {
        if (amount <= 0f || buf.isEmpty()) return
        val before = Loudness.of(Snip(buf.copyOf(), channels = 1, sampleRate = rate))
        saturate(buf, amount, rate)
        boostEnvelope(buf, amount, rate)
        rescaleToLoudness(buf, amount, before, rate)
    }

    /**
     * The full U6-aware application (docs/SYNTH_UPGRADE.md) an oversampling
     * engine needs: [saturate] and [boostEnvelope] together on [raw] while
     * it's still at `rate * Dsp.OVERSAMPLE`, decimated down to [rate] via
     * [Dsp.decimate], renormalized (decimation's own resampling kernel
     * loses some of a signal's peak wherever it relies on energy above the
     * new Nyquist a correct band-limiting filter has to remove), then
     * [rescaleToLoudness] against the pre-PUNCH reference - measured by
     * decimating and normalizing an unshaped copy of [raw], the same
     * treatment the real buffer gets, rather than on [raw] itself, which
     * would target a systematically louder reference than any decimated
     * render could actually reach.
     *
     * [raw]'s own rate isn't a separate parameter: [Dsp.decimate] always
     * treats its input as `rate * Dsp.OVERSAMPLE`, so a caller-supplied
     * render rate could silently disagree with what decimate assumes -
     * this derives it the same way decimate does, closing off that
     * mismatch at the boundary rather than trusting every caller to keep
     * the two in sync.
     *
     * Extracted out of [Thump]'s own render loop so the ordering that keeps
     * U6 actually anti-alias-safe - both stages before [Dsp.decimate], only
     * the (genuinely spectrally transparent) final rescale after - is
     * proven once, here, rather than trusted at each call site: PunchTest
     * exercises this function directly, so a future edit that moves either
     * stage back across the decimate boundary fails that test, not just a
     * hand-reconstructed stand-in for it.
     *
     * [channels] threads through [Dsp.decimate], the [Loudness.of]
     * measurements below, and [saturate]/[boostEnvelope] themselves - see
     * their own KDoc for how each stays image-safe on an interleaved
     * buffer (a linear per-frame gain for [boostEnvelope], reshaping the
     * fold and redistributing by original L/R proportion for [saturate]'s
     * nonlinearity).
     */
    fun applyOversampled(raw: FloatArray, amount: Float, rate: Int, channels: Int = 1): FloatArray {
        val renderRate = rate * Dsp.OVERSAMPLE
        val before = if (amount > 0f) {
            val reference = Dsp.decimate(raw.copyOf(), rate, channels)
            Dsp.normalize(reference)
            Loudness.of(Snip(reference, channels = channels, sampleRate = rate))
        } else {
            0f
        }
        saturate(raw, amount, renderRate, channels)
        boostEnvelope(raw, amount, renderRate, channels)
        val buf = Dsp.decimate(raw, rate, channels)
        Dsp.normalize(buf)
        rescaleToLoudness(buf, amount, before, rate, channels)
        return buf
    }
}
