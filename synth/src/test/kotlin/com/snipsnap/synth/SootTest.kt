package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SOOT's probes (docs/SOOT.md): the house set (finite, bounded, centred, under the loop threshold,
 * every control moves it, an unknown key and a non-finite control do not, JSON) and the round-0
 * claims the owner heard: a soft hit is a different strike, a take is a fresh strike, the coil's
 * echoes rise and fall away, and the dictaphone takes the top off.
 */
class SootTest {
    private val rate = Dsp.RATE

    private fun db(x: Double) = 20 * log10(x + 1e-12)

    /** Two cascaded 2nd-order Butterworth sections (RBJ), a 4th-order high- or low-pass. */
    private fun butter4(x: FloatArray, hz: Double, high: Boolean): DoubleArray {
        val w = 2 * Math.PI * hz / rate; val c = kotlin.math.cos(w); val al = kotlin.math.sin(w) / (2 * .7071067811865476); val a0 = 1 + al
        val b0 = (if (high) (1 + c) / 2 else (1 - c) / 2) / a0; val b1 = (if (high) -(1 + c) else (1 - c)) / a0
        val a1 = -2 * c / a0; val a2 = (1 - al) / a0
        var y = DoubleArray(x.size) { x[it].toDouble() }
        repeat(2) {
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            y = DoubleArray(y.size) { i -> val o = b0 * y[i] + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = y[i]; y2 = y1; y1 = o; o }
        }
        return y
    }

    private fun rms(x: FloatArray, from: Double = 0.0, to: Double = 99.0): Double {
        val a = (from * rate).toInt(); val b = minOf(x.size, (to * rate).toInt())
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / maxOf(1, b - a))
    }

    /** Energy above [hz] (a 4th-order high-pass) against the whole, in dB, over the first [seconds]. */
    private fun highShare(x: FloatArray, hz: Float, seconds: Double = .08): Double {
        val n = minOf(x.size, (seconds * rate).toInt())
        val y = butter4(x, hz.toDouble(), high = true)
        var hi = 0.0; var all = 0.0
        for (i in 0 until n) { hi += y[i] * y[i]; all += x[i] * x[i] }
        return 10 * log10(hi / all + 1e-20)
    }

    /** Difference energy between two renders, each peak-normalised, against the first, in dB. */
    private fun apart(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val pa = a.maxOf { abs(it) }; val pb = b.maxOf { abs(it) }
        var d = 0.0; var s = 0.0
        for (i in 0 until n) { val e = a[i] / pa - b[i] / pb; d += e * e; s += (a[i] / pa) * (a[i] / pa) }
        return 10 * log10(d / s + 1e-20)
    }

    @Test
    fun `every hit is finite, bounded, centred and under the loop threshold`() {
        for (voice in SootVoice.entries) {
            val corners = listOf(emptyMap(), Soot.macrosFor(voice).associate { it.name to 0f }, Soot.macrosFor(voice).associate { it.name to 1f })
            for (m in corners) for (v in listOf(.1f, 1f)) {
                val x = Soot.render(voice, m, v).samples
                assertEquals(minOf((Soot.secondsFor(voice) * rate).toInt(), (Soot.CAP_SECONDS * rate).toInt()), x.size, "$voice $m length")
                assertTrue(x.size < (1.5 * rate).toInt(), "$voice stays under the loop threshold")
                assertTrue(x.all { it.isFinite() }, "$voice $m finite")
                val peak = x.maxOf { abs(it) }
                assertTrue(peak in .01f..1f, "$voice $m v$v peak $peak")
                assertTrue(abs(x.average()) < .005, "$voice $m DC ${x.average()}")
            }
        }
    }

    @Test
    fun `every control changes the sound and an unknown key or a non-finite control does not`() {
        for (voice in SootVoice.entries) {
            val base = Soot.render(voice).samples
            for (spec in Soot.macrosFor(voice)) {
                val lo = Soot.render(voice, mapOf(spec.name to .15f)).samples
                val hi = Soot.render(voice, mapOf(spec.name to .85f)).samples
                val diff = lo.indices.sumOf { abs(lo[it] - hi[it]).toDouble() } / lo.size
                assertTrue(diff > 1e-4, "$voice ${spec.name} moves the sound (mean |diff| $diff)")
                assertContentEquals(base, Soot.render(voice, mapOf(spec.name to Float.NaN)).samples, "$voice ${spec.name} NaN falls back")
            }
            assertContentEquals(base, Soot.render(voice, mapOf("NOT_A_CONTROL" to .3f)).samples, "$voice ignores an unknown key")
        }
    }

    /** Where each drum's brightness lives: a split above most of its body, so the share moves with the strike. */
    private fun splitFor(voice: SootVoice) = when (voice) {
        SootVoice.KICK, SootVoice.COIL -> 1500f
        SootVoice.SNARE, SootVoice.PLATE -> 3000f
        SootVoice.CUP, SootVoice.ROD -> 5000f
        SootVoice.HAT_CLOSED, SootVoice.HAT_OPEN -> 9000f
    }

    @Test
    fun `a soft hit is a duller strike, not the hard one turned down`() {
        val failures = mutableListOf<String>()
        for (voice in SootVoice.entries) {
            val hz = splitFor(voice)
            // Dry (GRIME 0): the dictaphone's band and pump would hide what the strike itself did.
            val s = listOf(.3f, .6f, 1f).map { highShare(Soot.render(voice, mapOf("GRIME" to 0f), it).samples, hz) }
            val printed = listOf(.3f, 1f).map { highShare(Soot.render(voice, emptyMap(), it).samples, hz) }
            val gap = apart(Soot.render(voice, emptyMap(), .3f).samples, Soot.render(voice).samples)
            println("SOOT $voice share above $hz Hz, first 80 ms, at 0.3 / 0.6 / 1.0, dry: ${s.map { "%.1f".format(it) }} dB; printed at 0.3 / 1.0: ${printed.map { "%.1f".format(it) }} dB; soft vs hard apart ${"%.1f".format(gap)} dB")
            if (!(s[2] > s[0] + 1.0)) failures += "$voice dry $s"
            if (!(printed[1] > printed[0])) failures += "$voice printed $printed"
            if (!(gap > -30.0)) failures += "$voice soft hit is the hard one scaled ($gap)"
        }
        assertTrue(failures.isEmpty(), "a hard strike is brighter than a soft one: $failures")
    }

    @Test
    fun `every take is a fresh strike`() {
        for (voice in SootVoice.entries) {
            val takes = (0..2).map { Soot.render(voice, emptyMap(), 1f, it).samples }
            val d = listOf(apart(takes[0], takes[1]), apart(takes[1], takes[2]), apart(takes[0], takes[2]))
            println("SOOT $voice takes apart: ${d.map { "%.1f".format(it) }} dB")
            for (x in d) assertTrue(x > -40.0, "$voice: takes differ ($d)")
        }
    }

    @Test
    fun `the coil's echoes rise in pitch and fall away`() {
        val frames = (1.4 * rate).toInt()
        val impulse = DoubleArray(frames).also { it[10] = 1.0 }
        val ir = BreakerChain.spring(impulse, .55, frames, rate, Soot.COIL_TANK)
        val window = (.1 * rate).toInt()
        val energy = (0 until frames / window).map { w -> (w * window until (w + 1) * window).sumOf { ir[it] * ir[it] } }
        val top = energy.indices.maxBy { energy[it] }
        val levels = energy.map { 10 * log10(it / energy[top] + 1e-30) }
        println("SOOT coil energy per 100 ms, dB re its loudest: ${levels.map { "%.1f".format(it) }}")
        for (w in top + 1 until levels.size) assertTrue(levels[w] < levels[w - 1], "the coil never gains energy after its peak (window $w)")
        assertTrue(levels.last() < -40, "the coil decays")
        // The tighter coil disperses harder than BREAKER's surf tank near its bend (round 0: 49.6 ms a pass at 2 kHz against 8.9).
        val first = FloatArray((.08 * rate).toInt()) { ir[it].toFloat() }
        fun band(x: FloatArray, hz: Float): FloatArray {
            val a = Dsp.Biquad().apply { bandpass(hz, 2f, rate) }; val b = Dsp.Biquad().apply { bandpass(hz, 2f, rate) }
            return FloatArray(x.size) { b.process(a.process(x[it])) }
        }
        fun arrival(hz: Float): Int { val y = band(band(first, hz).reversedArray(), hz).reversedArray(); return y.indices.maxBy { abs(y[it]) } }
        val low = arrival(300f); val high = arrival(1800f)
        println("SOOT coil first echo: 300 Hz at ${"%.1f".format(low * 1000.0 / rate)} ms, 1.8 kHz at ${"%.1f".format(high * 1000.0 / rate)} ms")
        assertTrue(high > low, "the coil's chirp rises: highs arrive later")
    }

    /**
     * On the bright drums. On a dark one the ROM reader's hold adds aliased energy up there instead (the
     * plate: about -51 dB above 6 kHz dry, -25 printed), the profile's "metallic footprint"; that is a
     * listening question (docs/SOOT.md), not a defect this test should hide.
     */
    @Test
    fun `GRIME takes the top off the bright drums`() {
        for (voice in listOf(SootVoice.SNARE, SootVoice.HAT_CLOSED, SootVoice.HAT_OPEN)) {
            val dry = highShare(Soot.render(voice, mapOf("GRIME" to 0f)).samples, 6000f, .3)
            val heavy = highShare(Soot.render(voice, mapOf("GRIME" to .9f)).samples, 6000f, .3)
            println("SOOT $voice share above 6 kHz, GRIME 0 / .9: ${"%.1f".format(dry)} / ${"%.1f".format(heavy)} dB")
            assertTrue(heavy < dry - 3, "$voice: the dictaphone strips the highs ($dry -> $heavy)")
        }
    }

    @Test
    fun `the kick keeps its sub through the print`() {
        val x = Soot.render(SootVoice.KICK).samples
        val l = butter4(x, 100.0, high = false)
        val low = FloatArray(x.size) { l[it].toFloat() }
        val share = db(rms(low)) - db(rms(x))
        println("SOOT kick: energy under 100 Hz, ${"%.1f".format(share)} dB re the whole")
        assertTrue(share > -6.0, "the sub survives the dictaphone ($share dB)")
    }

    /** The level of the 20 ms block centred on [ms], against the loudest 20 ms block, in dB. */
    private fun levelAt(x: FloatArray, ms: Double): Double {
        val n = (.02 * rate).toInt()
        val blocks = (0 until x.size / n).map { k -> rms(x, k * n.toDouble() / rate, (k + 1) * n.toDouble() / rate) }
        return db(blocks[(ms / 20).toInt()]) - db(blocks.max())
    }

    /**
     * The owner heard model 1's dry Kotlin hat as "different" ("there is an effect sound on the Kotlin
     * one"): its own coil send rang on after the metal had died. Measured against the Python hat they
     * picked, at 100 ms: model 1 -29 dB, the pick -76. (Below 2.4 kHz the Kotlin hat also sits about
     * 10 dB over the pick, but the pick's own takes spread 15 dB there on their random phases alone, so
     * that is take-to-take variation, not this.)
     */
    @Test
    fun `model 2's hats carry no coil, model 1's did`() {
        for (voice in listOf(SootVoice.HAT_CLOSED, SootVoice.HAT_OPEN)) {
            val dry = mapOf("GRIME" to 0f)
            val at = if (voice == SootVoice.HAT_CLOSED) 100.0 else 700.0
            val m1 = levelAt(Soot.render(voice, dry, model = 1).samples, at)
            val m2 = levelAt(Soot.render(voice, dry, model = 2).samples, at)
            println("SOOT $voice level at $at ms, dry, model 1 / 2: ${"%.1f".format(m1)} / ${"%.1f".format(m2)} dB")
            // The open hat's own 0.55 s ring covers most of its coil, so its margin is smaller (measured 9 dB at 700 ms).
            val margin = if (voice == SootVoice.HAT_CLOSED) 15.0 else 6.0
            assertTrue(m2 < m1 - margin, "$voice: the coil's tail is gone ($m1 -> $m2)")
        }
    }

    @Test
    fun `a model-1 save keeps its GRIME and its hats' coil`() {
        for (voice in SootVoice.entries) {
            assertEquals(.9f, Soot.defaults(voice, model = 1).getValue("GRIME"))
            assertEquals(.45f, Soot.defaults(voice).getValue("GRIME"))
            val old = Patches.fromJsonText(SootPatch("Saved", voice, emptyMap(), model = 1).toJsonText()) as SootPatch
            assertEquals(1, old.model)
            assertContentEquals(Soot.render(voice, emptyMap(), model = 1).samples, old.render().samples, "$voice replays as model 1")
        }
        assertTrue(apart(Soot.render(SootVoice.HAT_CLOSED, model = 1).samples, Soot.render(SootVoice.HAT_CLOSED).samples) > -30.0)
    }

    @Test
    fun `a patch round-trips through JSON with its model`() {
        for (p in SootPresets.all()) {
            val back = Patches.fromJsonText(p.toJsonText()) as SootPatch
            assertEquals(p, back)
            assertEquals(Soot.MODEL_VERSION, back.model)
        }
    }

    @Test
    fun `BREAKER's tanks keep their arithmetic`() {
        // The Tank grew SOOT's fields; BREAKER's defaults must be the numbers it always used.
        for (t in listOf(BreakerChain.TANK_A, BreakerChain.TANK_B)) {
            assertEquals(listOf(Triple(.037, 0.0, 1.0), Triple(.043, .004, .8)), t.springs)
            assertEquals(1.0, t.t60Base); assertEquals(1.6, t.t60Span); assertEquals(250.0, t.sendHp); assertEquals(3500.0, t.tone)
        }
    }
}
