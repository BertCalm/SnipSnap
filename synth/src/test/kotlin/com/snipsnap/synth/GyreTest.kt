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

        /** Where a bowed note is read for its pitch: after its onset. */
        const val BOWED_FROM = 0.6f

        /** Neighbouring HOLD steps: the difference between their renders, against the louder, at least this. */
        const val HOLD_CHANGE_DB = -36.0
    }

    /** Runs [body] over [items] on all cores (every render is independent; a failed assertion in any propagates). */
    private fun <T> eachInParallel(items: List<T>, body: (T) -> Unit) {
        items.parallelStream().forEach { body(it) }
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
            assertTrue(Gyre.rotorHz(0.25f) * Gyre.handSeconds(voice, m) >= 0.75f, "$voice: SPIN 0.25 does not get round a short note")
            val f = Gyre.frequencyFor(voice, m.getValue("TUNE")).toDouble()
            val still = Gyre.render(voice, m + ("SPIN" to 0f)).samples
            val spun = Gyre.render(voice, m + ("SPIN" to 0.25f)).samples
            val end = (Gyre.handSeconds(voice, m) * RATE).toInt()
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
            println("$voice SPIN 0.25 on a ${"%.2f".format(Gyre.handSeconds(voice, m))} s note: partials swing ${swings.joinToString(" ") { "%.1f".format(it) }} dB, median ${"%.1f".format(median)}")
            assertTrue(median >= SPIN_SWING_DB, "$voice: SPIN 0.25 swings a short note's partials only $median dB (median)")
        }
    }

    /** The HOLD at which the hand lands [seconds] after the pluck, at the voice's default SYMPATHY. */
    private fun holdFor(voice: GyreVoice, seconds: Float): Float {
        val d = Gyre.defaults(voice)
        val open = Gyre.openSeconds(d.getValue("SYMPATHY"), d.getValue("TOUCH"), Gyre.shapeOf(voice).stroke)
        return (ln(seconds / Gyre.CHOKE_SECONDS.toDouble()) / ln(open / Gyre.CHOKE_SECONDS.toDouble())).toFloat() * Gyre.LOOP_THRESHOLD
    }

    @Test
    fun `HOLD runs from choked to open on a pluck, and every step is heard`() {
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
            val renders = holds.map { Gyre.render(voice, Gyre.defaults(voice) + mapOf("HOLD" to it, "SYMPATHY" to sym, "TOUCH" to 0f)).samples }
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

    @Test
    fun `HOLD on a bowed note is the stroke, and every step is heard`() {
        // Decision 3: at TOUCH 1 HOLD's top is the voice's stroke, the bow drawn that long and lifted as the
        // hand lands. The hand lands later at every step, exactly at the stroke at the top (HOLD 1 is the
        // LOOP's, and renders as the step below it, so its hand is at the stroke too), and each step changes the
        // sound by at least HOLD_CHANGE_DB, as a pluck's do. Not the pluck's "rung out" claim: a bow is still
        // sounding when the hand lands.
        eachInParallel(GyreVoice.entries) { voice ->
            val stroke = Gyre.shapeOf(voice).stroke
            val m = Gyre.defaults(voice) + mapOf("TOUCH" to 1f)
            val holds = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 0.95f)
            val hands = holds.map { Gyre.handSeconds(voice, m + ("HOLD" to it)) }
            val top = Gyre.handSeconds(voice, m + ("HOLD" to 1f))
            val renders = holds.map { Gyre.render(voice, m + ("HOLD" to it)).samples }
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
            println("$voice TOUCH 1: the hand lands at ${hands.joinToString(" ") { "%.2f".format(it) }} s and at the top ${"%.2f".format(top)} (stroke ${"%.1f".format(stroke)}); each step changes the sound by ${changes.joinToString(" ") { "%.1f".format(it) }} dB")
            assertEquals(stroke, top, 1e-3f, "$voice: HOLD's top is not the stroke")
            for (k in 1 until hands.size) assertTrue(hands[k] > hands[k - 1], "$voice: HOLD step $k's hand lands no later than step ${k - 1}'s")
            assertTrue(hands.first() <= 2f * Gyre.CHOKE_SECONDS, "$voice: HOLD 0 does not choke")
            for (k in changes.indices) assertTrue(changes[k] >= HOLD_CHANGE_DB, "$voice: HOLD steps $k and ${k + 1} differ by only ${changes[k]} dB")
        }
    }

    // ---- the strings play each other ------------------------------------------

    @Test
    fun `a plucked string sets the others ringing, through the bridge only`() {
        // Measured: FLICK's third string answers its first at -26.4 dB, HALO's at -26.9; with the bridge off,
        // exactly 0. HALO answered at -17.0 in round one, when its C4 sat on a membrane mode and poured its
        // fundamental into the others; the wolf guard's decay rule (WOLF_T60) keeps that fundamental now.
        for (voice in GyreVoice.entries) {
            // The claim is the pluck's: DRAWN and BOURDON default to a bow, which drives every string.
            val plucked = Gyre.defaults(voice) + ("TOUCH" to 0f)
            val coupled = Gyre.play(voice, plucked, Gyre.Probe(solo = 0, record = true)).strings!!
            val apart = Gyre.play(voice, plucked, Gyre.Probe(solo = 0, record = true, coupling = 0f)).strings!!
            val db = 20 * log10(rms(coupled[2]) / rms(coupled[0]))
            println("$voice: an unplucked string answers at ${"%.1f".format(db)} dB")
            assertEquals(0.0, rms(apart[2]), "$voice: string 3 rang with the bridge off")
            // BOURDON answers least (-33.2: its G3 string is a long way from its C2's partials on a large body); DRAWN most (-14.7).
            val bound = if (voice == GyreVoice.BOURDON) -36.0 else -30.0
            assertTrue(db > bound, "$voice: string 3 answered at only $db dB")
        }
    }

    // ---- the bound ---------------------------------------------------------------

    @Test
    fun `every corner is finite, bounded and never grows`() {
        // Every macro at both ends, both voices, at TOUCH 0 and 1 (256 renders). The bridge may move energy
        // between strings but never add it, so no 50 ms stretch after the attack is louder than the attack's
        // own loudest. Measured: the loudest later stretch is 0.443 of the attack (FLICK, SYMPATHY 1, the
        // sympathetic strings blooming); the raw peak at most 0.985 (round one: 0.596 and 0.767; the box adds level, BOX_TRIM takes it back).
        // A drawn note is hotter than a pluck, and the output is trimmed for it (BOW_OUT_TRIM_DB); its worst
        // is 1.102 at C5 on BODY 0's 520 Hz mode, FLICK (the ceiling with the bow class's 7 percent is 1.17).
        val cells = ArrayList<Pair<GyreVoice, Map<String, Float>>>()
        for (voice in GyreVoice.entries) for (touch in floatArrayOf(0f, 1f)) for (tune in floatArrayOf(0f, 1f)) for (sym in floatArrayOf(0f, 1f)) for (spin in floatArrayOf(0f, 1f))
            for (body in floatArrayOf(0f, 1f)) for (hold in floatArrayOf(0f, 1f)) {
            cells.add(voice to mapOf("TOUCH" to touch, "TUNE" to tune, "SYMPATHY" to sym, "SPIN" to spin, "BODY" to body, "HOLD" to hold))
        }
        eachInParallel(cells) { (voice, m) ->
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
            // A bowed note sustains above its attack by design, so the claim is only the pluck's.
            if (m.getValue("TOUCH") == 0f) assertTrue(later <= attack, "$voice $m: a later stretch is ${later / attack} of the attack")
        }
    }

    @Test
    fun `every note ends at least 40 dB under its loudest stretch`() {
        // The hand lands at HOLD and the render runs until both the played and the sympathetic
        // strings are END_DB down, so no note is cut off while it still rings. The reference is the
        // loudest 50 ms, not the first 100: a bow ramps up over BOW_ATTACK_SECONDS and a drawn note is
        // loudest well after its attack (a pluck's loudest is its attack, so for it nothing changes).
        // Measured: the quietest ending is 46.4 dB under its loudest stretch (before the hand: 14 dB, FLICK at HOLD 0 and SYMPATHY 1).
        val cells = ArrayList<Pair<GyreVoice, Map<String, Float>>>()
        for (voice in GyreVoice.entries) for (touch in floatArrayOf(0f, 1f)) for (tune in floatArrayOf(0f, 1f)) for (sym in floatArrayOf(0f, 1f)) for (spin in floatArrayOf(0f, 1f))
            for (body in floatArrayOf(0f, 1f)) for (hold in floatArrayOf(0f, 1f)) {
            cells.add(voice to mapOf("TOUCH" to touch, "TUNE" to tune, "SYMPATHY" to sym, "SPIN" to spin, "BODY" to body, "HOLD" to hold))
        }
        val worst = java.util.concurrent.atomic.AtomicReference(Double.POSITIVE_INFINITY)
        eachInParallel(cells) { (voice, m) ->
            val raw = Gyre.play(voice, m).raw
            val block = (0.05f * RATE * Dsp.OVERSAMPLE).toInt()
            val loudest = (0 until raw.size / block).maxOf { rms(raw, it * block, (it + 1) * block) }
            val end = rms(raw, raw.size - block, raw.size)
            val db = 20 * log10(loudest / max(end, 1e-30))
            worst.accumulateAndGet(db) { a, b -> minOf(a, b) }
            assertTrue(db >= 40.0, "$voice $m: ends only $db dB under its loudest stretch")
        }
        println("the quietest ending is ${"%.1f".format(worst.get())} dB under its loudest stretch")
    }

    // ---- pitch ---------------------------------------------------------------------

    /**
     * What each cell's worst note may read, in cents. Round one's 5 for a pluck and a full bow; 10.5 where the
     * bow has only just caught. BOURDON is allowed more: its lowest notes (C2 to F#2) sit on BODY 1's 95 Hz
     * membrane mode, the wolf, where the bridge's phase compensation cannot follow (plucked, 10.7 cents
     * uncorrected; bowed and corrected, 24.4 at D2 and F2). Below BODY 1 it is within 6.6 bowed.
     */
    private fun tuningBound(voice: GyreVoice, touch: Float, body: Float): Double = when {
        voice == GyreVoice.BOURDON && touch > 0f && body >= 1f -> 26.0
        voice == GyreVoice.BOURDON && touch == 0f -> 11.0
        voice == GyreVoice.BOURDON -> 12.0
        touch == 0f || touch == 1f -> 5.0
        else -> 10.5
    }

    @Test
    fun `every note is in tune, coupling and all`() {
        // Measured: worst 3.4 cents (HALO, BODY 1, SYMPATHY 0), with the bridge's phase cancelled (and re-cancelled
        // at every rotor step) and the wolf guard. A bowed note is read once it is speaking, from 0.6 s: with
        // no pluck at TOUCH 1 the string starts from the bow alone, and its first half second is the onset
        // (read from 0.1 s, neighbouring low notes were 10 cents apart and back). A bowed note is also
        // measured and corrected (Gyre.calibratedTrim): before it, the worst cell was 7 to 18 cents at BODY 0.5
        // to 1, after it at most 3.4 at TOUCH 0 and at TOUCH 1, 9.6 at TOUCH 0.5 (the bow just caught: raucous,
        // its pitch wanders) and, scanned outside this test, 5.8 at TOUCH 0.75.
        val cells = ArrayList<List<Any>>()
        // Every note at TOUCH 0; every other note where the bow is drawn (each costs a calibration render too);
        // BODY 0 and 1 only at TOUCH 0.5 (the middle BODY's worst, measured, is never the cell's).
        for (voice in GyreVoice.entries) for (touch in floatArrayOf(0f, 0.5f, 1f)) for (body in (if (touch == 0.5f) floatArrayOf(0f, 1f) else floatArrayOf(0f, 0.5f, 1f))) for (sym in floatArrayOf(0f, 1f)) cells.add(listOf(voice, touch, body, sym))
        eachInParallel(cells) { cell ->
            val voice = cell[0] as GyreVoice; val touch = cell[1] as Float; val body = cell[2] as Float; val sym = cell[3] as Float
            var worst = 0.0
            for (step in 0..Gyre.TUNE_SEMITONES step (if (touch == 0f) 1 else 2)) {
                val m = Gyre.defaults(voice) + mapOf("TOUCH" to touch, "TUNE" to step / 24f, "BODY" to body, "SYMPATHY" to sym)
                val f = Gyre.frequencyFor(voice, step / 24f)
                val from = if (touch == 0f) 0.1f else BOWED_FROM
                val cents = FineTuning.cents(FineTuning.measuredHz(Gyre.render(voice, m), f, from, if (touch == 0f) 0.3f else 0.5f), f.toDouble())
                worst = max(worst, abs(cents))
            }
            val bound = tuningBound(voice, touch, body)
            println("$voice TOUCH $touch BODY $body SYMPATHY $sym: worst ${"%.1f".format(worst)} cents")
            assertTrue(worst <= bound, "$voice TOUCH $touch BODY $body SYMPATHY $sym: $worst cents")
        }
    }

    @Test
    fun `never an octave low, at the most sympathetic`() {
        // G2: whole-number ratios repeat at the note. The house detector is the one keys and SPREAD use.
        // At every TOUCH it never reads below the note. A bowed note in the catch zone can read a higher
        // partial instead (a bow just over its minimum force is raucous, its third partial louder than its
        // first: FLICK reads the third on 11 of 25 notes at TOUCH 0.5, 5 at 0.6; HALO 4 and 3; nothing
        // above 0.9 but one note); the pluck (TOUCH 0 and 0.25) and the full bow read exactly, except
        // BOURDON: at BODY 1 its lowest notes sit on the membrane's 95 Hz mode, so two of them read a partial
        // plucked (a pluck's fundamental is a draw) and one or two bowed, and the detector, whose window holds
        // 16 periods of a 65 Hz note, finds no pitch on 7 or 8 of 25 at TOUCH 0.5.
        val cells = ArrayList<Pair<GyreVoice, Float>>()
        for (voice in GyreVoice.entries) for (touch in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) cells.add(voice to touch)
        eachInParallel(cells) { (voice, touch) ->
            var high = 0; var none = 0
            for (step in 0..Gyre.TUNE_SEMITONES step (if (touch == 0.25f || touch == 0.75f) 2 else 1)) {
                val m = Gyre.defaults(voice) + mapOf("TOUCH" to touch, "TUNE" to step / 24f, "SYMPATHY" to 1f, "BODY" to 1f)
                val f = Gyre.frequencyFor(voice, step / 24f)
                val hz = Pitch.detect(Gyre.render(voice, m))?.hz
                if (hz == null) { none++; continue }
                val cents = 1200 * ln(hz / f.toDouble()) / ln(2.0)
                assertTrue(cents > -50.0, "$voice TOUCH $touch step $step: read $hz Hz for $f, below the note")
                if (cents >= 50.0) high++
            }
            println("$voice TOUCH $touch: $high of ${Gyre.TUNE_SEMITONES + 1} notes read a higher partial, $none no pitch")
            val exact = voice != GyreVoice.BOURDON && (touch == 1f || touch <= 0.25f)
            if (exact) assertEquals(0, high + none, "$voice TOUCH $touch: notes read a higher partial or none")
            else if (voice == GyreVoice.BOURDON && (touch <= 0.25f || touch == 1f)) assertTrue(high + none <= 3, "$voice TOUCH $touch: ${high + none} notes misread")
            else assertTrue(high + none <= 12, "$voice TOUCH $touch: ${high + none} notes misread")
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
        for (voice in listOf(GyreVoice.HALO, GyreVoice.DRAWN, GyreVoice.BOURDON)) rotorMovesTimbre(voice)
    }

    private fun rotorMovesTimbre(voice: GyreVoice) {
        // A centroid does not move when only the level does. Measured: 164 Hz of swing at 2.5 Hz (12% of
        // the mean centroid in round one, 28% now) against 0.4 Hz
        // for a tremolo of the same depth on the still sound (HALO). A drawn note's level wanders on its own, so
        // its control swings too: DRAWN 155 Hz against 15.4, BOURDON 78.8 against 19.5 (4.0 times, the thinnest).
        // SWING_CONTACT at 0.5 changed these a little (DRAWN 176 without it, 155 with, BOURDON 71 and 79): the
        // rotor's other destinations carry the swing. Measured at the 0.35 it stays at: see Gyre.SWING_CONTACT.
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
        println("$voice: rotor at ${"%.2f".format(hz)} Hz: centroid swing ${"%.1f".format(rotor)} Hz, tremolo of the same depth ${"%.1f".format(control)} Hz")
        assertTrue(rotor > 3 * control, "$voice: the rotor's swing ($rotor Hz) is not clear of a tremolo's ($control Hz)")
        assertTrue(rotor > 40.0, "$voice: the rotor barely moves the timbre: $rotor Hz")
    }

    // ---- the sympathetic strings -------------------------------------------------------

    @Test
    fun `SYMPATHY reaches its target shares, plucked and bowed`() {
        // Task 5's targets, the source document's table read as levels: subtle, clear, a halo, a cloud. A
        // sustained bow keeps driving the sympathetic strings where a pluck lets them die, so with the bow
        // caught the share ran 3 to 9 dB over; the two-stage trim (BOW_SYMPATHY_TRIM_DB, then
        // BOW_SYMPATHY_FULL_DB once the upper strings have joined) takes it back. The mean of the voices within
        // 2 dB at TOUCH 0, 0.6, 0.75 and 1. Measured from TOUCH 0.55 up: -1.7 to +1.2 dB (at 1: -0.5, -0.1, +0.9,
        // +0.3). At the catch itself (TOUCH 0.45 to 0.5) it runs up to +4.6 dB hot and under it the bow only
        // damps and the share is low by design: neither is tested.
        val targets = mapOf(0.3f to -22.0, 0.6f to -12.0, 0.8f to -8.0, 1f to -5.0)
        for (touch in floatArrayOf(0f, 0.6f, 0.75f, 1f)) for ((sym, want) in targets) {
            // All four SYMPATHY levels where the bow is lifted or full; the two ends between, where it is catching.
            if ((touch == 0.6f || touch == 0.75f) && sym !in listOf(0.3f, 1f)) continue
            val shares = GyreVoice.entries.map { voice ->
                val m = Gyre.defaults(voice) + mapOf("SYMPATHY" to sym, "TOUCH" to touch)
                val on = Gyre.play(voice, m).raw
                val off = Gyre.play(voice, m, Gyre.Probe(sympathy = false)).raw
                var e1 = 0.0; var e2 = 0.0
                for (i in off.indices) { val d = on[i] - off[i].toDouble(); e1 += d * d; e2 += off[i].toDouble() * off[i] }
                10 * log10(e1 / e2)
            }
            val mean = shares.average()
            println("TOUCH $touch SYMPATHY $sym: share ${shares.map { "%.1f".format(it) }} dB, mean ${"%.1f".format(mean)} (target $want)")
            assertTrue(abs(mean - want) <= 2.5, "TOUCH $touch SYMPATHY $sym: mean share $mean dB, target $want")
            for ((i, share) in shares.withIndex()) assertTrue(abs(share - want) <= 4.5, "TOUCH $touch SYMPATHY $sym: ${GyreVoice.entries[i]}'s share $share dB, target $want")
        }
    }

    // ---- what the classifier and the kit see -----------------------------------------------

    @Test
    fun `no note at any corner reads as a drum, and the filed class is exact over the LOOP line`() {
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)
        val corners = listOf(emptyMap(), mapOf("BODY" to 0f), mapOf("BODY" to 1f), mapOf("SYMPATHY" to 0f), mapOf("HOLD" to 0f), mapOf("SPIN" to 1f))
        val worstHigh = java.util.concurrent.atomic.AtomicReference(0f)
        val cells = ArrayList<Pair<GyreVoice, Map<String, Float>>>()
        for (voice in GyreVoice.entries) for (corner in corners) cells.add(voice to corner)
        eachInParallel(cells) { (voice, corner) ->
            for (step in 0..Gyre.TUNE_SEMITONES) {
                val m = Gyre.defaults(voice) + corner + ("TUNE" to step / 24f)
                val heard = Classifier.classify(Gyre.render(voice, m))
                worstHigh.accumulateAndGet(heard.features.highRatio) { a, b -> max(a, b) }
                assertTrue(heard.drumClass !in choking, "$voice $corner step $step read ${heard.drumClass}")
                val filed = Gyre.drumClassFor(voice, m)
                if (heard.drumClass == DrumClass.LOOP || filed == DrumClass.LOOP) assertEquals(filed, heard.drumClass, "$voice $corner step $step")
            }
        }
        println("worst share over 2 kHz: ${"%.2f".format(worstHigh.get())} (the snare line is 0.5)")
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
    fun `a patch round-trips through JSON, and an R1 recipe is a pure pluck`() {
        // A round-one recipe has no TOUCH key; it decodes at the voice's default, which is 0 for FLICK and
        // HALO (decision 1), so it renders as it did and stays a pluck.
        val p = GyrePatch("Flick Test", GyreVoice.FLICK, mapOf("BODY" to 0.7f, "SPIN" to 0.3f))
        assertEquals(p, Patches.fromJsonText(p.toJsonText()))
        for (voice in listOf(GyreVoice.FLICK, GyreVoice.HALO)) assertEquals(0f, Gyre.defaults(voice).getValue("TOUCH"), "$voice")
        assertEquals(listOf("TUNE", "TOUCH", "SYMPATHY", "SPIN", "BODY", "HOLD"), Gyre.macrosFor(GyreVoice.FLICK).map { it.name })
    }

    // ---- TOUCH 0 is round one --------------------------------------------------------------

    @Test
    fun `TOUCH 0 is the approved R1c, within the bounds`() {
        // The strings are Bows now, the pluck goes in and the sound is taken at the bridge port, and the
        // note starts after a silent pre-roll. At TOUCH 0 the bow is lifted and that must sound as the
        // frozen R1c engine (LegacyGyre) did. Each voice at G3, C4 and G4, defaults otherwise, TOUCH absent
        // (decodes as 0): the pitch to 0.2 cents, the waveform's difference at least 40 dB under the
        // legacy render's energy, the octave bands' mean shift under 0.5 dB, the length identical.
        // Phase 0 on the prototype: the same pitch to its 0.1-cent resolution, -50.4 to -57.6 dB, 0.01 to
        // 0.12 dB, identical lengths.
        val from = (0.05f * RATE).toInt()
        for (voice in listOf(GyreVoice.FLICK, GyreVoice.HALO)) for (steps in intArrayOf(7, 12, 19)) {
            val m = Gyre.defaults(voice) + ("TUNE" to steps / 24f)
            val now = Gyre.render(voice, m)
            val then = LegacyGyre.render(LegacyGyreVoice.valueOf(voice.name), m)
            assertEquals(then.samples.size, now.samples.size, "$voice TUNE $steps: the length moved")
            val f = Gyre.frequencyFor(voice, steps / 24f)
            val cents = FineTuning.cents(FineTuning.measuredHz(now, f, 0.1f, 0.4f), f.toDouble()) -
                FineTuning.cents(FineTuning.measuredHz(then, f, 0.1f, 0.4f), f.toDouble())
            var d = 0.0; var e = 0.0
            for (i in now.samples.indices) { val x = now.samples[i] - then.samples[i].toDouble(); d += x * x; e += then.samples[i].toDouble() * then.samples[i] }
            val diff = 10 * log10(d / e)
            val shift = meanShift(bandShares(now.samples, from, 32_768), bandShares(then.samples, from, 32_768))
            println("$voice TUNE $steps: pitch ${"%.2f".format(cents)} cents from R1c, waveform ${"%.1f".format(diff)} dB, bands ${"%.2f".format(shift)} dB")
            assertTrue(abs(cents) <= 0.2, "$voice TUNE $steps: $cents cents from R1c")
            assertTrue(diff <= -40.0, "$voice TUNE $steps: the waveform differs only $diff dB under R1c")
            assertTrue(shift <= 0.5, "$voice TUNE $steps: the octave bands moved $shift dB")
        }
    }

    // ---- TOUCH ---------------------------------------------------------------------------

    /** A render's colour distance from another's, for a note at [f]: ARCO's yardstick, 0.05 to 0.8 s. */
    private fun colourD(a: FloatArray, b: FloatArray, f: Float): Double = ArcoBodyMeasure.colour(a, b, f.toDouble(), 0.05, 0.8).d

    /** A voice's C4 at [touch], the voice's own HOLD and SYMPATHY 0.3 (the plan's reading). */
    private fun touchRender(voice: GyreVoice, touch: Float): FloatArray =
        Gyre.render(voice, Gyre.defaults(voice) + mapOf("TUNE" to 0.5f, "SYMPATHY" to 0.3f, "TOUCH" to touch)).samples

    @Test
    fun `TOUCH is a continuum`() {
        // Renders at TOUCH 0, 0.1, ..., 1: each step's colour distance from the last, against the ends'
        // distance. The contact is raised to a power (CONTACT_CURVE) for this: a linear contact jumped at its
        // first steps. Measured worst step: FLICK 0.38 and HALO 0.33 (the plan's Phase 0 read 0.34, on a
        // prototype whose settings were not kept), DRAWN 0.47 (0.6 to 0.8, the upper strings joining) and
        // BOURDON 0.61 (0.4 to 0.6, the catch: the widest spread of string levels, so the bow takes over fastest).
        // The bound is each voice's measured worst with room, and BOURDON's catch is a real step in the sound.
        eachInParallel(GyreVoice.entries) { voice ->
            val f = Gyre.frequencyFor(voice, 0.5f)
            val renders = (0..10).map { touchRender(voice, it / 10f) }
            val ends = colourD(renders[0], renders[10], f)
            val steps = (1..10).map { colourD(renders[it - 1], renders[it], f) / ends }
            println("$voice: TOUCH's ends are ${"%.1f".format(ends)} apart; each 0.1 step is ${steps.joinToString(" ") { "%.2f".format(it) }} of it")
            val bound = when (voice) { GyreVoice.BOURDON -> 0.7; GyreVoice.DRAWN -> 0.55; else -> 0.45 }
            for (k in steps.indices) assertTrue(steps[k] <= bound, "$voice: TOUCH ${k / 10f} to ${(k + 1) / 10f} is ${steps[k]} of the ends' distance")
        }
    }

    @Test
    fun `the middle of TOUCH is not a crossfade`() {
        // TOUCH 0.5 against a 50/50 mix of the TOUCH 0 and 1 renders (the mix is two instruments heard
        // together; the middle is one string, plucked and bowed). Measured: 8.4 (FLICK), 11.5 (HALO), 9.5 (DRAWN) and 25.7 (BOURDON).
        eachInParallel(GyreVoice.entries) { voice ->
            val f = Gyre.frequencyFor(voice, 0.5f)
            val plucked = touchRender(voice, 0f)
            val bowed = touchRender(voice, 1f)
            val middle = touchRender(voice, 0.5f)
            val mix = FloatArray(maxOf(plucked.size, bowed.size)) { i -> 0.5f * (if (i < plucked.size) plucked[i] else 0f) + 0.5f * (if (i < bowed.size) bowed[i] else 0f) }
            val d = colourD(middle, mix, f)
            println("$voice: TOUCH 0.5 is ${"%.1f".format(d)} units from a 50/50 mix")
            assertTrue(d >= 2.0, "$voice: TOUCH 0.5 is only $d units from a crossfade")
        }
    }

    @Test
    fun `the bow sustains`() {
        // The note's level 0.5 s in against its attack's, at TOUCH 1 against TOUCH 0: a bow carries the
        // note on where a pluck has died. Measured: the bow holds it 45.8 (FLICK), 47.3 (HALO), 34.4 (DRAWN) and 39.2 (BOURDON) dB better.
        val w = (0.02 * RATE).toInt()
        fun held(x: FloatArray): Double {
            val attack = (0 until 5).maxOf { rms(x, it * w, (it + 1) * w) }
            val at = (0.5 * RATE).toInt()
            return 20 * log10(rms(x, at, at + 2 * w) / attack)
        }
        eachInParallel(GyreVoice.entries) { voice ->
            val plucked = held(touchRender(voice, 0f))
            val bowed = held(touchRender(voice, 1f))
            println("$voice: 0.5 s in, the pluck is ${"%.1f".format(plucked)} dB under its attack, the bow ${"%.1f".format(bowed)}")
            assertTrue(bowed - plucked >= 30.0, "$voice: the bow holds the note only ${bowed - plucked} dB better than the pluck")
        }
    }

    @Test
    fun `the bound holds at the extremes for 30 seconds`() {
        // The spec's "Testing" table: the settings no ordinary note reaches, so a slowly growing coupled
        // network cannot pass every other test. Coupling 1, every string's feedback at FB_CEILING, TOUCH 1,
        // SPIN 1, SYMPATHY 1, BODY 1, the bow drawn for 30 seconds (the hand lands then) and lifted. Every
        // sample is finite, the raw peak stays under RAW_PEAK_CEILING, no second after the first two is
        // louder than the loudest of those by more than the bow's sustain-over-attack margin, and after the
        // lift the note falls to END_DB under its loudest second (the render runs until it has). Measured: the raw
        // peak 0.623 (FLICK) and 0.633 (HALO); the drawn note is -0.1 dB against its first two seconds at its
        // loudest later second and -0.3 and -0.4 at its last (a bow holds its level, it does not build); 48.8 and
        // 49.7 dB down at the end.
        val rate = RATE * Dsp.OVERSAMPLE
        for (voice in GyreVoice.entries) {
            val m = Gyre.defaults(voice) + mapOf("TOUCH" to 1f, "SPIN" to 1f, "SYMPATHY" to 1f, "BODY" to 1f)
            val raw = Gyre.play(voice, m, Gyre.Probe(coupling = 1f, maxFeedback = true, handSeconds = 30f)).raw
            var peak = 0f
            for (v in raw) { assertTrue(v.isFinite(), "$voice: not finite"); peak = max(peak, abs(v)) }
            val seconds = DoubleArray(raw.size / rate) { rms(raw, it * rate, (it + 1) * rate) }
            val start = max(seconds[0], seconds[1])
            val drawn = (2 until 30).maxOf { seconds[it] }
            val last = seconds.indices.last { it < 30 }
            val after = rms(raw, raw.size - rate / 2, raw.size)
            println("$voice: raw peak ${"%.3f".format(peak)}; the loudest later second of the draw is ${"%.1f".format(20 * log10(drawn / start))} dB over the first two; ${seconds.size} s rendered, ${"%.1f".format(20 * log10(after / seconds.max()))} dB at the end; last drawn second ${"%.1f".format(20 * log10(seconds[last] / start))}")
            assertTrue(peak <= Gyre.RAW_PEAK_CEILING, "$voice: raw peak $peak")
            assertTrue(20 * log10(drawn / start) <= 6.0, "$voice: the draw grows ${20 * log10(drawn / start)} dB past its start")
            assertTrue(20 * log10(after / seconds.max()) <= -Gyre.END_DB.toDouble(), "$voice: the note falls only ${20 * log10(after / seconds.max())} dB after the lift")
        }
    }
}
