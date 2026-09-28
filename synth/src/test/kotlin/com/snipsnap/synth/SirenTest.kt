package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SIREN measured, not described (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md,
 * "Tests"): the pitch track is read off the render's own zero crossings,
 * and RATE, DEPTH, the LFO's shape and SWEEP's direction are each held to
 * what the knob says.
 */
class SirenTest {

    /** A plain tone: no modulation, no sweep, the button held a while. */
    private val plain = mapOf("DEPTH" to 0f, "SWEEP" to 0.5f, "HOLD" to 0.6f)

    private fun cents(hz: Float, want: Float): Float = 1200f * ln(hz / want) / ln(2f)

    private fun semitones(hi: Float, lo: Float): Float = 12f * ln(hi / lo) / ln(2f)

    /** Every cycle's own frequency from its rising zero crossing to the next: (seconds, hz). A filtered square crosses exactly twice a cycle. */
    private fun cycles(samples: FloatArray, rate: Int): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var last = -1.0
        for (i in 1 until samples.size) {
            val p = samples[i - 1]
            val q = samples[i]
            if (p < 0f && q >= 0f) {
                val x = i - 1 + (-p / (q - p)).toDouble()
                if (last >= 0) out.add((x / rate).toFloat() to (rate / (x - last)).toFloat())
                last = x
            }
        }
        return out
    }

    /** The pitch track on a 1 ms grid between [from] and [to] seconds, each point the cycle in flight. */
    private fun track(samples: FloatArray, rate: Int, from: Float, to: Float): FloatArray {
        val c = cycles(samples, rate)
        require(c.isNotEmpty()) { "no cycles to track" }
        val n = ((to - from) * 1000f).toInt()
        val out = FloatArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = from + i / 1000f
            while (j + 1 < c.size && c[j + 1].first <= t) j++
            out[i] = c[j].second
        }
        return out
    }

    private fun track(s: Snip, from: Float = 0.05f, to: Float = s.durationSeconds - 0.08f): FloatArray = track(s.samples, s.sampleRate, from, to)

    /** The lag, in seconds, where the mean-removed track's autocorrelation peaks between [minSec] and [maxSec]. */
    private fun periodOf(track: FloatArray, minSec: Float, maxSec: Float): Float {
        val mean = track.average().toFloat()
        val x = FloatArray(track.size) { track[it] - mean }
        var bestLag = 0
        var best = Double.NEGATIVE_INFINITY
        for (lag in (minSec * 1000f).toInt()..(maxSec * 1000f).toInt()) {
            var acc = 0.0
            for (i in 0 until x.size - lag) acc += x[i] * x[i + lag]
            val r = acc / (x.size - lag)
            if (r > best) { best = r; bestLag = lag }
        }
        return bestLag / 1000f
    }

    private fun maxStep(samples: FloatArray): Float {
        var m = 0f
        for (i in 1 until samples.size) m = max(m, abs(samples[i] - samples[i - 1]))
        return m
    }

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in SirenVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Siren.macrosFor(voice).associate { it.name to 0f },
                Siren.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Siren.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                val loud = Loudness.of(snip)
                assertTrue(loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f, "$voice too quiet at $macros: loudness $loud, peak ${snip.peak()}")
                assertTrue(snip.durationSeconds <= Siren.MAX_SECONDS, "$voice must stay a one-shot: ${snip.durationSeconds} s")
                val dc = snip.samples.average().toFloat()
                assertTrue(abs(dc) < 0.05f, "$voice has DC offset $dc at $macros")
            }
        }
    }

    @Test
    fun `six macros, the same on every voice, and the defaults say what the spec says`() {
        for (voice in SirenVoice.entries) {
            assertEquals(listOf("TUNE", "RATE", "DEPTH", "SWEEP", "GRIT", "HOLD"), Siren.macrosFor(voice).map { it.name })
        }
        val d = Siren.defaults(SirenVoice.WAIL)
        assertEquals(0.5f, Siren.rateHz(d.getValue("RATE")), 0.001f, "WAIL wails at half a hertz")
        assertEquals(12f, Siren.depthSemitones(d.getValue("DEPTH")), 0.01f, "WAIL reaches an octave each way")
        assertEquals(6f, Siren.rateHz(Siren.defaults(SirenVoice.TRILL).getValue("RATE")), 0.01f)
        assertEquals(8f, Siren.rateHz(Siren.defaults(SirenVoice.LASER).getValue("RATE")), 0.01f)
        assertEquals(10f, Siren.rateHz(Siren.defaults(SirenVoice.BIRD).getValue("RATE")), 0.01f)
    }

    @Test
    fun `modulation off, TUNE lands within five cents of its snapped note on every voice`() {
        for (voice in SirenVoice.entries) {
            for (step in 0..24 step 4) {
                val tune = step / Siren.TUNE_SEMITONES.toFloat()
                val want = Siren.frequencyFor(tune)
                assertEquals(60 + step, Siren.midiFor(tune))
                val s = Siren.render(voice, plain + ("TUNE" to tune))
                val t = track(s, 0.1f, 0.5f)
                val median = t.sorted()[t.size / 2]
                assertTrue(abs(cents(median, want)) < 5f, "$voice at step $step: ${median} Hz is ${cents(median, want)} cents from $want")
            }
        }
    }

    @Test
    fun `RATE means hertz - the pitch track repeats at the LFO's period`() {
        // Two LOOPs of WAIL, unlevelled: the longest stretch of steady
        // modulation the engine makes, and the period is whatever RATE said.
        for ((rateHz, tolerance) in listOf(0.5f to 0.05f, 4f to 0.05f, 25f to 0.1f)) {
            val macros = mapOf("RATE" to Siren.rateMacroFor(rateHz), "DEPTH" to 0.5f)
            val stretch = Siren.synthesizeLoopStretch(SirenVoice.WAIL, macros, loops = 2)
            val t = track(stretch, Dsp.RATE, 0f, stretch.size / Dsp.RATE.toFloat() - 0.01f)
            val want = 1f / rateHz
            val got = periodOf(t, want * 0.5f, want * 1.5f)
            assertTrue(abs(got - want) <= want * tolerance, "RATE $rateHz Hz: period $got s, wanted $want s")
        }
    }

    @Test
    fun `DEPTH means semitones each way - the pitch track spans twice what the knob says`() {
        for ((depth, want) in listOf(0.5f to 24f, 1f to 48f)) {
            val s = Siren.render(SirenVoice.WAIL, mapOf("RATE" to Siren.rateMacroFor(0.5f), "DEPTH" to depth, "HOLD" to 1f))
            val t = track(s, 0f, s.durationSeconds - 0.01f)
            val span = semitones(t.max(), t.min())
            assertTrue(abs(span - want) <= 1f, "DEPTH $depth: spans $span semitones, wanted $want")
        }
        val flat = track(Siren.render(SirenVoice.WAIL, plain))
        assertTrue(semitones(flat.max(), flat.min()) < 0.5f, "DEPTH 0 is a plain tone")
    }

    @Test
    fun `each voice's LFO has its shape - a symmetric triangle, two tones, a fall and a climb`() {
        // Two periods at half a hertz on a held one-shot, which starts at the
        // shape's own fixed phase; the track is read over the first period.
        fun oneShotTrack(voice: SirenVoice): FloatArray {
            val s = Siren.render(voice, mapOf("RATE" to Siren.rateMacroFor(0.5f), "DEPTH" to 0.5f, "SWEEP" to 0.5f, "HOLD" to 0.98f))
            return track(s, 0.02f, 2.02f)
        }
        run {
            val t = oneShotTrack(SirenVoice.WAIL)
            val peak = t.indices.maxBy { t[it] }
            // The triangle starts at its bottom, peaks at half a period, and is back at the bottom by the end.
            assertTrue(abs(peak - 1000) < 100, "WAIL peaks at ${peak} ms, wanted 1000")
            assertTrue(semitones(t[peak], t[0]) > 20f && semitones(t[peak], t[1990]) > 20f, "WAIL rises and falls the same distance")
        }
        run {
            val t = oneShotTrack(SirenVoice.LASER)
            // A falling ramp: the top at the start, the bottom just before
            // the snap-back at the period's end (the track's highest point
            // is the snap-back itself, at 2.0 s, since 20 ms in the ramp has
            // already fallen a little).
            val bottom = t.indices.minBy { t[it] }
            assertTrue(semitones(t.max(), t[0]) < 1.5f, "LASER starts at its top: ${semitones(t.max(), t[0])} semitones under it")
            assertTrue(bottom in 1900..2000, "LASER falls to its bottom just before the snap-back, at ${bottom} ms")
            assertTrue(semitones(t[1000], t[bottom]) > 8f && semitones(t[0], t[1000]) > 8f, "LASER is still falling at half a period")
        }
        run {
            val t = oneShotTrack(SirenVoice.BIRD)
            val top = t.indices.maxBy { t[it] }
            assertTrue(semitones(t[0], t.min()) < 1.5f, "BIRD starts at its bottom: ${semitones(t[0], t.min())} semitones over it")
            assertTrue(top in 1900..2000, "BIRD climbs to its top just before the snap-back, at ${top} ms")
            assertTrue(semitones(t[top], t[1000]) > 8f && semitones(t[1000], t[0]) > 8f, "BIRD is still climbing at half a period")
        }
        run {
            val t = oneShotTrack(SirenVoice.TRILL)
            val lo = t.min()
            val hi = t.max()
            val onATone = t.count { semitones(it, lo) < 1f || semitones(hi, it) < 1f }
            assertTrue(onATone >= t.size * 0.8, "TRILL sits on one of two tones: only $onATone of ${t.size} ms")
            assertTrue(t.take(400).all { semitones(it, lo) < 1f }, "TRILL starts on its low tone")
        }
    }

    @Test
    fun `SWEEP falls in below centre, rises in above it, and does nothing at the centre`() {
        fun ends(sweep: Float): Pair<Float, Float> {
            val s = Siren.render(SirenVoice.WAIL, mapOf("DEPTH" to 0f, "SWEEP" to sweep, "HOLD" to 0.6f))
            val t = track(s, 0.005f, s.durationSeconds - 0.07f)
            return t.take(50).average().toFloat() to t.takeLast(50).average().toFloat()
        }
        val (fallStart, fallEnd) = ends(0.1f)
        assertTrue(semitones(fallStart, fallEnd) > 6f, "SWEEP 0.1 falls in: starts ${semitones(fallStart, fallEnd)} semitones above")
        val (riseStart, riseEnd) = ends(0.9f)
        assertTrue(semitones(riseEnd, riseStart) > 6f, "SWEEP 0.9 rises in: starts ${semitones(riseEnd, riseStart)} semitones below")
        val (flatStart, flatEnd) = ends(0.5f)
        assertTrue(abs(cents(flatStart, flatEnd)) < 10f, "SWEEP centred is no sweep: ${cents(flatStart, flatEnd)} cents")
    }

    @Test
    fun `SWEEP is a glide, not a blip - still travelling at a third of a second, landed by the end of the hold`() {
        // The audition's finding: the first build's sweep was over in 100 ms
        // and could not be heard under the wail. The full dive now takes a
        // second, so it is still six semitones out at 0.3 s and lands
        // before the hold ends.
        val s = Siren.render(SirenVoice.WAIL, mapOf("DEPTH" to 0f, "SWEEP" to 0f, "HOLD" to 0.6f))
        val t = track(s, 0.005f, s.durationSeconds - 0.07f)
        val landed = t.takeLast(50).average().toFloat()
        val at300 = t[295]
        assertTrue(semitones(at300, landed) > 6f, "at 0.3 s the dive is only ${semitones(at300, landed)} semitones out")
        val at900 = t[895]
        assertTrue(abs(cents(at900, landed)) < 30f, "by 0.9 s the dive has not landed: ${cents(at900, landed)} cents out")
        // A short press still lands: the sweep is capped to most of the hold.
        val short = Siren.render(SirenVoice.WAIL, mapOf("DEPTH" to 0f, "SWEEP" to 0f, "HOLD" to 0f))
        val ts = track(short, 0.005f, short.durationSeconds - 0.07f)
        assertTrue(abs(cents(ts[ts.size - 10], ts.last())) < 30f, "a 0.3 s press does not land its note")
        assertTrue(semitones(ts[0], ts.last()) > 12f, "a 0.3 s press still starts well above the note")
        assertEquals(Siren.SWEEP_FAR_SECONDS, Siren.sweepSeconds(0f, 4f), 0.001f)
        assertEquals(0.3f * Siren.SWEEP_HOLD_FRACTION, Siren.sweepSeconds(0f, 0.3f), 0.001f)
        assertEquals(Siren.SWEEP_NEAR_SECONDS, Siren.sweepSeconds(0.5f, 4f), 0.001f)
    }

    @Test
    fun `pitch moves by step, never by reset - the fastest, deepest siren has no click`() {
        for (voice in SirenVoice.entries) {
            val wild = Siren.render(voice, mapOf("RATE" to 1f, "DEPTH" to 1f, "GRIT" to 0f, "HOLD" to 0.6f))
            val clean = Siren.render(voice, plain + ("GRIT" to 0f))
            val wildStep = maxStep(wild.samples) / wild.peak()
            val cleanStep = maxStep(clean.samples) / clean.peak()
            assertTrue(wildStep <= cleanStep * 1.5f, "$voice: a step of $wildStep against the tone's own $cleanStep")
        }
    }

    /** Energy within 8 Hz of a harmonic against everything between, in dB, over a one-second Blackman-Harris window. */
    private fun harmonicClarity(samples: FloatArray, rate: Int, f0: Float, start: Int): Double {
        val n = 1 shl 16
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples[start + i] * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        var on = 0.0
        var off = 0.0
        for (b in 1 until n / 2) {
            val hz = b.toFloat() * rate / n
            if (hz > 20_000f) break
            if (hz < f0 / 2) continue
            val k = Math.round(hz / f0)
            val e = (re[b] * re[b] + im[b] * im[b]).toDouble()
            if (abs(hz - k * f0) < 8f) on += e else off += e
        }
        return 10 * log10(on / off)
    }

    @Test
    fun `full GRIT at the top note stays clean of aliasing`() {
        val s = Siren.render(SirenVoice.WAIL, mapOf("TUNE" to 1f, "DEPTH" to 0f, "SWEEP" to 0.5f, "GRIT" to 1f, "HOLD" to 0.9f))
        val f0 = Siren.frequencyFor(1f)
        val clarity = harmonicClarity(s.samples, s.sampleRate, f0, start = (0.1f * s.sampleRate).toInt())
        assertTrue(clarity >= 45.0, "at $f0 Hz energy between harmonics is only ${"%.1f".format(clarity)} dB down")
    }

    @Test
    fun `the same recipe renders bit-identical audio, and there is no seed to vary`() {
        for (voice in SirenVoice.entries) {
            val a = Siren.render(voice, mapOf("SWEEP" to 0.2f))
            val b = Siren.render(voice, mapOf("SWEEP" to 0.2f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice differs between two renders")
        }
    }

    @Test
    fun `HOLD is how long the button is down, and its top is LOOP`() {
        assertEquals(Siren.HOLD_MIN_SECONDS, Siren.holdSeconds(0f), 0.001f)
        assertEquals(Siren.HOLD_MAX_SECONDS, Siren.holdSeconds(0.99f), 0.01f)
        assertTrue(!Siren.isLoop(0.95f) && Siren.isLoop(0.99f) && Siren.isLoop(1f))
        val short = Siren.render(SirenVoice.TRILL, mapOf("HOLD" to 0f))
        val long = Siren.render(SirenVoice.TRILL, mapOf("HOLD" to 0.9f))
        assertTrue(abs(short.durationSeconds - (Siren.ATTACK_SECONDS + 0.3f + Siren.RELEASE_T60)) < 0.02f, "HOLD 0 is ${short.durationSeconds} s")
        assertTrue(long.durationSeconds > 2.5f, "HOLD 0.9 is ${long.durationSeconds} s")
        assertEquals(com.snipsnap.audio.DrumClass.TONAL, Siren.drumClassFor(SirenVoice.WAIL, mapOf("HOLD" to 0.9f)))
        assertEquals(com.snipsnap.audio.DrumClass.LOOP, Siren.drumClassFor(SirenVoice.WAIL, mapOf("HOLD" to 1f)))
    }

    @Test
    fun `a LOOP is whole LFO periods, at least two seconds, and the pulse closes on whole cycles`() {
        for (voice in SirenVoice.entries) {
            for (rate in listOf(0f, 1f)) {
                val macros = mapOf("RATE" to rate, "HOLD" to 1f)
                val plan = Siren.planLoop(voice, macros)
                val hz = Siren.rateHz(rate)
                assertEquals(kotlin.math.ceil(Siren.LOOP_MIN_SECONDS * hz).toInt(), plan.periods, "$voice RATE $rate: periods")
                assertEquals(Math.round(plan.periods / hz * Dsp.RATE).toInt(), plan.frames, "$voice RATE $rate: frames")
                assertTrue(plan.frames >= Siren.LOOP_MIN_SECONDS * Dsp.RATE - 1, "$voice RATE $rate: ${plan.frames} frames is under two seconds")
                assertTrue(abs(plan.lfoHz - hz) / hz < 0.001, "$voice RATE $rate: the rounding moved RATE to ${plan.lfoHz} Hz")
                assertTrue(abs(cents(plan.baseHz.toFloat(), Siren.frequencyFor(0.5f))) < 1f, "$voice RATE $rate: the fit moved the note ${cents(plan.baseHz.toFloat(), Siren.frequencyFor(0.5f))} cents")

                val stretch = Siren.synthesizeLoopStretch(voice, macros, loops = 2)
                assertEquals(2 * plan.frames, stretch.size)
                var worst = 0f
                var peak = 0f
                for (i in 0 until plan.frames) {
                    worst = max(worst, abs(stretch[i] - stretch[i + plan.frames]))
                    peak = max(peak, abs(stretch[i]))
                }
                assertTrue(worst < peak * 1e-4f, "$voice RATE $rate: the second loop differs from the first by $worst (peak $peak)")

                val loop = Siren.render(voice, macros)
                assertEquals(plan.frames, loop.frameCount.toInt(), "$voice RATE $rate: the render is not one loop")
                val step = maxStep(loop.samples)
                assertTrue(abs(loop.samples.first()) <= step && abs(loop.samples.last()) <= step, "$voice RATE $rate: the cut is not on the tone's own edge")
                assertTrue(loop.samples.first() * loop.samples.last() <= 0f, "$voice RATE $rate: the loop does not start on a crossing")
                assertTrue(loop.durationSeconds <= Siren.MAX_SECONDS, "$voice RATE $rate: ${loop.durationSeconds} s")
            }
        }
    }

    @Test
    fun `the LOOP's wrap is seamless - the render matches a fresh stretch across its own seam`() {
        for (voice in SirenVoice.entries) {
            val loop = Siren.render(voice, mapOf("HOLD" to 1f)).samples
            // A fresh two-loop stretch, cut where the render cut, laid
            // against the render's own end-then-start: if the wrap is a
            // seam, the 256 frames across it differ; if the loop is truly
            // periodic they are the same audio up to the leveller's gain,
            // which is fitted by least squares rather than assumed.
            val stretch = Siren.synthesizeLoopStretch(voice, mapOf("HOLD" to 1f), loops = 2)
            val cut = Siren.bestCut(stretch, loop.size)
            val across = stretch.copyOfRange(cut + loop.size - 128, cut + loop.size + 128)
            val expect = loop.copyOfRange(loop.size - 128, loop.size) + loop.copyOfRange(0, 128)
            var num = 0.0
            var den = 0.0
            for (i in across.indices) { num += across[i] * expect[i]; den += across[i] * across[i] }
            val gain = (num / den).toFloat()
            var worst = 0f
            var peak = 0f
            for (i in across.indices) { worst = max(worst, abs(across[i] * gain - expect[i])); peak = max(peak, abs(expect[i])) }
            assertTrue(worst < peak * 1e-3f, "$voice: across the wrap the render differs from the stretch by $worst (peak $peak)")
        }
    }

    @Test
    fun `SCRAMBLE stays a siren - audible, unclipped, never LOOP`() {
        val random = Random(11)
        for (voice in SirenVoice.entries) {
            repeat(25) {
                val macros = Siren.scramble(voice, random)
                assertTrue(macros.values.all { it in 0f..1f }, "$voice scrambled out of range: $macros")
                assertTrue(!Siren.isLoop(macros.getValue("HOLD")), "$voice SCRAMBLE landed on LOOP: $macros")
                val s = Siren.render(voice, macros)
                assertTrue(s.peak() > 0.05f && s.samples.all { it in -1f..1f }, "$voice scrambled to silence or clipping: $macros")
            }
        }
    }

    @Test
    fun `the landing chain is ECHO on a one-shot and nothing on a LOOP`() {
        val shot = Siren.landingChain(mapOf("HOLD" to 0.5f))
        assertTrue(shot != null && shot.echo == Siren.LANDING_ECHO, "a one-shot lands with the rack's ECHO")
        assertEquals(null, Siren.landingChain(mapOf("HOLD" to 1f)), "a LOOP lands dry")
        assertEquals(null, Siren.landingChain(mapOf("HOLD" to 0.995f)), "anything at or past the threshold is a LOOP")
    }

    @Test
    fun `a recipe round-trips through the dispatcher, and a wrong macro is refused`() {
        val patch = SirenPatch("Test Wail", SirenVoice.WAIL, mapOf("RATE" to 0.2f, "HOLD" to 1f))
        val back = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertTrue(patch.render().samples.contentEquals(back.render().samples))
        assertFailsWith<IllegalArgumentException> { SirenPatch("Bad", SirenVoice.BIRD, mapOf("DECAY" to 0.5f)) }
    }

    @Test
    fun `the oversampled render is the native one, band-limited`() {
        // Same shape of check as VELVET's: the 4x path and a native render
        // agree on pitch, which is what oversampling must not move.
        val macros = plain + ("TUNE" to 0.25f)
        val native = Siren.synthesize(SirenVoice.WAIL, macros, Dsp.RATE)
        val over = Siren.render(SirenVoice.WAIL, macros)
        val a = track(native, Dsp.RATE, 0.1f, 0.4f).sorted()
        val b = track(over, 0.1f, 0.4f).sorted()
        assertTrue(abs(cents(a[a.size / 2], b[b.size / 2])) < 2f, "the oversampled render moved the pitch")
        assertTrue(min(native.size, over.samples.size) > 0)
    }

    @Test
    fun `planLoop itself stops when nobody wants it any more`() {
        // planLoop's own warm-up and integral run before synthesizeLoopStretch's
        // audio loop ever starts, and at RATE's floor they are themselves
        // seconds of iteration — a cancelled render must not have to wait
        // them out first (review finding on PR #368).
        var asked = 0
        val t0 = System.nanoTime()
        assertFailsWith<java.util.concurrent.CancellationException> {
            Siren.planLoop(SirenVoice.WAIL, mapOf("RATE" to 0f)) { ++asked > 0 }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(asked >= 1, "planLoop never asked")
        assertTrue(ms < 200, "a cancelled plan ran on for ${ms}ms")
    }
}
