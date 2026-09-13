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

    fun apply(buf: FloatArray, amount: Float, rate: Int = Dsp.RATE) {
        if (amount <= 0f || buf.isEmpty()) return
        val punch = amount.coerceIn(0f, 1f)

        val before = Loudness.of(Snip(buf.copyOf(), channels = 1, sampleRate = rate))

        val windowSamples = (ATTACK_WINDOW_SECONDS * rate).toInt().coerceAtLeast(1)
        // Cubic, both of them: a multi-onset voice like THUMP's CLAP (several
        // equal-height bursts a few ms apart) only has its very first burst
        // inside this window, so any shaping here inflates burst 1 relative
        // to the others - and Dsp.normalize's peak-rescale right after Punch
        // then shrinks every other burst by the same factor. Classifier.kt's
        // attackBurstCount needs each burst above 40% of the take's peak, so
        // burst 1 can't end up more than 2.5x the rest. Cubic keeps full
        // strength at punch=1 (where PunchTest's single-onset crest-factor
        // proof lives) while keeping PUNCH's default (0.5) mild enough that
        // CLAP still reads as four bursts, not one.
        val satAmount = punch * punch * punch * 0.3f
        val boostGain = punch * punch * punch * 6f
        val cutoffSamples = windowSamples * 4
        // exp() never actually reaches zero, so a raw exp(-i/window) cut off
        // at cutoffSamples leaves a small but real step in the gain right at
        // the boundary - subtracting its own value there pulls the whole
        // taper down to exactly zero at the cutoff instead, so the last
        // shaped sample meets the first untouched one at the same gain.
        val cutoffTaper = exp(-cutoffSamples.toFloat() / windowSamples)
        for (i in 0 until minOf(cutoffSamples, buf.size)) {
            val taper = exp(-i.toFloat() / windowSamples) - cutoffTaper
            buf[i] = Dsp.drive(buf[i], satAmount * taper) * (1f + boostGain * taper)
        }

        val after = Loudness.of(Snip(buf.copyOf(), channels = 1, sampleRate = rate))
        if (after > 1e-6f && before > 1e-6f) {
            val gain = before / after
            for (i in buf.indices) buf[i] *= gain
        }
    }
}
