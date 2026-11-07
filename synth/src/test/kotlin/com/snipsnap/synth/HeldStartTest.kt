package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The held zone's start, shaped ([HeldStart]): the owner's pick of 2026-11-06, [HeldStart.SHORT], on every ARCO and BORE
 * zone. The claims, each pinned here, on every zone of both engines' voices in both layers at the defaults, against the
 * zone as first built (`padMeasured`, the whole start):
 *  - the hit's first 80 ms are the zone's own frames, untouched;
 *  - the loop after the moved marker is the zone's own loop turned, frame for frame, so its level is the zone's, its seam
 *    reads 0 and nothing passes the 0.99 ceiling;
 *  - the marker lands well before the old one: the start is short;
 *  - by the handover the tone is the loop's (the hit's brightness or darkness is gone), where the whole start was still
 *    the hit there at CELLO's low notes;
 *  - the swell into the note arrives no later than the whole start's, and the handover neither dips nor swells against it
 *    by more than a couple of dB (the matched turn lines up the vibrato too; the blend holds the level for how well the
 *    two waves match).
 * And on a synthetic note with known answers: the noise burst plays for its 80 ms, then the tone is the note's own, in
 * phase. Nothing here has been heard beyond the audition the pick was made on.
 */
class HeldStartTest {

    private companion object {
        val RATE = Dsp.RATE

        class Case(val label: String, val full: KeyNote) {
            val shaped: KeyNote = HeldStart.shape(full, HeldStart.SHORT)
        }

        /** Every zone of ARCO and BORE, both layers, at the defaults: the zone as first built, and shaped. */
        val cases: List<Case> by lazy {
            val jobs = ArrayList<() -> Case>()
            for (voice in ArcoVoice.entries) for (midi in ArcoKeys.zoneMidis(voice)) for (layer in ArcoKeys.Layer.entries)
                jobs += { Case("ARCO $voice ${Scales.nameOf(midi)} ${layer.name.lowercase()}", ArcoKeys.padMeasured(voice, Arco.defaults(voice), midi, layer).note) }
            for (voice in BoreVoice.entries) for (midi in BoreKeys.padMidis(voice))
                BoreKeys.layerBreaths(voice, Bore.defaults(voice)).forEachIndexed { layer, breath ->
                    jobs += { Case("BORE $voice ${Scales.nameOf(midi)} ${if (layer == 0) "soft" else "hard"}", BoreKeys.padMeasured(voice, Bore.defaults(voice), midi, breath).note) }
                }
            jobs.parallelStream().map { it() }.toList()
        }

        fun db(x: Double) = 20 * log10(max(x, 1e-12))

        fun rms(x: FloatArray, from: Int, n: Int): Double {
            var e = 0.0
            for (i in from until from + n) e += x[i].toDouble() * x[i]
            return sqrt(e / n)
        }

        /** The power-weighted mean frequency of [n] frames of [x] from [from] (a power of two), Hann-weighted, as ArcoKeysTest reads it. */
        fun centroid(x: FloatArray, from: Int, n: Int): Double {
            val re = DoubleArray(n) { x[from + it] * (0.5 - 0.5 * cos(2.0 * PI * it / n)) }
            val im = DoubleArray(n)
            ArcoBodyMeasure.fft(re, im)
            var num = 0.0
            var den = 0.0
            for (b in 1 until n / 2) {
                val p = re[b] * re[b] + im[b] * im[b]
                num += p * b.toDouble() * RATE / n
                den += p
            }
            return num / den
        }
    }

    @Test
    fun `the pick is 80 ms of hit handed over by 300 ms, and a null hit or a zone it cannot shape is left as it was`() {
        assertEquals(0.08f, HeldStart.SHORT.pure)
        assertEquals(0.30f, HeldStart.SHORT.handover)
        val note = cases.first().full
        assertSame(note, HeldStart.shape(note, null), "a null hit changed the zone")
        val noLoop = KeyNote(note.snip, loopStartFrame = 0)
        assertSame(noLoop, HeldStart.shape(noLoop, HeldStart.SHORT), "a zone with no loop was shaped")
        // A start that never reaches its loop's level before its own marker: silence, then a loud loop.
        val n = RATE * 3
        val quiet = FloatArray(n) { if (it < RATE * 2) 0f else (0.5 * sin(2 * PI * 441.0 * it / RATE)).toFloat() }
        val never = KeyNote(Snip(quiet, 1, RATE), loopStartFrame = RATE * 2L)
        assertSame(never, HeldStart.shape(never, HeldStart.SHORT), "a start that never arrives was shaped")
    }

    @Test
    fun `every zone keeps its hit's first 80 ms and then plays its own loop turned, closed, under the ceiling and sooner`() {
        val a = (HeldStart.SHORT.pure * RATE).roundToInt()
        var latest = 0.0
        var latestLabel = ""
        for (c in cases) {
            val f = c.full.snip.samples
            val s = c.shaped.snip.samples
            val ls = c.full.loopStartFrame.toInt()
            val lf = f.size - ls
            val e = c.shaped.loopStartFrame.toInt()
            for (i in 0 until a) if (s[i] != f[i]) error("${c.label}: frame $i of the hit moved (${s[i]} against ${f[i]})")
            assertEquals(e + lf, s.size, "${c.label}: the shaped zone is not its start and one loop")
            // The loop after the marker is the zone's loop turned: find the turn on its first frames, then every frame must agree.
            val turn = (0 until lf).first { r -> (0 until 16).all { k -> s[e + k] == f[ls + (r + k) % lf] } }
            for (k in 0 until lf) if (s[e + k] != f[ls + (turn + k) % lf]) error("${c.label}: loop frame $k is not the zone's loop turned by $turn")
            val seam = Keys.seamError(s, e)
            assertTrue(seam < 1e-12, "${c.label}: the shaped seam reads $seam")
            assertTrue(s.all { abs(it) <= 0.99f + 1e-6f }, "${c.label}: a frame passes the 0.99 ceiling")
            assertTrue(e >= (HeldStart.SHORT.handover * RATE).roundToInt() + HeldStart.SETTLED_FRAMES, "${c.label}: the marker is before the handover is done")
            assertTrue(e < ls, "${c.label}: the marker did not move earlier ($e against $ls)")
            val seconds = e.toDouble() / RATE
            if (seconds > latest) { latest = seconds; latestLabel = c.label }
        }
        println("HeldStart: ${cases.size} zone-layers shaped; the latest marker %.3f s (%s); the first build's markers ran 1.80 to 3.08 s".format(Locale.ROOT, latest, latestLabel))
        assertTrue(latest < 1.2, "a marker lands at $latest s ($latestLabel): the start is not short")
    }

    @Test
    fun `by the handover the tone is the loop's, where the whole start was still the hit at the cello's low notes`() {
        val from = (HeldStart.SHORT.handover * RATE).roundToInt()
        val n = 4096
        var worst = 0.0
        var worstLabel = ""
        var fullWorst = 0.0
        for (c in cases) {
            val s = c.shaped.snip.samples
            val f = c.full.snip.samples
            val ls = c.full.loopStartFrame.toInt()
            val loop = (0 until 5).map { centroid(f, ls + it * n, n) }.average()
            val shaped = abs(centroid(s, from, n) / loop - 1)
            val full = abs(centroid(f, from, n) / loop - 1)
            if (shaped > worst) { worst = shaped; worstLabel = c.label }
            fullWorst = max(fullWorst, full)
        }
        println("HeldStart: at 0.30 to 0.40 s the centroid sits within %.1f %% of the loop's (worst %s); the whole start's, up to %.1f %% off".format(Locale.ROOT, 100 * worst, worstLabel, 100 * fullWorst))
        assertTrue(worst < 0.10, "$worstLabel: the centroid after the handover is ${100 * worst} % off the loop's")
        assertTrue(fullWorst > 0.30, "control: the whole start is within ${100 * fullWorst} % of the loop at 0.3 s everywhere, so the check cannot fail")
    }

    @Test
    fun `the swell keeps the note's timing, and the handover neither dips nor swells against the whole start`() {
        val w = RATE / 50
        val a = (HeldStart.SHORT.pure * RATE).roundToInt()
        class Row(val label: String, val lag: Double, val over: Double, val fullOver: Double, val dip: Double)
        val rows = ArrayList<Row>()
        for (c in cases) {
            val s = c.shaped.snip.samples
            val f = c.full.snip.samples
            val ls = c.full.loopStartFrame.toInt()
            val loop = rms(f, ls, f.size - ls)
            fun arrives(x: FloatArray, limit: Int): Double {
                var t = 0
                while (t + w < limit && db(rms(x, t, w) / loop) < -3.0) t += w / 2
                return t.toDouble() / RATE
            }
            val e = c.shaped.loopStartFrame.toInt()
            var over = -99.0
            var fullOver = -99.0
            var dip = 0.0
            var t = a
            while (t + w <= e) {
                val sl = db(rms(s, t, w) / loop)
                val fl = db(rms(f, t, w) / loop)
                over = max(over, sl - max(fl, 0.0))
                fullOver = max(fullOver, fl)
                if (fl > -20) dip = minOf(dip, sl - fl)
                t += w / 2
            }
            rows += Row(c.label, arrives(s, e) - arrives(f, ls), over, fullOver, dip)
        }
        for (r in rows.sortedByDescending { abs(it.lag) }.take(4)) println("HeldStart swell, lag: ${r.label} %+.0f ms".format(Locale.ROOT, 1000 * r.lag))
        for (r in rows.sortedByDescending { it.over }.take(4)) println("HeldStart swell, over: ${r.label} %.2f dB over max(full, loop); the full start's own loudest %.2f dB re the loop".format(Locale.ROOT, r.over, r.fullOver))
        for (r in rows.sortedBy { it.dip }.take(4)) println("HeldStart swell, dip: ${r.label} %.2f dB under the full start".format(Locale.ROOT, r.dip))
        // The swell reaches the note's level no later than the whole start does (it can be sooner: it follows the start's loudest
        // level so far, so it does not sag where the low strings catch); the handover runs at most a dB over the louder of the
        // whole start and the loop, and at most 2 dB under the whole start (measured 0.88 and 1.77 dB at the build).
        assertTrue(rows.all { it.lag <= 0.02 }, "a shaped swell arrives late: ${rows.filter { it.lag > 0.02 }.joinToString { it.label }}")
        assertTrue(rows.all { it.over < 1.0 }, "a handover swells: ${rows.filter { it.over >= 1.0 }.joinToString { "${it.label} ${it.over}" }}")
        assertTrue(rows.all { it.dip > -2.0 }, "a handover dips: ${rows.filter { it.dip <= -2.0 }.joinToString { "${it.label} ${it.dip}" }}")
    }

    @Test
    fun `on a known note the burst plays its 80 ms and then the tone is the note's own, in phase`() {
        // A 441 Hz tone (100 frames a period) swelling over 0.4 s under a noise burst in its first 0.1 s, then steady; the loop
        // is one second of the steady tone from 2 s.
        val rng = Random(11)
        val n = RATE * 3
        val x = FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val tone = 0.5 * minOf(1.0, t / 0.4) * sin(2 * PI * 441.0 * t)
            val burst = if (t < 0.1) 0.4 * (rng.nextDouble() - 0.5) else 0.0
            (tone + burst).toFloat()
        }
        val note = KeyNote(Snip(x, 1, RATE), loopStartFrame = RATE * 2L)
        val shaped = HeldStart.shape(note, HeldStart.SHORT)
        val s = shaped.snip.samples
        val a = (HeldStart.SHORT.pure * RATE).roundToInt()
        val h = (HeldStart.SHORT.handover * RATE).roundToInt()
        val e = shaped.loopStartFrame.toInt()
        for (i in 0 until a) assertEquals(x[i], s[i], "the burst's frame $i moved")
        // After the handover the shaped start is the tone alone: no burst left, and in phase with the note's own tone.
        var diff = 0.0
        var level = 0.0
        for (i in h until e) {
            val t = i.toDouble() / RATE
            val tone = 0.5 * minOf(1.0, t / 0.4) * sin(2 * PI * 441.0 * t)
            diff += (s[i] - tone) * (s[i] - tone)
            level += tone * tone
        }
        println("HeldStart synthetic: after the handover the start is the note's tone to %.1f dB".format(Locale.ROOT, 10 * log10(diff / level)))
        assertTrue(10 * log10(diff / level) < -30, "after the handover the start is not the note's own tone (${10 * log10(diff / level)} dB)")
        assertEquals(e + RATE, s.size)
    }
}
