package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.Mirrorball.Order
import com.snipsnap.synth.Mirrorball.Reel
import com.snipsnap.synth.MirrorballKits.Kit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MIRRORBALL's gesture and the kits' contracts, measured on real renders. The gesture tests also run the chain
 * with the mechanism under test taken away, so a test fails if the mechanism stops doing the work.
 */
class MirrorballTest {
    private val rate = Dsp.RATE

    private fun db(x: Double) = 20 * log10(max(x, 1e-9))

    private fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var p = 0f
        for (i in max(from, 0) until min(to, x.size)) p = max(p, abs(x[i]))
        return p.toDouble()
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        var n = 0
        for (i in max(from, 0) until min(to, x.size)) { sum += x[i].toDouble() * x[i]; n++ }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    private fun frames(ms: Float) = (ms * 0.001f * rate).toInt()

    /** Peak against rms over the first 100 ms: lower is denser. */
    private fun crest(x: FloatArray) = db(peak(x, 0, frames(100f)) / max(rms(x, 0, frames(100f)), 1e-12))

    /** How unlike two renders are once level is taken out: 0 for the same shape, about 1.4 for unrelated ones. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        val ea = sqrt(a.sumOf { it.toDouble() * it })
        val eb = sqrt(b.sumOf { it.toDouble() * it })
        var d = 0.0
        for (i in 0 until n) { val v = a[i] / ea - b[i] / eb; d += v * v }
        return sqrt(d)
    }

    /** Share of a render's energy above [hz], by a one-pole split: a plain brightness measure. */
    private fun brightness(x: FloatArray, hz: Float = 2_000f): Double {
        val lp = Dsp.FixedOnePole(hz)
        var high = 0.0
        var all = 0.0
        for (v in x) { val h = v - lp.lp(v); high += h.toDouble() * h; all += v.toDouble() * v }
        return high / max(all, 1e-18)
    }

    private companion object {
        /** Each kit is built once for the whole class: its grids are the renders the app would save. */
        val built = ConcurrentHashMap<Kit, List<ArrangedPad>>()
    }

    private fun kit(k: Kit): List<ArrangedPad> = built.getOrPut(k) { MirrorballKits.build(k) }

    private fun grid(k: Kit, role: Int, zone: Int, take: Int): FloatArray = kit(k)[role].velocityTakes[zone][take].samples

    @Test fun `every pad renders the same bytes twice`() {
        for (k in Kit.entries) for (role in 0 until MirrorballKits.PADS) for ((zone, v) in listOf(0 to .30f, 2 to 1f)) {
            // One side is the built kit, the other is rendered now through the single-pad path: a second run.
            assertContentEquals(grid(k, role, zone, 1), MirrorballKits.render(k, role, v, 1).samples, "${k.name} role $role v $v")
        }
    }

    @Test fun `every pad is finite bounded centred and ends in silence`() {
        for (k in Kit.entries) for (role in 0 until MirrorballKits.PADS) for (zone in 0..2) for (take in 0 until MirrorballKits.TAKES) {
            val x = grid(k, role, zone, take)
            val what = "${k.name} role $role zone $zone take $take"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val p = peak(x)
            assertTrue(p <= .98 + 1e-6 && p > .2, "$what peak $p")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-4f, "$what tail")
        }
    }

    @Test fun `every zone is levelled to one peak and no take runs far from it`() {
        for (k in Kit.entries) for (role in 0 until MirrorballKits.PADS) for (zone in 0..2) {
            // Level is the sampler's job, so a soft zone is not also rendered quieter.
            assertEquals(MirrorballKits.PAD_PEAK.toDouble(), peak(grid(k, role, zone, 0)), .005, "${k.name} role $role zone $zone")
            for (take in 1 until MirrorballKits.TAKES) {
                val p = peak(grid(k, role, zone, take))
                assertTrue(p in .6..(.98 + 1e-6), "${k.name} role $role zone $zone take $take peak $p")
            }
        }
    }

    /**
     * The first repeat against the hit, dB, in [order], with [combo] in place of the pad's own amp. Measured on
     * pads whose dry strike has died away by the first repeat, so the window holds the echo and not the drum.
     */
    private fun repeatOverHit(k: Kit, role: Int, order: Order, combo: Mirrorball.Combo? = null): Double {
        val s0 = MirrorballKits.settingOf(k, role)
        val s = if (combo == null) s0 else s0.copy(combo = combo)
        val dry = MirrorballKits.dryRaw(k, role, 1f, 0)
        val out = Mirrorball.process(dry, s, k.bpm, 1, (MirrorballKits.HIT_SECONDS * rate).toInt(), order).out
        val d = (Mirrorball.HEAD_SIXTEENTHS.getValue(s.reel.heads.min()) * Mirrorball.sixteenthFrames(k.bpm)).toInt()
        return db(peak(out, d, d + frames(60f)) / peak(out, 0, frames(60f)))
    }

    /** Echo pads whose own strike is 30 dB down by the first repeat. */
    private fun cleanEchoPads(k: Kit): List<Int> = (0 until MirrorballKits.HITS).filter { role ->
        val s = MirrorballKits.settingOf(k, role)
        if (!s.reel.on) return@filter false
        val dry = MirrorballKits.dryRaw(k, role, 1f, 0)
        val d = (Mirrorball.HEAD_SIXTEENTHS.getValue(s.reel.heads.min()) * Mirrorball.sixteenthFrames(k.bpm)).toInt()
        db(peak(dry, d, d + frames(60f)) / peak(dry)) < -30
    }

    @Test fun `with the amp last the echoes come back louder behind the hit`() {
        for (k in Kit.entries) {
            val pads = cleanEchoPads(k)
            assertTrue(pads.size >= 5, "${k.name}: only ${pads.size} pads to measure")
            val gaps = pads.map { repeatOverHit(k, it, Order.OWNER) - repeatOverHit(k, it, Order.RACK) }
            for ((role, gap) in pads.zip(gaps)) assertTrue(gap > -.5, "${k.name} role $role: the rack's order lifts the echo ${-gap} dB more")
            assertTrue(gaps.average() >= 2.0, "${k.name}: echoes only ${gaps.average()} dB louder with the amp last ($gaps)")
            // The mechanism is the amp's sag squeezing hit and echoes together. Take the sag and most of the gain
            // away and the two orders move closer together.
            val flat = MirrorballKits.ampOf(k).copy(drive = .8f, sag = 0f)
            val flatGaps = pads.map { repeatOverHit(k, it, Order.OWNER, flat.copy(cabHz = MirrorballKits.settingOf(k, it).combo.cabHz)) -
                repeatOverHit(k, it, Order.RACK, flat.copy(cabHz = MirrorballKits.settingOf(k, it).combo.cabHz)) }
            assertTrue(flatGaps.average() < gaps.average() - 1.0, "${k.name}: without sag the gap is ${flatGaps.average()} against ${gaps.average()} dB")
        }
    }

    @Test fun `the echoes land on the heads and not between them`() {
        // The closed hat's gallop: heads 1 and 2, a sixteenth and a dotted eighth.
        val k = Kit.PHILLY
        val role = 2
        val s = MirrorballKits.settingOf(k, role)
        assertEquals(listOf(1, 2), s.reel.heads)
        val dry = MirrorballKits.dryRaw(k, role, 1f, 0)
        val n = (MirrorballKits.HIT_SECONDS * rate).toInt()
        val sixteenth = Mirrorball.sixteenthFrames(k.bpm)
        fun windows(out: FloatArray): Triple<Double, Double, Double> {
            val d1 = sixteenth.toInt()
            val d2 = (3 * sixteenth).toInt()
            val gap = (2 * sixteenth).toInt()
            return Triple(peak(out, d1 - frames(5f), d1 + frames(30f)), peak(out, d2 - frames(5f), d2 + frames(30f)),
                peak(out, gap - frames(15f), gap + frames(15f)))
        }
        val (one, two, between) = windows(Mirrorball.process(dry, s, k.bpm, 1, n).out)
        assertTrue(db(one / between) > 10, "first head ${db(one)} against the gap ${db(between)} dB")
        assertTrue(db(two / between) > 10, "second head ${db(two)} against the gap ${db(between)} dB")
        // No echo: nothing at the first head stands over the gap.
        val (bare, _, bareGap) = windows(Mirrorball.process(dry, s.copy(reel = Reel()), k.bpm, 1, n).out)
        assertTrue(db(bare / max(bareGap, 1e-9)) < 6, "without the echo the first head still reads ${db(bare)} dB")
    }

    @Test fun `no kick has an echo, so four on the floor stays four`() {
        for (k in Kit.entries) for ((role, pad) in kit(k).withIndex()) if (pad.drumClass == DrumClass.KICK) {
            assertFalse(MirrorballKits.settingOf(k, role).reel.on, "${k.name} ${pad.displayName}")
        }
    }

    @Test fun `the tape loop never grows even with repeats at the top`() {
        for (heads in listOf(listOf(1, 2), listOf(2, 3), listOf(3))) for (bpm in listOf(60, 128, 200)) {
            val impulse = FloatArray(400).also { it[10] = 1f }
            val reel = Reel(heads, send = 1f, repeats = 1f)
            val out = Mirrorball.reel(impulse, reel, Mirrorball.Tape(age = 0f, wow = 1f, drive = 0f), bpm, 7, 6 * rate)
            // Windows of the longest head, so each holds the same share of the rhythm. They start half a head
            // after the input, so the tape's wobble (a few ms) never moves a repeat across a window's edge.
            val w = (Mirrorball.HEAD_SIXTEENTHS.getValue(heads.max()) * Mirrorball.sixteenthFrames(bpm)).toInt()
            val start = 10 + w / 2
            val levels = (0 until (out.size - start) / w).map { rms(out, start + it * w, start + (it + 1) * w) }.filter { it > 0 }
            for (i in 1 until levels.size) {
                assertTrue(levels[i] < levels[i - 1] * 1.001, "heads $heads at $bpm: window $i ${db(levels[i])} after ${db(levels[i - 1])} dB")
            }
        }
    }

    @Test fun `the compressor makes the snare denser and squeezes a softer hit less`() {
        for (k in Kit.entries) {
            val role = 1
            val press = MirrorballKits.settingOf(k, role).press
            fun drop(v: Float): Double {
                val dry = MirrorballKits.dryRaw(k, role, v, 0)
                return crest(dry) - crest(Mirrorball.press(dry, press))
            }
            val hard = drop(1f)
            val soft = drop(.3f)
            assertTrue(hard > 1.5, "${k.name}: the snare's crest falls only $hard dB")
            assertTrue(soft < hard, "${k.name}: a soft snare is squeezed as much as a hard one ($soft against $hard dB)")
            // The squeeze is the fixed threshold at work: a hit held 26 dB under it with no input gain comes
            // through with its crest unchanged.
            val quiet = MirrorballKits.dryRaw(k, role, 1f, 0).let { x -> FloatArray(x.size) { x[it] * .05f } }
            val change = abs(crest(quiet) - crest(Mirrorball.press(quiet, press.copy(inputDb = 0f))))
            assertTrue(change < .5, "${k.name}: a hit under the threshold changes crest by $change dB")
        }
    }

    @Test fun `all buttons in is a different squeeze from twenty to one`() {
        val k = Kit.PHILLY
        val dry = MirrorballKits.dryRaw(k, 1, 1f, 0)
        val press = MirrorballKits.settingOf(k, 1).press
        val twenty = Mirrorball.press(dry, press.copy(ratio = Mirrorball.Ratio.TWENTY))
        val all = Mirrorball.press(dry, press.copy(ratio = Mirrorball.Ratio.ALL))
        assertTrue(shapeDistance(twenty, all) > .02, "${shapeDistance(twenty, all)}")
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (k in Kit.entries) for (role in 0 until MirrorballKits.PADS) {
            val distance = shapeDistance(grid(k, role, 2, 0), grid(k, role, 0, 0))
            assertTrue(distance > .05, "${k.name} role $role: soft is a scaled copy of hard ($distance)")
        }
        for (k in Kit.entries) for (role in 0 until MirrorballKits.HITS) {
            val hard = brightness(MirrorballKits.dry(k, role, 1f, 0).samples)
            val soft = brightness(MirrorballKits.dry(k, role, .3f, 0).samples)
            assertTrue(soft < hard, "${k.name} role $role: soft $soft is not duller than hard $hard")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (k in Kit.entries) for (role in 0 until MirrorballKits.PADS) for (zone in 0..2) {
            val takes = List(MirrorballKits.TAKES) { grid(k, role, zone, it) }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val distance = shapeDistance(takes[a], takes[b])
                assertTrue(distance > .005, "${k.name} role $role zone $zone: takes $a and $b differ by only $distance")
            }
        }
    }

    @Test fun `the three kits are different kits`() {
        val names = Kit.entries.map { MirrorballKits.roles(it) }
        for (roles in names) { assertEquals(MirrorballKits.PADS, roles.size); assertEquals(roles.size, roles.toSet().size) }
        assertEquals(3, names.toSet().size)
        assertEquals(3, Kit.entries.map { it.bpm }.toSet().size)
        assertEquals(3, Kit.entries.map { MirrorballKits.tapeOf(it) }.toSet().size)
        assertEquals(3, Kit.entries.map { MirrorballKits.ampOf(it) }.toSet().size)
        // The kick and the main snare are different drums in each kit, dry, before any chain.
        for (role in 0..1) {
            val drums = Kit.entries.map { MirrorballKits.dry(it, role, 1f, 0).samples }
            for (a in drums.indices) for (b in a + 1 until drums.size) {
                assertTrue(shapeDistance(drums[a], drums[b]) > .5, "role $role: kits $a and $b share a drum")
            }
        }
        // Pad roles differ: two toms, none (hand drums and timbales instead), four synth toms.
        assertEquals(listOf(2, 0, 4), Kit.entries.map { k -> kit(k).count { it.drumClass == DrumClass.TOM } })
        assertEquals(listOf(0, 2, 1), Kit.entries.map { k -> kit(k).count { it.drumClass == DrumClass.CLAP || it.displayName == "Clap Snare" } })
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (k in Kit.entries) {
            val pads = kit(k)
            assertEquals(MirrorballKits.PADS, pads.size)
            assertEquals(listOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN), pads.take(4).map { it.drumClass })
            assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), pads.takeLast(2).map { it.drumClass })
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == MirrorballKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(MirrorballKits.roles(k)[role], pad.displayName)
                assertEquals("MIRRORBALL", pad.source["engine"])
                assertEquals(k.name, pad.source["kit"])
                assertEquals(MirrorballKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(Mirrorball.VERSION.toString(), pad.source["stageVersion"])
                val hat = pad.drumClass == DrumClass.HAT_CLOSED || pad.drumClass == DrumClass.HAT_OPEN
                assertEquals(if (hat) 1 else if (role >= MirrorballKits.HITS) 2 else 0, pad.muteGroup)
                // A phrase is filed as a loop; every hit, echo and all, stays under the classifier's 1.5 s.
                for (zone in pad.velocityTakes) for (take in zone) {
                    if (role >= MirrorballKits.HITS) assertTrue(take.frameCount > 1.5 * rate)
                    else assertTrue(take.frameCount < 1.5 * rate, "${k.name} role $role is ${take.frameCount} frames")
                }
            }
        }
    }

    /** Energy share of [lo]..[hi] Hz over the first [ms] of [x], dB, by FFT (zero-padded to 16384). */
    private fun bandShare(x: FloatArray, lo: Double, hi: Double, ms: Float = 300f): Double {
        val n = 16384
        val m = min(min(x.size, frames(ms)), n)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until m) re[i] = (x[i] * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / max(m - 1, 1)))).toFloat()
        com.snipsnap.audio.Fft.forward(re, im)
        var band = 0.0
        var all = 0.0
        for (k in 1 until n / 2) {
            val pw = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            val hz = k.toDouble() * rate / n
            all += pw
            if (hz >= lo && hz < hi) band += pw
        }
        return 10 * log10(max(band, 1e-30) / max(all, 1e-30))
    }

    private fun recording(name: String): FloatArray {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/backwash/v5/$name.wav")).use { it.readBytes() }
        val snip = com.snipsnap.audio.WavReader.read(bytes)
        val c = snip.channels
        val x = FloatArray(snip.samples.size / c) { i -> (0 until c).sumOf { snip.samples[i * c + it].toDouble() }.toFloat() / c }
        val p = peak(x)
        val on = max(0, x.indexOfFirst { abs(it) > .05 * p } - frames(1f))
        return x.copyOfRange(on, x.size)
    }

    @Test fun `the synthesized tambourine matches the recordings it was fitted to, band by band`() {
        val thirds = (0..12).map { 1000.0 * Math.pow(2.0, it / 3.0) }.filter { it >= 1600 }
        fun profile(x: FloatArray) = thirds.map { fc -> bandShare(x, fc / Math.pow(2.0, 1 / 6.0), fc * Math.pow(2.0, 1 / 6.0)) }
        for ((velocity, layer) in listOf(1f to 2, .3f to 1)) {
            val target = List(2) { profile(recording("tambourine-$layer-$it")) }.let { (a, b) -> a.indices.map { (a[it] + b[it]) / 2 } }
            val got = List(4) { profile(MirrorballTambourine.render(velocity, it)) }.let { takes -> target.indices.map { i -> takes.map { it[i] }.average() } }
            for ((i, fc) in thirds.withIndex()) {
                assertTrue(abs(got[i] - target[i]) < 3.0, "layer $layer, ${fc.toInt()} Hz: synth ${got[i]} against recording ${target[i]} dB")
            }
        }
    }

    @Test fun `every tambourine take is a new pattern of collisions, the same every time`() {
        val takes = List(4) { MirrorballTambourine.render(1f, it) }
        for (a in takes.indices) for (b in a + 1 until takes.size) {
            assertTrue(shapeDistance(takes[a], takes[b]) > .3, "takes $a and $b: ${shapeDistance(takes[a], takes[b])}")
        }
        assertContentEquals(takes[2], MirrorballTambourine.render(1f, 2))
        // A soft hit builds: its loudest 20 ms comes later than a hard hit's (the recordings: about 50 ms against at once).
        fun loudest(x: FloatArray) = (0 until 40).maxBy { rms(x, it * frames(5f), it * frames(5f) + frames(20f)) }
        assertTrue(loudest(MirrorballTambourine.render(.3f, 0)) > loudest(MirrorballTambourine.render(1f, 0)))
    }

    @Test fun `every kick has its beater, and the print gives it harmonics in proportion to the punch`() {
        for (k in Kit.entries) for ((role, pad) in kit(k).withIndex()) if (pad.drumClass == DrumClass.KICK && pad.displayName != "Long Kick") {
            val hard = grid(k, role, 2, 0)
            val click = bandShare(hard, 1500.0, 4000.0, 15f)
            assertTrue(click > -18, "${k.name} ${pad.displayName}: beater band only $click dB in the first 15 ms")
        }
        val k = Kit.PHILLY
        val dry = MirrorballKits.dryRaw(k, 0, 1f, 0)
        fun harmonics(intensity: Float?): Double {
            val s = if (intensity == null) MirrorballKits.settingOf(k, 0).copy(print = Mirrorball.Print(), parallel = 0f)
                    else MirrorballKits.settingOf(k, 0, intensity)
            return bandShare(Mirrorball.process(dry, s, k.bpm, 1).out, 250.0, 600.0)
        }
        val none = harmonics(null)
        val lighter = harmonics(.75f)
        val kit = harmonics(MirrorballKits.INTENSITY)
        val full = harmonics(1f)
        assertTrue(kit > none + 10, "the print adds only ${kit - none} dB at 250-600 Hz")
        assertTrue(lighter < kit && kit < full, "harmonics do not follow the punch: $lighter, $kit, $full dB")
    }

    @Test fun `no pad or kit name borrows a maker's name`() {
        for (k in Kit.entries) for (name in MirrorballKits.roles(k) + k.title + k.id) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
            assertFalse(Regex("(?i)1176|uafx|universal audio|strymon|capistan|teletronix|fender|sigma|salsoul|mfsb|moroder|syndrum|simmons").containsMatchIn(name), name)
        }
    }

    @Test fun `a setting refuses values outside its range and silence stays silent`() {
        assertTrue(runCatching { Mirrorball.Press(inputDb = Float.NaN) }.isFailure)
        assertTrue(runCatching { Mirrorball.Press(attack = 1.5f) }.isFailure)
        assertTrue(runCatching { Reel(listOf(1, 2, 3), .2f, .2f) }.isFailure)
        assertTrue(runCatching { Reel(listOf(4), .2f, .2f) }.isFailure)
        assertTrue(runCatching { Reel(listOf(1), Float.NaN, .2f) }.isFailure)
        assertTrue(runCatching { Mirrorball.Tape(age = -1f) }.isFailure)
        assertTrue(runCatching { Mirrorball.Combo(cabHz = 100f) }.isFailure)
        val s = MirrorballKits.settingOf(Kit.LOFT, 6)
        assertTrue(runCatching { Mirrorball.process(FloatArray(10), s, bpm = 20, seed = 1) }.isFailure)
        for (order in Order.entries) assertTrue(Mirrorball.process(FloatArray(4000), s, 120, 1, 8000, order).out.all { it == 0f })
    }
}
