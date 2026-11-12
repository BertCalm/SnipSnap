package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.CypherKits.Kit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CYPHER kits' contracts, measured on real renders: the slide reads, the root holds, the hats obey the
 * ratchet rule, a phrase lands where it was written, every zone is a different strike, every take a
 * different strike, every print carries its authored energy, and the two kits are two kits.
 */
class CypherTest {
    private val rate = Dsp.RATE

    private fun db(x: Double) = 20 * log10(max(x, 1e-9))

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        var n = 0
        for (i in max(from, 0) until min(to, x.size)) { sum += x[i].toDouble() * x[i]; n++ }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    private fun frames(ms: Float) = (ms * 0.001f * rate).toInt()

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

    /** Energy between [lo] and [hi] against energy under 2 kHz, by a direct DFT at 5 Hz steps. */
    private fun bandShare(x: FloatArray, lo: Float, hi: Float): Double {
        fun power(hz: Double): Double {
            var re = 0.0; var im = 0.0
            val w = 2 * PI * hz / rate
            for (i in x.indices) { re += x[i] * cos(w * i); im += x[i] * sin(w * i) }
            return re * re + im * im
        }
        var band = 0.0; var all = 0.0
        var hz = 10.0
        while (hz <= 2_000.0) { val p = power(hz); all += p; if (hz >= lo && hz < hi) band += p; hz += 5.0 }
        return band / all
    }

    private fun cents(hz: Float, ref: Float) = 1200.0 * log2(hz.toDouble() / ref)

    private fun energy(x: FloatArray) = x.sumOf { it.toDouble() * it }

    /** Seconds to fall 60 dB under the peak, measured as the last frame still above it. */
    private fun decaySeconds(x: FloatArray): Float {
        val peak = x.maxOf { abs(it) }
        var last = 0
        for (i in x.indices) if (abs(x[i]) > peak * 1e-3f) last = i
        return last.toFloat() / rate
    }

    /** Renders are pure, so the tests share them: each (kit, pad, velocity, take) is rendered once. */
    private fun rendered(kit: Kit, role: Int, velocity: Float, take: Int): Snip =
        renders.getOrPut(listOf(kit, role, velocity, take)) { CypherKits.render(kit, role, velocity, take) }

    private companion object {
        val renders = java.util.concurrent.ConcurrentHashMap<List<Any>, Snip>()
    }

    private fun subs(kit: Kit) = (0 until CypherKits.HITS).filter { CypherKits.muteGroup(kit, it) == CypherKits.SUB_GROUP }

    /**
     * A sub's pitch over a window, from its zero crossings: a sub is a sine, and the autocorrelation
     * detector's 40 Hz floor loses the lowest roots. [from] and [seconds] in seconds.
     */
    private fun subHz(x: FloatArray, from: Float, seconds: Float): Float {
        val a = (from * rate).toInt()
        val b = min(x.size, ((from + seconds) * rate).toInt())
        var crossings = 0
        var firstCross = -1
        var lastCross = -1
        for (i in a + 1 until b) if ((x[i - 1] < 0f) != (x[i] < 0f)) { crossings++; if (firstCross < 0) firstCross = i; lastCross = i }
        assertTrue(crossings >= 4, "too few cycles between $from and ${from + seconds} s")
        return (crossings - 1) / 2f * rate / (lastCross - firstCross)
    }

    private fun subMacros(kit: Kit, role: Int): Map<String, Float> {
        // The authored controls, read back from the part's own strike is not possible; the kits author
        // every sub through KitStrikes.sub, so TUNE/SLIDE/WHEN/DECAY are recovered from the dry render's
        // behaviour in the tests below instead. This helper names the pads only.
        return Thump.defaults(ThumpVoice.SUB)
    }

    @Test fun `every pad renders the same bytes twice`() {
        for (kit in Kit.entries) for (role in 0 until CypherKits.PADS) for (v in listOf(.3f, 1f)) {
            assertContentEquals(rendered(kit, role, v, 1).samples, CypherKits.render(kit, role, v, 1).samples, "${kit.name} role $role v $v")
        }
    }

    @Test fun `every pad is finite bounded centred and ends in silence`() {
        for (kit in Kit.entries) for (role in 0 until CypherKits.PADS) for (v in CypherKits.velocities) {
            val x = rendered(kit, role, v, 0).samples
            val what = "${kit.name} role $role v $v"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val peak = x.maxOf { abs(it) }
            assertTrue(peak <= .98f + 1e-6f && peak > .2f, "$what peak $peak")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-4f, "$what tail")
        }
    }

    @Test fun `every zone is levelled to one peak and no take runs far from it`() {
        for (kit in Kit.entries) for (role in 0 until CypherKits.PADS) for (v in CypherKits.velocities) {
            assertEquals(CypherKits.PAD_PEAK, rendered(kit, role, v, 0).peak(), .005f, "${kit.name} role $role v $v")
            for (take in 1 until CypherKits.TAKES) {
                val peak = rendered(kit, role, v, take).peak()
                assertTrue(peak in .6f..(.98f + 1e-6f), "${kit.name} role $role v $v take $take peak $peak")
            }
        }
    }

    @Test fun `the slide reads and the root never moves`() {
        // The before-window starts 100 ms in, past the settle; a quarter second so the detector can call a
        // note near its 40 Hz floor. The after-window starts where the slide has had three time constants.
        for (kit in Kit.entries) for (role in subs(kit)) {
            val roots = mutableListOf<Float>()
            for (v in CypherKits.velocities) for (take in 0 until CypherKits.TAKES) {
                roots += subHz(CypherKits.dry(kit, role, v, take).samples, .10f, .12f)
            }
            assertTrue(cents(roots.max(), roots.min()) < 10.0, "${kit.name} role $role: roots $roots")
        }
        // The named slide pads move by their authored interval (95 percent of it after three time constants).
        val expected = mapOf(
            (Kit.TRAP to 6) to -500.0, (Kit.TRAP to 7) to 1200.0,
            (Kit.DRILL to 5) to -700.0, (Kit.DRILL to 6) to 1200.0, (Kit.DRILL to 7) to -500.0,
        )
        for ((where, target) in expected) {
            val (kit, role) = where
            val s = CypherKits.dry(kit, role, 1f, 0)
            val root = subHz(s.samples, .10f, .12f)
            val tau = .08f + .10f * abs(target.toFloat()) / 1200f
            // Where the slide starts is authored per pad; read it as the first 0.25 s window whose pitch has
            // left the root by more than a quarter of the way, then measure three time constants later.
            var from = .10f
            while (from < s.frameCount / rate.toFloat() - .3f) {
                if (abs(cents(subHz(s.samples, from, .06f), root)) > abs(target) * .25) break
                from += .02f
            }
            val after = subHz(s.samples, min(from + 3f * tau, s.frameCount / rate.toFloat() - .13f), .12f)
            assertEquals(target * .95, cents(after, root), 80.0, "${kit.name} role $role moved ${cents(after, root)} cents")
        }
        // The straight subs do not move.
        for ((kit, role) in listOf(Kit.TRAP to 4, Kit.TRAP to 5, Kit.DRILL to 4)) {
            val s = CypherKits.dry(kit, role, 1f, 0)
            val a = subHz(s.samples, .10f, .12f)
            val b = subHz(s.samples, s.frameCount / rate.toFloat() - .35f, .12f)
            assertEquals(0.0, cents(b, a), 15.0, "${kit.name} role $role drifted")
        }
    }

    @Test fun `every sub puts part of itself where a phone plays`() {
        for (kit in Kit.entries) for (role in subs(kit)) {
            val share = bandShare(rendered(kit, role, 1f, 0).samples, 150f, 400f)
            assertTrue(share > .01, "${kit.name} role $role: $share of the drum between 150 and 400 Hz")
        }
    }

    @Test fun `the hats obey the ratchet rule`() {
        // A hat whose decay to -60 dB is at most 1.5 times the shortest period in its kit's roll keeps a 35 dB
        // dip between hits (prototype/cypher, Python); past four times it is a buzz. The closed hat is pad 2.
        for (kit in Kit.entries) {
            val period = assertNotNull(CypherKits.shortestPeriod(kit, 2), "${kit.name}: the hat never rolls")
            for (take in 0 until CypherKits.TAKES) {
                val t60 = decaySeconds(CypherKits.dry(kit, 2, 1f, take).samples)
                assertTrue(t60 <= 1.5f * period + .004f, "${kit.name} hat take $take decays in $t60 s against a ${period} s period")
            }
        }
        // And on the TRAP roll itself, the dip between consecutive 1/64 hits on a 2 ms envelope is at least 20 dB.
        val roll = rendered(Kit.TRAP, 14, 1f, 0).samples
        val beat = rate * 60.0 / Kit.TRAP.bpm
        val period = beat / 16
        var worst = 0.0
        for (i in 0 until 15) {
            val a = (3 * beat + i * period).toInt()
            val b = (3 * beat + (i + 1) * period).toInt()
            var peak = 0.0
            var trough = Double.MAX_VALUE
            var j = a
            while (j + frames(2f) <= b) {
                val level = rms(roll, j, j + frames(2f))
                peak = max(peak, level)
                if (j >= a + (b - a) / 2) trough = min(trough, level)
                j += frames(2f)
            }
            worst = min(worst, db(trough) - db(peak))
        }
        assertTrue(worst < -20.0, "the 1/64 roll's worst dip is $worst dB")
    }

    @Test fun `a phrase prints only the parts it names`() {
        // The drill snare bar sends its snare and clap to the room and keeps the kick and hats dry: the gate
        // is keyed by the snare, not by every hat, so the room opens once per backbeat and shuts between.
        val phrase = CypherKits.phrases(Kit.DRILL)[0]
        assertEquals(setOf(1, 8), phrase.printed)
        val stages = assertNotNull(CypherKits.stages(Kit.DRILL, 14, 1f, 0))
        val printedNotes = phrase.notes.filter { it.part in phrase.printed!! }.map { Cypher.startFrame(it, Kit.DRILL.bpm) }.sorted()
        assertEquals(printedNotes.size, stages.attacks.size, "attacks ${stages.attacks} against printed notes $printedNotes")
        // Between the clap pickup before beat three and the snare on the and of four the room shuts: 250 ms after
        // the snare on three its hold (140 ms) and close (60 ms) are over and the gate is at zero.
        val snareOnThree = Cypher.startFrame(Cypher.Note(2.0, 1, 1f), Kit.DRILL.bpm)
        assertEquals(0f, stages.gate[snareOnThree + frames(250f)], 1e-6f)
        // And the printed notes' room plus the dry notes is what the pad plays.
        assertTrue(stages.out.size >= CypherKits.dryRaw(Kit.DRILL, 14, 1f, 0).size)
    }

    @Test fun `a phrase lands where it was written`() {
        // On the drum phrase, GATECRASH's attack key hears one attack per distinct note time (notes closer
        // than its 12 ms re-arm merge), each within 2 ms of the authored start.
        run {
            val phrase = CypherKits.phrases(Kit.DRILL)[0]
            val starts = phrase.notes.map { Cypher.startFrame(it, Kit.DRILL.bpm) }.sorted()
            val distinct = mutableListOf<Int>()
            for (s in starts) if (distinct.isEmpty() || s - distinct.last() > frames(12f)) distinct += s
            val key = Gatecrash.process(CypherKits.dryRaw(Kit.DRILL, 14, 1f, 0), Gatecrash.Space(Gatecrash.Mode.STONE)).attacks
            assertEquals(distinct.size, key.size, "attacks $key against notes $distinct")
            for ((heard, written) in key.zip(distinct)) assertTrue(abs(heard - written) <= frames(2f), "$heard against $written")
        }
        // The key cannot hear a sub landing on a ringing sub (it wants the level to lead its held peak by
        // 1.7, and a sub is already at full level). On every phrase, every written note is instead an
        // onset: the 3 ms after its start are louder than the 3 ms before it. The 1/64 roll is held by the
        // ratchet test instead.
        for (kit in Kit.entries) for (role in CypherKits.HITS until CypherKits.PADS) {
            if (kit == Kit.TRAP && role == 14) continue
            val phrase = CypherKits.phrases(kit)[role - CypherKits.HITS]
            val x = CypherKits.dryRaw(kit, role, 1f, 0)
            for (note in phrase.notes) {
                val start = Cypher.startFrame(note, kit.bpm)
                val before = rms(x, start - frames(3f), start)
                val after = rms(x, start, start + frames(3f))
                assertTrue(after > before * 1.2, "${kit.name} role $role: no onset at beat ${note.beat} ($before before, $after after)")
            }
        }
    }

    @Test fun `a note's offset moves it by that many milliseconds and swing moves only the off-beats`() {
        val bpm = 140
        val on = Cypher.Note(1.0, 0, 1f)
        val late = Cypher.Note(1.0, 0, 1f, offsetMs = 18f)
        assertTrue(abs(frames(18f) - (Cypher.startFrame(late, bpm) - Cypher.startFrame(on, bpm))) <= 1)
        val early = Cypher.Note(0.0, 0, 1f, offsetMs = -20f)
        assertEquals(0, Cypher.startFrame(early, bpm))
        val grid = List(8) { Cypher.Note(it * .25, 0, 1f) }
        val swung = Cypher.swung(grid, .14f, bpm)
        for ((i, n) in swung.withIndex()) assertEquals(if (i % 2 == 1) .14f * 60_000f / bpm / 4 else 0f, n.offsetMs, 1e-3f, "step $i")
        val roll = Cypher.ratchet(2, 3.0, 1.0 / 16, 16, .85f, 1f, riseSemis = 4f)
        assertEquals(16, roll.size)
        assertEquals(0f, roll.first().tuneSemis)
        assertEquals(4f, roll.last().tuneSemis, 1e-6f)
        assertEquals(3.0 + 15.0 / 16, roll.last().beat, 1e-9)
    }

    @Test fun `a choked part stops where the next one starts`() {
        // Two subs a quarter beat apart: with the choke the first is silent by the time the second lands.
        val bpm = Kit.TRAP.bpm
        val first = Cypher.Note(0.0, 4, 1f)
        val second = Cypher.Note(0.25, 4, 1f)
        val strike = CypherKits.parts(Kit.TRAP)[4].strike
        val alone = KitStrikes.struck(strike, 1f, 0, CypherKits.touch(1f))
        val start2 = Cypher.startFrame(second, bpm)
        // The first sub, on its own, is still well above silence at the second's start.
        assertTrue(rms(alone, start2 - frames(2f), start2) > rms(alone, 0, alone.size) * .1)
        val choked = CypherKits.phraseRawForTest(Kit.TRAP, CypherKits.Phrase("probe", null, listOf(first, second), choke = setOf(4)), 1f, 0)
        val open = CypherKits.phraseRawForTest(Kit.TRAP, CypherKits.Phrase("probe", null, listOf(first, second)), 1f, 0)
        // Just before the second note the choked sum is the fade's tail, the open sum is the first sub.
        assertTrue(rms(choked, start2 - frames(1f), start2) < rms(open, start2 - frames(1f), start2) * .3)
        assertTrue(rms(choked, start2 - frames(8f), start2 - frames(6f)) > rms(open, start2 - frames(8f), start2 - frames(6f)) * .5)
    }

    @Test fun `every room carries its authored energy against the drum and none buries it`() {
        for (kit in Kit.entries) for (role in 0 until CypherKits.PADS) {
            val space = CypherKits.spaceOf(kit, role) ?: continue
            val stages = assertNotNull(CypherKits.stages(kit, role, 1f, 0))
            // Against what the space hears: the hit, or the notes a phrase prints.
            val measured = 10 * log10(energy(stages.ret) / energy(CypherKits.printedRaw(kit, role, 1f, 0)))
            assertEquals(space.roomDb.toDouble(), measured, 0.3, "${kit.name} role $role room energy")
            assertTrue(space.roomDb <= 4f, "${kit.name} role $role is authored at ${space.roomDb} dB")
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (kit in Kit.entries) for (role in 0 until CypherKits.PADS) {
            val hard = rendered(kit, role, 1f, 0).samples
            val soft = rendered(kit, role, .3f, 0).samples
            val distance = shapeDistance(hard, soft)
            assertTrue(distance > .05, "${kit.name} role $role: soft is a scaled copy of hard ($distance)")
        }
        // On single hits the soft strike is also duller: less top on a drum, less drive on a sub.
        for (kit in Kit.entries) for (role in 0 until CypherKits.HITS) {
            val hard = CypherKits.dry(kit, role, 1f, 0).samples
            val soft = CypherKits.dry(kit, role, .3f, 0).samples
            if (role in subs(kit)) assertTrue(bandShare(soft, 150f, 400f) < bandShare(hard, 150f, 400f), "${kit.name} sub $role: soft is not cleaner")
            else assertTrue(brightness(soft) < brightness(hard), "${kit.name} role $role: soft is not duller than hard")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (kit in Kit.entries) for (role in 0 until CypherKits.PADS) for (v in CypherKits.velocities) {
            val takes = List(CypherKits.TAKES) { rendered(kit, role, v, it).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val distance = shapeDistance(takes[a], takes[b])
                val floor = if (role in subs(kit)) .05 else .005
                assertTrue(distance > floor, "${kit.name} role $role v $v: takes $a and $b differ by only $distance")
            }
        }
    }

    @Test fun `the two kits are two kits`() {
        val names = Kit.entries.map { CypherKits.roles(it) }
        for (roles in names) { assertEquals(CypherKits.PADS, roles.size); assertEquals(roles.size, roles.toSet().size) }
        assertEquals(2, Kit.entries.map { it.bpm }.toSet().size)
        // At least six pads are differently named parts, the main snare is printed differently, and the hats
        // roll on different grids.
        assertTrue((names[0] zip names[1]).count { (a, b) -> a != b } >= 6)
        assertNull(CypherKits.spaceOf(Kit.TRAP, 1))
        assertNotNull(CypherKits.spaceOf(Kit.DRILL, 1))
        assertTrue(assertNotNull(CypherKits.shortestPeriod(Kit.TRAP, 2)) < assertNotNull(CypherKits.shortestPeriod(Kit.DRILL, 2)))
        // The kick and the main snare are different drums in each kit, dry, before any space.
        for (role in 0..1) {
            val drums = Kit.entries.map { CypherKits.dry(it, role, 1f, 0).samples }
            assertTrue(shapeDistance(drums[0], drums[1]) > .5, "role $role: the kits share a drum")
        }
        // No hat and no sub has a space in any kit.
        for (kit in Kit.entries) for (role in listOf(2, 3) + subs(kit)) assertNull(CypherKits.spaceOf(kit, role), "${kit.name} role $role")
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (kit in Kit.entries) {
            val pads = CypherKits.build(kit)
            assertEquals(CypherKits.PADS, pads.size)
            assertEquals(listOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN), pads.take(4).map { it.drumClass })
            assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), pads.takeLast(2).map { it.drumClass })
            assertEquals(4, subs(kit).size, "${kit.name} has four subs")
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == CypherKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(CypherKits.roles(kit)[role], pad.displayName)
                assertEquals("CYPHER", pad.source["engine"])
                assertEquals(kit.name, pad.source["kit"])
                assertEquals(CypherKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(Gatecrash.VERSION.toString(), pad.source["stageVersion"])
                assertEquals(Cypher.VERSION.toString(), pad.source["phraseVersion"])
                val hat = pad.drumClass == DrumClass.HAT_CLOSED || pad.drumClass == DrumClass.HAT_OPEN
                val expectedGroup = when {
                    hat -> CypherKits.HAT_GROUP
                    role >= CypherKits.HITS -> CypherKits.PHRASE_GROUP
                    role in subs(kit) -> CypherKits.SUB_GROUP
                    else -> 0
                }
                assertEquals(expectedGroup, pad.muteGroup, "${kit.name} role $role choke")
                if (role >= CypherKits.HITS) assertTrue(pad.snip.frameCount > 1.5 * rate)
                else assertTrue(pad.snip.frameCount < 1.5 * rate, "${kit.name} role $role is ${pad.snip.frameCount} frames")
                // A sub is authored KICK; the classifier may read it as TONAL. Recorded, not hidden.
                if (role in subs(kit)) {
                    assertEquals(DrumClass.KICK, pad.drumClass)
                    val read = Classifier.classify(pad.snip).drumClass
                    assertTrue(read == DrumClass.KICK || read == DrumClass.TONAL, "${kit.name} role $role reads $read")
                }
            }
        }
    }

    @Test fun `no pad or kit name borrows a maker's name`() {
        for (kit in Kit.entries) for (name in CypherKits.roles(kit) + kit.title + kit.id) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
            assertFalse(Regex("(?i)griselda|metro|luger|zaytoven|premier|alchemist|chop|melo").containsMatchIn(name), name)
        }
    }
}
