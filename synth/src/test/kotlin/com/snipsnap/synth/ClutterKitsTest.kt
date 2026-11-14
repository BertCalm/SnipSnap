package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.synth.ClutterKits.Kit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The CLUTTER genre kits' grids: GATECRASH's and SCUFF's probes on kits rendered by the engine itself. */
class ClutterKitsTest {

    private fun rendered(kit: Kit, role: Int, velocity: Float, take: Int) =
        renders.getOrPut(listOf(kit, role, velocity, take)) { ClutterKits.render(kit, role, velocity, take) }

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
        for (kit in Kit.entries) for (role in 0 until ClutterKits.PADS) {
            assertContentEquals(rendered(kit, role, 1f, 1).samples, ClutterKits.render(kit, role, 1f, 1).samples, "${kit.name} role $role")
        }
    }

    @Test fun `every pad is finite bounded centred and ends in silence`() {
        for (kit in Kit.entries) for (role in 0 until ClutterKits.PADS) for (v in ClutterKits.velocities) {
            val x = rendered(kit, role, v, 0).samples
            val what = "${kit.name} role $role v $v"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val p = peak(x)
            assertTrue(p <= .98f + 1e-6f && p > .2f, "$what peak $p")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-4f, "$what tail")
        }
    }

    @Test fun `every zone's takes are equally loud and its highest peak sits at the pad peak`() {
        val n = (ClutterKits.LEVEL_WINDOW_S * Dsp.RATE).toInt()
        fun rmsDb(x: FloatArray): Double { val m = min(n, x.size); return 10 * kotlin.math.log10((0 until m).sumOf { x[it].toDouble() * x[it] } / m) }
        for (kit in Kit.entries) for (role in 0 until ClutterKits.PADS) for (v in ClutterKits.velocities) {
            val takes = List(ClutterKits.TAKES) { rendered(kit, role, v, it).samples }
            assertEquals(ClutterKits.PAD_PEAK, takes.maxOf { peak(it) }, .005f, "${kit.name} role $role v $v")
            val levels = takes.map { rmsDb(it) }
            assertTrue(levels.max() - levels.min() < .1, "${kit.name} role $role v $v loudness spread ${levels.max() - levels.min()} dB")
        }
    }

    @Test fun `a built kit is the same bytes as its pads rendered one by one`() {
        val pads = ClutterKits.build(Kit.MICRO)
        for (role in listOf(0, 6, 14)) for ((z, v) in ClutterKits.velocities.withIndex()) for (t in 0 until ClutterKits.TAKES) {
            assertContentEquals(rendered(Kit.MICRO, role, v, t).samples, pads[role].velocityTakes[z][t].samples, "role $role v $v take $t")
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (kit in Kit.entries) for (role in 0 until ClutterKits.PADS) {
            val d = shapeDistance(rendered(kit, role, 1f, 0).samples, rendered(kit, role, .35f, 0).samples)
            assertTrue(d > .05, "${kit.name} role $role: soft is a scaled copy of hard ($d)")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (kit in Kit.entries) for (role in 0 until ClutterKits.PADS) for (v in ClutterKits.velocities) {
            val takes = List(ClutterKits.TAKES) { rendered(kit, role, v, it).samples }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val d = shapeDistance(takes[a], takes[b])
                assertTrue(d > .005, "${kit.name} role $role v $v: takes $a and $b differ by only $d")
            }
        }
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (kit in Kit.entries) {
            val pads = ClutterKits.build(kit)
            assertEquals(ClutterKits.PADS, pads.size)
            assertEquals(listOf(DrumClass.KICK, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.KICK), listOf(pads[0], pads[2], pads[3], pads[4]).map { it.drumClass })
            assertEquals(listOf(DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN), listOf(pads[6], pads[7]).map { it.drumClass })
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == ClutterKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(ClutterKits.pads(kit)[role].name, pad.displayName)
                assertEquals("CLUTTER", pad.source["engine"])
                assertEquals(kit.name, pad.source["kit"])
                assertEquals(ClutterKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(Clutter.MODEL_VERSION.toString(), pad.source["model"])
                assertEquals(when (role) { 2, 3 -> 1; 6, 7 -> 2; else -> 0 }, pad.muteGroup, "${kit.name} role $role")
            }
        }
    }

    @Test fun `the four kits are four kits`() {
        assertEquals(Kit.entries.size, Kit.entries.map { it.bpm }.toSet().size, "each kit has its own tempo")
        for (kit in Kit.entries) {
            val names = ClutterKits.pads(kit).map { it.name }
            assertEquals(ClutterKits.PADS, names.toSet().size, "${kit.name}: sixteen different pads")
        }
        // No pad is the same object at the same settings in two kits, and the same slot differs in shape.
        for (a in Kit.entries.indices) for (b in a + 1 until Kit.entries.size) {
            val ka = Kit.entries[a]; val kb = Kit.entries[b]
            for (role in 0 until ClutterKits.PADS) {
                val pa = ClutterKits.pads(ka)[role]; val pb = ClutterKits.pads(kb)[role]
                assertFalse(pa.voice == pb.voice && pa.macros == pb.macros, "${ka.name} and ${kb.name} share role $role")
                val d = shapeDistance(rendered(ka, role, 1f, 0).samples, rendered(kb, role, 1f, 0).samples)
                assertTrue(d > .05, "${ka.name} and ${kb.name} role $role are one sound ($d)")
            }
        }
    }

    @Test fun `no kit, pad or object is named after a maker`() {
        val names = Kit.entries.flatMap { k -> listOf(k.title) + ClutterKits.pads(k).map { it.name } } + ClutterVoice.entries.map { it.name }
        for (name in names) assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
    }
}
