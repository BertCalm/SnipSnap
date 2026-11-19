package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * KERF's fitter (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md, round 2).
 *
 * The repository holds no source audio, so these tests feed the fitter hits it can check the answer to: renders of the
 * fitted bank. A render is a fair stand-in for a chop because it has everything the fitter looks for (a gliding body,
 * layered noise, resonances, ticks) and the truth is known. How the fitter does on real chops is measured outside the
 * suite, on the owner's own loops, and recorded in the spec.
 */
class KerfFitTest {
    private val rate = KerfRender.RATE
    private val kitB = KerfBank.kits.getValue("B")
    private fun pad(n: Int) = kitB.first { it.pad == n }
    private fun db(ratio: Double) = 10 * log10(ratio + 1e-30)
    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double { var s = 0.0; for (i in from until to) s += x[i].toDouble() * x[i]; return sqrt(s / maxOf(1, to - from)) }

    /** Fit [source] and say how far the render of the fit lands from it: level in dB, and each weighty band in dB. */
    private fun roundTrip(source: FloatArray): Triple<KerfPatchData, Double, List<Double>> {
        val patch = KerfFit.fit(source, 7)
        val back = KerfRender.render(patch, edge = false)
        assertEquals(source.size, back.size)
        val a = KerfRender.bandEnergies(source); val b = KerfRender.bandEnergies(back); val top = a.drop(1).max()
        val bands = (1 until a.size).filter { a[it] >= top * 1e-3 }.map { abs(db(b[it] / a[it])) }
        return Triple(patch, 2 * db(rms(back) / rms(source)), bands)
    }

    @Test fun `a fit plays back the level and the band energies of what it was given`() {
        val level = ArrayList<Double>(); val bands = ArrayList<Double>()
        for (n in listOf(1, 3, 5, 7, 9, 11, 14, 15)) {
            val p = pad(n)
            val (patch, lv, bd) = roundTrip(KerfRender.render(p.patch, edge = false))
            level += abs(lv); bands += bd
            println("KERF fit pad $n ${p.drumClass}: level ${"%+.2f".format(lv)} dB, bands median ${"%.2f".format(bd.sorted()[bd.size / 2])} dB worst ${"%.2f".format(bd.max())} dB; " +
                "${patch.partials.size} partials, ${patch.events.size} events, ${patch.colour?.resonances?.size ?: 0} resonances, ${patch.ticks.size} ticks")
        }
        level.sort(); bands.sort()
        println("KERF fit round trip: level median ${"%.2f".format(level[level.size / 2])} dB worst ${"%.2f".format(level.last())} dB; " +
            "band median ${"%.2f".format(bands[bands.size / 2])} dB, 95th ${"%.2f".format(bands[(bands.size * 0.95).toInt()])} dB over ${bands.size} bands")
        // The yardstick is one patch rendered with two noise seeds (KerfRenderTest: band median 0.70 dB, 95th 3.02 dB,
        // level worst 1.68 dB). A fit adds its own error on top of that draw.
        assertTrue(level.last() < LEVEL_WORST_DB, "level worst ${level.last()} dB")
        assertTrue(bands[bands.size / 2] < BAND_MEDIAN_DB, "band median ${bands[bands.size / 2]} dB")
        assertTrue(bands[(bands.size * 0.95).toInt()] < BAND_95_DB, "band 95th ${bands[(bands.size * 0.95).toInt()]} dB")
    }

    @Test fun `a kick's body is found as a gliding oscillator at its own pitch`() {
        // Where a glide ends is not knowable from 0.3 s of a kick that is still falling (the truth here ends at 25 Hz),
        // so the pitch is compared while the body is sounding: 30 ms and 80 ms in.
        fun hzAt(p: KerfPartial, t: Double) = p.hzEnd + p.glideHz * kotlin.math.exp(-t / p.glideS)
        val truth = pad(1).patch.partials.maxBy { it.amp }
        val fit = KerfFit.fit(KerfRender.render(pad(1).patch, edge = false), 7).partials.maxBy { it.amp }
        for (t in listOf(0.030, 0.080)) assertEquals(hzAt(truth, t), hzAt(fit, t), hzAt(truth, t) * 0.08, "body pitch at ${t * 1000} ms")
        assertTrue(hzAt(fit, 0.030) > hzAt(fit, 0.080), "the body falls")
        // the fit spreads the body over more oscillators than the truth has, so the largest one comes out lower: measured -2.1 dB
        assertTrue(abs(2 * db(fit.amp / truth.amp)) < 3.0, "body level ${fit.amp} against ${truth.amp}")
    }

    @Test fun `a second sound inside the slice is found, and plays back where it was`() {
        // a hat dropped onto a kick's tail 120 ms in, a tenth as loud as the kick: the case the owner first heard fail
        val kick = KerfRender.render(pad(1).patch, edge = false); val hat = KerfRender.render(pad(11).patch, edge = false)
        val at = (0.120 * rate).toInt()
        val g = 0.25f * kick.maxOf { abs(it) } / hat.maxOf { abs(it) }
        val mix = FloatArray(kick.size) { i -> kick[i] + if (i >= at && i - at < hat.size) hat[i - at] * g else 0f }
        val patch = KerfFit.fit(mix, 7)
        assertTrue(patch.events.any { abs(it - 0.120) < 0.012 }, "an event near 120 ms: ${patch.events}")
        val back = KerfRender.render(patch, edge = false)
        fun top(x: FloatArray): Double { // energy above about 3.4 kHz in the 40 ms after the hat lands
            val e = KerfRender.bandEnergies(x.copyOfRange(at, at + (0.040 * rate).toInt())); return e.drop(11).sum()
        }
        val d = db(top(back) / top(mix))
        assertTrue(abs(d) < 3.0, "the hat's top end is ${"%.2f".format(d)} dB from the source after it lands")
        // and without layers it is not there: the guard this test is for
        val flat = KerfRender.render(patch, KerfRender.Controls(layers = false), edge = false)
        assertTrue(db(top(flat) / top(mix)) < -6.0, "with layers off the hat should be mostly gone: ${db(top(flat) / top(mix))} dB")
    }

    @Test fun `the same slice fits to the same numbers`() {
        val x = KerfRender.render(pad(5).patch, edge = false)
        assertEquals(KerfFit.fit(x, 3), KerfFit.fit(x, 3))
    }

    @Test fun `a slice that is not a one-shot is refused, and so is a scrap`() {
        assertFailsWith<IllegalArgumentException> { KerfFit.fit(FloatArray((KerfFit.MAX_SECONDS * rate).toInt()), 1) }
        assertFailsWith<IllegalArgumentException> { KerfFit.fit(FloatArray(100), 1) }
    }

    @Test fun `a loop becomes a kit whose pads know their neighbours, starting with the hit on the first frame`() {
        // eight hits, a third of a second apart, the first on frame 0 where a detector has nothing to rise from
        val order = listOf(1, 11, 5, 11, 2, 1, 5, 12); val gap = rate / 3
        val loop = FloatArray(gap * order.size)
        for ((i, n) in order.withIndex()) {
            val hit = KerfRender.render(pad(n).patch, edge = false)
            for (j in hit.indices) if (i * gap + j < loop.size) loop[i * gap + j] += hit[j]
        }
        val kit = KerfFit.kitFromLoop(Snip(loop, 1, rate), "test loop")
        println("KERF kit from a made loop: ${kit.joinToString { "${it.pad.drumClass}@${"%.3f".format(it.sourceFrame.toDouble() / rate)}" }}")
        assertEquals(0, kit.first().sourceFrame, "the first pad is the hit on frame 0")
        assertTrue(kit.size in order.size - 1..16, "${kit.size} pads from ${order.size} hits")
        // Every kick and snare has a cut within 25 ms of it (the app's chopper snaps back to a zero crossing; measured
        // worst 18 ms). A quiet hat straight after a snare may get no cut of its own: it stays inside that slice, which
        // is what the fitter's layers are for.
        for ((i, n) in order.withIndex()) if (n != 11 && n != 12) {
            assertTrue(kit.any { abs(it.sourceFrame - i * gap) < 0.025 * rate }, "no cut near hit $i at ${i * gap}")
        }
        for ((i, p) in kit.withIndex()) {
            assertEquals(i + 1, p.pad.pad)
            assertTrue(p.seconds < KerfFit.MAX_SECONDS)
            assertEquals(kit[(i + 1) % kit.size].pad.patch, p.pad.next, "pad ${i + 1}'s next is the pad after it, wrapping")
            assertEquals(kit[(i - 1 + kit.size) % kit.size].pad.patch, p.pad.prev)
            val chop = KerfRender.chopped(p.pad.patch, p.pad.prev, p.pad.next, p.pad.bleed, p.pad.slop)
            assertTrue(chop.samples.all { it.isFinite() } && chop.samples.maxOf { abs(it) } < 4f)
        }
    }

    companion object {
        /** Measured 2026-11-19 on eight pads of kit B: level worst 3.27 dB, band median 0.87 dB, 95th 3.04 dB. */
        const val LEVEL_WORST_DB = 4.0
        const val BAND_MEDIAN_DB = 1.5
        const val BAND_95_DB = 4.5
    }
}
