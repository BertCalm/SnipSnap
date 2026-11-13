package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.synth.ScuffKits.Kit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The SCUFF kit's grid: GATECRASH's probes on a kit rendered by the engine itself. */
class ScuffKitsTest {
    private val rate = Dsp.RATE

    private fun rendered(kit: Kit, role: Int, velocity: Float, take: Int) =
        renders.getOrPut(listOf(kit, role, velocity, take)) { ScuffKits.render(kit, role, velocity, take) }

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
        for (kit in Kit.entries) for (role in 0 until ScuffKits.PADS) {
            assertContentEquals(rendered(kit, role, 1f, 1).samples, ScuffKits.render(kit, role, 1f, 1).samples, "${kit.name} role $role")
        }
    }

    @Test fun `every pad is finite bounded centred and ends in silence`() {
        for (kit in Kit.entries) for (role in 0 until ScuffKits.PADS) for (v in ScuffKits.velocities) {
            val x = rendered(kit, role, v, 0).samples
            val what = "${kit.name} role $role v $v"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val p = peak(x)
            assertTrue(p <= .98f + 1e-6f && p > .2f, "$what peak $p")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-4f, "$what tail")
        }
    }

    @Test fun `every zone is levelled to one peak and no take runs far from it`() {
        for (kit in Kit.entries) for (role in 0 until ScuffKits.PADS) for (v in ScuffKits.velocities) {
            assertEquals(ScuffKits.PAD_PEAK, peak(rendered(kit, role, v, 0).samples), .005f, "${kit.name} role $role v $v")
            for (take in 1 until ScuffKits.TAKES) {
                val p = peak(rendered(kit, role, v, take).samples)
                assertTrue(p in .6f..(.98f + 1e-6f), "${kit.name} role $role v $v take $take peak $p")
            }
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (kit in Kit.entries) for (role in 0 until ScuffKits.PADS) {
            val d = shapeDistance(rendered(kit, role, 1f, 0).samples, rendered(kit, role, .3f, 0).samples)
            assertTrue(d > .05, "${kit.name} role $role: soft is a scaled copy of hard ($d)")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (kit in Kit.entries) for (role in 0 until ScuffKits.PADS) for (v in ScuffKits.velocities) {
            val takes = List(ScuffKits.TAKES) { rendered(kit, role, v, it).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val d = shapeDistance(takes[a], takes[b])
                assertTrue(d > .005, "${kit.name} role $role v $v: takes $a and $b differ by only $d")
            }
        }
    }

    @Test fun `the phrases go through one bus and the bus changes them`() {
        for (kit in Kit.entries) for (role in ScuffKits.HITS until ScuffKits.PADS) {
            val dry = ScuffKits.phraseRaw(kit, role, 1f, 0, bus = false)
            val bussed = ScuffKits.phraseRaw(kit, role, 1f, 0, bus = true)
            assertEquals(dry.size, bussed.size)
            assertTrue(shapeDistance(dry, bussed) > .05, "${kit.name} role $role: SLAM on the bus did nothing")
            // The bus squares the sum off: its crest factor falls.
            fun crest(x: FloatArray) = peak(x) / sqrt(x.sumOf { it.toDouble() * it } / x.size)
            assertTrue(crest(bussed) < crest(dry), "${kit.name} role $role: crest ${crest(dry)} -> ${crest(bussed)}")
        }
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (kit in Kit.entries) {
            val pads = ScuffKits.build(kit)
            assertEquals(ScuffKits.PADS, pads.size)
            assertEquals(listOf(DrumClass.KICK, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN), pads.take(4).map { it.drumClass })
            assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), pads.takeLast(2).map { it.drumClass })
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == ScuffKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(ScuffKits.roles(kit)[role], pad.displayName)
                assertEquals("SCUFF", pad.source["engine"])
                assertEquals(kit.name, pad.source["kit"])
                assertEquals(ScuffKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(Scuff.MODEL_VERSION.toString(), pad.source["model"])
                val hat = pad.drumClass == DrumClass.HAT_CLOSED || pad.drumClass == DrumClass.HAT_OPEN
                assertEquals(if (hat) 1 else if (role >= ScuffKits.HITS) 2 else 0, pad.muteGroup)
                if (role >= ScuffKits.HITS) assertTrue(pad.snip.frameCount > 1.5 * rate, "${kit.name} role $role is a loop")
                else assertTrue(pad.snip.frameCount < 1.5 * rate, "${kit.name} role $role is ${pad.snip.frameCount} frames")
            }
        }
    }

    @Test fun `no pad or kit name borrows a maker's name`() {
        for (kit in Kit.entries) for (name in ScuffKits.roles(kit) + kit.title + kit.id) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
        }
    }
}
