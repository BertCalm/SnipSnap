package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.EmberKits.Kit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The EMBER kits' grids: CLUTTER's and SCUFF's probes on kits rendered by the engine itself, plus the percussion
 * gate (every kit carries a roll or riser). Each kit is built once (its pads render in parallel) and the probes
 * read the built grids; a few pads are rendered again one by one to prove the build is the render.
 */
class EmberKitsTest {

    private companion object {
        val built: Map<Kit, List<ArrangedPad>> by lazy { Kit.entries.associateWith { EmberKits.build(it) } }
    }

    private fun grid(kit: Kit, role: Int) = built.getValue(kit)[role].velocityTakes.map { zone -> zone.map { it.samples } }

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

    @Test fun `a built kit is the same bytes as its pads rendered one by one, twice`() {
        for ((kit, role) in listOf(Kit.CLUB to 0, Kit.AFTER_HOURS to 3, Kit.SOUL to 7, Kit.FREE to 14)) {
            for ((z, v) in EmberKits.velocities.withIndex()) for (t in listOf(0, 3)) {
                val one = EmberKits.render(kit, role, v, t).samples
                assertContentEquals(one, grid(kit, role)[z][t], "${kit.name} role $role v $v take $t")
                assertContentEquals(one, EmberKits.render(kit, role, v, t).samples, "${kit.name} role $role v $v take $t again")
            }
        }
    }

    @Test fun `every pad is finite bounded centred and ends in silence`() {
        for (kit in Kit.entries) for (role in 0 until EmberKits.PADS) for ((z, takes) in grid(kit, role).withIndex()) for (x in takes) {
            val what = "${kit.name} role $role zone $z"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val p = peak(x)
            assertTrue(p <= .98f + 1e-6f && p > .2f, "$what peak $p")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-4f, "$what tail")
        }
    }

    @Test fun `every zone's takes are equally loud and its highest peak sits at the pad peak`() {
        for (kit in Kit.entries) for (role in 0 until EmberKits.PADS) {
            val voice = EmberKits.pads(kit)[role].voice
            val n = if (voice in EmberKits.WHOLE) Int.MAX_VALUE else (EmberKits.LEVEL_WINDOW_S * Dsp.RATE).toInt()
            fun rmsDb(x: FloatArray): Double { val m = min(n, x.size); return 10 * log10((0 until m).sumOf { x[it].toDouble() * x[it] } / m) }
            for ((z, takes) in grid(kit, role).withIndex()) {
                assertEquals(EmberKits.PAD_PEAK, takes.maxOf { peak(it) }, .005f, "${kit.name} role $role zone $z")
                val levels = takes.map { rmsDb(it) }
                assertTrue(levels.max() - levels.min() < .1, "${kit.name} role $role zone $z loudness spread ${levels.max() - levels.min()} dB")
            }
        }
    }

    /**
     * The owner's velocity gate asks for a change of strike, not of level. Measured as the house measure does it (the
     * level-matched third-octave spectrogram, EmberTest's bar of 1.8 dB): a waveform distance undersells a drum
     * whose deep tone has the same phase at every velocity (the elbow tom measures 0.049 there, 10 dB here).
     */
    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (kit in Kit.entries) for (role in 0 until EmberKits.PADS) {
            val g = grid(kit, role)
            val voice = EmberKits.pads(kit)[role].voice
            val d = if (voice in EmberKits.WHOLE) EmberMeasures.fullDistanceDb(g.last()[0], g.first()[0]) else EmberMeasures.distanceDb(g.last()[0], g.first()[0])
            assertTrue(d >= 1.8, "${kit.name} role $role: soft against hard only $d dB apart in shape")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (kit in Kit.entries) for (role in 0 until EmberKits.PADS) for ((z, takes) in grid(kit, role).withIndex()) {
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val d = shapeDistance(takes[a], takes[b])
                assertTrue(d > .005, "${kit.name} role $role zone $z: takes $a and $b differ by only $d")
            }
        }
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (kit in Kit.entries) {
            val pads = built.getValue(kit)
            assertEquals(EmberKits.PADS, pads.size)
            assertEquals(DrumClass.KICK, pads[0].drumClass, "${kit.name}: A01 is the kick")
            val hats = pads.indices.filter { pads[it].muteGroup == 1 }
            assertTrue(hats.size >= 2, "${kit.name}: a hi-hat that chokes")
            for (role in hats) assertTrue(pads[role].drumClass == DrumClass.HAT_CLOSED || pads[role].drumClass == DrumClass.HAT_OPEN, "${kit.name} role $role chokes but is no hat")
            assertTrue(hats.any { pads[it].drumClass == DrumClass.HAT_OPEN } && hats.any { pads[it].drumClass == DrumClass.HAT_CLOSED }, "${kit.name}: the closed hat closes the open one")
            for ((role, pad) in pads.withIndex()) {
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == EmberKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(EmberKits.pads(kit)[role].name, pad.displayName)
                assertEquals(EmberKits.pads(kit)[role].voice.drumClass, pad.drumClass)
                assertEquals("EMBER", pad.source["engine"])
                assertEquals(kit.name, pad.source["kit"])
                assertEquals(EmberKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(Ember.MODEL_VERSION.toString(), pad.source["model"])
                assertEquals(EmberKits.muteGroup(kit, role), pad.muteGroup, "${kit.name} role $role")
            }
        }
    }

    /** The percussion gate: every kit carries an authored roll or riser on A15 or A16. */
    @Test fun `every kit carries a roll or a riser`() {
        val moving = setOf(EmberVoice.PRESS_ROLL, EmberVoice.BRUSH_ROLL, EmberVoice.CYMBAL_SWELL)
        for (kit in Kit.entries) {
            val pads = EmberKits.pads(kit)
            assertTrue(pads[14].voice in moving || pads[15].voice in moving, "${kit.name}: no roll or riser on A15/A16")
        }
    }

    @Test fun `the four kits are four kits`() {
        assertEquals(Kit.entries.size, Kit.entries.map { it.bpm }.toSet().size, "each kit has its own tempo")
        for (kit in Kit.entries) {
            val names = EmberKits.pads(kit).map { it.name }
            assertEquals(EmberKits.PADS, names.toSet().size, "${kit.name}: sixteen different pads")
        }
        // No pad is the same voice at the same settings in two kits, and the same slot differs in shape.
        for (a in Kit.entries.indices) for (b in a + 1 until Kit.entries.size) {
            val ka = Kit.entries[a]; val kb = Kit.entries[b]
            for (role in 0 until EmberKits.PADS) {
                val pa = EmberKits.pads(ka)[role]; val pb = EmberKits.pads(kb)[role]
                assertFalse(pa.voice == pb.voice && pa.macros == pb.macros, "${ka.name} and ${kb.name} share role $role")
                val d = shapeDistance(grid(ka, role).last()[0], grid(kb, role).last()[0])
                assertTrue(d > .05, "${ka.name} and ${kb.name} role $role are one sound ($d)")
            }
        }
    }

    @Test fun `the kits are the prototype kits pad for pad`() {
        // A spot check against prototype/ember/kits.py, which generated the tables: one pad per kit.
        assertEquals(mapOf("TUNE" to .55f, "DAMP" to .25f), EmberKits.pads(Kit.CLUB)[0].macros)
        assertEquals(EmberVoice.BRUSH_SWEEP, EmberKits.pads(Kit.AFTER_HOURS)[3].voice)
        assertEquals("The Room Claps", EmberKits.pads(Kit.SOUL)[15].name)
        assertEquals(mapOf("BUZZ" to 0f, "TUNE" to .6f, "DAMP" to .1f, "DISTANCE" to .45f, "WARMTH" to .25f), EmberKits.pads(Kit.FREE)[1].macros)
    }

    @Test fun `no kit or pad is named after a maker`() {
        val names = Kit.entries.flatMap { k -> listOf(k.title) + EmberKits.pads(k).map { it.name } }
        for (name in names) assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
    }
}
