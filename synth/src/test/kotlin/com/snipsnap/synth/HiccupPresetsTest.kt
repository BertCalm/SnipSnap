package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * HICCUP's authored presets, filed in the factory bank after the owner's listen of 2026-11-15. That listen
 * covered the three kits, not these presets one by one. SCUFF's preset contracts that apply to authored
 * settings: sanity, determinism, round trip, names, blocklist, distinctness. Nothing here claims a preset
 * sounds good.
 */
class HiccupPresetsTest {
    private val bank = HiccupPresets.all()
    private val renders = HashMap<HiccupPatch, FloatArray>()
    private fun rendered(p: HiccupPatch): FloatArray = renders.getOrPut(p) { p.render().samples }
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    @Test fun `the bank is twenty-one complete patches and every voice has at least two`() {
        assertEquals(21, bank.size)
        for (patch in bank) {
            assertEquals(Hiccup.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys, "${patch.name}: a complete map")
            assertTrue(patch.macros.values.all { it in 0f..1f }, "${patch.name}: macros in 0..1")
            assertEquals(1f, patch.velocity, "${patch.name}: struck full")
            assertEquals(HiccupPresets.TAKE, patch.take, "${patch.name}: take")
            assertEquals(Hiccup.MODEL_VERSION, patch.model)
        }
        for (voice in HiccupVoice.entries) {
            assertTrue(HiccupPresets.forVoice(voice).size >= 2, "$voice has at least two presets")
            assertEquals(bank.filter { it.voice == voice }, HiccupPresets.forVoice(voice))
        }
    }

    @Test fun `served through the factory roster, neither a prototype nor rosterless`() {
        assertFalse(HiccupPatch.ENGINE in PatchTypes.PROTOTYPES, "the owner heard the kits on 2026-11-15")
        assertFalse(HiccupPatch.ENGINE in PatchTypes.ROSTERLESS)
        assertEquals(bank, Presets.all().filter { it.engine == HiccupPatch.ENGINE }, "the whole bank is filed in Presets.all()")
        for (voice in HiccupVoice.entries) assertEquals(HiccupPresets.forVoice(voice), Presets.forVoice(HiccupPatch.ENGINE, voice.name))
    }

    @Test fun `every preset renders finite, bounded, non-silent, centred, under 1_5 s and faded out`() {
        for (patch in bank) {
            val x = rendered(patch)
            assertTrue(x.size > 0.010f * Dsp.RATE, "${patch.name}: longer than 10 ms")
            assertTrue(x.size < 1.5f * Dsp.RATE, "${patch.name}: under the classifier's loop line, ${x.size / Dsp.RATE} s")
            assertTrue(x.all { it.isFinite() }, "${patch.name}: finite")
            assertTrue(x.all { it in -1f..1f }, "${patch.name}: bounded")
            assertTrue(peak(x) > .5f, "${patch.name}: too quiet, peak ${peak(x)}")
            assertTrue(abs(x.last()) < .01f, "${patch.name}: ends faded, at ${x.last()}")
            assertTrue(abs(x.average()) < .005, "${patch.name}: centred, mean ${x.average()}")
        }
    }

    @Test fun `every preset is deterministic and round-trips through JSON with its velocity, take and model`() {
        for (patch in bank) {
            assertContentEquals(rendered(patch), patch.render().samples, "${patch.name}: the same bytes twice")
            val restored = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored, "${patch.name}: equal after a round trip")
            assertContentEquals(rendered(patch), restored.render().samples, "${patch.name}: the same bytes after a round trip")
            val soft = Velocity.atVelocity(patch, .5f).samples
            assertContentEquals(patch.copy(velocity = .5f).render().samples, soft, "${patch.name}: velocity re-strikes the same recipe")
            assertFalse(soft.contentEquals(rendered(patch)), "${patch.name}: a softer strike is another sound")
        }
    }

    @Test fun `preset names are unique, short, and never a maker's`() {
        val names = bank.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
        for (patch in bank) assertTrue(patch.name.isNotBlank() && patch.name.length <= 20, "${patch.name}: short enough for a list")
        val offenders = bank.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    @Test fun `every two presets are different recipes and different sounds`() {
        for (i in bank.indices) for (j in i + 1 until bank.size) {
            val a = bank[i]; val b = bank[j]
            assertFalse(rendered(a).contentEquals(rendered(b)), "${a.name} and ${b.name} render the same bytes")
            if (a.voice != b.voice) continue
            assertNotEquals(a.macros, b.macros, "${a.name} and ${b.name} are one recipe")
            assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .02, "${a.name} and ${b.name} sit on top of each other")
        }
    }
}
