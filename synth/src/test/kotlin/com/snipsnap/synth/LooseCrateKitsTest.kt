package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.LooseCrateKits.Kit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The LOOSE CRATE kits' contracts, on real renders, in CypherTest's shape: the same bytes twice, every zone
 * levelled, a softer hit a different and duller strike, four different takes in every zone, four kits that
 * are four kits, grooves that are bar-exact and close on themselves, and HUMMIN opening with round 0's sounds.
 */
class LooseCrateKitsTest {
    private val rate = Dsp.RATE

    /** How unlike two renders are once level is taken out: 0 for the same shape, about 1.4 for unrelated ones. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        val ea = sqrt(a.sumOf { it.toDouble() * it })
        val eb = sqrt(b.sumOf { it.toDouble() * it })
        var d = 0.0
        for (i in 0 until n) { val v = a[i] / ea - b[i] / eb; d += v * v }
        return sqrt(d)
    }

    /** Share of a render's energy above [hz], by a one-pole split. */
    private fun brightness(x: FloatArray, hz: Float = 2_000f): Double {
        val lp = Dsp.FixedOnePole(hz)
        var high = 0.0
        var all = 0.0
        for (v in x) { val h = v - lp.lp(v); high += h.toDouble() * h; all += v.toDouble() * v }
        return high / max(all, 1e-18)
    }

    /**
     * The magnitude-weighted spectral centroid of the first 50 ms after the onset: the probe StrikeVelocityTest
     * uses, since a power-weighted centroid (or one band split) hides a drum's overtones and a hat's darkening.
     */
    private fun onsetCentroid(x: FloatArray): Double {
        val peak = x.maxOf { abs(it) }
        val on = x.indexOfFirst { abs(it) > peak * .05f }
        val n = min((.05f * rate).toInt(), x.size - on)
        var num = 0.0
        var den = 0.0
        for (k in 1 until n / 2) {
            var re = 0.0
            var im = 0.0
            val w = 2 * Math.PI * k / n
            for (i in 0 until n) { val hann = .5 - .5 * Math.cos(2 * Math.PI * i / n); re += x[on + i] * hann * Math.cos(w * i); im -= x[on + i] * hann * Math.sin(w * i) }
            val mag = sqrt(re * re + im * im)
            num += mag * k * rate / n
            den += mag
        }
        return num / max(den, 1e-18)
    }

    private fun rendered(kit: Kit, role: Int, velocity: Float, take: Int): Snip =
        renders.getOrPut(listOf(kit, role, velocity, take)) { LooseCrateKits.render(kit, role, velocity, take) }

    private companion object {
        val renders = java.util.concurrent.ConcurrentHashMap<List<Any>, Snip>()
    }

    private fun subs(kit: Kit) = (0 until LooseCrateKits.HITS).filter { LooseCrateKits.muteGroup(kit, it) == LooseCrateKits.SUB_GROUP }

    @Test fun `every pad renders the same bytes twice`() {
        for (kit in Kit.entries) for (role in 0 until LooseCrateKits.PADS) {
            assertContentEquals(rendered(kit, role, .65f, 1).samples, LooseCrateKits.render(kit, role, .65f, 1).samples, "${kit.name} role $role")
        }
    }

    @Test fun `every pad is finite bounded centred and every hit ends in silence`() {
        for (kit in Kit.entries) for (role in 0 until LooseCrateKits.PADS) for (v in LooseCrateKits.velocities) {
            val x = rendered(kit, role, v, 0).samples
            val what = "${kit.name} role $role v $v"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val peak = x.maxOf { abs(it) }
            assertTrue(peak <= .98f + 1e-6f && peak > .2f, "$what peak $peak")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            if (role != LooseCrateKits.GROOVE) assertTrue(abs(x.last()) < 1e-4f, "$what tail")
        }
    }

    @Test fun `every zone is levelled to one peak and no take runs far from it`() {
        for (kit in Kit.entries) for (role in 0 until LooseCrateKits.PADS) for (v in LooseCrateKits.velocities) {
            assertEquals(LooseCrateKits.PAD_PEAK, rendered(kit, role, v, 0).peak(), .005f, "${kit.name} role $role v $v")
            for (take in 1 until LooseCrateKits.TAKES) {
                val peak = rendered(kit, role, v, take).peak()
                assertTrue(peak in .6f..(.98f + 1e-6f), "${kit.name} role $role v $v take $take peak $peak")
            }
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (kit in Kit.entries) for (role in 0 until LooseCrateKits.PADS) {
            val distance = shapeDistance(rendered(kit, role, 1f, 0).samples, rendered(kit, role, .3f, 0).samples)
            assertTrue(distance > .05, "${kit.name} role $role: soft is a scaled copy of hard ($distance)")
        }
        // On single hits the soft strike is also duller, measured dry (the converter's grain is a fixed size, so
        // it is relatively louder on a quiet hit and can brighten the printed soft zone; round 0's thud kick).
        for (kit in Kit.entries) for (role in 0 until LooseCrateKits.HITS) {
            if (role in subs(kit)) continue
            val hard = LooseCrateKits.dry(kit, role, 1f, 0).samples
            val soft = LooseCrateKits.dry(kit, role, .3f, 0).samples
            assertTrue(brightness(soft) < brightness(hard), "${kit.name} role $role: soft is not duller than hard")
        }
    }

    @Test fun `the print never makes a softer hit brighter`() {
        // Fed in at its zone's level, a soft hit's converter grain was relatively louder and a 12-bit print
        // brightened every low or woody drum's soft zone. Every pad now enters the print at one level.
        for (kit in Kit.entries) for (role in 0 until LooseCrateKits.HITS) {
            if (role in subs(kit)) continue
            val hard = rendered(kit, role, 1f, 0).samples
            val soft = rendered(kit, role, .3f, 0).samples
            val (cs, ch) = onsetCentroid(soft) to onsetCentroid(hard)
            assertTrue(cs <= ch * 1.005, "${kit.name} role $role: printed soft onset centroid $cs Hz against hard $ch Hz")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (kit in Kit.entries) for (role in 0 until LooseCrateKits.PADS) for (v in LooseCrateKits.velocities) {
            val takes = List(LooseCrateKits.TAKES) { rendered(kit, role, v, it).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val distance = shapeDistance(takes[a], takes[b])
                val floor = if (role in subs(kit)) .05 else .005
                assertTrue(distance > floor, "${kit.name} role $role v $v: takes $a and $b differ by only $distance")
            }
        }
    }

    @Test fun `the four kits are four kits`() {
        val names = Kit.entries.map { LooseCrateKits.roles(it) }
        for (roles in names) { assertEquals(LooseCrateKits.PADS, roles.size); assertEquals(roles.size, roles.toSet().size) }
        assertEquals(4, Kit.entries.map { it.bpm }.toSet().size)
        assertEquals(4, Kit.entries.map { LooseCrateKits.printOf(it).name }.toSet().size)
        // "Different effect strengths on the same kit are not sufficient evidence of distinct genre kits": every
        // pair differs in at least six pad names, and the kick and the main snare are different drums, dry.
        for (a in Kit.entries.indices) for (b in a + 1 until Kit.entries.size) {
            assertTrue((names[a] zip names[b]).count { (x, y) -> x != y } >= 6, "${Kit.entries[a]} and ${Kit.entries[b]} share pads")
            for (role in 0..1) {
                val d = shapeDistance(LooseCrateKits.dry(Kit.entries[a], role, 1f, 0).samples, LooseCrateKits.dry(Kit.entries[b], role, 1f, 0).samples)
                assertTrue(d > .5, "role $role: ${Kit.entries[a]} and ${Kit.entries[b]} share a drum ($d)")
            }
        }
    }

    @Test fun `every groove is bar-exact and closes on itself`() {
        for (kit in Kit.entries) for (v in LooseCrateKits.velocities) {
            val x = rendered(kit, LooseCrateKits.GROOVE, v, 0).samples
            assertEquals(LooseCrateKits.grooveFrames(kit), x.size, "${kit.name} length")
            // The step across the wrap is no bigger than the steps just before it: no click where the pad repeats.
            val wrap = abs(x[0] - x[x.size - 1])
            var largest = 0f
            for (i in x.size - (.02f * rate).toInt() until x.size - 1) largest = max(largest, abs(x[i + 1] - x[i]))
            assertTrue(wrap <= largest * 1.5f + 1e-4f, "${kit.name} v $v: wrap step $wrap against $largest")
        }
    }

    /**
     * Round 2 (the owner heard round 1 as "a bit drum machine like"): a groove keeps sounding between hits as its
     * references do. The quietest tenth of its 10 ms frames, under its loudest frame, sits in a band around its
     * references' (HUMMIN's 18 to 23 dB, CLOTH's 45, HAZE's 22 to 31, RITUAL's 35 to 44); round 1 measured 45, 44,
     * 45 and 56, every groove falling to near silence between hits.
     */
    @Test fun `every groove keeps sounding between hits as its references do`() {
        val bands = mapOf(Kit.HUMMIN to -33.0..-15.0, Kit.CLOTH to -56.0..-40.0, Kit.HAZE to -36.0..-18.0, Kit.RITUAL to -48.0..-30.0)
        for (kit in Kit.entries) {
            val x = rendered(kit, LooseCrateKits.GROOVE, 1f, 0).samples
            val hop = rate / 100
            val frames = DoubleArray(x.size / hop) { f -> sqrt((0 until hop).sumOf { val v = x[f * hop + it].toDouble(); v * v } / hop) }
            val loudest = frames.max()
            val quiet = frames.sorted()[frames.size / 10]
            val db = 20 * kotlin.math.log10(quiet / loudest)
            assertTrue(db in bands.getValue(kit), "${kit.name}: quietest tenth $db dB under the loudest frame, band ${bands.getValue(kit)}")
        }
    }

    /** Round 2: a drummer's kicks are not all one strength; the references' land 5 to 12 dB apart, round 1's within 1.6 dB. */
    @Test fun `every groove's kicks land at least 8 dB apart from softest to hardest`() {
        for (kit in Kit.entries) {
            val parts = LooseCrateKits.parts(kit)
            val notes = LooseCrateKits.played(kit, LooseCrateKits.phrases(kit)[1].notes)
            val kicks = notes.filter { it.part < LooseCrateKits.HITS && parts[it.part].cls == DrumClass.KICK }.map { it.weight }
            val spread = 20 * kotlin.math.log10(kicks.max().toDouble() / kicks.min())
            assertTrue(spread >= 8.0, "${kit.name}: kicks only $spread dB apart")
            // Every kick has the snare wires under it.
            assertEquals(kicks.size, notes.count { it.part == LooseCrateKits.WIRES }, "${kit.name} wires")
        }
    }

    /**
     * Round 3: "Crate cloth could use a bit of warmth or compression in the bass" (the owner). CLOTH's print squeezes
     * its bass alone: on CLOTH's own groove, before the print, the squeeze lowers the bass crest (peak against RMS
     * under 200 Hz) by at least 2 dB and leaves the band above it untouched. The reference's bass crest measured 17.9
     * dB, round 2's CLOTH groove 21.4 (Python probes, docs/LOOSE_CRATE.md).
     */
    @Test fun `CLOTH's print presses its bass into body and leaves the rest alone`() {
        val print = LooseCrateKits.printOf(Kit.CLOTH)
        assertNotNull(print.bassSqueeze)
        assertNotNull(print.valve)
        val plain = LooseCrateKits.Print(print.name, print.crunch, print.tape, print.hitVinyl, print.grooveVinyl, print.valve, bassSqueeze = null)
        val bare = LooseCrateKits.Print(print.name, print.crunch, print.tape, print.hitVinyl, print.grooveVinyl)
        val dry = LooseCrateKits.dry(Kit.CLOTH, LooseCrateKits.GROOVE, 1f, 0).samples
        fun low(s: FloatArray): FloatArray {
            val a = Dsp.FixedOnePole(200f); val b = Dsp.FixedOnePole(200f); val c = Dsp.FixedOnePole(200f); val d = Dsp.FixedOnePole(200f)
            return FloatArray(s.size) { d.lp(c.lp(b.lp(a.lp(s[it])))) }
        }
        fun crest(s: FloatArray) = 20 * kotlin.math.log10(s.maxOf { abs(it) } / sqrt(s.sumOf { it.toDouble() * it } / s.size))
        val full = crest(low(print.apply(dry, phrase = true)))
        val noSqueeze = crest(low(plain.apply(dry, phrase = true)))
        val round2 = crest(low(bare.apply(dry, phrase = true)))
        println("CLOTH bass crest: round 2 print $round2 dB, with the tube $noSqueeze dB, with the tube and the squeeze $full dB")
        assertTrue(full <= round2 - 2.0, "bass crest $round2 dB in round 2's print, $full with round 3's")
        assertTrue(full < noSqueeze, "the squeeze does nothing: $noSqueeze dB without it, $full with it")
    }

    @Test fun `HUMMIN opens with round 0's sounds as they were heard`() {
        // Round 0's candidates are the HUMMIN strikes, through CRATE: the printed hard first take must match.
        val pairs = mapOf(0 to "hum-kick-layer", 1 to "flam-snare", 2 to "dark-hat", 4 to "hum-kick-sub", 5 to "bounce-snare", 6 to "thud-kick", 13 to "slap-snare")
        for ((role, name) in pairs) {
            val heard = LooseCrateRound0Generator.pad(LooseCrateRound0Generator.candidates.first { it.name == name }, 1f, 0, printed = true).samples
            val kit = rendered(Kit.HUMMIN, role, 1f, 0).samples
            assertTrue(shapeDistance(heard, kit) < 1e-3, "role $role differs from round 0's $name")
        }
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (kit in Kit.entries) {
            // Built from the same renders the other tests use: build() itself is only arrangement.
            val pads = LooseCrateKits.build(kit, ::rendered)
            assertEquals(LooseCrateKits.PADS, pads.size)
            assertEquals(listOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN), pads.take(4).map { it.drumClass })
            assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), pads.takeLast(2).map { it.drumClass })
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == LooseCrateKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(LooseCrateKits.roles(kit)[role], pad.displayName)
                assertEquals("LOOSE CRATE", pad.source["engine"])
                assertEquals(kit.name, pad.source["kit"])
                assertEquals(LooseCrateKits.VERSION.toString(), pad.source["kitVersion"])
                val hat = pad.drumClass == DrumClass.HAT_CLOSED || pad.drumClass == DrumClass.HAT_OPEN
                val expected = when {
                    hat -> LooseCrateKits.HAT_GROUP
                    role >= LooseCrateKits.HITS -> LooseCrateKits.PHRASE_GROUP
                    role in subs(kit) -> LooseCrateKits.SUB_GROUP
                    else -> 0
                }
                assertEquals(expected, pad.muteGroup, "${kit.name} role $role choke")
                if (role >= LooseCrateKits.HITS) assertTrue(pad.snip.frameCount > 1.5 * rate, "${kit.name} role $role")
                else assertTrue(pad.snip.frameCount < 1.5 * rate, "${kit.name} role $role is ${pad.snip.frameCount} frames")
            }
        }
    }

    @Test fun `no pad or kit name borrows a maker's or the producer's name`() {
        for (kit in Kit.entries) for (name in LooseCrateKits.roles(kit) + kit.title + kit.id) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
            // The producer the references were named after, his aliases, album titles and label (the brief's terms).
            assertFalse(Regex("(?i)madlib|quasimoto|lootpack|konducta|madvillain|stones ?throw|yesterdays new|beat konducta").containsMatchIn(name), name)
        }
    }
}
