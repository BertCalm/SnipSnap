package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * KERF's renderer: fitted numbers in, a chopped drum hit out
 * (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md). A port of `prototype/kerf/resynth.py`.
 *
 * The characteristic gesture: the last hit still ringing → a new hit lands on top of it → its own ring and wash →
 * the knife comes down early or late.
 *
 * What each stage is for:
 * - **Body** ([body]): sine oscillators with a pitch glide. A kick's thump is a falling pitch, which a sine follows
 *   exactly.
 * - **Noise** ([noise]): seeded noise shaped by [BAND_COUNT] bands. Each band is a fast crack on a slower ring over a
 *   floor that never dies, plus a layer for every later hit inside the slice.
 * - **Colour** ([coloured]): the noise first passes through up to 48 narrow resonances. Without them a cymbal's ring
 *   is hiss, and hiss under a kick is masked.
 * - **Ticks**: bursts of about a millisecond where the stick landed. The noise is shaped in 5.8 ms windows and cannot
 *   make anything sharper than that.
 * - **Chop** ([chopped]): the previous hit's patch, rendered past its own cut, bleeds underneath; the two cut points
 *   are a little off.
 *
 * No 4× internal rate, on purpose: nothing here is nonlinear (sines under 15 kHz, linearly filtered noise, short
 * band-limited bursts), so nothing can alias and oversampling would buy nothing. A saturation stage added later must
 * run oversampled in the house way.
 *
 * There is no feedback path, so there is no stability argument to make: every component is a decaying envelope times
 * a bounded source.
 *
 * The noise is [Dsp.Noise], not the prototype's generator, so a Kotlin render differs from a Python one sample for
 * sample. What must agree is the energy in each band, the level and the length; `KerfRenderTest` holds them to the
 * Python figures for all 64 pads of [KerfBank].
 */
object KerfRender {
    const val MODEL_VERSION = 1
    const val RATE = Dsp.RATE

    /** Window and hop of the noise shaper: 5.8 ms windows every 0.73 ms. */
    const val WINDOW = 256
    const val HOP = 32
    private const val BINS = WINDOW / 2 + 1

    /**
     * Band edges in FFT bins (172 Hz each): single bins to about 1.4 kHz, then widening by about a third of an octave.
     * Built from bins so no band is ever empty; an empty band fits as silence and drags its neighbours down.
     * These are the prototype's `BINS`, and a patch's bands are meaningless against any other set.
     */
    val EDGES = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 13, 16, 20, 25, 32, 40, 50, 63, 79, 100, 126, BINS)
    const val BAND_COUNT = 19

    private val window = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / WINDOW) }
    private val windowSum = window.sum()
    private fun binHz(bin: Int) = bin.toDouble() * RATE / WINDOW
    private val centres = DoubleArray(BAND_COUNT) { sqrt(binHz(EDGES[it]) * binHz(EDGES[it + 1] - 1)) }

    /** The centre of band [b] in Hz (the geometric mean of its first and last bin). */
    fun bandCentreHz(b: Int): Double = centres[b]

    /**
     * The render-side controls. All defaults reproduce the fit.
     * @param cut fraction of the slice rendered before the edge.
     * @param floor multiplies every band's floor (the wash under the hit).
     * @param decay multiplies every decay time; the crack only ever shortens.
     * @param tuneSemitones moves the body, the resonances and the band centres together.
     */
    data class Controls(
        val cut: Double = 1.0, val floor: Double = 1.0, val decay: Double = 1.0, val tuneSemitones: Double = 0.0,
        val layers: Boolean = true, val colour: Boolean = true,
        /**
         * Where the floor (the loop's wash under the hit) stops, in seconds: past it the floor dies 60 dB in
         * [FLOOR_STOP_T60]. Infinite by default, so every chop renders exactly as before; [ring] sets it to the slice's
         * end, because the wash belongs to the loop and a ringing note carries on without it.
         */
        val floorUntil: Double = Double.POSITIVE_INFINITY,
    )

    /** How fast the floor dies past [Controls.floorUntil]: 60 dB in this long. */
    const val FLOOR_STOP_T60 = 0.03

    /** The longest a [ring] may run. A decay fitted over a fifth of a second can claim a minute; the ear sets this. */
    const val RING_MAX_SECONDS = 4.0

    /** The fade every [ring] ends on. */
    const val RING_FADE_SECONDS = 0.15

    /**
     * A struck note rung out past its cut (the pitched-pad round, 2026-11-21): in a loop the next note lands long before
     * a bar or a bell stops ringing, so the fit saw only the front of the ring. Every partial and every band keeps the
     * decay the fit measured; the floor stops at the slice's end ([Controls.floorUntil]); the note runs until its
     * slowest partial or band has fallen 60 dB, at least the slice, at most [maxSeconds], and ends on a
     * [RING_FADE_SECONDS] fade. No neighbours, no knife: a pitched pad is the note, not the chop.
     */
    fun ring(patch: KerfPatchData, controls: Controls = Controls(), maxSeconds: Double = RING_MAX_SECONDS): FloatArray {
        val c = controls.copy(floorUntil = patch.seconds * controls.cut)
        // only what is within 40 dB of the loudest part sets the length: a whisper with a long fitted decay must not
        // hold every note to the cap
        val loudest = patch.partials.maxOfOrNull { it.amp } ?: 0.0
        val partialEnd = patch.partials.filter { it.amp >= loudest * 0.01 }.maxOfOrNull { it.attackS + it.t60 * c.decay } ?: 0.0
        // a band's power falls 60 dB in its t60 by bandEnvelope's law (10^(-6 t / t60) in power)
        val ringing = patch.bands.maxOf { it.level }
        val bandEnd = patch.bands.filter { it.level >= ringing * 1e-4 }.maxOfOrNull { it.attackS + it.t60 * c.decay } ?: 0.0
        val seconds = max(patch.seconds * c.cut, min(maxSeconds, max(partialEnd, bandEnd)))
        val out = raw(patch, c, extendSeconds = seconds - patch.seconds * c.cut)
        fadeOut(out, (RING_FADE_SECONDS * RATE).toInt())
        return FloatArray(out.size) { out[it].toFloat() }
    }

    /** A chopped render and where its knife landed. */
    class Chop(val samples: FloatArray, val startMs: Double, val overMs: Double, val fadeMs: Double)

    /** One hit, clean: no neighbours, cut where the next hit began, with a 1 ms fade when [edge] is set. */
    fun render(patch: KerfPatchData, controls: Controls = Controls(), extendSeconds: Double = 0.0, edge: Boolean = true): FloatArray {
        val out = raw(patch, controls, extendSeconds)
        if (edge) fadeOut(out, (0.001 * RATE).toInt())
        return FloatArray(out.size) { out[it].toFloat() }
    }

    /** The hit before its edge, for the fitter, which measures a render against its source ([KerfFit]). */
    internal fun rawRender(patch: KerfPatchData): DoubleArray = raw(patch, Controls())

    /** The body oscillators alone, added into [out]: what the fitter subtracts before it looks for the next one. */
    internal fun addBody(patch: KerfPatchData, out: DoubleArray) = body(patch, Controls(), 1.0, out)

    /** The hit before its edge, in doubles, so [chopped] with nothing to add lands on the same bytes as [render]. */
    private fun raw(patch: KerfPatchData, controls: Controls, extendSeconds: Double = 0.0): DoubleArray {
        val n = Math.rint((patch.seconds * controls.cut + extendSeconds) * RATE).toInt().coerceAtLeast(1)
        val ratio = 2.0.pow(controls.tuneSemitones / 12.0)
        val out = DoubleArray(n)
        body(patch, controls, ratio, out)
        noise(patch, controls, ratio, out)
        ticks(patch, ratio, out)
        return out
    }

    /**
     * The hit as a hand-made chop.
     *
     * BLEED: the previous hit keeps ringing under this one. It is that hit's own patch, rendered past the point
     * where it was cut, dying away over half a second. The fit already holds some of the previous hit, so this is
     * extra on top.
     *
     * SLOP: where the knife landed, from [draw] (or from the patch's seed), so a pad always chops the same way.
     * A start that is early puts a few ms of the previous tail in front of the attack; a start that is late shaves the
     * attack; an end that is late catches the front of the next hit; and the end fade shrinks from 1 ms towards
     * nothing, so the cut can click. Late starts are rarer and capped at 3 ms because they cost punch.
     *
     * `bleed = 0, slop = 0` is exactly [render]. The neighbours follow TUNE and DECAY, not CUT: a chop pitched down
     * has its bleed pitched down with it.
     */
    fun chopped(
        patch: KerfPatchData, prev: KerfPatchData?, next: KerfPatchData?, bleed: Double, slop: Double,
        controls: Controls = Controls(), draw: KerfDraw? = null,
        /**
         * The level of the next hit's front where the cut runs late, against its own. 1 for a chop as cut; a soft
         * strike ([KerfVelocity]) passes the hit's own gain, because the overrun is the knife's error, sized to the
         * hit that was chopped. The previous hit's tail is not scaled: that is the bleed, and it stays.
         */
        nextGain: Double = 1.0,
    ): Chop {
        val d = draw ?: drawFor(patch.seed)
        val startMs = if (d.which > 0.25) slop * (-3.0 + 15.0 * d.start) else -slop * 3.0 * d.start
        val overMs = if (next != null) slop * 36.0 * d.end.pow(1.5) else 0.0
        val neighbour = Controls(decay = controls.decay, tuneSemitones = controls.tuneSemitones)

        val over = (overMs * RATE / 1000).toInt()
        val hit = raw(patch, controls, extendSeconds = over.toDouble() / RATE)
        if (over > 0 && next != null && next.seconds * RATE >= over) {
            val head = raw(next, neighbour)   // this hit keeps ringing under the next one's attack
            val at = hit.size - over
            for (i in 0 until min(over, head.size)) if (at + i >= 0) hit[at + i] += head[i] * nextGain
        }
        val pre = if (prev != null) (max(startMs, 0.0) * RATE / 1000).toInt() else 0
        val shave = min((max(-startMs, 0.0) * RATE / 1000).toInt(), hit.size - 1)
        val out = DoubleArray(pre + hit.size - shave)
        System.arraycopy(hit, shave, out, pre, hit.size - shave)

        if (prev != null && (bleed > 0.0 || pre > 0)) {
            val cutAt = Math.rint(prev.seconds * RATE).toInt()
            val tail = raw(prev, neighbour, extendSeconds = out.size.toDouble() / RATE + 0.01)
            val from = max(0, cutAt - pre)
            for (i in out.indices) {
                if (from + i >= tail.size) break
                val t = (i - pre).toDouble() / RATE
                // the pre-roll is the real tail at full level; under the hit it is BLEED, fading 60 dB in half a second
                val g = if (t < 0) 1.0 else bleed * 10.0.pow(-3.0 * t / 0.5)
                out[i] += tail[from + i] * g
            }
        }
        val fade = max(2, ((1.0 - 0.9 * slop) * 0.001 * RATE).toInt())
        fadeOut(out, fade)
        return Chop(FloatArray(out.size) { out[it].toFloat() }, startMs, overMs, fade * 1000.0 / RATE)
    }

    /** The draw a pad gets when none was stored with it: three numbers in 0..1 from the patch's own seed. */
    fun drawFor(seed: Long): KerfDraw {
        val noise = Dsp.Noise(Dsp.seedFor(seed, "slop"))
        fun unit() = ((noise.next() + 1f) / 2f).toDouble().coerceIn(0.0, 1.0)
        return KerfDraw(unit(), unit(), unit())
    }

    /** A render as a mono [Snip], turned down only if it would clip. */
    fun toSnip(samples: FloatArray, ceiling: Float = 0.9f): Snip {
        var peak = 0f
        for (v in samples) peak = max(peak, abs(v))
        val g = ceiling / max(1f, peak)
        return Snip(FloatArray(samples.size) { (samples[it] * g).coerceIn(-1f, 1f) }, 1, RATE)
    }

    // ---------------------------------------------------------------- body

    private fun body(patch: KerfPatchData, c: Controls, ratio: Double, out: DoubleArray) {
        for (p in patch.partials) {
            val hz = p.hzEnd * ratio; val glide = p.glideHz * ratio; val t60 = p.t60 * c.decay
            val attack = p.attackS; val start = min(p.startS, attack)
            val ramps = attack - start > 0.0015 || start > 0.0
            val span = max(attack - start, 5e-4)
            var phase = p.phase
            for (i in out.indices) {
                val t = i.toDouble() / RATE
                phase += 2 * PI * (hz + glide * exp(-t / p.glideS)) / RATE
                val env = when {
                    ramps && t < attack -> ((t - start) / span).coerceIn(0.0, 1.0)
                    else -> 10.0.pow(-3.0 * max(t - attack, 0.0) / t60)
                }
                out[i] += p.amp * env * cos(phase)
            }
        }
    }

    // ---------------------------------------------------------------- noise

    /** One band's power over time: the attack ramp, the crack, the ring, the floor, and every later layer. */
    internal fun bandEnvelope(b: KerfBand, c: Controls, t: Double): Double {
        val t60 = b.t60 * c.decay; val snapT60 = b.snapT60 * min(1.0, c.decay)
        val floor = b.floor * c.floor * (if (t > c.floorUntil) 10.0.pow(-6.0 * (t - c.floorUntil) / FLOOR_STOP_T60) else 1.0)
        val tt = t - b.attackS
        var e = if (b.attackS > 1e-4 && tt < 0) {
            val ramp = (t / b.attackS).coerceIn(0.0, 1.0)
            (b.snap + b.level + floor) * ramp * ramp
        } else {
            val tp = max(tt, 0.0)
            b.snap * 10.0.pow(-6.0 * tp / snapT60) + b.level * 10.0.pow(-6.0 * tp / t60) + floor
        }
        if (c.layers) for (ly in b.layers) {
            val tl = t - ly.atS
            val on = (1.0 + tl / 0.0015).coerceIn(0.0, 1.0)   // 1.5 ms rise into each later layer
            val tp = max(tl, 0.0)
            e += on * on * (ly.snap * 10.0.pow(-6.0 * tp / snapT60) + ly.level * 10.0.pow(-6.0 * tp / t60))
        }
        return e
    }

    private fun noise(patch: KerfPatchData, c: Controls, ratio: Double, out: DoubleArray) {
        val n = out.size
        val need = n + WINDOW
        val gen = Dsp.Noise(Dsp.seedFor(patch.seed))
        var size = 1
        while (size < need) size = size shl 1
        val white = DoubleArray(size) { gen.next().toDouble() }
        // The gain law below is "this much power in this band in this frame", measured on the source with the same
        // transform. Dividing by the noise's own mean power per bin makes the noise a unit source for it.
        val reference = meanPower(stft(white, need))
        val source = if (c.colour && patch.colour != null) coloured(white, patch.colour, ratio) else white
        val frames = stft(source, need)

        val logCentres = DoubleArray(BAND_COUNT) { ln(centres[it] * ratio) }
        val logBin = DoubleArray(BINS) { ln(max(binHz(it), 1.0)) }
        val logGain = DoubleArray(BAND_COUNT)
        val norm = 1.0 / sqrt(reference)
        for ((j, frame) in frames.withIndex()) {
            val t = j.toDouble() * HOP / RATE
            for (b in 0 until BAND_COUNT) {
                val width = EDGES[b + 1] - EDGES[b]
                logGain[b] = 0.5 * ln(bandEnvelope(patch.bands[b], c, t) / width + 1e-30)
            }
            frame.re[0] = 0f; frame.im[0] = 0f
            for (k in 1 until BINS) {
                // Between band centres the gain is interpolated in log frequency; outside them it holds the end value.
                // (The prototype silenced everything below the first centre, which muted the lowest band whenever
                // TUNE went up. Holding the end value is the one deliberate difference from it.)
                val g = exp(interpolate(logBin[k], logCentres, logGain)) * norm
                frame.re[k] = (frame.re[k] * g).toFloat(); frame.im[k] = (frame.im[k] * g).toFloat()
            }
        }
        val y = istft(frames, need)
        for (i in 0 until n) out[i] += y[i]
    }

    /**
     * The noise through the resonance bank. The curve is rescaled to average 1 inside each coarse band, so the band
     * fit's energy stays where the fit put it and only the fine grain changes.
     */
    private fun coloured(white: DoubleArray, colour: KerfColour, ratio: Double): DoubleArray {
        val size = white.size
        val re = FloatArray(size) { white[it].toFloat() }; val im = FloatArray(size)
        Fft.forward(re, im)
        val half = size / 2
        val curve = DoubleArray(half + 1) { k ->
            val f = k.toDouble() * RATE / size / ratio
            var v = colour.base
            for (r in colour.resonances) { val x = (f - r.hz) / (r.bandwidthHz / 2); v += r.gain / (1 + x * x) }
            v
        }
        val step = RATE.toDouble() / WINDOW
        for (b in 0 until BAND_COUNT) {
            val lo = binHz(EDGES[b]) - step / 2
            val hi = if (EDGES[b + 1] < BINS) binHz(EDGES[b + 1]) - step / 2 else Double.MAX_VALUE
            val from = Math.ceil(lo * size / RATE).toInt().coerceIn(0, half + 1)
            val to = if (hi == Double.MAX_VALUE) half + 1 else Math.ceil(hi * size / RATE).toInt().coerceIn(0, half + 1)
            if (to <= from) continue
            var sum = 0.0
            for (k in from until to) sum += curve[k]
            val mean = sum / (to - from)
            if (mean > 0) for (k in from until to) curve[k] /= mean
        }
        for (k in 0..half) {
            val g = sqrt(curve[k]).toFloat()
            re[k] *= g; im[k] *= g
            if (k in 1 until half) { re[size - k] *= g; im[size - k] *= g }
        }
        Fft.inverse(re, im)
        return DoubleArray(size) { re[it].toDouble() }
    }

    // ---------------------------------------------------------------- ticks

    private fun ticks(patch: KerfPatchData, ratio: Double, out: DoubleArray) {
        for ((k, tick) in patch.ticks.withIndex()) {
            val at = (tick.atS * RATE).toInt()
            val m = min(96, out.size - at)
            if (at < 0 || m < 8) continue
            // two cycles of a damped tone at the tick's own brightness, plus seeded noise: sharp, short, and not a
            // sample of anything
            val gen = Dsp.Noise(Dsp.seedFor(patch.seed, "tick", k))
            val burst = DoubleArray(m) { i ->
                val t = i.toDouble() / RATE
                sin(2 * PI * tick.hz * ratio * t) * exp(-t / 0.0006) + 0.5 * gen.next() * exp(-t / 0.0004)
            }
            val peak = burst.maxOf { abs(it) }
            if (peak <= 0) continue
            for (i in 0 until m) out[at + i] += tick.amp * burst[i] / peak
        }
    }

    // ---------------------------------------------------------------- transform

    internal class Frame(val re: FloatArray, val im: FloatArray)

    /**
     * Short-time transform of the first [length] samples: Hann windows of [WINDOW] every [HOP], the signal padded by
     * half a window at each end, each frame divided by the window's sum. Frame `j` is centred on sample `j * HOP`.
     * This is the transform the patch's band figures were measured with, so its scaling is part of the patch format.
     */
    internal fun stft(x: DoubleArray, length: Int = x.size): List<Frame> {
        val padded = length + WINDOW
        val count = 1 + Math.ceil((padded - WINDOW).toDouble() / HOP).toInt()
        return List(count) { j ->
            val re = FloatArray(WINDOW); val im = FloatArray(WINDOW)
            for (k in 0 until WINDOW) {
                val i = j * HOP + k - WINDOW / 2
                if (i in 0 until length) re[k] = (x[i] * window[k] / windowSum).toFloat()
            }
            Fft.forward(re, im)
            Frame(re, im)
        }
    }

    /** Overlap-add inverse of [stft], mirroring the upper half of each spectrum from the lower. */
    private fun istft(frames: List<Frame>, length: Int): DoubleArray {
        val out = DoubleArray(length); val weight = DoubleArray(length)
        for ((j, f) in frames.withIndex()) {
            for (k in 1 until WINDOW / 2) { f.re[WINDOW - k] = f.re[k]; f.im[WINDOW - k] = -f.im[k] }
            Fft.inverse(f.re, f.im)
            for (k in 0 until WINDOW) {
                val i = j * HOP + k - WINDOW / 2
                if (i in 0 until length) { out[i] += f.re[k] * windowSum * window[k]; weight[i] += window[k] * window[k] }
            }
        }
        for (i in 0 until length) if (weight[i] > 1e-10) out[i] /= weight[i]
        return out
    }

    private fun meanPower(frames: List<Frame>): Double {
        var sum = 0.0
        for (f in frames) for (k in 0 until BINS) sum += f.re[k].toDouble() * f.re[k] + f.im[k].toDouble() * f.im[k]
        return sum / (frames.size * BINS)
    }

    /**
     * Energy in each band summed over the whole of [x], with the bin below the first band as element 0. The measure
     * a render is held to against its fit (and, later, what the fitter measures a source with).
     */
    fun bandEnergies(x: FloatArray): DoubleArray {
        val frames = stft(DoubleArray(x.size) { x[it].toDouble() })
        val out = DoubleArray(BAND_COUNT + 1)
        for (f in frames) {
            out[0] += f.re[0].toDouble() * f.re[0] + f.im[0].toDouble() * f.im[0]
            for (b in 0 until BAND_COUNT) for (k in EDGES[b] until EDGES[b + 1]) {
                out[b + 1] += f.re[k].toDouble() * f.re[k] + f.im[k].toDouble() * f.im[k]
            }
        }
        return out
    }

    private fun interpolate(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x <= xs[0]) return ys[0]
        if (x >= xs[xs.size - 1]) return ys[ys.size - 1]
        var i = 1
        while (xs[i] < x) i++
        val f = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
        return ys[i - 1] + f * (ys[i] - ys[i - 1])
    }

    private fun fadeOut(buf: DoubleArray, frames: Int) {
        val m = min(frames, buf.size)
        if (m < 2) return
        for (i in 0 until m) buf[buf.size - m + i] *= 1.0 - i.toDouble() / (m - 1)
    }
}
