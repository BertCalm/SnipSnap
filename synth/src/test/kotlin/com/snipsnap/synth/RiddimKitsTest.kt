package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.RiddimKits.Kit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The RIDDIM kits' grids, on kits built by the engines themselves: CALABASH's kit probes, for a kit of two engines. */
class RiddimKitsTest {

    private companion object {
        /** Each kit is built once (192 renders, in parallel) and every test reads it. */
        val built = java.util.concurrent.ConcurrentHashMap<Kit, List<ArrangedPad>>()
        fun kit(k: Kit): List<ArrangedPad> = built.getOrPut(k) { RiddimKits.build(k) }
    }

    private fun take(k: Kit, role: Int, z: Int, t: Int): FloatArray = kit(k)[role].velocityTakes[z][t].samples

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

    @Test fun `a built kit is the same bytes as its pads rendered one by one`() {
        for ((k, role) in listOf(Kit.CLAVE_DANCEHALL to 0, Kit.DEMBOW to 12, Kit.BOOM_RIDDIM to 15)) {
            for ((z, v) in RiddimKits.velocities.withIndex()) for (t in 0 until RiddimKits.TAKES) {
                assertContentEquals(take(k, role, z, t), RiddimKits.render(k, role, v, t).samples, "${k.name} role $role v $v take $t")
            }
        }
    }

    @Test fun `every take is finite bounded centred and ends in silence`() {
        for (k in Kit.entries) for (role in 0 until RiddimKits.PADS) for (z in RiddimKits.velocities.indices) for (t in 0 until RiddimKits.TAKES) {
            val x = take(k, role, z, t)
            val what = "${k.name} role $role zone $z take $t"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val p = peak(x)
            assertTrue(p <= RiddimKits.PAD_PEAK + 1e-6f && p > .2f, "$what peak $p")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-3f, "$what tail ${x.last()}")
        }
    }

    @Test fun `every zone's takes are equally loud and its highest peak sits at the pad peak`() {
        for (k in Kit.entries) for (role in 0 until RiddimKits.PADS) for (z in RiddimKits.velocities.indices) {
            val pad = RiddimKits.pads(k)[role]
            val window = if (pad.isPhrase) Int.MAX_VALUE else (RiddimKits.LEVEL_WINDOW_S * Riddim.RATE).toInt()
            val takes = List(RiddimKits.TAKES) { take(k, role, z, it) }
            val what = "${k.name} role $role zone $z"
            assertEquals(RiddimKits.PAD_PEAK, takes.maxOf { peak(it) }, .001f, what)
            val levels = takes.map { 20 * log10(RiddimKits.loudness(it, window)) }
            assertTrue(levels.max() - levels.min() < .1, "$what loudness spread ${levels.max() - levels.min()} dB")
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (k in Kit.entries) for (role in 0 until RiddimKits.PADS) {
            val d = shapeDistance(take(k, role, 2, 0), take(k, role, 0, 0))
            assertTrue(d > .05, "${k.name} role $role: soft is a scaled copy of hard ($d)")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (k in Kit.entries) for (role in 0 until RiddimKits.PADS) for (z in RiddimKits.velocities.indices) {
            val takes = List(RiddimKits.TAKES) { take(k, role, z, it) }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val d = shapeDistance(takes[a], takes[b])
                assertTrue(d > .005, "${k.name} role $role zone $z: takes $a and $b differ by only $d")
            }
        }
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (k in Kit.entries) {
            val pads = kit(k)
            assertEquals(RiddimKits.PADS, pads.size)
            assertEquals(DrumClass.KICK, pads[0].drumClass, "${k.name} A01")
            for ((role, pad) in pads.withIndex()) {
                val authored = RiddimKits.pads(k)[role]
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == RiddimKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(authored.name, pad.displayName)
                assertEquals(authored.drumClass, pad.drumClass)
                assertEquals(authored.engine, pad.source["engine"])
                assertEquals("RIDDIM", pad.source["family"])
                assertEquals(k.name, pad.source["kit"])
                assertEquals(authored.voiceName, pad.source["voice"])
                assertEquals(RiddimKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(RiddimKits.muteGroup(k, role), pad.muteGroup)
                assertEquals(authored.articulation, pad.source["articulation"], "${k.name} role $role")
                val expected = when {
                    role == 15 -> if (k == Kit.BOOM_RIDDIM || k == Kit.BAILE_FUNK) "riser" else "roll"
                    k == Kit.SABAR_SWING && role == 10 -> "riser"         // CALABASH's talking call, a phrase there too
                    else -> "finite"
                }
                assertEquals(expected, authored.articulation, "${k.name} role $role")
            }
            // The hats choke each other on every kit.
            assertTrue(pads[2].drumClass == DrumClass.HAT_CLOSED && pads[3].drumClass == DrumClass.HAT_OPEN, k.name)
            assertTrue(pads[2].muteGroup != 0 && pads[2].muteGroup == pads[3].muteGroup, "${k.name}: the hats do not choke")
        }
        // Boom Riddim's six booms are one bass voice: each cuts the last.
        val booms = RiddimKits.pads(Kit.BOOM_RIDDIM).indices.filter { RiddimKits.pads(Kit.BOOM_RIDDIM)[it].riddim in setOf(RiddimVoice.BOOM, RiddimVoice.SLIDE) }
        assertEquals(listOf(0, 5, 6, 7, 8, 9), booms)
        assertEquals(1, booms.map { RiddimKits.muteGroup(Kit.BOOM_RIDDIM, it) }.toSet().size)
    }

    @Test fun `drums and percussion share every kit`() {
        for (k in Kit.entries) {
            val engines = RiddimKits.pads(k).map { it.engine }.toSet()
            assertEquals(setOf("RIDDIM", "CALABASH"), engines, k.name)
        }
    }

    @Test fun `every kit ends in its roll or riser at its own tempo`() {
        for (k in Kit.entries) {
            val pad = RiddimKits.pads(k)[15]
            assertTrue(pad.isPhrase, k.name)
            assertEquals(k.bpm.toDouble(), Riddim.bpmOf(pad.macros.getValue("SPEED")), 1e-3, k.name)
            // One bar plus 0.6 s of ring; SPEED is a Float, so the tempo it carries can land a frame either side.
            assertEquals((4 * 60.0 / k.bpm + 0.6) * Riddim.RATE, kit(k)[15].snip.samples.size.toDouble(), 1.0, k.name)
        }
    }

    @Test fun `the six kits are six kits`() {
        assertEquals(Kit.entries.size, Kit.entries.map { it.bpm }.toSet().size, "each kit has its own tempo")
        for (k in Kit.entries) assertEquals(RiddimKits.PADS, RiddimKits.pads(k).map { it.name }.toSet().size, "${k.name}: sixteen different pads")
        // As approved: Clave Dancehall and Boom Riddim share the plain open hat and clap, Clave Dancehall and Dembow
        // the shaker and the cowbell, Dembow and Sabar Swing CALABASH's tama. Every other slot is another voice or
        // other settings, and at least 13 of 16 differ in shape.
        val shared = mapOf(
            setOf(Kit.CLAVE_DANCEHALL, Kit.BOOM_RIDDIM) to setOf(3, 4),
            setOf(Kit.CLAVE_DANCEHALL, Kit.DEMBOW) to setOf(7, 10),
            setOf(Kit.DEMBOW, Kit.SABAR_SWING) to setOf(12),
        ).withDefault { emptySet() }
        for (a in Kit.entries.indices) for (b in a + 1 until Kit.entries.size) {
            val ka = Kit.entries[a]; val kb = Kit.entries[b]
            val same = (0 until RiddimKits.PADS).filter { role ->
                val pa = RiddimKits.pads(ka)[role]; val pb = RiddimKits.pads(kb)[role]
                pa.engine == pb.engine && pa.voiceName == pb.voiceName && pa.macros == pb.macros
            }.toSet()
            assertEquals(shared.getValue(setOf(ka, kb)), same, "${ka.name} and ${kb.name}")
            val distinct = (0 until RiddimKits.PADS).count { shapeDistance(take(ka, it, 2, 0), take(kb, it, 2, 0)) > .05 }
            assertTrue(distinct >= 13, "${ka.name} and ${kb.name}: only $distinct slots differ")
        }
    }
}
