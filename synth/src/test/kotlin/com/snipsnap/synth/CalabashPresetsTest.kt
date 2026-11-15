package com.snipsnap.synth

import com.snipsnap.synth.CalabashKits.Kit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CALABASH's factory bank: the four approved genre kits' pads as patches (round 3, after the owner approved the
 * Kotlin kits on 2026-11-15). CLUTTER's six preset contracts: identity (each preset is a pad the owner heard),
 * sanity, round trip, names, the naming rule, distinctness; and the roster's registration.
 */
class CalabashPresetsTest {
    private val bank = CalabashPresets.all()

    private companion object {
        /** Renders are seconds for the rolls: each preset renders once for the whole class. */
        val renders = java.util.concurrent.ConcurrentHashMap<CalabashPatch, FloatArray>()
    }

    private fun rendered(p: CalabashPatch): FloatArray = renders.getOrPut(p) { p.render().samples }
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** Normalised correlation over the first [n] samples: the same shape at any level measures 1. */
    private fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until n) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return ab / sqrt(aa * bb)
    }

    // ---------- identity: the bank is the four approved kits, pad for pad ----------

    @Test fun `the bank is every pad of the four approved kits at the pad's own settings`() {
        assertEquals(Kit.entries.flatMap(CalabashPresets::kitPatches), bank)
        assertEquals(Kit.entries.size * CalabashKits.PADS, bank.size, "sixteen pads a kit, four kits")
        for (kit in Kit.entries) {
            val pads = CalabashKits.pads(kit)
            val patches = CalabashPresets.kitPatches(kit)
            assertEquals(pads.map { it.voice }, patches.map { it.voice }, "${kit.name}: pad order")
            for ((pad, patch) in pads.zip(patches)) {
                assertEquals(Calabash.settled(pad.voice, pad.macros), patch.macros, "${patch.name}: the pad's own macros")
                assertEquals(Calabash.macrosFor(pad.voice).map { it.name }.toSet(), patch.macros.keys, "${patch.name}: a complete map")
                assertEquals(1f, patch.velocity, "${patch.name}: struck full")
                assertEquals(CalabashPresets.TAKE, patch.take, "${patch.name}: the kit grid's first take")
                assertEquals(Calabash.MODEL_VERSION, patch.model)
            }
        }
        // Every voice but the low agogo bell is in a kit; that one has no preset (disclosed).
        val missing = CalabashVoice.entries.filter { CalabashPresets.forVoice(it).isEmpty() }
        assertEquals(listOf(CalabashVoice.AGOGO_LOW), missing)
    }

    @Test fun `every preset is the pad the owner heard`() {
        // The kit's hard zone, first take, is the engine's render of the pad's own macros, scaled by the kit's level
        // law: the preset is that render at the engine's peak match, byte for byte.
        for (kit in Kit.entries) for ((pad, patch) in CalabashKits.pads(kit).zip(CalabashPresets.kitPatches(kit))) {
            assertContentEquals(Calabash.render(pad.voice, pad.macros, 1f, 0).samples, rendered(patch), patch.name)
        }
        // And through the kit itself, on a hit, a hand drum and a roll: the same shape at another level.
        for ((kit, role) in listOf(Kit.AFROBEATS to 0, Kit.AFROCUBAN to 4, Kit.MBALAX to 15)) {
            val patch = CalabashPresets.kitPatches(kit)[role]
            val preset = rendered(patch)
            val pad = CalabashKits.render(kit, role, 1f, 0).samples
            val n = min(preset.size, pad.size)
            assertEquals(preset.size, pad.size, "${patch.name}: the same length")
            val c = correlation(preset, pad, n)
            assertTrue(c > .999999, "${patch.name}: the preset is the kit's pad, correlation $c")
            assertTrue(peak(pad) <= peak(preset) + 1e-6f, "${patch.name}: the kit never lifts a take")
        }
    }

    // ---------- registration: the roster serves the bank and CALABASH is no longer rosterless ----------

    @Test fun `the roster serves the bank and nothing else of CALABASH`() {
        assertFalse(CalabashPatch.ENGINE in PatchTypes.ROSTERLESS, "CALABASH left the rosterless list with the owner's approval")
        assertFalse(CalabashPatch.ENGINE in PatchTypes.PROTOTYPES)
        assertEquals(bank, Presets.all().filterIsInstance<CalabashPatch>())
        for (voice in CalabashVoice.entries) assertEquals(CalabashPresets.forVoice(voice), Presets.forVoice(CalabashPatch.ENGINE, voice.name))
        assertTrue(Presets.forVoice(CalabashPatch.ENGINE, "UNKNOWN").isEmpty())
        for (patch in bank) assertEquals(patch, Presets.byName(CalabashPatch.ENGINE, patch.voiceName, patch.name))
    }

    // ---------- sanity: every preset is a real, clean, finished sound ----------

    @Test fun `every preset renders finite, bounded, at the engine's peak, centred and faded out`() {
        for (patch in bank) {
            val x = rendered(patch)
            assertTrue(x.size > 0.050f * Dsp.RATE, "${patch.name}: longer than 50 ms")
            assertTrue(x.all { it.isFinite() }, "${patch.name}: finite")
            assertEquals(Calabash.PEAK, peak(x), 1e-4f, "${patch.name}: the engine's peak match")
            // The engine's ending: a raised-cosine fade over its last 50 ms, so the last sample is zero.
            assertTrue(abs(x.last()) < 1e-7f, "${patch.name}: fades to nothing, ends at ${x.last()}")
            val tail = x.copyOfRange(x.size - (0.001f * Dsp.RATE).toInt(), x.size)
            assertTrue(peak(tail) < .01f, "${patch.name}: the last ms is faint, peaks at ${peak(tail)}")
            assertTrue(abs(x.average()) < .005, "${patch.name}: centred, mean ${x.average()}")
        }
    }

    // ---------- round trip: a preset is a Patch, with its strike outside the macro map ----------

    @Test fun `every preset round-trips through JSON with its velocity, take and model, and re-strikes by velocity`() {
        for (patch in bank) {
            val restored = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored, "${patch.name}: equal after a round trip")
            assertContentEquals(rendered(patch), restored.render().samples, "${patch.name}: the same bytes after a round trip")
        }
        // Velocity re-strikes the same recipe; one pad of every voice in the bank (a roll is seconds a render).
        for (patch in bank.distinctBy { it.voice }) {
            val soft = Velocity.atVelocity(patch, .5f).samples
            assertContentEquals(patch.copy(velocity = .5f).render().samples, soft, "${patch.name}: velocity re-strikes the same recipe")
            assertFalse(soft.contentEquals(rendered(patch)), "${patch.name}: a softer strike is another sound")
        }
    }

    // ---------- names: unique, caps, from the kit, and never a maker's ----------

    @Test fun `preset names are unique across the bank, caps, and say which kit they came from`() {
        val names = bank.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
        assertEquals(listOf("AFROBEAT", "AFROBEATS", "MBALAX", "AFROCUBAN"), Kit.entries.map(CalabashPresets::tag))
        for (kit in Kit.entries) for ((pad, patch) in CalabashKits.pads(kit).zip(CalabashPresets.kitPatches(kit))) {
            assertEquals(patch.name.uppercase(), patch.name, "${patch.name}: caps like every chip")
            assertTrue(patch.name.startsWith(CalabashPresets.tag(kit) + " "), "${patch.name}: named after its kit")
            // The house limit for a preset menu (CLUTTER's, CONTOUR's, DRUMLINE's).
            assertTrue(patch.name.length <= 24, "${patch.name}: short enough for a list")
            if (pad.name != "Talking Drum Fall") assertEquals("${CalabashPresets.tag(kit)} ${pad.name}".uppercase(), patch.name)
        }
        assertTrue("AFROBEATS TALKING FALL" in names, "the one shortened name")
    }

    @Test fun `no preset name borrows a maker's name`() {
        val offenders = bank.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // ---------- distinctness: no two presets of a voice are one sound ----------

    @Test fun `every two presets of a voice are different recipes and different sounds`() {
        for (voice in CalabashVoice.entries) {
            val presets = CalabashPresets.forVoice(voice)
            for (i in presets.indices) for (j in i + 1 until presets.size) {
                val a = presets[i]; val b = presets[j]
                assertNotEquals(a.macros, b.macros, "${a.name} and ${b.name} are one recipe")
                assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .02, "${a.name} and ${b.name} sit on top of each other")
                assertFalse(rendered(a).contentEquals(rendered(b)), "${a.name} and ${b.name} render the same bytes")
            }
        }
    }
}
