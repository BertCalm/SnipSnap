package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.CalabashKits.Kit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The CALABASH genre kits' grids, on kits built by the engine itself: CLUTTER's probes, with CALABASH's level law. */
class CalabashKitsTest {

    private companion object {
        /** Each kit is built once (192 renders, in parallel) and every test reads it. */
        val built = java.util.concurrent.ConcurrentHashMap<Kit, List<ArrangedPad>>()
        fun kit(k: Kit): List<ArrangedPad> = built.getOrPut(k) { CalabashKits.build(k) }
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
        for ((k, role) in listOf(Kit.AFROBEATS to 0, Kit.MBALAX to 6, Kit.AFROCUBAN to 15)) {
            for ((z, v) in CalabashKits.velocities.withIndex()) for (t in 0 until CalabashKits.TAKES) {
                assertContentEquals(take(k, role, z, t), CalabashKits.render(k, role, v, t).samples, "${k.name} role $role v $v take $t")
            }
        }
    }

    @Test fun `every take is finite bounded centred and ends in silence`() {
        for (k in Kit.entries) for (role in 0 until CalabashKits.PADS) for (z in CalabashKits.velocities.indices) for (t in 0 until CalabashKits.TAKES) {
            val x = take(k, role, z, t)
            val what = "${k.name} role $role zone $z take $t"
            assertTrue(x.all { it.isFinite() }, "$what finite")
            val p = peak(x)
            assertTrue(p <= CalabashKits.PAD_PEAK + 1e-6f && p > .2f, "$what peak $p")
            assertTrue(abs(x.average()) < .005, "$what DC ${x.average()}")
            assertTrue(abs(x.last()) < 1e-4f, "$what tail ${x.last()}")
        }
    }

    @Test fun `every zone's takes are equally loud and its highest peak sits at the pad peak`() {
        for (k in Kit.entries) for (role in 0 until CalabashKits.PADS) for (z in CalabashKits.velocities.indices) {
            val voice = CalabashKits.pads(k)[role].voice
            val window = if (voice in CalabashKits.PHRASES) Int.MAX_VALUE else (CalabashKits.LEVEL_WINDOW_S * Calabash.RATE).toInt()
            val takes = List(CalabashKits.TAKES) { take(k, role, z, it) }
            val what = "${k.name} role $role zone $z"
            assertEquals(CalabashKits.PAD_PEAK, takes.maxOf { peak(it) }, .001f, what)
            val levels = takes.map { 20 * log10(CalabashKits.loudness(it, window)) }
            assertTrue(levels.max() - levels.min() < .1, "$what loudness spread ${levels.max() - levels.min()} dB")
        }
    }

    @Test fun `a softer hit is a different strike and not only a quieter one`() {
        for (k in Kit.entries) for (role in 0 until CalabashKits.PADS) {
            val d = shapeDistance(take(k, role, 2, 0), take(k, role, 0, 0))
            assertTrue(d > .05, "${k.name} role $role: soft is a scaled copy of hard ($d)")
        }
    }

    @Test fun `every zone holds four different strikes`() {
        for (k in Kit.entries) for (role in 0 until CalabashKits.PADS) for (z in CalabashKits.velocities.indices) {
            val takes = List(CalabashKits.TAKES) { take(k, role, z, it) }
            for (a in takes.indices) for (b in a + 1 until takes.size) {
                val d = shapeDistance(takes[a], takes[b])
                assertTrue(d > .005, "${k.name} role $role zone $z: takes $a and $b differ by only $d")
            }
        }
    }

    @Test fun `a built kit carries its grid its classes its chokes and its provenance`() {
        for (k in Kit.entries) {
            val pads = kit(k)
            assertEquals(CalabashKits.PADS, pads.size)
            assertEquals(DrumClass.KICK, pads[0].drumClass, "${k.name} A01")
            for ((role, pad) in pads.withIndex()) {
                val authored = CalabashKits.pads(k)[role]
                assertEquals(3, pad.velocityTakes.size)
                assertTrue(pad.velocityTakes.all { it.size == CalabashKits.TAKES })
                assertContentEquals(pad.velocityTakes.last().first().samples, pad.snip.samples)
                assertEquals(authored.name, pad.displayName)
                assertEquals(authored.voice.drumClass, pad.drumClass)
                assertEquals("CALABASH", pad.source["engine"])
                assertEquals(k.name, pad.source["kit"])
                assertEquals(authored.voice.name, pad.source["voice"])
                assertEquals(CalabashKits.VERSION.toString(), pad.source["kitVersion"])
                assertEquals(Calabash.MODEL_VERSION.toString(), pad.source["model"])
                assertEquals(CalabashKits.muteGroup(k, role), pad.muteGroup)
                assertEquals(if (role == 15) CalabashKits.PHRASES.getValue(authored.voice) else "finite", pad.source["articulation"], "${k.name} role $role")
            }
            // Hats choke hats wherever a kit has a closed and an open one.
            val closed = pads.indices.filter { pads[it].drumClass == DrumClass.HAT_CLOSED && CalabashKits.pads(k)[it].voice == CalabashVoice.HAT_CLOSED }
            val open = pads.indices.filter { pads[it].drumClass == DrumClass.HAT_OPEN }
            for (c in closed) for (o in open) assertTrue(pads[c].muteGroup != 0 && pads[c].muteGroup == pads[o].muteGroup, "${k.name}: hat $c does not choke $o")
        }
    }

    @Test fun `every kit ends in its roll or riser`() {
        for (k in Kit.entries) {
            val roll = kit(k)[15]
            assertTrue(CalabashKits.pads(k)[15].voice in CalabashKits.PHRASES, k.name)
            assertTrue(roll.snip.samples.size > 2 * Calabash.RATE, "${k.name}: the phrase is ${roll.snip.samples.size} frames")
        }
    }

    @Test fun `the four kits are four kits`() {
        assertEquals(Kit.entries.size, Kit.entries.map { it.bpm }.toSet().size, "each kit has its own tempo")
        for (k in Kit.entries) {
            val names = CalabashKits.pads(k).map { it.name }
            assertEquals(CalabashKits.PADS, names.toSet().size, "${k.name}: sixteen different pads")
        }
        // No slot is the same voice at the same settings in two kits, and the same slot differs in shape.
        for (a in Kit.entries.indices) for (b in a + 1 until Kit.entries.size) {
            val ka = Kit.entries[a]; val kb = Kit.entries[b]
            for (role in 0 until CalabashKits.PADS) {
                val pa = CalabashKits.pads(ka)[role]; val pb = CalabashKits.pads(kb)[role]
                assertFalse(pa.voice == pb.voice && pa.macros == pb.macros, "${ka.name} and ${kb.name} share role $role")
                val d = shapeDistance(take(ka, role, 2, 0), take(kb, role, 2, 0))
                assertTrue(d > .05, "${ka.name} and ${kb.name} role $role are one sound ($d)")
            }
        }
    }

    @Test fun `no kit, pad or voice is named after a maker`() {
        val names = Kit.entries.flatMap { k -> listOf(k.title) + CalabashKits.pads(k).map { it.name } } + CalabashVoice.entries.map { it.name }
        for (name in names) assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(name), name)
    }
}
