package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Dsp.RATE
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * THROWAWAY SPIKE. Not a test, not shipping code, not committed. Delete
 * after the audition.
 *
 * The question it answers: does a fixed modal body ([Modes.ring], the
 * snare's primitive) and/or a pick-position comb on the exciter give
 * PLUCK's four voices an instrument identity, or is the identity gap in
 * the string model itself?
 *
 * Renders A/B WAVs for every voice at factory defaults into
 * `$PLUCK_SPIKE_OUT/<VOICE>/`. Skips silently when the variable is unset,
 * so a normal test run never writes files.
 *
 * The body tables are hand-picked ballpark figures for the ears, NOT
 * sourced the way [Modes.Material] is. A real spec would source them.
 */
class PluckBodySpike {

    /** One fixed body resonance: absolute Hz, strike gain, seconds to -60 dB. */
    private data class Res(val hz: Float, val gain: Float, val t60: Float)

    private val bodies: Map<PluckVoice, List<Res>> = mapOf(
        // Classical guitar: air resonance, top plate, back-coupled, then plate modes.
        PluckVoice.NYLON to listOf(
            Res(98f, 1.0f, 0.45f), Res(195f, 0.8f, 0.35f), Res(250f, 0.5f, 0.25f),
            Res(410f, 0.4f, 0.18f), Res(560f, 0.3f, 0.14f), Res(780f, 0.25f, 0.11f),
            Res(1200f, 0.15f, 0.08f), Res(2400f, 0.08f, 0.05f),
        ),
        // Long paulownia box.
        PluckVoice.KOTO to listOf(
            Res(140f, 1.0f, 0.5f), Res(205f, 0.7f, 0.35f), Res(310f, 0.6f, 0.25f),
            Res(470f, 0.4f, 0.18f), Res(690f, 0.3f, 0.13f), Res(1050f, 0.2f, 0.09f),
            Res(1600f, 0.12f, 0.06f),
        ),
        // Hand-sized box with sound holes.
        PluckVoice.KALIMBA to listOf(
            Res(330f, 1.0f, 0.25f), Res(620f, 0.5f, 0.15f), Res(950f, 0.4f, 0.11f),
            Res(1400f, 0.25f, 0.08f), Res(2100f, 0.15f, 0.06f),
        ),
        // Soundboard: dense and low.
        PluckVoice.HARP to listOf(
            Res(110f, 0.9f, 0.5f), Res(165f, 0.7f, 0.4f), Res(240f, 0.6f, 0.3f),
            Res(330f, 0.5f, 0.25f), Res(450f, 0.4f, 0.2f), Res(600f, 0.35f, 0.15f),
            Res(820f, 0.25f, 0.12f), Res(1100f, 0.2f, 0.09f), Res(1500f, 0.12f, 0.06f),
        ),
    )

    @Test
    fun render() {
        val root = System.getenv("PLUCK_SPIKE_OUT")?.let(::File) ?: return
        root.mkdirs()
        val report = StringBuilder()

        for (voice in PluckVoice.entries) {
            val dir = File(root, voice.name).apply { mkdirs() }
            val m = Pluck.defaults(voice)

            /** Renders, writes the equal-RMS audition copy, returns the un-levelled shipped-tail snip. */
            fun write(name: String, body: Float = 0f, pick: Float = 0f, seconds: Float? = null, damp: Float? = null): Snip {
                val raw = string(voice, m, pick, seconds, damp)
                val mixed = if (body > 0f) withBody(raw, bodies.getValue(voice), body) else raw
                val snip = finish(mixed)
                val (levelled, limited) = auditionLevel(snip)
                WavWriter.write(File(dir, "$name.wav"), levelled)
                if (limited) report.append("  ${voice.name}/$name: peak guard hit, sits slightly under the common RMS\n")
                return snip
            }

            // 00 is the real shipped engine; 01 is this file's copy of it with
            // nothing enabled. Their difference is printed so a divergent copy
            // cannot masquerade as a finding.
            val shipped = Pluck.render(voice, m)
            WavWriter.write(File(dir, "00_shipped.wav"), auditionLevel(shipped).first)
            val shippedPeak = shipped.samples.maxOf { abs(it) }
            val baseline = write("01_spike_baseline")
            var maxDiff = 0f
            for (i in shipped.samples.indices) {
                maxDiff = maxOf(maxDiff, abs(shipped.samples[i] - (baseline.samples.getOrNull(i) ?: 0f)))
            }
            report.append(
                "${voice.name}: shipped vs spike-baseline max |diff| = $maxDiff; " +
                    "shipped peak = $shippedPeak, RMS = ${rms(shipped.samples)} " +
                    "(peak at the 0.99 ceiling means Dsp.levelTo never reached the loudness target)\n",
            )

            write("10_body_subtle", body = 0.5f)
            write("11_body_equal", body = 1f)
            write("12_body_dominant", body = 3f)
            write("20_pick_bridge", pick = 0.08f)
            write("21_pick_quarter", pick = 0.25f)
            write("22_pick_centre", pick = 0.5f)
            write("30_body_equal_pick_quarter", body = 1f, pick = 0.25f)
            write("40_long_ring", seconds = 3.5f, damp = 0.05f)
            write("41_long_ring_body_equal", seconds = 3.5f, damp = 0.05f, body = 1f)
        }

        File(root, "README.txt").writeText(readme(report.toString()))
        println(report)
    }

    private data class Const(val loopHz: Float, val pickLo: Float, val pickHi: Float, val ring: Float)

    /** [Pluck.synthesize], copied, plus an exciter comb and ring/damp overrides. */
    private fun string(voice: PluckVoice, m: Map<String, Float>, pick: Float, secondsOverride: Float?, dampOverride: Float?): FloatArray {
        val rate = RATE * Dsp.OVERSAMPLE
        val freq = Pluck.frequencyFor(voice, m.getValue("TUNE"))
        val damp = dampOverride ?: m.getValue("DAMP")
        val pickTone = m.getValue("PICK")
        val double = m.getValue("DOUBLE")
        val c = when (voice) {
            PluckVoice.KALIMBA -> Const(2600f, 900f, 4500f, 0.9f)
            PluckVoice.NYLON -> Const(3400f, 1200f, 6000f, 1.1f)
            PluckVoice.HARP -> Const(5200f, 1800f, 9000f, 1.3f)
            PluckVoice.KOTO -> Const(4200f, 1500f, 8000f, 1.0f)
        }
        val seconds = secondsOverride ?: (c.ring * Dsp.lin(1f - damp, 0.35f, 1f)).coerceAtMost(1.35f)
        val pickHz = Dsp.expMap(pickTone, c.pickLo, c.pickHi)
        val out = ks(freq, seconds, damp, c.loopHz, pickHz, seed = 11, rate = rate, pick = pick)
        if (double > 0.01f) {
            val det = ks(freq * Dsp.lin(double, 1.002f, 1.012f), seconds, damp, c.loopHz, pickHz, seed = 23, rate = rate, pick = pick)
            val g = double * 0.7f
            for (i in out.indices) out[i] += det[i] * g
        }
        return out
    }

    /**
     * [Pluck.ks], copied verbatim except for the pick-position comb on the
     * exciter (Jaffe & Smith 1983): the burst minus a copy of itself delayed
     * by `pick` of one period. `pick = 0` leaves the shipped exciter untouched.
     */
    private fun ks(freq: Float, seconds: Float, damp: Float, bodyLoopHz: Float, pickHz: Float, seed: Int, rate: Int, pick: Float): FloatArray {
        val loopHz = bodyLoopHz * Dsp.lin(1f - damp, 0.35f, 1.6f)
        val fb = Dsp.lin(1f - damp, 0.94f, 0.998f)

        val filterA = 1.0 - exp(-2.0 * PI * min(loopHz, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * freq / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        val filterDelay = -filterPhase / w
        val exact = (rate / freq) - filterDelay - 0.5
        require(exact >= 2.0) { "loop too short: $exact" }
        val n = floor(exact).toInt()
        val frac = (exact - n).toFloat()
        val a = (1f - frac) / (1f + frac)
        var apX1 = 0f
        var apY1 = 0f

        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(n + 2))

        // One period of filtered, zero-meaned noise, exactly as shipped.
        val noise = Dsp.Noise(seed)
        val pickLp = Dsp.OnePole(rate)
        val burst = FloatArray(n)
        for (i in 0 until n) burst[i] = pickLp.lp(noise.next(), pickHz)
        var mean = 0f
        for (v in burst) mean += v
        mean /= n
        for (i in 0 until n) burst[i] -= mean

        val d = if (pick > 0f) (pick * n).roundToInt().coerceAtLeast(1) else 0
        val excLen = min(n + d, out.size)
        for (i in 0 until excLen) {
            val x = if (i < n) burst[i] else 0f
            val xd = if (d > 0 && i - d in 0 until n) burst[i - d] else 0f
            out[i] = x - xd
        }

        val loopLp = Dsp.OnePole(rate)
        for (i in n + 1 until out.size) {
            val dl = 0.5f * (out[i - n] + out[i - n - 1])
            val tuned = a * (dl - apY1) + apX1
            apX1 = dl
            apY1 = tuned
            out[i] += fb * loopLp.lp(tuned, loopHz)
        }
        return out
    }

    /**
     * The string drives a fixed body. The body layer is levelled to the
     * string's RMS so `amount` means what it says; [Modes.ring]'s KDoc explains
     * why its raw output is hundreds of times louder than its input.
     */
    private fun withBody(dry: FloatArray, body: List<Res>, amount: Float): FloatArray {
        val rate = RATE * Dsp.OVERSAMPLE
        val modes = body.map { Modes.Mode(ratio = it.hz, gain = it.gain, t60 = it.t60) }
        val wet = Modes.ring(dry, 1f, modes, rate)
        val g = rms(dry) / rms(wet).coerceAtLeast(1e-9f)
        return FloatArray(dry.size) { dry[it] + amount * g * wet[it] }
    }

    private fun rms(buf: FloatArray): Float {
        var acc = 0.0
        for (v in buf) acc += v.toDouble() * v
        return sqrt(acc / buf.size.coerceAtLeast(1)).toFloat()
    }

    /** [Pluck.render]'s tail: decimate, level by loudness, fade. */
    private fun finish(raw: FloatArray): Snip {
        val out = Dsp.decimate(raw, RATE)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /**
     * Spike-only equal-RMS pass for a fair A/B. The first run showed every
     * shipped PLUCK render pinned at the 0.99 peak ceiling: the noise-burst
     * attack has a crest factor the loudness target cannot reach, so
     * [Dsp.levelTo] peak-limits instead, and the body renders (which round
     * the attack off) came out up to 2x louder in RMS. Loudness wins A/Bs
     * for the wrong reason, so everything is set to one quiet RMS here.
     * Returns the levelled copy and whether the peak guard had to intervene.
     */
    private fun auditionLevel(snip: Snip): Pair<Snip, Boolean> {
        val out = snip.samples.copyOf()
        var g = AUDITION_RMS / rms(out).coerceAtLeast(1e-9f)
        val peak = out.maxOf { abs(it) }
        val limited = peak * g > 0.99f
        if (limited) g = 0.99f / peak
        for (i in out.indices) out[i] *= g
        return Snip(out, channels = 1, sampleRate = RATE) to limited
    }

    private companion object {
        /** Quiet on purpose: low enough that no file needs the peak guard. */
        const val AUDITION_RMS = 0.03f
    }

    private fun readme(report: String) = """
        PLUCK BODY SPIKE - listening guide
        ==================================

        Every folder is one voice at its factory defaults. All files are set
        to the same RMS (a spike-only pass, deliberately quiet: turn up), so
        compare timbre, not level. The shipped engine itself is peak-limited,
        not loudness-matched; see the notes at the end.

        The question: does a body and/or a pick position make the voice
        sound like the instrument it is named after?

        The three that matter most, in order:
          00_shipped                    what the app renders today
          11_body_equal                 the string driving a fixed modal body
          30_body_equal_pick_quarter    body plus pick a quarter along the string

        Body amount, subtle to ugly end:
          10_body_subtle   11_body_equal   12_body_dominant

        Pick position only, no body:
          20_pick_bridge   thin and bright, low harmonics cut
          21_pick_quarter  the usual playing spot
          22_pick_centre   hollow, even harmonics gone

        Ring ceiling preview (DAMP near zero, 3.5 s instead of the 1.35 s cap):
          40_long_ring     41_long_ring_body_equal

        01_spike_baseline should be identical to 00_shipped; the numbers
        below are the maximum sample difference and should be ~0:

        $report
    """.trimIndent()
}
