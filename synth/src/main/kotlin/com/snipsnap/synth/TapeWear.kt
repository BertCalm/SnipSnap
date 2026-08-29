package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.cos

/**
 * Tape wear — what earned mileage *sounds* like. The chain is a gentler
 * cousin of the `tape` era, parameterised by wear `w` (the ledger's
 * `1 − exp(−mileage/K)` curve): flutter via a slow resample wobble, a
 * high-frequency shelf, the rare dropout, and a whisper of hiss.
 *
 * **The maximum is the feature.** Earned wear lives in `w ∈ [0, 1)` and
 * the caps below say exactly what a lifetime of plays is allowed to do:
 * flutter never past ±6 cents, hiss never above −48 dBFS, the shelf
 * corner never below 8 kHz, dropouts rare — and *never on a strong hit*
 * (the envelope protects them structurally). Past `w = 1` is reachable
 * only by a deliberate override: chosen destruction is a treatment,
 * earned patina is capped.
 *
 * Deterministic per (seed, w): the same tape always has the same
 * scratches. And `w = 0` is a bit-identical passthrough — a new tape
 * *is* the pristine audio, not a subtle copy of it.
 */
object TapeWear {

    // ---- the caps: what w = 1.0 is allowed to sound like -------------------

    const val MAX_FLUTTER_CENTS = 6f
    const val MAX_HISS_DB = -48f
    const val MIN_SHELF_HZ = 8_000f

    /** Above the shelf corner the top end fades to this gain at full wear. */
    const val SHELF_FLOOR_GAIN = 0.35f

    /** Envelope above this fraction of the peak is a hit — protected. */
    const val HIT_PROTECT = 0.4f

    /** Even deliberate destruction tops out — past this isn't tape any more. */
    const val MAX_OVERRIDE_W = 3f

    private const val FLUTTER_RATE_HZ = 5.5f
    private const val DROPOUT_SEC = 0.035f
    private const val DROPOUT_FLOOR = 0.12f

    /** Expected dropouts per second at full wear — rare is the point. */
    private const val DROPOUTS_PER_SEC = 1f / 6f

    fun process(snip: Snip, w: Float, seed: Int = 0): Snip {
        require(w.isFinite() && w >= 0f && w <= MAX_OVERRIDE_W) {
            "wear is 0..$MAX_OVERRIDE_W, got $w"
        }
        if (w == 0f) return snip
        var s = Eras.wow(snip, depthCents = flutterCents(w), rateHz = FLUTTER_RATE_HZ)
        s = shelf(s, shelfHz(w), shelfGain(w))
        s = dropouts(s, w, seed)
        return hiss(s, w, seed)
    }

    // ---- the curve's parameters, each honouring its cap at w = 1 -----------

    /** Linear in w: ≤ [MAX_FLUTTER_CENTS] for every earned wear. */
    internal fun flutterCents(w: Float): Float = MAX_FLUTTER_CENTS * w

    /** Slides 18 kHz → 8 kHz and stops: never below [MIN_SHELF_HZ], override included. */
    internal fun shelfHz(w: Float): Float =
        maxOf(MIN_SHELF_HZ, 18_000f + (MIN_SHELF_HZ - 18_000f) * w)

    internal fun shelfGain(w: Float): Float =
        (1f + (SHELF_FLOOR_GAIN - 1f) * w).coerceAtLeast(0.05f)

    /** Peak hiss amplitude: exactly the −48 dBFS cap at w = 1. */
    internal fun hissAmp(w: Float): Float =
        Math.pow(10.0, MAX_HISS_DB / 20.0).toFloat() * w

    // ---- the stages --------------------------------------------------------

    /** A high shelf: below the corner untouched, above it faded toward [gain]. */
    private fun shelf(snip: Snip, cornerHz: Float, gain: Float): Snip {
        if (gain >= 1f) return snip
        val low = Eras.onePoleLowpass(snip, cornerHz)
        return Snip(
            FloatArray(snip.samples.size) { i ->
                low.samples[i] + (snip.samples[i] - low.samples[i]) * gain
            },
            snip.channels, snip.sampleRate,
        )
    }

    /**
     * Where the oxide let go. A span is eligible only when *everything* it
     * would touch sits below [HIT_PROTECT] × the tape's peak — a dropout
     * lands in the pocket between hits, never on one. Rare by rate (about
     * one per six seconds at full wear), deterministic per seed.
     */
    internal fun dropoutSpans(snip: Snip, w: Float, seed: Int): List<IntRange> {
        val frames = snip.frameCount
        if (frames == 0) return emptyList()
        var peak = 0f
        for (s in snip.samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        if (peak <= 0f) return emptyList()

        val dropFrames = (DROPOUT_SEC * snip.sampleRate).toInt().coerceAtLeast(1)
        val step = dropFrames / 2
        val quiet = ArrayList<Int>()
        var start = 0
        while (start + dropFrames <= frames) {
            var localMax = 0f
            for (f in start until start + dropFrames) {
                for (c in 0 until snip.channels) {
                    val a = abs(snip.samples[f * snip.channels + c])
                    if (a > localMax) localMax = a
                }
            }
            if (localMax < HIT_PROTECT * peak) quiet += start
            start += step
        }
        if (quiet.isEmpty()) return emptyList()

        val seconds = frames.toFloat() / snip.sampleRate
        val expected = seconds * DROPOUTS_PER_SEC * minOf(w, 1f)
        val rnd = kotlin.random.Random(seed * 31 + 0x7A9E)
        var count = expected.toInt()
        if (rnd.nextFloat() < expected - count) count++
        if (count == 0) return emptyList()
        return (0 until count)
            .map { quiet[rnd.nextInt(quiet.size)] }
            .distinct()
            .sorted()
            .map { it until minOf(frames, it + dropFrames) }
    }

    private fun dropouts(snip: Snip, w: Float, seed: Int): Snip {
        val spans = dropoutSpans(snip, w, seed)
        if (spans.isEmpty()) return snip
        val out = snip.samples.copyOf()
        for (span in spans) {
            val len = span.last - span.first + 1
            for (i in 0 until len) {
                // Raised-cosine dip: unity at the edges, the floor mid-span.
                val dip = 0.5f * (1f - cos(2.0 * Math.PI * i / len).toFloat())
                val g = 1f - (1f - DROPOUT_FLOOR) * dip
                val f = span.first + i
                for (c in 0 until snip.channels) out[f * snip.channels + c] *= g
            }
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** Seeded, deterministic noise under everything — the tape's breath. */
    private fun hiss(snip: Snip, w: Float, seed: Int): Snip {
        val amp = hissAmp(w)
        if (amp <= 0f) return snip
        val rnd = kotlin.random.Random(seed * 31 + 0x4155)
        return Snip(
            FloatArray(snip.samples.size) { i ->
                snip.samples[i] + (rnd.nextFloat() * 2 - 1) * amp
            },
            snip.channels, snip.sampleRate,
        )
    }
}
