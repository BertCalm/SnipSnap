package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The COMPRESSOR section against its spec (docs/superpowers/specs/2026-10-23-compressor-section-design.md).
 * Every bar is the Kotlin measurement minus a stated margin; the Kotlin section matched the spec's
 * Python prototype within one 16-bit step on the prototype's own clips (the spec's As built).
 */
class CompressorTest {

    private val snare by lazy { Thump.render(ThumpVoice.SNARE) }
    private val choir by lazy { Vox.render(VoxVoice.CHOIR) }
    private val jangle by lazy { baked(MagnetVoice.JANGLE) }
    private val chug by lazy { baked(MagnetVoice.CHUG) }

    private fun baked(voice: MagnetVoice): Snip = Magnet.landingChain(voice).process(Magnet.render(voice))

    private fun at(amount: Float, attack: Float = 0.5f, recover: Float = 0.66f) =
        mapOf("AMOUNT" to amount, "ATTACK" to attack, "RECOVER" to recover)

    /** The section's own detector (5 ms mean square, the louder channel), in dB under the loudest level. */
    private fun levelDb(s: Snip): DoubleArray {
        val k = 1.0 - exp(-1.0 / (0.005 * s.sampleRate))
        var e = -1.0
        val ms = DoubleArray(s.frameCount) { f ->
            var a = 0.0
            for (c in 0 until s.channels) {
                val x = s.samples[f * s.channels + c].toDouble()
                if (x * x > a) a = x * x
            }
            e = if (e < 0) a else e + k * (a - e)
            e
        }
        val db = DoubleArray(ms.size) { 10 * log10(maxOf(ms[it], 1e-18)) }
        val top = db.max()
        return DoubleArray(db.size) { db[it] - top }
    }

    /** Ring time: from the loudest level to the last moment within 20 dB of it, ms. */
    private fun ringMs(s: Snip): Int {
        val l = levelDb(s)
        val peak = l.indices.maxBy { l[it] }
        var last = peak
        for (i in peak until l.size) if (l[i] >= -20) last = i
        return ((last - peak) * 1000L / s.sampleRate).toInt()
    }

    /** The level 1.5 s after [ref]'s loudest point against 50 ms after it, dB: how much of a long note is held up. */
    private fun lateDb(s: Snip, ref: Snip): Double {
        val r = levelDb(ref)
        val peak = r.indices.maxBy { r[it] }
        val l = levelDb(s)
        return l[peak + (1.5 * s.sampleRate).toInt()] - l[peak + (0.05 * s.sampleRate).toInt()]
    }

    /** How far the sample peak stands over the body (the level 30 to 80 ms after the loudest point), dB. */
    private fun spikeDb(s: Snip): Double {
        val l = levelDb(s)
        val peak = l.indices.maxBy { l[it] }
        val from = peak + (0.03 * s.sampleRate).toInt()
        val to = peak + (0.08 * s.sampleRate).toInt()
        var sq = 0.0
        for (i in from until to) sq += Math.pow(10.0, l[i] / 10.0)
        val bodyDb = 10 * log10(sq / (to - from))
        var samplePeak = 0f
        for (v in s.samples) samplePeak = maxOf(samplePeak, abs(v))
        val loudestDb = 10 * log10(levelMax(s))
        return 20 * log10(samplePeak.toDouble()) - (bodyDb + loudestDb)
    }

    private fun levelMax(s: Snip): Double {
        val k = 1.0 - exp(-1.0 / (0.005 * s.sampleRate))
        var e = -1.0
        var top = 0.0
        for (f in 0 until s.frameCount) {
            var a = 0.0
            for (c in 0 until s.channels) { val x = s.samples[f * s.channels + c].toDouble(); if (x * x > a) a = x * x }
            e = if (e < 0) a else e + k * (a - e)
            if (e > top) top = e
        }
        return top
    }

    /** The last 20 ms under the peak, dB. */
    private fun endDb(s: Snip): Double {
        val n = (0.02 * s.sampleRate).toInt() * s.channels
        var peak = 0f
        for (v in s.samples) peak = maxOf(peak, abs(v))
        var sq = 0.0
        for (i in s.samples.size - n until s.samples.size) sq += s.samples[i].toDouble() * s.samples[i]
        return 20 * log10(peak.toDouble()) - 20 * log10(maxOf(sqrt(sq / n), 1e-12))
    }

    @Test
    fun `AMOUNT zero is a copy, and the section is deterministic`() {
        assertContentEquals(snare.samples, Compressor.process(snare, at(0f)).samples)
        assertContentEquals(Compressor.process(snare, at(0.7f)).samples, Compressor.process(snare, at(0.7f)).samples)
    }

    @Test
    fun `COMPRESSOR sits after WAH and before SQUASH, the chain runs it, and an absent section keeps old recipes byte-stable`() {
        val names = FxChain.SECTION_NAMES
        assertEquals(names.indexOf("wah") + 1, names.indexOf("compressor"), "COMPRESSOR is not right after WAH: $names")
        assertEquals(names.indexOf("compressor") + 1, names.indexOf("squash"), "COMPRESSOR is not right before SQUASH: $names")
        val without = FxChain(squash = mapOf("AMOUNT" to 0.5f))
        assertFalse(without.toJsonText().contains("compressor"), "no compressor key unless the section is set")
        val with = FxChain(compressor = at(0.7f))
        assertEquals(with, FxChain.fromJsonText(with.toJsonText()))
        assertContentEquals(Compressor.process(snare, at(0.7f)).samples, with.process(snare).samples, "the chain's row does not run Compressor.process")
    }

    /**
     * The sustain law. Measured: the snare rings 84 ms dry, 124 through the COMP chip's old SQUASH recipe at 0.7,
     * 249 here (2.96 times dry); the choir 371, 527 and 829 (2.23 times). Bars: 2.5 and 2.0 times dry, and beating
     * the old recipe.
     */
    @Test
    fun `at AMOUNT 0_7 a snare rings two and a half times as long and a choir twice, beating the old COMP`() {
        val old = mapOf("AMOUNT" to 0.7f, "ATTACK" to 0.05f)
        for ((name, x, factor) in listOf(Triple("snare", snare, 2.5), Triple("choir", choir, 2.0))) {
            val dry = ringMs(x)
            val ring = ringMs(Compressor.process(x, at(0.7f)))
            assertTrue(ring >= factor * dry, "$name rings $ring ms against $dry dry, under ${factor}x")
            assertTrue(ring > ringMs(Squash.process(x, old)), "$name: the section does not beat the old SQUASH recipe")
        }
    }

    /** Measured on the snare: 177, 249 and 276 ms at AMOUNT 0.3, 0.7 and 1. */
    @Test
    fun `AMOUNT raises the ring time`() {
        val rings = listOf(0.3f, 0.7f, 1f).map { ringMs(Compressor.process(snare, at(it))) }
        assertTrue(rings[0] < rings[1] && rings[1] < rings[2], "ring times not rising with AMOUNT: $rings")
    }

    /**
     * RECOVER runs the way the owner asked: up is more sustain. Measured: snare 124 ms at 0, 249 at the default,
     * 257 at 1; choir 459, 829, 829 (the default already sits near the top, as the spec says).
     */
    @Test
    fun `RECOVER up is more sustain`() {
        for ((name, x) in listOf("snare" to snare, "choir" to choir)) {
            val low = ringMs(Compressor.process(x, at(0.7f, recover = 0f)))
            val mid = ringMs(Compressor.process(x, at(0.7f)))
            val high = ringMs(Compressor.process(x, at(0.7f, recover = 1f)))
            assertTrue(low < mid && mid <= high, "$name: RECOVER 0, default, 1 ring $low, $mid, $high ms")
        }
    }

    /**
     * A guitar after its amp is already flat, so the section works on the second half of the note. Measured, level
     * 1.5 s in against 50 ms in: JANGLE -6.3 dB baked, +0.9 at AMOUNT 0.7, -3.9 at RECOVER 0; CHUG -7.6, -0.5, -5.7.
     * Bars: at least 4 dB above the baked pad at the default, and RECOVER 0 at least 3 dB under the default.
     */
    @Test
    fun `a baked guitar's second half is held up, and RECOVER 0 gives it back its fade`() {
        for ((name, x) in listOf("JANGLE" to jangle, "CHUG" to chug)) {
            val dry = lateDb(x, x)
            val held = lateDb(Compressor.process(x, at(0.7f)), x)
            val natural = lateDb(Compressor.process(x, at(0.7f, recover = 0f)), x)
            assertTrue(held >= dry + 4.0, "$name holds ${"%.1f".format(held)} dB at 1.5 s against ${"%.1f".format(dry)} baked")
            assertTrue(natural <= held - 3.0, "$name at RECOVER 0 holds ${"%.1f".format(natural)} dB, not 3 dB under the default ${"%.1f".format(held)}")
        }
    }

    /** ATTACK lets more of the hit through: the prototype measured 14.3 to 18.8 dB on the snare, about 4 to 11.5 on the guitars. */
    @Test
    fun `ATTACK raises the hit over the body`() {
        for ((name, x, gap) in listOf(Triple("snare", snare, 2.5), Triple("JANGLE", jangle, 4.0))) {
            val soft = spikeDb(Compressor.process(x, at(0.7f, attack = 0f)))
            val punch = spikeDb(Compressor.process(x, at(0.7f, attack = 1f)))
            assertTrue(punch >= soft + gap, "$name's hit stands ${"%.1f".format(soft)} dB at ATTACK 0 and ${"%.1f".format(punch)} at 1, under a $gap dB gap")
        }
    }

    /** The floor: no setting lifts a pad's end into the room. Measured at worst 64.9 dB (the snare at ATTACK 1); the bar is the WAH section's 50. */
    @Test
    fun `the last 20 ms stay at least 50 dB under the peak`() {
        for ((name, x) in listOf("snare" to snare, "choir" to choir, "CHUG" to chug)) for (m in listOf(at(1f), at(0.7f, attack = 1f), at(0.7f, attack = 0f), at(0.7f, recover = 1f))) {
            val end = endDb(Compressor.process(x, m))
            assertTrue(end >= 50.0, "$name with $m ends ${"%.1f".format(end)} dB under its peak")
        }
    }

    /** A decaying low E at RECOVER's fast end: the prototype measured harmonics 2 to 5 at 45 dB under the fundamental. */
    @Test
    fun `a low note stays clean at the fastest recovery`() {
        val rate = WavWriter.MPC_SAMPLE_RATE
        val f0 = 82.4
        val tone = FloatArray((1.2 * rate).toInt()) { i -> val t = i.toDouble() / rate; (0.5 * sin(2 * PI * f0 * t) * exp(-t / 0.25)).toFloat() }
        val out = Compressor.process(Snip(tone, 1, rate), at(0.7f, recover = 1f)).samples
        val from = (0.2 * rate).toInt()
        val n = (0.4 * rate).toInt()
        fun power(f: Double): Double {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
                val x = out[from + i] * w
                re += x * cos(2 * PI * f * i / rate)
                im -= x * sin(2 * PI * f * i / rate)
            }
            return re * re + im * im
        }
        val harmonics = (2..5).sumOf { power(f0 * it) }
        val db = 10 * log10(harmonics / power(f0))
        assertTrue(db <= -40.0, "harmonics 2 to 5 at ${"%.1f".format(db)} dB under a low E, over the -40 bar")
    }

    @Test
    fun `a stereo pad keeps its image, one linked detector`() {
        val stereo = choir
        if (stereo.channels != 2) return
        val out = Compressor.process(stereo, at(0.7f))
        // Each frame is one gain on both channels, so the channel ratio survives wherever both are well above zero.
        var checked = 0
        for (f in 0 until stereo.frameCount step 97) {
            val l = stereo.samples[f * 2]
            val r = stereo.samples[f * 2 + 1]
            if (abs(l) < 0.02f || abs(r) < 0.02f) continue
            assertEquals(l / r, out.samples[f * 2] / out.samples[f * 2 + 1], 1e-3f * abs(l / r), "frame $f's image moved")
            checked++
        }
        assertTrue(checked > 50, "too few frames to judge the image: $checked")
    }
}
