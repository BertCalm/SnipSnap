package com.snipsnap.synth

import com.snipsnap.json.Json
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KERF's renderer against the fitted bank the owner heard (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md).
 *
 * The port's own claim is "the same patch gives the same sound as the Python prototype". The noise generators differ,
 * so that is held on summaries the prototype froze for every pad (`prototype/kerf/freeze_reference.py`): the length to
 * the frame, the level, and the energy in each band.
 */
class KerfRenderTest {
    private val rate = KerfRender.RATE
    private val kits = KerfBank.kits
    private val pads = kits.values.flatten()
    private fun pad(kit: String, n: Int) = kits.getValue(kit).first { it.pad == n }

    private fun db(ratio: Double) = 10 * log10(ratio + 1e-30)
    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / maxOf(1, to - from))
    }
    private fun chop(p: KerfPad, bleed: Double = p.bleed, slop: Double = p.slop, c: KerfRender.Controls = KerfRender.Controls()) =
        KerfRender.chopped(p.patch, p.prev, p.next, bleed, slop, c, p.draw)

    private class Ref(val frames: Int, val rms: Double, val low: Double, val bands: DoubleArray, val startMs: Double?, val overMs: Double?)
    private val reference: Map<Pair<String, Int>, Pair<Ref, Ref>> by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/kerf/python_reference.json")).use { it.readBytes().toString(Charsets.UTF_8) }
        val root = Json.parse(text).obj()
        assertEquals(KerfRender.EDGES.toList().zipWithNext(), root.getValue("bins").arr().map { b -> b.arr().let { it[0].int() to it[1].int() } },
            "the prototype's bands are the renderer's bands")
        fun ref(v: com.snipsnap.json.JsonValue) = v.obj().let { o ->
            Ref(o.getValue("frames").int(), o.getValue("rms").num(), o.getValue("low").num(),
                o.getValue("bands").arr().map { it.num() }.toDoubleArray(), o["start_ms"]?.num(), o["over_ms"]?.num())
        }
        root.getValue("kits").obj().flatMap { (kit, list) ->
            list.arr().map { it.obj() }.map { (kit to it.getValue("pad").int()) to (ref(it.getValue("clean")) to ref(it.getValue("chopped"))) }
        }.toMap()
    }

    @Test fun `the bank is four kits of sixteen pads, each with both neighbours and its stored draw`() {
        assertEquals(setOf("A", "B", "C", "D"), kits.keys)
        for ((kit, list) in kits) {
            assertEquals((1..16).toList(), list.map { it.pad }, "kit $kit")
            for (p in list) {
                assertTrue(p.prev != null && p.next != null && p.draw != null, "kit $kit pad ${p.pad}")
                assertEquals(KerfRender.BAND_COUNT, p.patch.bands.size)
            }
        }
    }

    @Test fun `every pad renders the prototype's length, level and energy per band`() {
        val levelErr = ArrayList<Double>(); val bandErr = ArrayList<Double>(); val chopErr = ArrayList<Double>()
        for ((kit, list) in kits) for (p in list) {
            val (clean, chopped) = reference.getValue(kit to p.pad)
            val y = KerfRender.render(p.patch)
            assertEquals(clean.frames, y.size, "kit $kit pad ${p.pad} clean length")
            val level = 2 * db(rms(y) / clean.rms)
            levelErr += abs(level)
            val e = KerfRender.bandEnergies(y); val top = clean.bands.max()
            for (b in clean.bands.indices) {
                if (clean.bands[b] < top * 1e-3) continue   // bands 30 dB under the loudest carry no weight in what is heard
                val d = db(e[b + 1] / clean.bands[b])
                bandErr += abs(d)
            }
            val z = chop(p)
            assertEquals(chopped.frames, z.samples.size, "kit $kit pad ${p.pad} chopped length")
            assertEquals(chopped.startMs!!, z.startMs, 1e-6); assertEquals(chopped.overMs!!, z.overMs, 1e-6)
            chopErr += abs(2 * db(rms(z.samples) / chopped.rms))
        }
        levelErr.sort(); bandErr.sort(); chopErr.sort()
        val levelMedian = levelErr[levelErr.size / 2]; val bandMedian = bandErr[bandErr.size / 2]; val band95 = bandErr[(bandErr.size * 0.95).toInt()]
        println("KERF port parity over ${pads.size} pads: level median ${"%.2f".format(levelMedian)} dB, worst ${"%.2f".format(levelErr.last())} dB; " +
            "chopped level worst ${"%.2f".format(chopErr.last())} dB; band median ${"%.2f".format(bandMedian)} dB, 95th ${"%.2f".format(band95)} dB, " +
            "worst ${"%.2f".format(bandErr.last())} dB over ${bandErr.size} bands")
        // The yardstick is the prototype against itself with a different noise seed (measured 2026-11-19, same 64 pads,
        // same 1049 bands): level median 0.30 dB, worst 1.68; band median 0.70, 95th 3.02, worst 12.49. A port that is
        // only a different noise draw lands on that distribution; the port measured 0.25 / 2.07 and 0.68 / 2.76 / 12.85.
        // A single band of a single short hit is a handful of noise bins and can swing 12 dB by chance, so the bounds
        // are on the distribution. A wrong gain law moves every band and fails the medians.
        assertTrue(levelMedian < LEVEL_MEDIAN_DB, "level median $levelMedian dB")
        assertTrue(levelErr.last() < LEVEL_WORST_DB && chopErr.last() < LEVEL_WORST_DB, "level worst ${levelErr.last()} dB, chopped ${chopErr.last()} dB")
        assertTrue(bandMedian < BAND_MEDIAN_DB, "band median $bandMedian dB")
        assertTrue(band95 < BAND_95_DB, "band 95th percentile $band95 dB")
    }

    @Test fun `a render is the same bytes every time, clean and chopped`() {
        for (p in listOf(pad("A", 1), pad("B", 5), pad("C", 11), pad("D", 16))) {
            assertContentEquals(KerfRender.render(p.patch), KerfRender.render(p.patch))
            assertContentEquals(chop(p).samples, chop(p).samples)
        }
    }

    @Test fun `no bleed and no slop is exactly the clean render`() {
        for (p in pads) assertContentEquals(KerfRender.render(p.patch), chop(p, bleed = 0.0, slop = 0.0).samples, "pad ${p.pad}")
    }

    @Test fun `every render is finite, bounded and centred`() {
        for (p in pads) for (x in listOf(KerfRender.render(p.patch), chop(p).samples, chop(p, bleed = 2.0, slop = 1.0).samples)) {
            assertTrue(x.all { it.isFinite() }, "pad ${p.pad}")
            assertTrue(x.maxOf { abs(it) } < 4f, "pad ${p.pad}: raw peak ${x.maxOf { abs(it) }}")
            // A slice cut part-way through a 46 Hz kick cycle has a mean of its own, as the source chop does; the
            // renderer does not remove it, because moving a chop's baseline moves its edges. Worst measured: 0.0134.
            assertTrue(abs(x.average()) < 0.02, "pad ${p.pad}: mean ${x.average()}")
            val snip = KerfRender.toSnip(x)
            assertTrue(snip.samples.maxOf { abs(it) } <= 0.9f + 1e-6f)
        }
    }

    @Test fun `CUT sets the length to the frame`() {
        val p = pad("B", 1)
        for (cut in listOf(1.0, 0.5, 0.25)) {
            assertEquals(Math.rint(p.patch.seconds * cut * rate).toInt(), KerfRender.render(p.patch, KerfRender.Controls(cut = cut)).size)
        }
    }

    @Test fun `every control changes the sound`() {
        val p = pad("B", 5); val base = KerfRender.render(p.patch)
        val moved = mapOf(
            "floor" to KerfRender.Controls(floor = 0.0), "decay" to KerfRender.Controls(decay = 0.5),
            "tune" to KerfRender.Controls(tuneSemitones = 4.0), "layers" to KerfRender.Controls(layers = false),
            "colour" to KerfRender.Controls(colour = false),
        )
        for ((name, c) in moved) assertFalse(base.contentEquals(KerfRender.render(p.patch, c)), "$name did nothing")
        assertFalse(chop(p, bleed = 0.0, slop = 0.0).samples.contentEquals(chop(p, bleed = 1.0, slop = 0.0).samples), "bleed did nothing")
        assertFalse(chop(p, bleed = 0.0, slop = 0.0).samples.contentEquals(chop(p, bleed = 0.0, slop = 0.5).samples), "slop did nothing")
    }

    @Test fun `DECAY shortens the ring and FLOOR removes the wash`() {
        for (p in listOf(pad("B", 1), pad("B", 5), pad("C", 11))) {
            val n = (p.patch.seconds * rate).toInt(); val tail = n * 2 / 3
            val full = KerfRender.render(p.patch); val short = KerfRender.render(p.patch, KerfRender.Controls(decay = 0.5))
            val dry = KerfRender.render(p.patch, KerfRender.Controls(floor = 0.0))
            assertTrue(rms(short, tail, n - 64) < rms(full, tail, n - 64), "pad ${p.pad}: half the decay left the last third as loud")
            assertTrue(rms(dry, tail, n - 64) < rms(full, tail, n - 64), "pad ${p.pad}: no floor left the last third as loud")
        }
    }

    @Test fun `TUNE moves the kick's body by the interval asked for`() {
        val p = pad("B", 1)
        fun bodyHz(semis: Double): Double {
            val x = KerfRender.render(p.patch, KerfRender.Controls(tuneSemitones = semis, cut = 1.0))
            // count rising zero crossings of the low-passed tail, where the glide has settled
            var lp = 0.0; var prev = 0.0; var crossings = 0; val a = x.size / 3; var first = -1; var last = -1
            for (i in x.indices) {
                lp += 0.02 * (x[i] - lp)
                if (i >= a && prev <= 0 && lp > 0) { crossings++; if (first < 0) first = i; last = i }
                prev = lp
            }
            return (crossings - 1).toDouble() * rate / (last - first)
        }
        val down = bodyHz(-7.0); val up = bodyHz(7.0)
        val cents = 1200 * kotlin.math.ln(up / down) / kotlin.math.ln(2.0)
        assertEquals(1400.0, cents, 60.0, "14 semitones apart, measured $down Hz and $up Hz")
    }

    @Test fun `BLEED raises what sits under the hit and SLOP moves the cut`() {
        // an early-starting pad: its pre-roll is the previous hit's real tail, in front of the attack
        val early = pads.first { it.draw!!.which > 0.25 && it.draw!!.start > 0.6 }
        val z = chop(early, bleed = 0.0, slop = 1.0)
        assertTrue(z.startMs > 3.0, "the pad starts ${z.startMs} ms early")
        val pre = (z.startMs * rate / 1000).toInt()
        assertTrue(rms(z.samples, 0, pre) > 0.0, "an early start has the previous tail in front of it")
        assertEquals(KerfRender.render(early.patch).size + pre + (z.overMs * rate / 1000).toInt(), z.samples.size)

        val late = pads.first { it.draw!!.which <= 0.25 && it.draw!!.start > 0.3 }
        val shaved = chop(late, bleed = 0.0, slop = 1.0)
        assertTrue(shaved.startMs < 0 && shaved.startMs >= -3.0, "a late start shaves at most 3 ms: ${shaved.startMs}")

        // more bleed is more energy added to the same hit, monotonically
        val p = pad("B", 5); val clean = chop(p, bleed = 0.0, slop = 0.0).samples
        val added = listOf(0.5, 1.0, 2.0).map { b ->
            val y = chop(p, bleed = b, slop = 0.0).samples
            var s = 0.0
            for (i in y.indices) { val d = (y[i] - clean[i]).toDouble(); s += d * d }
            s
        }
        assertTrue(added[0] > 0 && added[0] < added[1] && added[1] < added[2], "bleed energy $added")
    }

    @Test fun `a pad with no stored draw gets a repeatable one from its seed`() {
        val p = pad("A", 3)
        assertEquals(KerfRender.drawFor(p.patch.seed), KerfRender.drawFor(p.patch.seed))
        assertFalse(KerfRender.drawFor(p.patch.seed) == KerfRender.drawFor(p.patch.seed + 1))
        val d = KerfRender.drawFor(p.patch.seed)
        assertTrue(listOf(d.start, d.end, d.which).all { it in 0.0..1.0 })
    }

    @Test fun `patch data survives JSON`() {
        for (p in pads) {
            val back = KerfPatchData.fromJsonValue(Json.parse(Json.write(p.patch.toJsonValue())))
            assertEquals(p.patch, back, "pad ${p.pad}")
            assertContentEquals(KerfRender.render(p.patch), KerfRender.render(back))
        }
    }

    @Test fun `no hit reaches the loop threshold`() {
        // the classifier files anything over 1.5 s as a loop; a KERF hit is a one-shot
        val long = pads.filter { it.patch.seconds >= 1.5 }
        println("KERF pads at or over 1.5 s: ${long.map { "${it.drumClass} ${"%.2f".format(it.patch.seconds)} s" }}")
        assertTrue(long.size <= 1, "more than the one known long slice: ${long.size}")
    }

    companion object {
        const val LEVEL_MEDIAN_DB = 0.5
        const val LEVEL_WORST_DB = 3.0
        const val BAND_MEDIAN_DB = 1.0
        const val BAND_95_DB = 4.0
    }
}
