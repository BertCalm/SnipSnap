package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.synth.ServoKits.Kit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The SERVO kits' grids: GATECRASH's probes on kits rendered by the engine itself. */
class ServoKitsTest {
    private val rate = Dsp.RATE

    private fun rendered(kit: Kit, role: Int, velocity: Float, take: Int) =
        renders.getOrPut(listOf(kit, role, velocity, take)) { ServoKits.render(kit, role, velocity, take) }

    private companion object {
        val renders = java.util.concurrent.ConcurrentHashMap<List<Any>, com.snipsnap.audio.Snip>()
    }

    /** Distance between two renders once level is taken out: the same audio measures 0. */
    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        val ea = sqrt(a.sumOf { it.toDouble() * it })
        val eb = sqrt(b.sumOf { it.toDouble() * it })
        var d = 0.0
        for (i in 0 until n) { val v = a[i] / ea - b[i] / eb; d += v * v }
        return sqrt(d)
    }

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    @Test fun `every pad renders the same bytes twice`() {
        for (kit in Kit.entries) for (role in 0 until ServoKits.PADS) {
            assertContentEquals(rendered(kit, role, 1f, 1).samples, ServoKits.render(kit, role, 1f, 1).samples, "${kit.name} role $role")
        }
    }

    @Test fun `every pad is finite bounded centred and ends in silence`() {
        for (kit in Kit.entries) for (role in 0 until ServoKits.PADS) for (v in ServoKits.velocities) {
            val x = rendered(kit, role, v, 0).samples
            val what = "${kit.name} role $role v $v"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val p = peak(x)
            assertTrue(p <= .98f + 1e-6f && p > .2f, "$what peak $p")
            // The house bound is .005 to .01.
            assertTrue(abs(x.average()) < .008, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-4f, "$what tail")
        }
    }

    @Test fun `every zone's takes are matched in loudness and the loudest peaks at the pad peak`() {
        val n = (ServoKits.LEVEL_WINDOW_S * rate).toInt()
        fun rms(x: FloatArray): Double { val m = min(n, x.size); return sqrt((0 until m).sumOf { x[it].toDouble() * x[it] } / m) }
        for (kit in Kit.entries) for (role in 0 until ServoKits.PADS) for (v in ServoKits.velocities) {
            val takes = List(ServoKits.TAKES) { rendered(kit, role, v, it).samples }
            val what = "${kit.name} role $role v $v"
            assertEquals(ServoKits.PAD_PEAK, takes.maxOf { peak(it) }, .01f, "$what loudest peak")
            val levels = takes.map(::rms)
            // The finish stops a take 80 dB under its peak, so a matched window can differ by its last frames.
            assertTrue(levels.max() / levels.min() < 1.03, "$what: takes differ in loudness, $levels")
            for ((t, x) in takes.withIndex()) assertTrue(peak(x) > .3f, "$what take $t peak ${peak(x)}")
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (kit in Kit.entries) for (role in 0 until ServoKits.PADS) {
            val d = shapeDistance(rendered(kit, role, 1f, 0).samples, rendered(kit, role, .35f, 0).samples)
            assertTrue(d > .05, "${kit.name} role $role: soft is a scaled copy of hard ($d)")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (kit in Kit.entries) for (role in 0 until ServoKits.PADS) for (v in ServoKits.velocities) {
            val takes = List(ServoKits.TAKES) { rendered(kit, role, v, it).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val d = shapeDistance(takes[a], takes[b])
                assertTrue(d > .005, "${kit.name} role $role v $v: takes $a and $b differ by only $d")
            }
        }
    }

    @Test fun `the phrases share one rail and the rail changes them`() {
        for (kit in Kit.entries) for (role in ServoKits.HITS until ServoKits.PADS) {
            val own = ServoKits.phraseRaw(kit, role, 1f, 0, shared = false)
            val shared = ServoKits.phraseRaw(kit, role, 1f, 0, shared = true)
            assertEquals(own.size, shared.size)
            assertTrue(shapeDistance(own, shared) > .05, "${kit.name} role $role: the shared rail did nothing")
        }
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (kit in Kit.entries) {
            val pads = ServoKits.build(kit)
            assertEquals(ServoKits.PADS, pads.size)
            assertEquals(listOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN), pads.take(4).map { it.drumClass })
            assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), pads.takeLast(2).map { it.drumClass })
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == ServoKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(ServoKits.roles(kit)[role], pad.displayName)
                assertEquals("SERVO", pad.source["engine"])
                assertEquals(kit.name, pad.source["kit"])
                assertEquals(ServoKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(Servo.MODEL_VERSION.toString(), pad.source["model"])
                val hat = pad.drumClass == DrumClass.HAT_CLOSED || pad.drumClass == DrumClass.HAT_OPEN
                assertEquals(if (role >= ServoKits.HITS) 3 else if (role == 2 || role == 3) 1 else if (hat) 2 else 0, pad.muteGroup)
                if (role >= ServoKits.HITS) assertTrue(pad.snip.frameCount > 1.5 * rate, "${kit.name} role $role is a loop")
                else assertTrue(pad.snip.frameCount < 1.5 * rate, "${kit.name} role $role is ${pad.snip.frameCount} frames")
            }
        }
    }

    @Test fun `the three kits are different kits`() {
        val roles = Kit.entries.map { ServoKits.roles(it) }
        for (r in roles) { assertEquals(ServoKits.PADS, r.size); assertEquals(r.size, r.toSet().size) }
        assertEquals(Kit.entries.size, Kit.entries.map { it.bpm }.toSet().size, "each kit has its own tempo")
        assertEquals(Kit.entries.size, Kit.entries.map { it.railGrowl }.toSet().size, "each kit has its own rail")
        // Pad for pad no kit is another at other settings: every role differs in shape, not only in level.
        for (a in Kit.entries) for (b in Kit.entries) if (a.ordinal < b.ordinal) for (role in 0 until ServoKits.PADS) {
            val d = shapeDistance(rendered(a, role, 1f, 0).samples, rendered(b, role, 1f, 0).samples)
            assertTrue(d > .05, "role $role: ${b.name} is ${a.name} ($d)")
        }
        // The kits lead with different readouts: JIT is the shortest kit, and its kick is tuned above ELECTRO's boom.
        fun meanFrames(kit: Kit) = (0 until ServoKits.HITS).map { rendered(kit, it, 1f, 0).frameCount }.average()
        assertTrue(meanFrames(Kit.JIT) < meanFrames(Kit.ELECTRO) && meanFrames(Kit.JIT) < meanFrames(Kit.NEURO), "JIT is not the shortest kit")
        assertTrue(rendered(Kit.JIT, 0, 1f, 0).frameCount < rendered(Kit.ELECTRO, 0, 1f, 0).frameCount, "JIT's kick is not shorter than ELECTRO's boom")
        val m = { kit: Kit, role: Int -> ServoKits.parts(kit)[role].macros }
        assertTrue(m(Kit.JIT, 0).getValue("TUNE") > m(Kit.ELECTRO, 0).getValue("TUNE"))
        assertTrue((0 until ServoKits.HITS).all { m(Kit.NEURO, it).getValue("GROWL") > m(Kit.ELECTRO, it).getValue("GROWL") }, "NEURO's rail rings more on every pad")
        assertTrue((0 until ServoKits.HITS).map { m(Kit.JIT, it).getValue("HEAT") }.average() > (0 until ServoKits.HITS).map { m(Kit.ELECTRO, it).getValue("HEAT") }.average() + .2)
    }

    @Test fun `no pad or kit name borrows a maker's name`() {
        for (kit in Kit.entries) for (name in ServoKits.roles(kit) + kit.title + kit.id) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
        }
    }
}
