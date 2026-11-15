package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CALABASH round 1: the house probes on every voice, and the design's claims turned into tests (STROKE switches the
 * event, the talking drum rises as it is squeezed, the fixed draws are the approved prototype's). Distances are the
 * prototype's measure (`prototype/ember/measures.py`, CLUTTER's): the RMS difference of level-matched third-octave
 * spectrograms, every band floored 80 dB under the loudest; over the first 150 ms in 5 ms frames, or over the whole
 * render in 10 ms frames for the long voices (the rolls, ride and crash) and for the controls.
 *
 * The A/B against the prototype itself is `prototype/calabash/compare_kotlin.py` (it needs Python); this file holds
 * what the port must keep whatever the prototype says.
 */
class CalabashTest {

    private val all = CalabashVoice.entries
    private val long = setOf(CalabashVoice.RIDE, CalabashVoice.CRASH, CalabashVoice.SHEKERE_RUSH, CalabashVoice.TALKING_CALL,
        CalabashVoice.SABAR_ROLL, CalabashVoice.ABANICO)
    private val rolls = setOf(CalabashVoice.SHEKERE_RUSH, CalabashVoice.TALKING_CALL, CalabashVoice.SABAR_ROLL, CalabashVoice.ABANICO)

    private fun render(v: CalabashVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0) =
        Calabash.render(v, macros, velocity, take).samples

    /** Renders in parallel (the voices are independent); results in the order asked. */
    private fun <T> par(items: List<T>, f: (T) -> FloatArray): List<FloatArray> = items.parallelStream().map(f).toList()

    private fun dist(v: CalabashVoice, a: FloatArray, b: FloatArray) = if (v in long) fullDistanceDb(a, b) else distanceDb(a, b)

    @Test fun `every voice is finite, peak-matched, centred and as long as its voice`() {
        val ys = par(all) { render(it) }
        for ((v, y) in all.zip(ys)) {
            assertTrue(y.all { it.isFinite() }, "$v finite")
            assertEquals(Calabash.PEAK, y.maxOf { abs(it) }, 1e-4f, "$v peak")
            assertTrue(abs(y.average()) < 0.005, "$v DC ${y.average()}")
            assertEquals((v.seconds * Calabash.RATE).toInt(), y.size, "$v length")
        }
    }

    @Test fun `every take replays byte for byte and the four takes are four strikes`() {
        val short = all.filter { it !in rolls }
        val takes = par(short.flatMap { v -> (0..Calabash.MAX_TAKE).map { v to it } }) { (v, t) -> render(v, take = t) }
        for ((i, v) in short.withIndex()) {
            val ts = takes.subList(4 * i, 4 * i + 4)
            assertContentEquals(ts[2], render(v, take = 2), "$v take 2 replays")
            var closest = Double.MAX_VALUE
            for (a in 0 until 4) for (b in a + 1 until 4) closest = min(closest, dist(v, ts[a], ts[b]))
            // ETCH's takes the owner heard as one strike measured 0.5 to 0.8 dB on this measure.
            assertTrue(closest >= 1.0, "$v closest takes $closest dB")
        }
        // The rolls re-strike every stroke: two takes are two performances.
        val rollTakes = par(rolls.flatMap { v -> listOf(v to 0, v to 1) }) { (v, t) -> render(v, take = t) }
        for ((i, v) in rolls.withIndex()) {
            val d = fullDistanceDb(rollTakes[2 * i], rollTakes[2 * i + 1])
            assertTrue(d >= 1.0, "$v takes 0 and 1: $d dB")
        }
    }

    @Test fun `velocity changes the stroke, not only the level`() {
        val pairs = par(all.flatMap { listOf(it to 0.35f, it to 1f) }) { (v, vel) -> render(v, velocity = vel) }
        for ((i, v) in all.withIndex()) {
            val d = dist(v, pairs[2 * i], pairs[2 * i + 1])
            // The approved prototype's weakest kit pad measured 1.84 dB soft against hard (the talking call, a roll
            // whose strokes keep their own dynamics); every one-shot voice clears 2 dB.
            assertTrue(d >= (if (v in rolls) 1.0 else 2.0), "$v soft against hard at matched level: $d dB")
        }
    }

    @Test fun `every control moves its voice and an unknown control or a bad value changes nothing`() {
        // The rolls take seconds a render: their own controls (TUNE, SPEED, SQUEEZE) are swept, ROOM and AGE are the
        // same finish every voice shares and are swept on the one-shots.
        val cases = all.flatMap { v ->
            Calabash.macrosFor(v).map { it.name }.filter { v !in rolls || it !in setOf("ROOM", "AGE") }.flatMap { m ->
                listOf(Triple(v, m, 0f), Triple(v, m, 1f))
            }
        }
        val ys = par(cases) { (v, m, x) -> render(v, mapOf(m to x)) }
        for (i in cases.indices step 2) {
            val (v, m, _) = cases[i]
            val d = fullDistanceDb(ys[i], ys[i + 1])
            // The prototype's weakest was the tom's AGE at 1.21 dB (an era chain on a dark low drum).
            assertTrue(d >= 1.0, "$v $m moved it $d dB between 0 and 1")
        }
        for (v in all.filter { it !in rolls }) {
            val plain = render(v)
            assertContentEquals(plain, render(v, mapOf("NOPE" to 0.9f)), "$v unknown control")
            assertContentEquals(plain, render(v, mapOf("TUNE" to Float.NaN)), "$v NaN control")
            assertContentEquals(plain, render(v, velocity = Float.NaN), "$v NaN velocity")
        }
    }

    @Test fun `STROKE switches the event on every drum that has one`() {
        val anchors = mapOf(
            CalabashVoice.CONGA to listOf(0f, 1f / 3f, 2f / 3f, 1f), CalabashVoice.BONGO_HIGH to listOf(0f, 1f / 3f, 2f / 3f, 1f),
            CalabashVoice.NDER to listOf(0f, 1f / 3f, 2f / 3f, 1f), CalabashVoice.TIMBALE to listOf(0f, .5f, 1f),
            CalabashVoice.COWBELL to listOf(0f, .5f, 1f), CalabashVoice.SHEKERE to listOf(0f, .5f, 1f),
        )
        val cases = anchors.flatMap { (v, xs) -> xs.map { v to it } }
        val ys = par(cases) { (v, x) -> render(v, mapOf("STROKE" to x)) }
        var k = 0
        for ((v, xs) in anchors) {
            val strokes = ys.subList(k, k + xs.size)
            k += xs.size
            for (a in strokes.indices) for (b in a + 1 until strokes.size) {
                val d = distanceDb(strokes[a], strokes[b])
                assertTrue(d >= 3.0, "$v STROKE ${xs[a]} against ${xs[b]}: $d dB")
            }
        }
    }

    @Test fun `the talking drum rises as the arm squeezes`() {
        for (v in listOf(CalabashVoice.DUNDUN, CalabashVoice.TAMA)) {
            val relaxed = render(v, mapOf("SQUEEZE" to 0f))
            val pressed = render(v, mapOf("SQUEEZE" to 0.8f, "CONTOUR" to 0f))
            val lo = dominantHz(relaxed, 0.2, 0.4, 80.0, 900.0)
            val hi = dominantHz(pressed, 0.2, 0.4, 80.0, 900.0)
            // Eight semitones of squeeze is a ratio of 1.59; the head's own settling and the modes' mix leave room.
            assertTrue(hi > lo * 1.35, "$v relaxed $lo Hz, squeezed $hi Hz")
        }
    }

    @Test fun `the patch carries velocity, take and model and refuses what it cannot render`() {
        val p = CalabashPatch("Slap", CalabashVoice.CONGA, mapOf("STROKE" to 1f), velocity = 0.6f, take = 2)
        val back = CalabashPatch.fromJsonText(p.toJsonText())
        assertEquals(p, back)
        assertEquals("CALABASH", back.engine)
        assertContentEquals(p.render().samples, back.render().samples)
        assertFailsWith<IllegalArgumentException> { CalabashPatch("x", CalabashVoice.CONGA, emptyMap(), take = Calabash.MAX_TAKE + 1) }
        assertFailsWith<IllegalArgumentException> { CalabashPatch("x", CalabashVoice.CONGA, emptyMap(), velocity = 1.5f) }
        assertFailsWith<IllegalArgumentException> { CalabashPatch("x", CalabashVoice.CONGA, emptyMap(), model = Calabash.MODEL_VERSION + 1) }
        assertFailsWith<IllegalArgumentException> { CalabashPatch("x", CalabashVoice.CONGA, mapOf("SQUEEZE" to .5f)) }
    }

    @Test fun `every voice files as the kit role it plays`() {
        fun of(c: DrumClass) = all.filter { Calabash.drumClassFor(it) == c }.toSet()
        assertEquals(setOf(CalabashVoice.KICK, CalabashVoice.DEEP_KICK), of(DrumClass.KICK))
        assertEquals(setOf(CalabashVoice.SNARE, CalabashVoice.RIMSHOT), of(DrumClass.SNARE))
        assertEquals(setOf(CalabashVoice.HAT_CLOSED, CalabashVoice.HAT_FOOT), of(DrumClass.HAT_CLOSED))
        assertEquals(setOf(CalabashVoice.HAT_OPEN), of(DrumClass.HAT_OPEN))
        assertEquals(setOf(CalabashVoice.CLAP, CalabashVoice.SNAP), of(DrumClass.CLAP))
        assertEquals(setOf(CalabashVoice.TOM, CalabashVoice.COL), of(DrumClass.TOM))
        // The long voices are PERC, as BREAKER files its crash and riser: never a LOOP.
        for (v in long) assertEquals(DrumClass.PERC, Calabash.drumClassFor(v), "$v")
    }

    @Test fun `AGE 0 is an exact bypass and the room is built once per ROOM`() {
        val x = DoubleArray(4000) { sin(it * 0.01) }
        assertSame(x, Calabash.ageChain(x, 0.0, 1.0))
        assertSame(Calabash.roomIr(0.3), Calabash.roomIr(0.3))
    }

    @Test fun `the fixed draws are the approved prototype's`() {
        // numpy's PCG64 seeded by seed_for("calabash-room"): its first draws, and the first of the second half.
        val noise = CalabashFits.roomNoise
        assertEquals(502_740, noise.size)
        assertEquals(-1.2144702672958374, noise[0], 1e-12)
        assertEquals(0.8308261036872864, noise[1], 1e-12)
        assertEquals(-0.8047413229942322, noise[251_370], 1e-12)
        assertEquals(0.9214503765106201, noise.last(), 1e-12)
        assertEquals(6, CalabashFits.crowd.size)
    }

    @Test fun `the four kits are the approved ones and every pad is a valid patch`() {
        for (kit in CalabashKits.Kit.entries) {
            val pads = CalabashKits.pads(kit)
            assertEquals(CalabashKits.PADS, pads.size, "$kit")
            for (pad in pads) CalabashPatch(pad.name, pad.voice, pad.macros)
            // A16 is every kit's roll or riser (the percussion gate).
            assertTrue(pads.last().voice in rolls, "$kit A16 is ${pads.last().voice}")
        }
        assertEquals(listOf(112, 104, 126, 176), CalabashKits.Kit.entries.map { it.bpm })
    }

    // ------------------------------------------------------------------ measures (prototype/ember/measures.py)

    private val thirdOct = DoubleArray(22) { 80.0 * 2.0.pow(it / 3.0) }

    private fun spectroDb(x: FloatArray, frameMs: Int, totalS: Double): Array<DoubleArray> {
        val rate = Calabash.RATE
        val take = min(x.size, (totalS * rate).toInt())
        var rms = 0.0
        for (i in 0 until take) rms += x[i].toDouble() * x[i]
        rms = sqrt(rms / max(1, take)) + 1e-12
        val hop = frameMs * rate / 1000
        val win = 4 * hop
        var size = 1
        while (size < win) size = size shl 1
        val padded = DoubleArray(max(take, win + hop)) { if (it < take) x[it] / rms else 0.0 }
        val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / (win - 1)) }
        val edges = thirdOct.map { (it / 2.0.pow(1.0 / 6)) to (it * 2.0.pow(1.0 / 6)) }
        val rows = ArrayList<DoubleArray>()
        var i = 0
        while (i < max(1, padded.size - win)) {
            val re = FloatArray(size); val im = FloatArray(size)
            for (k in 0 until win) re[k] = (padded[i + k] * hann[k]).toFloat()
            Fft.forward(re, im)
            val e = DoubleArray(thirdOct.size)
            for (bin in 0..size / 2) {
                val f = bin.toDouble() * rate / size
                val p = re[bin].toDouble().pow(2) + im[bin].toDouble().pow(2)
                for (b in edges.indices) if (f >= edges[b].first && f < edges[b].second) e[b] += p
            }
            rows.add(DoubleArray(thirdOct.size) { 10 * log10(e[it] + 1e-12) })
            i += hop
        }
        val top = rows.maxOf { it.max() }
        return Array(rows.size) { r -> DoubleArray(thirdOct.size) { max(rows[r][it], top - 80.0) } }
    }

    private fun rmsDiff(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        val n = min(a.size, b.size)
        var s = 0.0; var c = 0
        for (r in 0 until n) for (k in a[r].indices) { s += (a[r][k] - b[r][k]).pow(2); c++ }
        return sqrt(s / c)
    }

    private fun distanceDb(a: FloatArray, b: FloatArray) = rmsDiff(spectroDb(a, 5, 0.15), spectroDb(b, 5, 0.15))

    private fun fullDistanceDb(a: FloatArray, b: FloatArray): Double {
        val n = max(a.size, b.size)
        return rmsDiff(spectroDb(a.copyOf(n), 10, n.toDouble() / Calabash.RATE), spectroDb(b.copyOf(n), 10, n.toDouble() / Calabash.RATE))
    }

    /** The strongest frequency between [lo] and [hi] inside a time window, by a DFT scan in 2 Hz steps. */
    private fun dominantHz(x: FloatArray, t0: Double, t1: Double, lo: Double, hi: Double): Double {
        val a = (t0 * Calabash.RATE).toInt(); val b = min(x.size, (t1 * Calabash.RATE).toInt())
        val n = b - a
        var best = lo; var bestE = -1.0
        var f = lo
        while (f <= hi) {
            var re = 0.0; var im = 0.0
            for (i in 0 until n) {
                val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
                val ph = 2 * PI * f * i / Calabash.RATE
                re += x[a + i] * w * cos(ph); im -= x[a + i] * w * sin(ph)
            }
            val e = re * re + im * im
            if (e > bestE) { bestE = e; best = f }
            f += 2.0
        }
        return best
    }
}
