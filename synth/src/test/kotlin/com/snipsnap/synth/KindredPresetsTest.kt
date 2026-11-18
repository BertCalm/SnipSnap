package com.snipsnap.synth

import com.snipsnap.synth.KindredKits.Kit
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
 * KINDRED's factory bank: the four approved genre kits' one-shots as patches (after the owner approved the Kotlin
 * kits and the hardware on 2026-11-18). SERVO's preset contracts: identity (each preset is a pad the owner heard),
 * registration, sanity, round trip, names, distinctness.
 */
class KindredPresetsTest {
    private val bank = KindredPresets.all()

    private companion object {
        val renders = java.util.concurrent.ConcurrentHashMap<KindredPatch, FloatArray>()
    }

    private fun rendered(p: KindredPatch): FloatArray = renders.getOrPut(p) { p.render().samples }
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** Normalised correlation over the first [n] samples: the same shape at any level measures 1. */
    private fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until n) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return ab / sqrt(aa * bb)
    }

    // ---------- identity: the bank is the four approved kits, pad for pad ----------

    @Test fun `the bank is every one-shot of the four approved kits at the pad's own recipe`() {
        assertEquals(Kit.entries.flatMap(KindredPresets::kitPatches), bank)
        assertEquals(Kit.entries.size * KindredKits.HITS, bank.size, "fourteen one-shots a kit, four kits; the phrase and riser are not presets")
        for (kit in Kit.entries) {
            val recipes = KindredKits.recipes(kit)
            val patches = KindredPresets.kitPatches(kit)
            assertEquals(recipes.map { it.id }, patches.map { it.recipe }, "${kit.name}: pad order")
            for ((r, patch) in recipes.zip(patches)) {
                assertEquals(r.voice, patch.voice)
                assertEquals(Kindred.settled(r.voice, r.macros), patch.macros, "${patch.name}: the pad's own macros")
                assertEquals(Kindred.macrosFor(r.voice).map { it.name }.toSet(), patch.macros.keys, "${patch.name}: a complete map")
                assertEquals(1f, patch.velocity, "${patch.name}: struck full")
                assertEquals(KindredPresets.TAKE, patch.take, "${patch.name}: the kit grid's first take")
                assertEquals(Kindred.MODEL_VERSION, patch.model)
            }
        }
        assertTrue(KindredVoice.entries.all { KindredPresets.forVoice(it).isNotEmpty() }, "every voice plays at least one pad")
    }

    @Test fun `every preset is the pad the owner heard`() {
        // The preset is the engine's render of the pad's recipe; the kit's hard zone, first take, is the same strike
        // through the kit's finish and level law. A kick, a clap (three strikes), a bell and a ping across the kits.
        for ((kit, role) in listOf(Kit.TECHNO to 0, Kit.TECHNO to 1, Kit.AMBIENT to 5, Kit.AMBIENT to 7, Kit.INDUSTRIAL to 8, Kit.ELECTRO to 9)) {
            val patch = KindredPresets.kitPatches(kit)[role]
            val preset = KitStrikes.finish(rendered(patch).copyOf(), 1f, ceiling = Float.MAX_VALUE).samples
            val pad = KindredKits.render(kit, role, 1f, 0).samples
            val c = correlation(preset, pad, min(preset.size, pad.size))
            assertTrue(c > .999, "${patch.name}: the preset is the kit's pad, correlation $c")
        }
    }

    // ---------- registration: the roster serves the bank, and KINDRED is no longer a prototype ----------

    @Test fun `the roster serves the bank and nothing else of KINDRED`() {
        assertFalse(KindredPatch.ENGINE in PatchTypes.ROSTERLESS)
        assertFalse(KindredPatch.ENGINE in PatchTypes.PROTOTYPES, "KINDRED left the prototypes with the owner's approval")
        assertEquals(bank, Presets.all().filterIsInstance<KindredPatch>())
        for (voice in KindredVoice.entries) assertEquals(KindredPresets.forVoice(voice), Presets.forVoice(KindredPatch.ENGINE, voice.name))
        assertTrue(Presets.forVoice(KindredPatch.ENGINE, "UNKNOWN").isEmpty())
        for (patch in bank) assertEquals(patch, Presets.byName(KindredPatch.ENGINE, patch.voiceName, patch.name))
    }

    // ---------- sanity: every preset is a real, clean, finished sound ----------

    @Test fun `every preset renders finite, bounded, centred and faded out`() {
        for (patch in bank) {
            val x = rendered(patch)
            assertTrue(x.size > 0.050f * Dsp.RATE, "${patch.name}: longer than 50 ms")
            assertTrue(x.size < 1.5f * Dsp.RATE, "${patch.name}: a one-shot, under the loop line")
            assertTrue(x.all { it.isFinite() }, "${patch.name}: finite")
            assertTrue(peak(x) in .05f..1f, "${patch.name}: peak ${peak(x)}")
            assertTrue(abs(x.last()) < 1e-6f, "${patch.name}: fades to nothing, ends at ${x.last()}")
            assertTrue(abs(x.average()) < .005, "${patch.name}: centred, mean ${x.average()}")
        }
    }

    // ---------- round trip: a preset is a Patch, with its strike and recipe outside the macro map ----------

    @Test fun `every preset round-trips through JSON with its velocity, take, recipe and model, and re-strikes by velocity`() {
        for (patch in bank) {
            val restored = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored, "${patch.name}: equal after a round trip")
            assertContentEquals(rendered(patch), restored.render().samples, "${patch.name}: the same bytes after a round trip")
        }
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
        assertEquals(listOf("TECHNO", "INDUSTRIAL", "ELECTRO", "AMBIENT"), Kit.entries.map(KindredPresets::tag))
        for (kit in Kit.entries) for ((r, patch) in KindredKits.recipes(kit).zip(KindredPresets.kitPatches(kit))) {
            assertEquals("${KindredPresets.tag(kit)} ${r.name}".uppercase(), patch.name)
            assertTrue(patch.name.length <= 24, "${patch.name}: short enough for a list")
        }
    }

    @Test fun `no preset name borrows a maker's name`() {
        val offenders = bank.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // ---------- distinctness: no two presets of a voice are one sound ----------

    @Test fun `every two presets of a voice are different recipes and different sounds`() {
        // KINDRED's recipes change the circuit as well as the macros, so two pads may share macros and still differ:
        // the contract is a different recipe and different audio.
        for (voice in KindredVoice.entries) {
            val presets = KindredPresets.forVoice(voice)
            for (i in presets.indices) for (j in i + 1 until presets.size) {
                val a = presets[i]; val b = presets[j]
                assertNotEquals(a.recipe, b.recipe, "${a.name} and ${b.name} are one recipe")
                assertFalse(rendered(a).contentEquals(rendered(b)), "${a.name} and ${b.name} render the same bytes")
            }
        }
    }
}
