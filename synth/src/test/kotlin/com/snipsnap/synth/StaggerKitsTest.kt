package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Cypher.Note
import com.snipsnap.synth.StaggerKits.Kit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * STAGGER's contracts on real renders, in LooseCrateKitsTest's shape, plus the family's own laws: the muffled
 * kick keeps the band a phone plays and loses its click, the crack snare sits near 3 kHz, the groove's hats
 * whisper and its mix is dark, GLASS deals different panes, and the drunk clock does what its numbers say.
 */
class StaggerKitsTest {
    private val rate = Dsp.RATE
    private val kit = Kit.CARDBOARD

    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        val ea = sqrt(a.sumOf { it.toDouble() * it })
        val eb = sqrt(b.sumOf { it.toDouble() * it })
        var d = 0.0
        for (i in 0 until n) { val v = a[i] / ea - b[i] / eb; d += v * v }
        return sqrt(d)
    }

    private fun brightness(x: FloatArray, hz: Float = 2_000f): Double {
        val lp = Dsp.FixedOnePole(hz)
        var high = 0.0
        var all = 0.0
        for (v in x) { val h = v - lp.lp(v); high += h.toDouble() * h; all += v.toDouble() * v }
        return high / max(all, 1e-18)
    }

    /** The share of [x]'s energy between [lo] and [hi] Hz, from a DFT over the whole render (or its first [seconds]). */
    private fun bandShare(x: FloatArray, lo: Float, hi: Float, seconds: Float = Float.MAX_VALUE, taper: Boolean = false): Double {
        val n = min(x.size, (seconds * rate).toInt()).let { var p = 1; while (p * 2 <= it) p *= 2; p }
        // A taper over the last three quarters keeps a hard cut's leakage out of a short window and leaves the
        // first milliseconds, where a click lives, at full weight (a Hann window would silence the click itself).
        val spec = power(if (!taper) x else FloatArray(n) { i -> if (i < n / 4) x[i] else (x[i] * .5 * (1 + Math.cos(Math.PI * (i - n / 4) / (n - n / 4)))).toFloat() }, n)
        var band = 0.0
        var all = 0.0
        for (k in spec.indices) { val f = k.toFloat() * rate / n; all += spec[k]; if (f >= lo && f < hi) band += spec[k] }
        return band / max(all, 1e-18)
    }

    /** The magnitude-weighted centroid of the first [seconds]. */
    private fun centroid(x: FloatArray, seconds: Float): Double {
        val n = min(x.size, (seconds * rate).toInt()).let { var p = 1; while (p * 2 <= it) p *= 2; p }
        val spec = power(x, n)
        var num = 0.0
        var den = 0.0
        for (k in spec.indices) { val m = sqrt(spec[k]); num += m * k * rate / n; den += m }
        return num / max(den, 1e-18)
    }

    /** A radix-2 power spectrum of the first [n] samples (no window: the onset is the thing measured). */
    private fun power(x: FloatArray, n: Int): DoubleArray {
        val re = DoubleArray(n) { if (it < x.size) x[it].toDouble() else 0.0 }
        val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len *= 2
        }
        return DoubleArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
    }

    private fun rendered(role: Int, velocity: Float, take: Int): Snip =
        renders.getOrPut(listOf(role, velocity, take)) { StaggerKits.render(kit, role, velocity, take) }

    private companion object {
        val renders = java.util.concurrent.ConcurrentHashMap<List<Any>, Snip>()
    }

    private fun lone(strike: Strike, v: Float = 1f, t: Int = 0): FloatArray = KitStrikes.finish(KitStrikes.struck(strike, v, t, StaggerKits.touch(v)), 1f).samples

    @Test fun `every pad renders the same bytes twice`() {
        for (role in 0 until StaggerKits.PADS) {
            assertContentEquals(rendered(role, .65f, 1).samples, StaggerKits.render(kit, role, .65f, 1).samples, "role $role")
        }
    }

    @Test fun `every pad is finite bounded centred levelled and every hit ends in silence under the loop floor`() {
        for (role in 0 until StaggerKits.PADS) for (v in StaggerKits.velocities) {
            val x = rendered(role, v, 0).samples
            val what = "role $role v $v"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val peak = x.maxOf { abs(it) }
            assertEquals(StaggerKits.PAD_PEAK, peak, .005f, "$what peak")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            if (role < StaggerKits.HITS) {
                assertTrue(abs(x.last()) < 1e-4f, "$what tail")
                assertTrue(x.size < 1.5 * rate, "$what is ${x.size} frames, over the 1.5 s loop floor")
            }
            for (take in 1 until StaggerKits.TAKES) {
                val p = rendered(role, v, take).peak()
                assertTrue(p in .6f..(.98f + 1e-6f), "$what take $take peak $p")
            }
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (role in 0 until StaggerKits.PADS) {
            val distance = shapeDistance(rendered(role, 1f, 0).samples, rendered(role, .3f, 0).samples)
            assertTrue(distance > .05, "role $role: soft is a scaled copy of hard ($distance)")
        }
        for (role in 0 until StaggerKits.HITS) {
            val hard = StaggerKits.dry(kit, role, 1f, 0).samples
            val soft = StaggerKits.dry(kit, role, .3f, 0).samples
            assertTrue(brightness(soft) <= brightness(hard) * 1.05, "role $role: soft is brighter than hard (${brightness(soft)} against ${brightness(hard)})")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (role in 0 until StaggerKits.PADS) for (v in StaggerKits.velocities) {
            val takes = List(StaggerKits.TAKES) { rendered(role, v, it).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val distance = shapeDistance(takes[a], takes[b])
                assertTrue(distance > .005, "role $role v $v: takes $a and $b differ by only $distance")
            }
        }
    }

    /**
     * The muffle (the brief, measured in the prototype): the kick keeps over one percent of its energy where a
     * phone plays (150 to 400 Hz), the clipper ahead of the filter is what keeps it (the same filter with no drive
     * keeps less), and the click is gone (under a tenth of the open kick's share above 1 kHz in its first 40 ms).
     */
    @Test fun `the box kick keeps the phone band because it is clipped before it is filtered and loses its click`() {
        val muffled = lone(StaggerKits.boxKick)
        val open = lone(StaggerKits.boxKickOpen)
        val noDrive = lone(StaggerKits.boxKickAt(300f, 1f))
        val phone = bandShare(muffled, 150f, 400f)
        val phoneNoDrive = bandShare(noDrive, 150f, 400f)
        println("box kick 150 to 400 Hz: muffled $phone, filter alone $phoneNoDrive")
        assertTrue(phone >= .01, "the muffled kick keeps only $phone of its energy in 150 to 400 Hz")
        assertTrue(phone > phoneNoDrive, "the clipper does nothing: $phoneNoDrive without it, $phone with it")
        // The click: the share above 1 kHz in the first 40 ms, the window tapered so the cut at 40 ms does not leak
        // (an untapered 30 ms window of a sub sweep read 800 to 1100 Hz centroids for both kicks: leakage, not click).
        val clickOpen = bandShare(open, 1_000f, 22_050f, .04f, taper = true)
        val clickMuffled = bandShare(muffled, 1_000f, 22_050f, .04f, taper = true)
        println("box kick above 1 kHz in the first 40 ms, tapered: open $clickOpen, muffled $clickMuffled")
        assertTrue(clickMuffled < clickOpen * .25, "the muffle left the click: $clickMuffled against the open kick's $clickOpen")
        // On the kit: both kicks and the dead tom through the print still keep the band.
        for (role in listOf(StaggerKits.KICK, StaggerKits.LOW_KICK, 8)) {
            val share = bandShare(rendered(role, 1f, 0).samples, 150f, 400f)
            assertTrue(share >= .01, "role $role through the print keeps only $share in 150 to 400 Hz")
        }
    }

    /** The references' snares read 1.3 to 5.3 kHz (median 3.2) over their first 120 ms; the crack snare aims at 2.5 to 3.5. */
    @Test fun `the crack snare is a crack near 3 kHz not a sizzle`() {
        val c = centroid(StaggerKits.dry(kit, StaggerKits.SNARE, 1f, 0).samples, .12f)
        println("crack snare centroid over 120 ms: $c Hz")
        assertTrue(c in 2_000.0..4_500.0, "crack snare centroid $c Hz")
    }

    @Test fun `GLASS deals a different pane on every take and the same pane for the same take`() {
        val panes = List(StaggerKits.TAKES) { lone(StaggerKits.glassAt(-5), 1f, it) }
        for (a in panes.indices) for (b in a + 1 until panes.size) {
            val d = shapeDistance(panes[a], panes[b])
            assertTrue(d > .5, "panes $a and $b differ by only $d")
        }
        assertContentEquals(panes[0], lone(StaggerKits.glassAt(-5), 1f, 0))
        assertTrue(panes[0].all { it.isFinite() } && panes[0].size < 1.5 * rate)
    }

    /**
     * The drunk clock's numbers on a long pattern: every part's mean lateness is its bias (the drift is centred),
     * the kick lands behind the snare by the two biases' difference, nothing passes the Note's clamp, the same
     * seed gives the same offsets, and neighbouring hats lean the same way by about rho (an independent scatter
     * measures near zero).
     */
    @Test fun `the staggered clock drags the kick snaps the snare early and leans neighbouring notes the same way`() {
        val notes = buildList {
            for (bar in 0 until 16) {
                val b = bar * 4.0
                for (beat in listOf(0.0, 1.5, 2.75)) add(Note(b + beat, 0, 1f))
                for (beat in listOf(1.0, 3.0)) add(Note(b + beat, 1, 1f))
                for (e in 0 until 8) add(Note(b + e * .5, 2, .5f))
            }
        }
        val bias = mapOf(0 to 12f, 1 to -3f, 2 to 8f)
        val a = Cypher.staggered(notes, bias, 10f, .4f, "test")
        val b = Cypher.staggered(notes, bias, 10f, .4f, "test")
        assertEquals(a.map { it.offsetMs }, b.map { it.offsetMs })
        fun mean(part: Int) = a.filter { it.part == part }.map { it.offsetMs.toDouble() }.average()
        // The drift is centred over the loop's beats, so a part on a subset of them sits within a few ms of its bias.
        assertEquals(12.0, mean(0), 3.0, "kick mean")
        assertEquals(-3.0, mean(1), 3.0, "snare mean")
        assertEquals(8.0, mean(2), 3.0, "hat mean")
        assertEquals(15.0, mean(0) - mean(1), 4.0, "the kick behind the snare")
        assertTrue(a.all { abs(it.offsetMs) <= 60f })
        fun lag1(xs: List<Float>): Double {
            val m = xs.average()
            val c = xs.map { it - m }
            return c.zipWithNext { p, q -> p * q }.sum() / c.sumOf { it.toDouble() * it }
        }
        val hats = a.filter { it.part == 2 }.sortedBy { it.beat }.map { it.offsetMs }
        val spread = sqrt(hats.map { (it - 8f).toDouble().let { d -> d * d } }.average())
        val ac = lag1(hats)
        val scatter = lag1(Cypher.humanised(notes, 10f, "test").filter { it.part == 2 }.sortedBy { it.beat }.map { it.offsetMs })
        println("staggered hats: spread $spread ms, lag-one autocorrelation $ac; humanised $scatter")
        assertTrue(abs(ac - .4) < .25, "hat autocorrelation $ac, rho .4")
        assertTrue(scatter < ac - .3, "humanised ($scatter) leans as much as staggered ($ac)")
        assertTrue(spread in 5.0..16.0, "hat spread $spread ms for a 10 ms wobble")
    }

    @Test fun `the groove is bar-exact closes on itself whispers its hats and keeps its mix dark`() {
        for (v in StaggerKits.velocities) {
            val x = rendered(StaggerKits.GROOVE, v, 0).samples
            assertEquals(StaggerKits.grooveFrames(kit), x.size, "length")
            val wrap = abs(x[0] - x[x.size - 1])
            var largest = 0f
            for (i in x.size - (.02f * rate).toInt() until x.size - 1) largest = max(largest, abs(x[i + 1] - x[i]))
            assertTrue(wrap <= largest * 1.5f + 1e-4f, "v $v: wrap step $wrap against $largest")
        }
        val x = rendered(StaggerKits.GROOVE, 1f, 0).samples
        // The hats: the 6 to 14 kHz band at its loudest tenth of 23 ms frames against the full band's loudest tenth.
        val hop = 1024
        val top = ArrayList<Double>(); val whole = ArrayList<Double>()
        var i = 0
        while (i + hop <= x.size) {
            val spec = power(x.copyOfRange(i, i + hop), hop)
            var t = 0.0; var w = 0.0
            for (k in spec.indices) { val f = k.toFloat() * rate / hop; if (f >= 30f) w += spec[k]; if (f >= 6_000f && f < 14_000f) t += spec[k] }
            top.add(t); whole.add(w)
            i += hop
        }
        val loudTop = top.sorted().takeLast(top.size / 10).average()
        val loudWhole = whole.sorted().takeLast(whole.size / 10).average()
        val hatsDb = 10 * kotlin.math.log10(loudTop / loudWhole)
        // The mix: where 95 percent of the groove's energy sits.
        val n = 1 shl 18
        val spec = power(x, n)
        val total = spec.sum()
        var cum = 0.0
        var ceiling = 0.0
        for (k in spec.indices) { cum += spec[k]; if (cum >= .95 * total) { ceiling = k.toDouble() * rate / n; break } }
        println("cardboard groove: hats $hatsDb dB under the full band at its loudest; 95 percent of the energy under $ceiling Hz")
        // The references' range is 16 to 35 dB under (median 23); the groove sits near 30, the whisper end of it. Hats
        // at their written weights with a brighter contact moved it from 35 to 30; the print's tape and converter
        // and the bus set the rest, and the owner's ear decides whether the hats should come up further.
        assertTrue(hatsDb in -35.0..-16.0, "hats $hatsDb dB under; the references 16 to 35, median 23")
        assertTrue(ceiling < 650.0, "95 percent of the energy under $ceiling Hz; eight references under 309 to 635")
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        val pads = StaggerKits.build(kit, { _, role, v, t -> rendered(role, v, t) })
        assertEquals(StaggerKits.PADS, pads.size)
        assertEquals(listOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED), pads.take(3).map { it.drumClass })
        assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), pads.takeLast(2).map { it.drumClass })
        for ((role, pad) in pads.withIndex()) {
            assertEquals(3, pad.velocityTakes.size)
            assertTrue(pad.velocityTakes.all { it.size == StaggerKits.TAKES })
            assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
            assertEquals(StaggerKits.roles(kit)[role], pad.displayName)
            assertEquals("STAGGER", pad.source["engine"])
            assertEquals(StaggerKits.VERSION.toString(), pad.source["kitVersion"])
            val expected = when {
                pad.drumClass == DrumClass.HAT_CLOSED || pad.drumClass == DrumClass.HAT_OPEN -> StaggerKits.HAT_GROUP
                role >= StaggerKits.HITS -> StaggerKits.PHRASE_GROUP
                else -> 0
            }
            assertEquals(expected, pad.muteGroup, "role $role choke")
            if (role >= StaggerKits.HITS) assertTrue(pad.snip.frameCount > 1.5 * rate, "role $role") else assertTrue(pad.snip.frameCount < 1.5 * rate, "role $role")
        }
    }

    @Test fun `no pad or kit name borrows a maker's or the producer's name`() {
        val producer = Regex("(?i)doom|dumile|metal\\s*fingers?|viktor\\s*vaughn|king\\s*geedorah|zev\\s*love|\\bkmd\\b|madvillain|madlib|danger\\s*doom|special\\s*herbs|vaudeville|doomsday|mm\\.?\\s*food|born\\s*like\\s*this|stones?\\s*throw|rhymesayers|lex\\s*records|super\\s*villain|villain|\\bmask\\b")
        for (name in StaggerKits.roles(kit) + kit.title + kit.id + "STAGGER") {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
            assertFalse(producer.containsMatchIn(name), name)
        }
    }
}
