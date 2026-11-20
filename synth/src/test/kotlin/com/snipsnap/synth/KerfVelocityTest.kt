package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KERF's velocity and round robin, judged as the owner's gate asks (docs/ENGINE_ACCEPTANCE_GATES.md): a soft layer must
 * be a different strike, not the same one quieter, and the takes must be distinct strikes, not copies.
 *
 * Every layer is compared after peak matching, as the app writes it (`Velocity.kerfLayerAt`): the sampler owns
 * loudness, so whatever difference is left is character.
 */
class KerfVelocityTest {
    private val rate = KerfRender.RATE
    private val pads = KerfBank.kits.values.flatten()
    private fun recipe(p: KerfPad) = KerfRecipe(p.patch, p.prev, p.next, p.bleed, p.slop, p.draw)
    private fun db(x: Double) = 10 * log10(x + 1e-30)
    private fun energy(x: FloatArray, from: Int = 0, to: Int = x.size): Double { var e = 0.0; for (i in from until minOf(to, x.size)) e += x[i].toDouble() * x[i]; return e }
    private fun centroid(x: FloatArray): Double {
        val e = KerfRender.bandEnergies(x); var w = 0.0; var t = 1e-30
        for (b in 0 until KerfRender.BAND_COUNT) { w += KerfRender.bandCentreHz(b) * e[b + 1]; t += e[b + 1] }
        return w / t
    }
    /** What is left of [soft] after the best single gain that makes it [full]: 0 for a copy at another level. */
    private fun residualDb(full: FloatArray, soft: FloatArray): Double {
        val n = minOf(full.size, soft.size); var fs = 0.0; var ss = 0.0
        for (i in 0 until n) { fs += full[i].toDouble() * soft[i]; ss += soft[i].toDouble() * soft[i] }
        val g = fs / ss; var r = 0.0
        for (i in 0 until n) { val d = full[i] - g * soft[i]; r += d * d }
        return db(r / energy(full, 0, n))
    }
    private fun matched(reference: Snip, layer: Snip) = Velocity.peakMatch(reference, layer).samples

    @Test fun `velocity 1 is the pad itself, and a take 0 or a dial at 0 is too`() {
        for (p in pads.take(8)) {
            val r = recipe(p)
            assertContentEquals(r.render().samples, r.renderAt(1.0).samples)
            assertContentEquals(r.renderAt(0.5).samples, r.take(0, 0.5).samples)
            assertContentEquals(r.renderAt(1.0).samples, r.take(3, 1.0, amount = 0.0).samples)
        }
    }

    private class Stats(val centroid: Double, val floorRise: Double, val tickDrop: Double, val residualMedian: Double, val nearestCopy: Double, val pads: Int)

    /** The velocity law at [depth] over the whole bank at velocity [v], peak-matched as the app writes a layer. */
    private fun stats(depth: Double, v: Double = 0.45): Stats {
        val centroidRatio = ArrayList<Double>(); val floorRise = ArrayList<Double>(); val residual = ArrayList<Double>(); val tickDrop = ArrayList<Double>()
        for (p in pads) {
            if (p.prev == null || p.patch.seconds < 0.1) continue
            val r = recipe(p); val full = r.render(); val soft = matched(full, r.renderAt(v, depth))
            centroidRatio += centroid(soft) / centroid(full.samples)
            // the floor and the ticks against the hit, from the struck numbers: the two quantities the ghosts measured
            val struck = KerfVelocity.strike(p.patch, v, depth)
            fun floorShare(x: KerfPatchData) = x.bands.sumOf { it.floor } / x.bands.sumOf { it.snap + it.level + 1e-30 }
            floorRise += db(floorShare(struck) / floorShare(p.patch))
            if (p.patch.ticks.isNotEmpty()) {
                tickDrop += 20 * log10(struck.ticks.maxOf { it.amp } / p.patch.ticks.maxOf { it.amp }) - 20 * log10(KerfVelocity.hitGain(v, depth))
            }
            residual += residualDb(full.samples, soft)
        }
        fun med(x: List<Double>) = x.sorted()[x.size / 2]
        return Stats(med(centroidRatio), med(floorRise), med(tickDrop), med(residual), residual.min(), residual.size).also {
            println("KERF velocity 0.45 at depth $depth over ${it.pads} pads: centroid ${"%.3f".format(it.centroid)}x (ghosts 0.86x), " +
                "floor against hit ${"%+.1f".format(it.floorRise)} dB (ghosts +5.3), tick against hit ${"%+.1f".format(it.tickDrop)} dB (ghosts -2.2), " +
                "not-a-copy residual ${"%.1f".format(it.residualMedian)} dB (nearest a copy ${"%.1f".format(it.nearestCopy)})")
        }
    }

    @Test fun `at depth 1 the law is the ghost notes' - duller, a softer tick, the floor not following the hit down`() {
        val m = stats(1.0)
        assertTrue(m.centroid in 0.80..0.92, "a soft hit is duller, near the ghosts' 0.86x: ${m.centroid}")
        assertTrue(m.floorRise in 3.5..7.5, "the floor does not follow the hit all the way down: ${m.floorRise} dB")
        assertTrue(m.tickDrop in -4.0..-1.0, "the tick falls a little further than the hit: ${m.tickDrop} dB")
        // A copy at another level leaves nothing (minus infinity); a different strike leaves something on every pad.
        assertTrue(m.nearestCopy > -30.0, "every soft layer is more than a level change: nearest ${m.nearestCopy} dB")
    }

    @Test fun `the owner's depth goes further than the ghosts on every row, and is still a strike, not a copy`() {
        // The owner heard depth 1 as "a bit indistinguishable" and picked the strongest of 1, 2 and 3 (2026-11-20).
        assertEquals(3.0, KerfVelocity.DEPTH)
        val measured = stats(1.0); val chosen = stats(KerfVelocity.DEPTH)
        assertTrue(chosen.centroid < measured.centroid - 0.08, "duller than the measured law: ${chosen.centroid} against ${measured.centroid}")
        assertTrue(chosen.floorRise > measured.floorRise + 2.0, "deeper in the wash: ${chosen.floorRise} against ${measured.floorRise} dB")
        assertTrue(chosen.tickDrop < measured.tickDrop - 4.0, "a softer stick: ${chosen.tickDrop} against ${measured.tickDrop} dB")
        assertTrue(chosen.residualMedian > measured.residualMedian, "further from a copy at another level")
        assertTrue(chosen.nearestCopy > -30.0, "every soft layer is more than a level change: nearest ${chosen.nearestCopy} dB")
        assertTrue(chosen.centroid > 0.5, "still the same drum: ${chosen.centroid}x")
    }

    @Test fun `the bleed under a soft hit stays where it was, so against the hit it rises by the hit's own drop`() {
        val v = 0.45; val rise = ArrayList<Double>()
        for (p in pads) {
            if (p.prev == null) continue
            fun bleedShare(vel: Double): Double {
                val hit = KerfVelocity.strike(p.patch, vel)
                val g = KerfVelocity.hitGain(vel)
                val with = KerfRender.chopped(hit, p.prev, p.next, 1.0, 0.0, draw = p.draw, nextGain = g).samples
                val without = KerfRender.chopped(hit, p.prev, p.next, 0.0, 0.0, draw = p.draw, nextGain = g).samples
                var d = 0.0; for (i in with.indices) { val x = (with[i] - without[i]).toDouble(); d += x * x }
                return d / energy(without)
            }
            rise += db(bleedShare(v) / bleedShare(1.0))
        }
        val m = rise.sorted()[rise.size / 2]
        println("KERF bleed against the hit at velocity 0.45: ${"%+.1f".format(m)} dB over ${rise.size} pads (measured ghosts +12.2 dB; the hit falls ${"%.1f".format(-20 * log10(KerfVelocity.hitGain(v)))} dB)")
        assertTrue(m in 6.0..14.0, "bleed rise $m dB")
    }

    @Test fun `softer is further - the change grows steadily with velocity`() {
        val p = KerfBank.kits.getValue("B").first { it.pad == 5 }; val r = recipe(p); val full = r.render()
        val c = listOf(1.0, 0.8, 0.6, 0.4, 0.2).map { centroid(matched(full, r.renderAt(it))) }
        println("KERF centroid by velocity 1, .8, .6, .4, .2: ${c.map { it.toInt() }} Hz")
        for (i in 1 until c.size) assertTrue(c[i] < c[i - 1], "velocity steps down in brightness: $c")
    }

    @Test fun `the takes of a round robin are distinct strikes, repeatable, and none is a copy at another level`() {
        val levelSpread = ArrayList<Double>()
        for (p in pads.filterIndexed { i, _ -> i % 4 == 0 }) {
            val r = recipe(p)
            // as the app writes them: each take peak-matched to the pad (Robin's restriker), the sampler owning level
            val pad = r.take(0)
            val takes = (0 until 4).map { matched(pad, r.take(it)) }
            for (i in takes.indices) for (j in i + 1 until takes.size) {
                assertFalse(takes[i].contentEquals(takes[j]), "pad ${p.pad}: takes $i and $j are the same bytes")
                val n = minOf(takes[i].size, takes[j].size)
                assertTrue(residualDb(takes[i].copyOf(n), takes[j].copyOf(n)) > -30.0, "pad ${p.pad}: take $j is take $i at another level")
            }
            assertContentEquals(takes[2], matched(pad, r.take(2)), "the same take is the same strike every time")
            // a take is the same drum: its level and brightness stay near the pad's
            println("KERF takes pad ${p.drumClass} ${p.pad}: energy ${takes.drop(1).map { "%+.1f".format(db(energy(it) / energy(takes[0]))) }} dB, " +
                "centroid ${takes.drop(1).map { "%.2f".format(centroid(it) / centroid(takes[0])) }}x, frames ${takes.map { it.size }}")
            // the same pad chopped again: its level and brightness stay near the pad's
            for (t in takes.drop(1)) {
                // Peak matching a noise burst leaves its energy as random as its peak, so a hat's takes spread by a
                // few dB in energy; a kick whose low end the fit left to noise spreads too (the round-2a gap). The
                // spread over every take is bounded below; one take may reach 4 dB.
                levelSpread += abs(db(energy(t) / energy(takes[0])))
                assertTrue(abs(db(energy(t) / energy(takes[0]))) < 4.0, "pad ${p.pad}: a take's energy is within 4 dB")
                // Brightness moves with the noise draw: on kit B's snare five draws gave 1.16x to 1.34x the pad's own
                // (measured 2026-11-20; the bank's own draw sits at the dark end of its spread), and the knife alone
                // 1.00x. That is the model's own take-to-take variance, bounded here, and named for the owner's ear.
                assertTrue(centroid(t) / centroid(takes[0]) in 0.75..1.4, "pad ${p.pad}: a take's brightness stays near the pad's")
            }
        }
        levelSpread.sort()
        println("KERF take energy after peak match, over ${levelSpread.size} takes: median ${"%.1f".format(levelSpread[levelSpread.size / 2])} dB, worst ${"%.1f".format(levelSpread.last())} dB")
        assertTrue(levelSpread[levelSpread.size / 2] < 1.5, "median take energy spread ${levelSpread[levelSpread.size / 2]} dB")
    }
}
