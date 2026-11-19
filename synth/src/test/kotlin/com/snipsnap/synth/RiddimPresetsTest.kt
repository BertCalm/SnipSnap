package com.snipsnap.synth

import com.snipsnap.synth.RiddimKits.Kit
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
 * RIDDIM's factory bank: the six accepted kits' RIDDIM pads as patches (after the owner accepted the kits on the phone
 * and the MPC, 2026-11-19). CALABASH's preset contracts: identity (each preset is a pad the owner heard), sanity, round
 * trip, names, distinctness; and the roster's registration.
 */
class RiddimPresetsTest {
    private val bank = RiddimPresets.all()

    private companion object {
        /** Renders are seconds for the rolls: each preset renders once for the whole class. */
        val renders = java.util.concurrent.ConcurrentHashMap<RiddimPatch, FloatArray>()
    }

    private fun rendered(p: RiddimPatch): FloatArray = renders.getOrPut(p) { p.render().samples }
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** Normalised correlation over the first [n] samples: the same shape at any level measures 1. */
    private fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until n) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return ab / sqrt(aa * bb)
    }

    // ---------- identity: the bank is the six accepted kits' RIDDIM pads, each recipe once ----------

    @Test fun `the bank is every RIDDIM pad of the six kits at the pad's own settings, each recipe once`() {
        for (kit in Kit.entries) {
            val pads = RiddimKits.pads(kit).filter { it.riddim != null }
            val patches = RiddimPresets.kitPatches(kit)
            assertEquals(pads.map { it.riddim }, patches.map { it.voice }, "${kit.name}: pad order")
            for ((pad, patch) in pads.zip(patches)) {
                assertEquals(Riddim.settled(pad.riddim!!, pad.macros), patch.macros, "${patch.name}: the pad's own macros")
                assertEquals(Riddim.macrosFor(pad.riddim).map { it.name }.toSet(), patch.macros.keys, "${patch.name}: a complete map")
                assertEquals(1f, patch.velocity, "${patch.name}: struck full")
                assertEquals(RiddimPresets.TAKE, patch.take, "${patch.name}: the kit grid's first take")
                assertEquals(Riddim.MODEL_VERSION, patch.model)
            }
        }
        val all = Kit.entries.flatMap(RiddimPresets::kitPatches)
        assertEquals(all.distinctBy { it.voice to it.macros }, bank)
        // The pads two kits share at the same settings, filed under the first kit only (disclosed).
        assertEquals(setOf("DEMBOW PUNCH KICK", "BOOM OPEN HAT", "BOOM CLAP", "WOOD WOOD A3"), (all - bank.toSet()).map { it.name }.toSet())
        assertEquals(69 - 4, bank.size, "sixty-nine RIDDIM pads, four of them repeats")
        // Every RIDDIM voice is on some kit, so every voice has a preset.
        assertEquals(emptyList(), RiddimVoice.entries.filter { RiddimPresets.forVoice(it).isEmpty() })
    }

    @Test fun `every preset is the pad the owner heard`() {
        // The kit's hard zone, first take, is the engine's render of the pad's own macros, scaled by the kit's level
        // law: the preset is that render at the engine's peak match, byte for byte.
        for (kit in Kit.entries) for (pad in RiddimKits.pads(kit)) {
            val voice = pad.riddim ?: continue
            val patch = bank.firstOrNull { it.voice == voice && it.macros == Riddim.settled(voice, pad.macros) }
                ?: error("${kit.name} ${pad.name}: no preset")
            assertContentEquals(Riddim.render(voice, pad.macros, 1f, 0).samples, rendered(patch), patch.name)
        }
        // And through the kit itself, on a kick, a bell and a roll: the same shape at another level.
        for ((kit, role) in listOf(Kit.CLAVE_DANCEHALL to 0, Kit.BAILE_FUNK to 8, Kit.WOOD_BRONZE to 15)) {
            val name = "${RiddimPresets.tag(kit)} ${RiddimKits.pads(kit)[role].name}".uppercase()
            val patch = bank.single { it.name == name }
            val preset = rendered(patch)
            val pad = RiddimKits.render(kit, role, 1f, 0).samples
            assertEquals(preset.size, pad.size, "${patch.name}: the same length")
            val c = correlation(preset, pad, min(preset.size, pad.size))
            assertTrue(c > .999999, "${patch.name}: the preset is the kit's pad, correlation $c")
        }
    }

    // ---------- registration: the roster serves the bank and RIDDIM is no longer rosterless ----------

    @Test fun `the roster serves the bank and nothing else of RIDDIM`() {
        assertFalse(RiddimPatch.ENGINE in PatchTypes.ROSTERLESS, "RIDDIM left the rosterless list with the owner's acceptance")
        assertFalse(RiddimPatch.ENGINE in PatchTypes.PROTOTYPES)
        assertEquals(bank, Presets.all().filterIsInstance<RiddimPatch>())
        for (voice in RiddimVoice.entries) assertEquals(RiddimPresets.forVoice(voice), Presets.forVoice(RiddimPatch.ENGINE, voice.name))
        assertTrue(Presets.forVoice(RiddimPatch.ENGINE, "UNKNOWN").isEmpty())
        for (patch in bank) assertEquals(patch, Presets.byName(RiddimPatch.ENGINE, patch.voiceName, patch.name))
    }

    // ---------- sanity: every preset is a real, clean, finished sound ----------

    @Test fun `every preset renders finite, bounded, at the engine's peak, centred and faded out`() {
        for (patch in bank) {
            val x = rendered(patch)
            assertTrue(x.size > 0.050f * Riddim.RATE, "${patch.name}: longer than 50 ms")
            assertTrue(x.all { it.isFinite() }, "${patch.name}: finite")
            assertEquals(Riddim.PEAK.toFloat(), peak(x), 1e-4f, "${patch.name}: the engine's peak match")
            val tail = x.copyOfRange(x.size - (0.001f * Riddim.RATE).toInt(), x.size)
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
        assertEquals(listOf("CLAVE", "DEMBOW", "BOOM", "BAILE", "SABAR", "WOOD"), Kit.entries.map(RiddimPresets::tag))
        for (kit in Kit.entries) for (patch in RiddimPresets.kitPatches(kit)) {
            assertEquals(patch.name.uppercase(), patch.name, "${patch.name}: caps like every chip")
            assertTrue(patch.name.startsWith(RiddimPresets.tag(kit) + " "), "${patch.name}: named after its kit")
            // The house limit for a preset menu (CLUTTER's, CALABASH's, DRUMLINE's).
            assertTrue(patch.name.length <= 24, "${patch.name}: short enough for a list")
        }
    }

    @Test fun `no preset name borrows a maker's name`() {
        val offenders = bank.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // ---------- distinctness: no two presets of a voice are one sound ----------

    @Test fun `every two presets of a voice are different recipes and different sounds`() {
        for (voice in RiddimVoice.entries) {
            val presets = RiddimPresets.forVoice(voice)
            for (i in presets.indices) for (j in i + 1 until presets.size) {
                val a = presets[i]; val b = presets[j]
                assertNotEquals(a.macros, b.macros, "${a.name} and ${b.name} are one recipe")
                assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .02, "${a.name} and ${b.name} sit on top of each other")
                assertFalse(rendered(a).contentEquals(rendered(b)), "${a.name} and ${b.name} render the same bytes")
            }
        }
    }
}
