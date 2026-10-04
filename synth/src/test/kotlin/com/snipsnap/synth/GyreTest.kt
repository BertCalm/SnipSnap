package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
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

    private companion object {
        /** BODY 0 to 1, and each half of it, in the octave bands' mean shift. */
        const val BODY_SHIFT_DB = 7.0
        const val BODY_HALF_DB = 2.5

        /** SPIN 0.25 on a short note, in the median partial's swing. */
        const val SPIN_SWING_DB = 5.0

        /** The short note SPIN is judged on: the hand lands this long after the pluck. */
        const val SHORT_NOTE_SECONDS = 0.55f

        /** Each HOLD step's hand, in dB under the attack: HOLD 0 cuts a loud note, the top lands on a rung-out one, and every step is heard. */
        const val CHOKE_DB = -15.0
        const val OPEN_DB = -33.0

        /** Neighbouring HOLD steps: the difference between their renders, against the louder, at least this. */
        const val HOLD_CHANGE_DB = -36.0
    }

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
        // G9: the first stretch of SPIN must move something. Measured: 0.02 against 0 differs by 4.8% (FLICK), 5.7% (HALO).
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

    /**
     * Each octave band's share of the energy, in dB, over [size] samples from [from] (60 Hz to 8 kHz,
     * seven bands): the yardstick the owner's first listen was measured with.
     */
    private fun bandShares(x: FloatArray, from: Int, size: Int): DoubleArray {
        val re = FloatArray(size) { i -> if (from + i < x.size) x[from + i] * (0.5f - 0.5f * cos(2 * PI * i / size).toFloat()) else 0f }
        val im = FloatArray(size)
        Fft.forward(re, im)
        val edges = doubleArrayOf(60.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0)
        val e = DoubleArray(edges.size - 1)
        var total = 1e-30
        for (k in 1 until size / 2) {
            val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            total += p
            val hz = k.toDouble() * RATE / size
            for (b in e.indices) if (hz >= edges[b] && hz < edges[b + 1]) e[b] += p
        }
        return DoubleArray(e.size) { 10 * log10(e[it] / total + 1e-6) }
    }

    private fun meanShift(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { abs(a[it] - b[it]) } / a.size

    @Test
    fun `BODY changes the instrument as much as SYMPATHY does`() {
        // The owner's first listen: "Body doesn't make an impact". Measured then, on the audition's clips,
        // BODY 0 to 1 moved the octave bands 3.6 dB on average (FLICK) and 3.4 (HALO), against SYMPATHY's
        // 13.3 and 12.4; here, 0.6 to 4.1 dB over the three notes, each half 0.2 to 2.3. With the box:
        // 8.1 to 14.4, each half at least 3.2.
        val from = (0.05f * RATE).toInt()
        for (voice in GyreVoice.entries) for (tune in floatArrayOf(0f, 0.5f, 1f)) {
            val m = Gyre.defaults(voice) + ("TUNE" to tune)
            val small = bandShares(Gyre.render(voice, m + ("BODY" to 0f)).samples, from, 32_768)
            val middle = bandShares(Gyre.render(voice, m + ("BODY" to 0.5f)).samples, from, 32_768)
            val large = bandShares(Gyre.render(voice, m + ("BODY" to 1f)).samples, from, 32_768)
            val whole = meanShift(small, large)
            val halves = minOf(meanShift(small, middle), meanShift(middle, large))
            println("$voice TUNE $tune: BODY 0 to 1 moves the bands ${"%.1f".format(whole)} dB, each half at least ${"%.1f".format(halves)}")
            assertTrue(whole >= BODY_SHIFT_DB, "$voice TUNE $tune: BODY 0 to 1 moves the bands only $whole dB")
            assertTrue(halves >= BODY_HALF_DB, "$voice TUNE $tune: one half of BODY moves the bands only $halves dB")
        }
    }

    /** The level in dB of each of the note's first [count] partials over [block] samples from [from]. */
    private fun partials(x: FloatArray, from: Int, block: Int, f: Double, count: Int): DoubleArray = DoubleArray(count) { h ->
        val hz = f * (h + 1)
        var re = 0.0; var im = 0.0
        for (i in 0 until block) {
            val v = x[from + i] * (0.5 - 0.5 * cos(2 * PI * i / block))
            re += v * cos(2 * PI * hz * i / RATE); im += v * sin(2 * PI * hz * i / RATE)
        }
        20 * log10(sqrt(re * re + im * im) + 1e-9)
    }

    @Test
    fun `the tail waits for the box, at the loudest swell SPIN gives it`() {
        // Copilot's review of #434: a peak rings A = 10^(dB/40) times longer than a band-pass of its
        // Q, and SPIN's swell boosts it further. The box's six sections at the largest gain the rotor
        // gives each peak, rung by an impulse: the time its 10 ms level takes to fall 60 dB from its
        // loudest, against boxT60. Measured: within boxT60 everywhere, to the 10 ms window (BODY 1, SPIN 1:
        // 0.43 s against 0.51; the band-pass formula this replaced allowed 0.15).
        val rate = RATE * Dsp.OVERSAMPLE
        val window = (0.01 * rate).toInt()
        for (body in floatArrayOf(0f, 0.5f, 1f)) for (spin in floatArrayOf(0f, 0.25f, 1f)) {
            val size = Gyre.boxSize(body)
            val q = Dsp.lin(size, Gyre.BOX_Q_SMALL, Gyre.BOX_Q_LARGE)
            val swell = 1f + Gyre.spinDepth(spin) * Gyre.SWING_BOX
            val box = Array(Gyre.BOX_SMALL_HZ.size + 2) { Dsp.Biquad() }
            for (j in Gyre.BOX_SMALL_HZ.indices) box[j].peaking(
                Dsp.expMap(size, Gyre.BOX_SMALL_HZ[j], Gyre.BOX_LARGE_HZ[j]),
                Dsp.lin(size, Gyre.BOX_SMALL_DB[j], Gyre.BOX_LARGE_DB[j]) * swell, q, rate,
            )
            box[Gyre.BOX_SMALL_HZ.size].lowShelf(Gyre.BOX_LOW_SHELF_HZ, Dsp.lin(size, Gyre.BOX_LOW_SMALL_DB, Gyre.BOX_LOW_LARGE_DB), rate)
            box[Gyre.BOX_SMALL_HZ.size + 1].highShelf(Gyre.BOX_HIGH_SHELF_HZ, Dsp.lin(size, Gyre.BOX_HIGH_SMALL_DB, Gyre.BOX_HIGH_LARGE_DB), rate)
            val t60 = Gyre.boxT60(body, spin)
            val y = FloatArray(((t60 * 2f + 0.05f) * rate).toInt()) { i -> var v = if (i == 0) 1f else 0f; for (b in box) v = b.process(v); v }
            // The impulse's own sample is the dry path; the ring is what follows it.
            val levels = DoubleArray(y.size / window) { rms(y, maxOf(1, it * window), (it + 1) * window) }
            val loudest = levels.indices.maxBy { levels[it] }
            val down = (loudest until levels.size).first { levels[it] < levels[loudest] * 1e-3 }
            val measured = (down - loudest) * window.toDouble() / rate
            println("BODY $body SPIN $spin: the box falls 60 dB in ${"%.3f".format(measured)} s, boxT60 ${"%.3f".format(t60)} s")
            assertTrue(measured <= t60 * 1.05 + 0.01, "BODY $body SPIN $spin: the box rings ${measured} s, the tail allows $t60")
        }
    }

    @Test
    fun `SPIN is heard on a short note at a quarter turn`() {
        // The owner's first listen: "Spin not noticable on short notes". Each partial's level against
        // the still note, block by block while the note rings (before the hand lands), with the slow
        // drift taken out: what is left is the rotor's swing. Octave bands hide it (partials 2 and 3
        // share a band and turn in opposite phase). Measured before the fix: a median swing of 5.5 dB
        // (FLICK, one partial near a notch carrying most of it) and 2.7 (HALO), the rotor 0.23 of a
        // turn round in FLICK's 0.53 s.
        val block = 2_048
        for (voice in GyreVoice.entries) {
            val hold = holdFor(voice, SHORT_NOTE_SECONDS)
            val m = Gyre.defaults(voice) + ("HOLD" to hold)
            assertTrue(Gyre.rotorHz(0.25f) * Gyre.dampSeconds(hold, m.getValue("SYMPATHY")) >= 0.75f, "$voice: SPIN 0.25 does not get round a short note")
            val f = Gyre.frequencyFor(voice, m.getValue("TUNE")).toDouble()
            val still = Gyre.render(voice, m + ("SPIN" to 0f)).samples
            val spun = Gyre.render(voice, m + ("SPIN" to 0.25f)).samples
            val end = (Gyre.dampSeconds(hold, m.getValue("SYMPATHY")) * RATE).toInt()
            val diff = ArrayList<DoubleArray>()
            val level = ArrayList<DoubleArray>()
            var b = (0.05f * RATE).toInt()
            while (b + block <= end) {
                val x = partials(still, b, block, f, 8)
                val y = partials(spun, b, block, f, 8)
                diff.add(DoubleArray(8) { y[it] - x[it] }); level.add(x)
                b += block / 2
            }
            val mean = DoubleArray(8) { h -> level.sumOf { it[h] } / level.size }
            val swings = (0 until 8).filter { mean[it] > mean.max() - 30.0 }.map { h ->
                val n = diff.size
                val tm = (n - 1) / 2.0
                val ym = diff.sumOf { it[h] } / n
                val slope = (0 until n).sumOf { (it - tm) * (diff[it][h] - ym) } / (0 until n).sumOf { (it - tm) * (it - tm) }
                val rest = DoubleArray(n) { diff[it][h] - ym - slope * (it - tm) }
                rest.max() - rest.min()
            }.sorted()
            val median = swings[swings.size / 2]
            println("$voice SPIN 0.25 on a ${"%.2f".format(Gyre.dampSeconds(hold, m.getValue("SYMPATHY")))} s note: partials swing ${swings.joinToString(" ") { "%.1f".format(it) }} dB, median ${"%.1f".format(median)}")
            assertTrue(median >= SPIN_SWING_DB, "$voice: SPIN 0.25 swings a short note's partials only $median dB (median)")
        }
    }

    /** The HOLD at which the hand lands [seconds] after the pluck, at the voice's default SYMPATHY. */
    private fun holdFor(voice: GyreVoice, seconds: Float): Float {
        val open = Gyre.openSeconds(Gyre.defaults(voice).getValue("SYMPATHY"))
        return (ln(seconds / Gyre.CHOKE_SECONDS.toDouble()) / ln(open / Gyre.CHOKE_SECONDS.toDouble())).toFloat() * Gyre.LOOP_THRESHOLD
    }

    @Test
    fun `HOLD runs from choked to open, and every step is heard`() {
        // The owner's second listen: "I don't hear the distinction". Round one's FLICK hand landed
        // 25 dB under the attack at HOLD 0, 50 at 0.5 and 108 at 0.95 (HALO 14, 32, 77): the note had
        // rung out before the hand came, and neighbouring steps were the same sound. Five steps, both
        // voices, at SYMPATHY 0, the default and 1: the level at the moment the hand lands (a choke at
        // the bottom, a rung-out note at the top), and how much of the sound changes from each step to
        // the next (the difference's energy against the note's, as rendered). Measured: the hand lands
        // 2 to 4 dB down at HOLD 0 and 41 to 45 at the top, about 10 dB lower each step; each step
        // changes the sound by -10.8 to -33.0 dB, the top step least (near open it trims a quiet tail).
        val w = (0.02 * RATE).toInt()
        for (voice in GyreVoice.entries) for (sym in listOf(0f, Gyre.defaults(voice).getValue("SYMPATHY"), 1f)) {
            val holds = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 0.95f)
            val renders = holds.map { Gyre.render(voice, Gyre.defaults(voice) + mapOf("HOLD" to it, "SYMPATHY" to sym)).samples }
            val levels = holds.indices.map { k ->
                val x = renders[k]
                val attack = (0 until 5).maxOf { rms(x, it * w, (it + 1) * w) }
                val d = (Gyre.dampSeconds(holds[k], sym) * RATE).toInt()
                20 * log10(rms(x, maxOf(0, d - w), maxOf(w, d)) / attack)
            }
            val changes = (1 until holds.size).map { k ->
                val a = renders[k - 1]; val b = renders[k]
                var d = 0.0; var e = 0.0
                for (i in 0 until maxOf(a.size, b.size)) {
                    val x = if (i < a.size) a[i].toDouble() else 0.0
                    val y = if (i < b.size) b[i].toDouble() else 0.0
                    d += (x - y) * (x - y); e += maxOf(x * x, y * y)
                }
                10 * log10(d / e)
            }
            println("$voice SYMPATHY $sym: the hand lands at ${levels.joinToString(" ") { "%.0f".format(it) }} dB; each step changes the sound by ${changes.joinToString(" ") { "%.1f".format(it) }} dB")
            assertTrue(levels.first() >= CHOKE_DB, "$voice SYMPATHY $sym: HOLD 0's hand lands ${levels.first()} dB down, not a choke")
            assertTrue(levels.last() <= OPEN_DB, "$voice SYMPATHY $sym: the top's hand lands only ${levels.last()} dB down, not open")
            for (k in changes.indices) assertTrue(changes[k] >= HOLD_CHANGE_DB, "$voice SYMPATHY $sym: HOLD steps $k and ${k + 1} differ by only ${changes[k]} dB")
            for (k in 1 until levels.size) assertTrue(levels[k] < levels[k - 1], "$voice SYMPATHY $sym: HOLD step $k's hand lands no lower than step ${k - 1}'s")
        }
    }

    // ---- the strings play each other ------------------------------------------

    @Test
    fun `a plucked string sets the others ringing, through the bridge only`() {
        // Measured: FLICK's third string answers its first at -26.4 dB, HALO's at -26.9; with the bridge off,
        // exactly 0. HALO answered at -17.0 in round one, when its C4 sat on a membrane mode and poured its
        // fundamental into the others; the wolf guard's decay rule (WOLF_T60) keeps that fundamental now.
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
        // own loudest. Measured: the loudest later stretch is 0.443 of the attack (FLICK, SYMPATHY 1, the
        // sympathetic strings blooming); the raw peak at most 0.985 (round one: 0.596 and 0.767; the box adds level, BOX_TRIM takes it back).
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
        // ending is 55.1 dB under its attack (before the hand: 14 dB, FLICK at HOLD 0 and SYMPATHY 1).
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
        // Measured: worst 3.4 cents (HALO, BODY 1, SYMPATHY 0), with the bridge's phase cancelled (and re-cancelled
        // at every rotor step) and the wolf guard.
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
        // A centroid does not move when only the level does. Measured: 164 Hz of swing at 2.5 Hz (12% of
        // the mean centroid in round one, 28% now) against 0.4 Hz
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
