package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GYRE round one's claims (docs/superpowers/plans/2026-10-01-gyre-round-1.md). Every bound is a
 * Phase-0 or Task-5 measurement with room, and each test prints what it measured.
 */
class GyreTest {

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from).coerceAtLeast(1))
    }

    // ---- the knobs ------------------------------------------------------------

    @Test
    fun `every macro changes the sound`() {
        for (voice in GyreVoice.entries) {
            val base = Gyre.render(voice, Gyre.defaults(voice)).samples
            for (spec in Gyre.macrosFor(voice)) {
                val moved = Gyre.defaults(voice) + (spec.name to if (spec.default > 0.5f) 0.1f else 0.9f)
                val other = Gyre.render(voice, moved).samples
                val n = minOf(base.size, other.size)
                var diff = 0.0
                for (i in 0 until n) { val d = base[i] - other[i].toDouble(); diff += d * d }
                assertTrue(other.size != base.size || diff / n > 1e-7, "$voice ${spec.name} did nothing")
            }
        }
    }

    @Test
    fun `SPIN's low end is not dead`() {
        // G9: the first stretch of SPIN must move something. Measured: 0.02 against 0 differs by 1.9% (FLICK), 2.7% (HALO).
        for (voice in GyreVoice.entries) {
            val still = Gyre.render(voice, Gyre.defaults(voice) + ("SPIN" to 0f)).samples
            val turning = Gyre.render(voice, Gyre.defaults(voice) + ("SPIN" to 0.02f)).samples
            var d = 0.0; var e = 0.0
            for (i in still.indices) { val x = still[i] - turning[i].toDouble(); d += x * x; e += still[i].toDouble() * still[i] }
            val rel = sqrt(d / e)
            println("$voice SPIN 0.02 against 0: ${"%.3f".format(rel)}")
            assertTrue(rel > 0.008, "$voice: SPIN 0.02 is $rel from SPIN 0")
        }
    }

    // ---- the strings play each other ------------------------------------------

    @Test
    fun `a plucked string sets the others ringing, through the bridge only`() {
        // Measured: FLICK's third string answers its first at -25.1 dB, HALO's at -17.0; with the bridge off, exactly 0.
        for (voice in GyreVoice.entries) {
            val coupled = Gyre.play(voice, Gyre.defaults(voice), Gyre.Probe(solo = 0, record = true)).strings!!
            val apart = Gyre.play(voice, Gyre.defaults(voice), Gyre.Probe(solo = 0, record = true, coupling = 0f)).strings!!
            val db = 20 * log10(rms(coupled[2]) / rms(coupled[0]))
            println("$voice: an unplucked string answers at ${"%.1f".format(db)} dB")
            assertEquals(0.0, rms(apart[2]), "$voice: string 3 rang with the bridge off")
            assertTrue(db > -30.0, "$voice: string 3 answered at only $db dB")
        }
    }

    // ---- the bound ---------------------------------------------------------------

    @Test
    fun `every corner is finite, bounded and never grows`() {
        // Every macro at both ends, both voices (64 renders). The bridge may move energy between
        // strings but never add it, so no 50 ms stretch after the attack is louder than the attack's
        // own loudest. Measured: the loudest later stretch is 0.596 of the attack (HALO, SYMPATHY 1, the
        // sympathetic strings blooming); the raw peak at most 0.767.
        for (voice in GyreVoice.entries) for (tune in floatArrayOf(0f, 1f)) for (sym in floatArrayOf(0f, 1f)) for (spin in floatArrayOf(0f, 1f))
            for (body in floatArrayOf(0f, 1f)) for (hold in floatArrayOf(0f, 1f)) {
            val m = mapOf("TUNE" to tune, "SYMPATHY" to sym, "SPIN" to spin, "BODY" to body, "HOLD" to hold)
            val raw = Gyre.play(voice, m).raw
            var peak = 0f
            for (v in raw) { assertTrue(v.isFinite(), "$voice $m: not finite"); peak = max(peak, abs(v)) }
            val block = (0.05f * RATE * Dsp.OVERSAMPLE).toInt()
            val blocks = DoubleArray(raw.size / block) { rms(raw, it * block, (it + 1) * block) }
            val attack = max(blocks[0], blocks[1])
            var later = 0.0
            for (k in 2 until blocks.size) later = max(later, blocks[k])
            println("$voice $m: raw peak ${"%.3f".format(peak)}, loudest later stretch ${"%.3f".format(later / attack)} of the attack")
            assertTrue(peak <= Gyre.RAW_PEAK_CEILING, "$voice $m: raw peak $peak")
            assertTrue(later <= attack, "$voice $m: a later stretch is ${later / attack} of the attack")
        }
    }

    @Test
    fun `every note ends at least 40 dB under its attack`() {
        // The hand lands at HOLD and the render runs until both the played and the sympathetic
        // strings are END_DB down, so no note is cut off while it still rings. Measured: the quietest
        // ending is 43.1 dB under its attack (before the hand: 14 dB, FLICK at HOLD 0 and SYMPATHY 1).
        var worst = Double.POSITIVE_INFINITY
        for (voice in GyreVoice.entries) for (tune in floatArrayOf(0f, 1f)) for (sym in floatArrayOf(0f, 1f)) for (spin in floatArrayOf(0f, 1f))
            for (body in floatArrayOf(0f, 1f)) for (hold in floatArrayOf(0f, 1f)) {
            val m = mapOf("TUNE" to tune, "SYMPATHY" to sym, "SPIN" to spin, "BODY" to body, "HOLD" to hold)
            val raw = Gyre.play(voice, m).raw
            val block = (0.05f * RATE * Dsp.OVERSAMPLE).toInt()
            val attack = max(rms(raw, 0, block), rms(raw, block, 2 * block))
            val end = rms(raw, raw.size - block, raw.size)
            val db = 20 * log10(attack / max(end, 1e-30))
            worst = minOf(worst, db)
            assertTrue(db >= 40.0, "$voice $m: ends only $db dB under its attack")
        }
        println("the quietest ending is ${"%.1f".format(worst)} dB under its attack")
    }

    // ---- pitch ---------------------------------------------------------------------

    @Test
    fun `every note is in tune, coupling and all`() {
        // Measured: worst 3.3 cents (FLICK, BODY 1, SYMPATHY 1), with the bridge's phase cancelled and the wolf guard.
        for (voice in GyreVoice.entries) for (body in floatArrayOf(0f, 0.5f, 1f)) for (sym in floatArrayOf(0f, 1f)) {
            var worst = 0.0
            for (step in 0..Gyre.TUNE_SEMITONES) {
                val m = Gyre.defaults(voice) + mapOf("TUNE" to step / 24f, "BODY" to body, "SYMPATHY" to sym)
                val f = Gyre.frequencyFor(voice, step / 24f)
                val cents = FineTuning.cents(FineTuning.measuredHz(Gyre.render(voice, m), f, 0.1f, 0.3f), f.toDouble())
                worst = max(worst, abs(cents))
            }
            println("$voice BODY $body SYMPATHY $sym: worst ${"%.1f".format(worst)} cents")
            assertTrue(worst <= 5.0, "$voice BODY $body SYMPATHY $sym: $worst cents")
        }
    }

    @Test
    fun `never an octave low, at the most sympathetic`() {
        // G2: whole-number ratios repeat at the note. The house detector is the one keys and SPREAD use.
        for (voice in GyreVoice.entries) for (step in 0..Gyre.TUNE_SEMITONES) {
            val m = Gyre.defaults(voice) + mapOf("TUNE" to step / 24f, "SYMPATHY" to 1f, "BODY" to 1f)
            val f = Gyre.frequencyFor(voice, step / 24f)
            val hz = Pitch.detect(Gyre.render(voice, m))?.hz ?: error("$voice step $step: no pitch")
            val cents = 1200 * ln(hz / f.toDouble()) / ln(2.0)
            assertTrue(abs(cents) < 50.0, "$voice step $step: read $hz Hz for $f")
        }
    }

    // ---- the rotor is not tremolo ----------------------------------------------------

    private fun centroids(x: FloatArray, block: Int): DoubleArray {
        val maxBin = (8_000.0 * block / RATE).toInt()
        return DoubleArray(x.size / block) { b ->
            var num = 0.0; var den = 0.0
            for (k in 1..maxBin step 2) {
                var re = 0.0; var im = 0.0
                for (t in 0 until block) {
                    val v = x[b * block + t] * (0.5 - 0.5 * cos(2 * PI * t / block))
                    re += v * cos(2 * PI * k * t / block); im -= v * sin(2 * PI * k * t / block)
                }
                val mag = sqrt(re * re + im * im)
                num += mag * k * RATE / block; den += mag
            }
            if (den > 0) num / den else 0.0
        }
    }

    /** The amplitude of [series] at [hz] after a quadratic trend is removed. */
    private fun swingAt(series: DoubleArray, blockSeconds: Double, hz: Double): Double {
        val n = series.size
        val t = DoubleArray(n) { it.toDouble() / n }
        val a = Array(3) { DoubleArray(3) }; val y = DoubleArray(3)
        for (i in 0 until n) { val p = doubleArrayOf(1.0, t[i], t[i] * t[i]); for (r in 0..2) { y[r] += p[r] * series[i]; for (c in 0..2) a[r][c] += p[r] * p[c] } }
        fun det(m: Array<DoubleArray>) = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        val d = det(a)
        val coef = DoubleArray(3) { c -> det(Array(3) { r -> DoubleArray(3) { k -> if (k == c) y[r] else a[r][k] } }) / d }
        var re = 0.0; var im = 0.0
        for (i in 0 until n) {
            val res = series[i] - (coef[0] + coef[1] * t[i] + coef[2] * t[i] * t[i])
            val ph = 2 * PI * hz * i * blockSeconds
            re += res * cos(ph); im += res * sin(ph)
        }
        return 2 * sqrt(re * re + im * im) / n
    }

    @Test
    fun `the rotor moves the timbre, which a tremolo cannot`() {
        // A centroid does not move when only the level does. Measured: 102 Hz of swing against 0.5 Hz
        // for a tremolo of the same depth on the still sound.
        val voice = GyreVoice.HALO
        val m = Gyre.defaults(voice) + mapOf("SPIN" to 0.45f, "HOLD" to 0.9f)
        val hz = Gyre.rotorHz(0.45f).toDouble()
        val spun = Gyre.render(voice, m).samples
        val still = Gyre.render(voice, m + ("SPIN" to 0f)).samples
        val block = 1024
        val bs = block.toDouble() / RATE
        val levels = DoubleArray(spun.size / block) { b -> ln(rms(spun, b * block, (b + 1) * block) + 1e-9) }
        val depth = swingAt(levels, bs, hz)
        // [depth] is the swing of the log level, so the control's gain is exp(depth * sin), the same swing.
        val tremolo = FloatArray(still.size) { i -> (still[i] * kotlin.math.exp(depth * sin(2 * PI * hz * i / RATE))).toFloat() }
        val from = (0.3 * RATE).toInt()
        val to = minOf(spun.size, tremolo.size, 4 * RATE)
        val rotor = swingAt(centroids(spun.copyOfRange(from, to), block), bs, hz)
        val control = swingAt(centroids(tremolo.copyOfRange(from, to), block), bs, hz)
        println("rotor at ${"%.2f".format(hz)} Hz: centroid swing ${"%.1f".format(rotor)} Hz, tremolo of the same depth ${"%.1f".format(control)} Hz")
        assertTrue(rotor > 4 * control, "the rotor's swing ($rotor Hz) is not clear of a tremolo's ($control Hz)")
        assertTrue(rotor > 40.0, "the rotor barely moves the timbre: $rotor Hz")
    }

    // ---- the sympathetic strings -------------------------------------------------------

    @Test
    fun `SYMPATHY reaches its target shares`() {
        // Task 5's targets, the source document's table read as levels: subtle, clear, a halo, a cloud.
        val targets = mapOf(0.3f to -22.0, 0.6f to -12.0, 0.8f to -8.0, 1f to -5.0)
        for ((sym, want) in targets) {
            val shares = GyreVoice.entries.map { voice ->
                val m = Gyre.defaults(voice) + ("SYMPATHY" to sym)
                val on = Gyre.play(voice, m).raw
                val off = Gyre.play(voice, m, Gyre.Probe(sympathy = false)).raw
                var e1 = 0.0; var e2 = 0.0
                for (i in off.indices) { val d = on[i] - off[i].toDouble(); e1 += d * d; e2 += off[i].toDouble() * off[i] }
                10 * log10(e1 / e2)
            }
            val mean = shares.average()
            println("SYMPATHY $sym: share ${shares.map { "%.1f".format(it) }} dB, mean ${"%.1f".format(mean)} (target $want)")
            assertTrue(abs(mean - want) <= 2.0, "SYMPATHY $sym: mean share $mean dB, target $want")
        }
    }

    // ---- what the classifier and the kit see -----------------------------------------------

    @Test
    fun `no note at any corner reads as a drum, and the filed class is exact over the LOOP line`() {
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)
        val corners = listOf(emptyMap(), mapOf("BODY" to 0f), mapOf("BODY" to 1f), mapOf("SYMPATHY" to 0f), mapOf("HOLD" to 0f), mapOf("SPIN" to 1f))
        var worstHigh = 0f
        for (voice in GyreVoice.entries) for (corner in corners) for (step in 0..Gyre.TUNE_SEMITONES) {
            val m = Gyre.defaults(voice) + corner + ("TUNE" to step / 24f)
            val heard = Classifier.classify(Gyre.render(voice, m))
            worstHigh = max(worstHigh, heard.features.highRatio)
            assertTrue(heard.drumClass !in choking, "$voice $corner step $step read ${heard.drumClass}")
            val filed = Gyre.drumClassFor(voice, m)
            if (heard.drumClass == DrumClass.LOOP || filed == DrumClass.LOOP) assertEquals(filed, heard.drumClass, "$voice $corner step $step")
        }
        println("worst share over 2 kHz: ${"%.2f".format(worstHigh)} (the snare line is 0.5)")
    }

    @Test
    fun `the render is exactly as long as renderFrames says`() {
        for (voice in GyreVoice.entries) for (hold in floatArrayOf(0f, 0.5f, 0.99f, 1f)) for (sym in floatArrayOf(0f, 1f)) {
            val m = Gyre.defaults(voice) + mapOf("HOLD" to hold, "SYMPATHY" to sym)
            assertEquals(Gyre.renderFrames(m, voice), Gyre.render(voice, m).samples.size, "$voice HOLD $hold SYMPATHY $sym")
        }
    }

    @Test
    fun `HOLD's top step is reserved for the LOOP and renders as the step below it`() {
        for (voice in GyreVoice.entries) {
            val top = Gyre.render(voice, Gyre.defaults(voice) + ("HOLD" to 1f)).samples
            val below = Gyre.render(voice, Gyre.defaults(voice) + ("HOLD" to Gyre.LOOP_THRESHOLD)).samples
            assertTrue(top.contentEquals(below), "$voice")
        }
    }

    @Test
    fun `scramble stays in bounds and every roll is a sound`() {
        val random = Random(41)
        repeat(60) {
            val voice = GyreVoice.entries[it % 2]
            val m = Gyre.scramble(voice, random)
            assertTrue(m.getValue("HOLD") <= Gyre.SCRAMBLE_HOLD_CEILING)
            val s = Gyre.render(voice, m).samples
            var peak = 0f
            for (v in s) { assertTrue(v.isFinite()); peak = max(peak, abs(v)) }
            assertTrue(peak in 0.01f..1f, "$voice $m: peak $peak")
        }
    }

    @Test
    fun `a patch round-trips through JSON, and an R1 recipe has no TOUCH`() {
        val p = GyrePatch("Flick Test", GyreVoice.FLICK, mapOf("BODY" to 0.7f, "SPIN" to 0.3f))
        assertEquals(p, Patches.fromJsonText(p.toJsonText()))
        assertTrue(Gyre.macrosFor(GyreVoice.FLICK).none { it.name == "TOUCH" })
    }
}
