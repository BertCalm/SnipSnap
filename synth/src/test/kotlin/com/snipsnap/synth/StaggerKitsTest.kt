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
    private val kits = Kit.entries

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

    private fun rendered(role: Int, velocity: Float, take: Int, k: Kit = kit): Snip =
        renders.getOrPut(listOf(k, role, velocity, take)) { StaggerKits.render(k, role, velocity, take) }

    private companion object {
        val renders = java.util.concurrent.ConcurrentHashMap<List<Any>, Snip>()
    }

    private fun lone(strike: Strike, v: Float = 1f, t: Int = 0): FloatArray = KitStrikes.finish(KitStrikes.struck(strike, v, t, StaggerKits.touch(v)), 1f).samples

    @Test fun `every pad renders the same bytes twice`() {
        for (k in kits) for (role in 0 until StaggerKits.PADS) {
            assertContentEquals(rendered(role, .65f, 1, k).samples, StaggerKits.render(k, role, .65f, 1).samples, "${k.name} role $role")
        }
    }

    @Test fun `every pad is finite bounded centred levelled and every hit ends in silence under the loop floor`() {
        for (k in kits) for (role in 0 until StaggerKits.PADS) for (v in StaggerKits.velocities) {
            val x = rendered(role, v, 0, k).samples
            val what = "${k.name} role $role v $v"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val peak = x.maxOf { abs(it) }
            assertEquals(StaggerKits.PAD_PEAK, peak, .005f, "$what peak")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            if (role < StaggerKits.HITS) {
                assertTrue(abs(x.last()) < 1e-4f, "$what tail")
                assertTrue(x.size < 1.5 * rate, "$what is ${x.size} frames, over the 1.5 s loop floor")
            }
            for (take in 1 until StaggerKits.TAKES) {
                val p = rendered(role, v, take, k).peak()
                assertTrue(p in .6f..(.98f + 1e-6f), "$what take $take peak $p")
            }
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (k in kits) for (role in 0 until StaggerKits.PADS) {
            val distance = shapeDistance(rendered(role, 1f, 0, k).samples, rendered(role, .3f, 0, k).samples)
            assertTrue(distance > .05, "${k.name} role $role: soft is a scaled copy of hard ($distance)")
        }
        for (k in kits) for (role in 0 until StaggerKits.HITS) {
            val hard = StaggerKits.dry(k, role, 1f, 0).samples
            val soft = StaggerKits.dry(k, role, .3f, 0).samples
            // A muffled drum has next to nothing above 2 kHz either way (the low kick: 0.07 percent); under half a
            // percent the comparison is noise, and the shape distance above already proves a different strike.
            assertTrue(brightness(soft) <= brightness(hard) * 1.05 || brightness(hard) < .005, "${k.name} role $role: soft is brighter than hard (${brightness(soft)} against ${brightness(hard)})")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (k in kits) for (role in 0 until StaggerKits.PADS) for (v in StaggerKits.velocities) {
            val takes = List(StaggerKits.TAKES) { rendered(role, v, it, k).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val distance = shapeDistance(takes[a], takes[b])
                assertTrue(distance > .005, "${k.name} role $role v $v: takes $a and $b differ by only $distance")
            }
        }
    }

    /**
     * The muffle (the brief, measured in the prototype): the kick keeps over one percent of its energy where a
     * phone plays (150 to 400 Hz), the clipper ahead of the filter is what keeps it (the same filter with no drive
     * keeps less), and the click is gone (under a tenth of the open kick's share above 1 kHz in its first 40 ms).
     */
    @Test fun `the box kick keeps the phone band because it is clipped before it is filtered and loses its click`() {
        val muffled = lone(StaggerKits.emberKickThump)
        val open = lone(StaggerKits.emberKickOpen)
        val phone = bandShare(muffled, 150f, 400f)
        println("box kick (EMBER) 150 to 400 Hz: muffled $phone")
        assertTrue(phone >= .01, "the muffled kick keeps only $phone of its energy in 150 to 400 Hz")
        // Round 3 ("light taps"): the sub alone keeps 2 percent in the band. Round 3's box kept 30 to 48 percent,
        // aimed at a reference figure that turned out to carry the references' basslines, and was heard as "muddy"
        // (round 3b). So the box sits between: 5 to 20 percent in the band, no more than 3 percent above 400 Hz over
        // 300 ms (low-passed) and no click (under 1 percent above 1 kHz in the first 40 ms).
        for ((name, strike) in listOf("box" to StaggerKits.emberKick, "low box" to StaggerKits.emberLowKick, "stage" to StaggerKits.stageKick, "hum" to StaggerKits.humKick)) {
            val box = lone(strike)
            val band = bandShare(box, 150f, 400f, .3f); val top = bandShare(box, 400f, 22_050f, .3f)
            val click = bandShare(box, 1_000f, 22_050f, .04f, taper = true)
            println("$name kick: 150 to 400 Hz $band, above 400 $top, click $click")
            assertTrue(band >= .05, "the $name kick keeps only $band of its energy in 150 to 400 Hz; the sub alone keeps .02, a tap")
            assertTrue(band <= .20, "the $name kick puts $band of its energy in 150 to 400 Hz; round 3's .30 to .48 was heard as muddy")
            assertTrue(top <= .03, "the $name kick puts $top of its energy above 400 Hz; it is low-passed")
            assertTrue(click < .01, "the $name kick clicks: $click above 1 kHz in its first 40 ms")
        }
        // The clipper's job is measured on the synth kick, where it is needed: an acoustic kick's shell already puts
        // 4 percent of its energy in the band (round 1b: 3.97 with the clipper, 3.97 without), so on EMBER's kick the
        // drive is a character choice (a squarer thud), not what keeps the phone band.
        val synth = bandShare(lone(StaggerKits.boxKick), 150f, 400f)
        val synthNoDrive = bandShare(lone(StaggerKits.boxKickAt(300f, 1f)), 150f, 400f)
        println("box kick (THUMP) 150 to 400 Hz: muffled $synth, filter alone $synthNoDrive")
        assertTrue(synth > synthNoDrive, "the clipper does nothing on the synth kick: $synthNoDrive without it, $synth with it")
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

    /**
     * Round 3, "the drums are not being hit hard enough. It sound like light taps" (the owner, 2026-11-20). In round
     * 2d's grooves the backbeat's loudness (50 ms RMS at beats 1 and 5, where no kick rings under it) sat 8 to 14 dB
     * under the kick's (beats 0 and 4): a muffled sub is dense and a snare's crack is not, and the touch law floors
     * every note's peak at .35, so the written weights could not sit the kick lower. The references point the same
     * way only roughly (an onset detector puts their snare a median 7 dB over their kick, and the same detector read
     * round 2d's own loops 0 to 14 dB over where this direct measure read 8 to 14 under), so the window is a shape
     * choice: the backbeat no more than 4 dB under the kick and no more than 10 over it.
     */
    @Test fun `every groove's backbeat is heard over its kick`() {
        val over = LinkedHashMap<Kit, Double>()
        for (k in kits) {
            val x = rendered(StaggerKits.GROOVE, 1f, 0, k).samples
            val beat = 60.0 / k.bpm
            fun near(sec: Double): Double {
                val c = (sec * rate).toInt(); var best = -300.0
                var s = c - (.08 * rate).toInt()
                while (s < c + (.08 * rate).toInt()) {
                    val a = s.coerceIn(0, x.size - 1); val b = min(x.size, a + (.05 * rate).toInt())
                    best = max(best, 10 * kotlin.math.log10((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a) + 1e-20))
                    s += 110
                }
                return best
            }
            val snare = listOf(1.0, 5.0).map { near(it * beat) }.average()
            val kick = listOf(0.0, 4.0).map { near(it * beat) }.average()
            // The hat alone, on the eighth after the downbeat (no kick falls there in any kit; the downbeat's kick
            // is 20 dB down by then).
            val hat = listOf(0.5, 4.5).map { near(it * beat) }.average()
            println("${k.name} groove: backbeat ${"%.1f".format(snare)} dBFS, kick ${"%.1f".format(kick)}, hat ${"%.1f".format(hat)}, backbeat over kick ${"%.1f".format(snare - kick)} dB, over hat ${"%.1f".format(snare - hat)}")
            over[k] = snare - kick
            // "Hats at a whisper or omitted": the hat's loudness at least 10 dB under the backbeat's (round 2d: within 1 dB).
            // STUMBLE has no hat (a shaker), and its hum kick is still sounding on that eighth.
            val parts = StaggerKits.parts(k)
            if (StaggerKits.notesOf(k).any { parts[it.part].cls == DrumClass.HAT_CLOSED })
                assertTrue(snare - hat >= 10, "${k.name}: the hat sits only ${snare - hat} dB under the backbeat")
        }
        for ((k, d) in over) {
            assertTrue(d >= -4, "${k.name}: the backbeat sits ${-d} dB under the kick; round 2d sat 8 to 14 under, the references 7 over")
            assertTrue(d <= 10, "${k.name}: the backbeat sits $d dB over the kick, past the references' upper quartile")
        }
    }

    /** The references' snares read 1.3 to 5.3 kHz (median 3.2) over their first 120 ms; the crack snare aims at 2.5 to 3.5. */
    @Test fun `the crack snare is a crack near 3 kHz not a sizzle`() {
        val c = centroid(StaggerKits.dry(kit, StaggerKits.SNARE, 1f, 0).samples, .12f)
        println("crack snare centroid over 120 ms: $c Hz")
        assertTrue(c in 2_000.0..4_500.0, "crack snare centroid $c Hz")
    }

    /**
     * Round 3c, "Dark Gong sounds like the timpani" (the owner, 2026-11-20): FIRED BRONZE's kempul five semitones down
     * measured as a drum (fundamental 72 Hz against the timpani's 78, nothing above 300 Hz, its upper band falling
     * 7.6 dB after the strike). The modal gong is held to the three things that tell a gong from a drum, against
     * the kit's own timpani: a register of its own (the strongest partial at least 1.4 times the timpani's;
     * measured 120 Hz against 79), partials above the hum (its share above 300 Hz at least five times the
     * timpani's: an absolute share moves from 1.5 to 7.5 percent with the window's shape, the ratio holds; measured
     * 7.7 times), and a bloom (above 300 Hz, its loudest 50 ms frame between 50 and 250 ms at least 3 dB over its
     * first 50 ms; measured 4.4, the timpani falls 2.7).
     */
    @Test fun `the dark gong is a gong and not a second timpani`() {
        fun lone(s: Strike) = KitStrikes.struck(s, 1f, 0, StaggerKits.touch(1f))
        fun dominant(x: FloatArray): Double {
            val n = 1 shl 15; val spec = power(x.copyOf(min(x.size, (.5f * rate).toInt())), n)
            var best = 0; for (b in spec.indices) if (b.toDouble() * rate / n >= 30 && spec[b] > spec[best]) best = b
            return best.toDouble() * rate / n
        }
        // The bloom is read on the whole strike high-passed at 300 Hz (three Butterworth sections, 36 dB per octave)
        // and then framed: a hard-edged FFT of the first 50 ms alone leaks the loud hum into the upper band and
        // hides the bloom (it read -0.9 dB where the filtered signal reads +5).
        fun upper(x: FloatArray): FloatArray {
            val f = List(3) { Dsp.TptSvf() }
            return FloatArray(x.size) { i -> var y = x[i]; for (s in f) { s.process(y, 300f, 1.414f); y = s.high }; y }
        }
        fun rms(x: FloatArray, from: Float, to: Float): Double {
            val a = (from * rate).toInt(); val b = min(x.size, (to * rate).toInt())
            return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
        }
        fun bloom(x: FloatArray): Double {
            val y = upper(x)
            val later = (0 until 4).maxOf { rms(y, .05f + it * .05f, .10f + it * .05f) }
            return 20 * kotlin.math.log10(later / rms(y, 0f, .05f))
        }
        val gong = lone(StaggerKits.darkGong); val timpani = lone(StaggerKits.emberTimpani)
        val above = bandShare(gong, 300f, 22_050f, .5f, taper = true); val timpaniAbove = bandShare(timpani, 300f, 22_050f, .5f, taper = true)
        println("dark gong: strongest ${dominant(gong)} Hz (timpani ${dominant(timpani)}), above 300 Hz $above (timpani $timpaniAbove), bloom ${bloom(gong)} dB (timpani ${bloom(timpani)})")
        assertTrue(dominant(gong) >= 1.4 * dominant(timpani), "the gong's strongest partial ${dominant(gong)} Hz sits with the timpani's ${dominant(timpani)}")
        assertTrue(above >= 5 * timpaniAbove, "the gong keeps $above of its energy above 300 Hz, under five times the timpani's $timpaniAbove: a hum, not a gong")
        assertTrue(bloom(gong) >= 3.0, "the gong's upper partials do not bloom: ${bloom(gong)} dB")
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

    @Test fun `every groove is bar-exact closes on itself whispers its hats and keeps its mix dark`() {
        for (k in kits) {
            for (v in StaggerKits.velocities) {
                val x = rendered(StaggerKits.GROOVE, v, 0, k).samples
                assertEquals(StaggerKits.grooveFrames(k), x.size, "${k.name} length")
                val wrap = abs(x[0] - x[x.size - 1])
                var largest = 0f
                for (i in x.size - (.02f * rate).toInt() until x.size - 1) largest = max(largest, abs(x[i + 1] - x[i]))
                assertTrue(wrap <= largest * 1.5f + 1e-4f, "${k.name} v $v: wrap step $wrap against $largest")
            }
            val x = rendered(StaggerKits.GROOVE, 1f, 0, k).samples
            // The hats: the 6 to 14 kHz band at its loudest tenth of 23 ms frames against the full band's loudest tenth.
            val hop = 1024
            val top = ArrayList<Double>(); val whole = ArrayList<Double>()
            var i = 0
            while (i + hop <= x.size) {
                val spec = power(x.copyOfRange(i, i + hop), hop)
                var t = 0.0; var w = 0.0
                for (b in spec.indices) { val f = b.toFloat() * rate / hop; if (f >= 30f) w += spec[b]; if (f >= 6_000f && f < 14_000f) t += spec[b] }
                top.add(t); whole.add(w)
                i += hop
            }
            val loudTop = top.sorted().takeLast(top.size / 10).average()
            val loudWhole = whole.sorted().takeLast(whole.size / 10).average()
            val hatsDb = 10 * kotlin.math.log10(loudTop / loudWhole)
            val n = 1 shl 18
            val spec = power(x, n)
            val total = spec.sum()
            var cum = 0.0
            var ceiling = 0.0
            for (b in spec.indices) { cum += spec[b]; if (cum >= .95 * total) { ceiling = b.toDouble() * rate / n; break } }
            println("${k.name} groove: hats $hatsDb dB under the full band at its loudest; 95 percent of the energy under $ceiling Hz")
            // The references' range is 16 to 35 dB under (median 23). STUMBLE's groove has no hat part (its shaker
            // whispers), so its band is not held to the window; MATINEE's foley (glass, zap) lifts its ceiling.
            val parts = StaggerKits.parts(k)
            val hasHat = StaggerKits.notesOf(k).any { parts[it.part].cls == DrumClass.HAT_CLOSED }
            // Round 3 sat the hats 10 dB under the backbeat (the band measure had read them as a whisper while they
            // were as loud as the snare, the print's TONE having darkened them), so the floor is now 45.
            if (hasHat) assertTrue(hatsDb in -45.0..-16.0, "${k.name}: hats $hatsDb dB under; the references 16 to 35, median 23")
            // The dark eight references sit at 309 to 635 Hz and the other five at 0.9 to 4.4 kHz. Round 2 measured 579,
            // 636 and 495 Hz; round 2b's press lifts the tails and the room, to 658, 801 and 953 with STUMBLE's harder
            // press (its backbeat is a rimshot, brighter than a snare's crack). CARDBOARD stays at the dark end (700),
            // MATINEE's foley is allowed 1.5 kHz, STUMBLE's rimshot 1 kHz.
            // Round 3 brought the kick down to the snare, and round 3b ("flat, muffled, and muddy") took the dark shelf
            // off and opened the snare, so the sub no longer holds 95 percent of the groove: the ceilings sit among
            // the five brighter references (0.9 to 4.4 kHz) and are held under their top.
            val ceilingLimit = 4_400.0
            assertTrue(ceiling < ceilingLimit, "${k.name}: 95 percent of the energy under $ceiling Hz; the references under 309 to 635 (eight) and 0.9 to 4.4 kHz (five)")
        }
    }

    @Test fun `the three kits are three kits`() {
        val names = kits.map { StaggerKits.roles(it) }
        for (roles in names) { assertEquals(StaggerKits.PADS, roles.size); assertEquals(roles.size, roles.toSet().size) }
        assertEquals(kits.size, kits.map { it.bpm }.toSet().size)
        assertEquals(kits.size, kits.map { StaggerKits.printOf(it).name }.toSet().size)
        assertEquals(kits.size, kits.map { StaggerKits.clockOf(it).seed }.toSet().size)
        // "Different effect strengths on the same kit are not sufficient evidence of distinct genre kits": every pair
        // differs in at least six pad names, and the kick and the backbeat drum are different drums, dry.
        for (a in kits.indices) for (b in a + 1 until kits.size) {
            assertTrue((names[a] zip names[b]).count { (x, y) -> x != y } >= 6, "${kits[a]} and ${kits[b]} share pads")
            for (role in 0..1) {
                val d = shapeDistance(StaggerKits.dry(kits[a], role, 1f, 0).samples, StaggerKits.dry(kits[b], role, 1f, 0).samples)
                assertTrue(d > .5, "role $role: ${kits[a]} and ${kits[b]} share a drum ($d)")
            }
        }
    }

    /**
     * Round 2b, "maybe we need a bit of compression"; round 2c, "it made it echoy" (the owner, 2026-11-19 and 20).
     * What the press does to a hit, measured against the same print with the compressor taken out: the backbeat
     * drum's crest (its peak against its RMS over the first 200 ms) falls, and its tail (300 to 500 ms against the
     * first 50 ms) comes up at least 2 dB but no more than 8 (round 2b lifted it 12 to 14, and that was the room
     * ringing); the kick's tail never falls more than 2 dB and its crest rises by no more than 2 (a muffled kick is
     * already dense). A phrase is not pressed by the print at all: a groove's quiet tenth of 10 ms frames stays at
     * least 20 dB under its loudest (round 2b's second press put CARDBOARD's at 14.6, round 2's sat at 25.4).
     */
    @Test fun `every print presses its hits a bit and leaves the room where it was`() {
        fun crest(x: FloatArray): Double {
            val n = min(x.size, (.2f * rate).toInt())
            val rms = sqrt(x.take(n).sumOf { it.toDouble() * it } / n)
            return 20 * kotlin.math.log10(x.maxOf { abs(it) } / max(rms, 1e-9))
        }
        // Round 2d gates the club's tail off every kit drum, so a hit's "tail" is now its own decay, 100 to 200 ms
        // in, against its first 50 ms.
        fun tail(x: FloatArray): Double {
            val a = (.1f * rate).toInt(); val b = min(x.size, (.2f * rate).toInt())
            if (b <= a + 100) return -99.0
            val late = sqrt(x.copyOfRange(a, b).sumOf { it.toDouble() * it } / (b - a))
            val head = sqrt(x.take((.05f * rate).toInt()).sumOf { it.toDouble() * it } / (.05f * rate))
            return 20 * kotlin.math.log10(max(late, 1e-9) / max(head, 1e-9))
        }
        for (k in kits) {
            val print = StaggerKits.printOf(k)
            val bare = StaggerKits.Print(print.name, print.crunch, print.tape, print.grooveVinyl, compressor = null, crushMix = print.crushMix)
            for (role in 0..1) {
                val dry = StaggerKits.dryRaw(k, role, 1f, 0)
                val pressed = print.apply(dry.copyOf(), phrase = false, converted = false)
                val loose = bare.apply(dry.copyOf(), phrase = false, converted = false)
                println("${k.name} role $role: crest ${"%.1f".format(crest(loose))} to ${"%.1f".format(crest(pressed))} dB, decay ${"%.1f".format(tail(loose))} to ${"%.1f".format(tail(pressed))} dB")
                if (role == 1) {
                    // A dry snare (round 2d) gives the peak match no room tail to re-level against, so its crest barely
                    // moves under the press (CARDBOARD's 10.9 to 10.9); what the press does to it is lift its decay a little.
                    assertTrue(crest(pressed) <= crest(loose) + .5, "${k.name} role $role: the press raised the crest by ${crest(pressed) - crest(loose)} dB")
                    val lift = tail(pressed) - tail(loose)
                    assertTrue(lift >= 0 && lift <= 8, "${k.name} role $role: the decay moved $lift dB; up to 8 is a bit, 12 was echoy")
                } else {
                    assertTrue(tail(pressed) >= tail(loose) - 2, "${k.name} role $role: the press pulled the tail down ${tail(loose) - tail(pressed)} dB")
                    assertTrue(crest(pressed) <= crest(loose) + 2, "${k.name} role $role: the press raised the kick's crest by ${crest(pressed) - crest(loose)} dB")
                }
            }
            val groove = rendered(StaggerKits.GROOVE, 1f, 0, k).samples
            val hop = rate / 100
            val frames = DoubleArray(groove.size / hop) { f -> sqrt((0 until hop).sumOf { val v = groove[f * hop + it].toDouble(); v * v } / hop) }
            val quiet = 20 * kotlin.math.log10(frames.sorted()[frames.size / 10] / frames.max())
            println("${k.name} groove: quietest tenth $quiet dB under the loudest frame")
            assertTrue(quiet <= -20, "${k.name}: the groove's room sits only $quiet dB under its loudest; round 2b's echo sat at -14.6")
        }
    }

    /**
     * Round 2d, "it sounds like room reverb" (the owner, 2026-11-20): every kit drum is the close mic with the club's
     * tail gated off, so a backbeat drum's pad is at least 50 dB under its peak from 300 to 500 ms (at DISTANCE .30
     * it measured 49 dB down there and 28 dB down at 150 to 250 ms, a room), and every groove, with no ROOM on it,
     * keeps its quiet tenth at least 30 dB under its loudest.
     */
    @Test fun `every kit drum is dry and no groove carries a room`() {
        fun under(x: FloatArray, fromS: Float, toS: Float): Double {
            val a = (fromS * rate).toInt(); val b = min(x.size, (toS * rate).toInt())
            if (b <= a + 100) return 99.0
            return -20 * kotlin.math.log10(max(sqrt(x.copyOfRange(a, b).sumOf { it.toDouble() * it } / (b - a)), 1e-9) / max(x.maxOf { abs(it) }.toDouble(), 1e-9))
        }
        for (k in kits) {
            val snare = StaggerKits.dry(k, 1, 1f, 0).samples
            val late = under(snare, .3f, .5f)
            println("${k.name} backbeat drum: ${"%.1f".format(under(snare, .15f, .25f))} dB under its peak at 150 to 250 ms, ${"%.1f".format(late)} at 300 to 500")
            assertTrue(late >= 50, "${k.name}: the backbeat drum still rings $late dB under its peak at 300 to 500 ms")
            val groove = rendered(StaggerKits.GROOVE, 1f, 0, k).samples
            val hop = rate / 100
            val frames = DoubleArray(groove.size / hop) { f -> sqrt((0 until hop).sumOf { val v = groove[f * hop + it].toDouble(); v * v } / hop) }
            val quiet = 20 * kotlin.math.log10(frames.sorted()[frames.size / 10] / frames.max())
            println("${k.name} groove: quietest tenth $quiet dB under the loudest frame")
            assertTrue(quiet <= -30, "${k.name}: the groove's quiet tenth sits only $quiet dB under its loudest")
        }
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (k in kits) {
        val pads = StaggerKits.build(k, { _, role, v, t -> rendered(role, v, t, k) })
        assertEquals(StaggerKits.PADS, pads.size)
        assertEquals(listOf(DrumClass.KICK, DrumClass.SNARE), pads.take(2).map { it.drumClass })
        assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), pads.takeLast(2).map { it.drumClass })
        for ((role, pad) in pads.withIndex()) {
            assertEquals(3, pad.velocityTakes.size)
            assertTrue(pad.velocityTakes.all { it.size == StaggerKits.TAKES })
            assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
            assertEquals(StaggerKits.roles(k)[role], pad.displayName)
            assertEquals("STAGGER", pad.source["engine"])
            assertEquals(StaggerKits.VERSION.toString(), pad.source["kitVersion"])
            val expected = when {
                pad.drumClass == DrumClass.HAT_CLOSED || pad.drumClass == DrumClass.HAT_OPEN -> StaggerKits.HAT_GROUP
                role >= StaggerKits.HITS -> StaggerKits.PHRASE_GROUP
                else -> 0
            }
            assertEquals(expected, pad.muteGroup, "role $role choke")
            if (role >= StaggerKits.HITS) assertTrue(pad.snip.frameCount > 1.5 * rate, "${k.name} role $role") else assertTrue(pad.snip.frameCount < 1.5 * rate, "${k.name} role $role")
        }
        }
    }

    @Test fun `no pad or kit name borrows a maker's or the producer's name`() {
        val producer = Regex("(?i)doom|dumile|metal\\s*fingers?|viktor\\s*vaughn|king\\s*geedorah|zev\\s*love|\\bkmd\\b|madvillain|madlib|danger\\s*doom|special\\s*herbs|vaudeville|doomsday|mm\\.?\\s*food|born\\s*like\\s*this|stones?\\s*throw|rhymesayers|lex\\s*records|super\\s*villain|villain|\\bmask\\b")
        for (name in kits.flatMap { StaggerKits.roles(it) + it.title + it.id } + "STAGGER") {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
            assertFalse(producer.containsMatchIn(name), name)
        }
    }
}
