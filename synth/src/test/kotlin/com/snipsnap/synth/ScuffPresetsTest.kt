package com.snipsnap.synth

import com.snipsnap.synth.ScuffKits.Kit
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
 * SCUFF's factory bank: the two accepted kits' hits as patches (round 4, after the owner's listen of
 * 2026-11-13). The six preset contracts: identity (each preset is a pad the owner heard), sanity, round trip,
 * names, the naming rule, distinctness; and the roster's registration, which `PresetsTest` reflects over.
 */
class ScuffPresetsTest {
    private val bank = ScuffPresets.all()
    private val renders = HashMap<ScuffPatch, FloatArray>()
    private fun rendered(p: ScuffPatch): FloatArray = renders.getOrPut(p) { p.render().samples }
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** Normalised correlation over the first [n] samples: the same shape at any level measures 1. */
    private fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until n) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return ab / sqrt(aa * bb)
    }

    // ---------- identity: the bank is the two accepted kits, hit for hit ----------

    @Test fun `the bank is every hit of both accepted kits at the kit's own settings`() {
        assertEquals(Kit.entries.flatMap(ScuffPresets::kitPatches), bank)
        assertEquals(Kit.entries.size * ScuffKits.HITS, bank.size, "fourteen hits a kit, two kits")
        for (kit in Kit.entries) {
            val parts = ScuffKits.parts(kit)
            val patches = ScuffPresets.kitPatches(kit)
            assertEquals(parts.map { it.voice }, patches.map { it.voice }, "${kit.name}: pad order")
            for ((part, patch) in parts.zip(patches)) {
                assertEquals(Scuff.settled(part.voice, kit.chain + part.macros), patch.macros, "${patch.name}: the kit's own macros")
                assertEquals(Scuff.macrosFor(part.voice).map { it.name }.toSet(), patch.macros.keys, "${patch.name}: a complete map")
                assertEquals(1f, patch.velocity, "${patch.name}: struck full")
                assertEquals(ScuffPresets.TAKE, patch.take, "${patch.name}: the first dealt take")
                assertEquals(Scuff.MODEL_VERSION, patch.model)
            }
        }
        for (voice in ScuffVoice.entries) assertTrue(ScuffPresets.forVoice(voice).size >= 2, "$voice is in both kits")
    }

    @Test fun `every preset is the pad the owner heard, through the engine's own finish`() {
        for (kit in Kit.entries) for ((role, patch) in ScuffPresets.kitPatches(kit).withIndex()) {
            val preset = rendered(patch)
            assertContentEquals(preset, Scuff.render(patch.voice, patch.macros, patch.velocity, patch.take).samples, patch.name)
            // The kit's pad is the same strike through the kit's level law, which adds a second 20 Hz block (a
            // kick's sub shifts under it: measured .987 against the bare preset), and the kit's ending (a gate
            // 80 dB down; 6 ms of silence after the crash). Send the preset through that same finish and compare
            // the shape short of the tails.
            val pad = ScuffKits.render(kit, role, 1f, 0).samples
            val finished = KitStrikes.finish(preset.copyOf(), 1f).samples
            // The overlap ends where the preset does, with its 4 ms fade; stop 5 ms short of that (the tick
            // is only some 15 ms long, so the margin is small on purpose).
            val n = min(finished.size, pad.size) - (0.005f * Dsp.RATE).toInt()
            assertTrue(n > 0.002f * Dsp.RATE, "${patch.name}: enough to compare")
            val c = correlation(finished, pad, n)
            assertTrue(c > .999, "${patch.name}: the preset is the kit's pad, correlation $c")
        }
    }

    // ---------- registration: the roster serves the bank and SCUFF is no longer rosterless ----------

    @Test fun `the roster serves the bank and nothing else of SCUFF`() {
        assertFalse(ScuffPatch.ENGINE in PatchTypes.ROSTERLESS, "SCUFF left the rosterless list with the owner's listen")
        assertFalse(ScuffPatch.ENGINE in PatchTypes.PROTOTYPES)
        assertEquals(bank, Presets.all().filterIsInstance<ScuffPatch>())
        for (voice in ScuffVoice.entries) assertEquals(ScuffPresets.forVoice(voice), Presets.forVoice(ScuffPatch.ENGINE, voice.name))
        assertTrue(Presets.forVoice(ScuffPatch.ENGINE, "UNKNOWN").isEmpty())
        for (patch in bank) assertEquals(patch, Presets.byName(ScuffPatch.ENGINE, patch.voiceName, patch.name))
    }

    // ---------- sanity: every preset is a real, clean, finished sound ----------

    @Test fun `every preset renders finite, bounded, loud enough, centred and faded out at its gate`() {
        for (patch in bank) {
            val x = rendered(patch)
            assertTrue(x.size > 0.010f * Dsp.RATE, "${patch.name}: longer than 10 ms")
            assertTrue(x.all { it.isFinite() }, "${patch.name}: finite")
            assertTrue(x.all { it in -1f..1f }, "${patch.name}: bounded")
            assertTrue(peak(x) > .5f, "${patch.name}: too quiet, peak ${peak(x)}")
            // The engine's ending: the tail gate cuts where a 5 ms RMS falls 40 dB under its peak, then the
            // finish fades the last 4 ms to nothing. The last millisecond is faint (a long bass hit measured
            // .025 of peak), the last sample zero. The crash is the exception the kit disclosed at round 2:
            // its reverse envelope rises to the last sample, so only the fade ends it (measured .11 of peak).
            assertTrue(x.last() == 0f, "${patch.name}: fades to nothing, ends at ${x.last()}")
            val tail = x.copyOfRange(x.size - (0.001f * Dsp.RATE).toInt(), x.size)
            val bound = if (patch.voice == ScuffVoice.INHALE) .25f else .05f
            assertTrue(peak(tail) < bound, "${patch.name}: the last ms sits under the gate, peaks at ${peak(tail)}")
            // One 20 Hz block, where the kit's pad has two: a short rim through the kit's harder SLAM keeps an
            // offset inside its 60 ms (FLINT RIM measured .0145 of peak, 37 dB down; the kit's pad .005 after
            // its second block). Disclosed in docs/SCUFF.md; the bound is the measurement with room, not a target.
            assertTrue(abs(x.average()) < .02, "${patch.name}: centred, mean ${x.average()}")
        }
    }

    // ---------- round trip: a preset is a Patch, with its strike outside the macro map ----------

    @Test fun `every preset round-trips through JSON with its velocity, take and model, and re-strikes by velocity`() {
        for (patch in bank) {
            val restored = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored, "${patch.name}: equal after a round trip")
            assertContentEquals(rendered(patch), restored.render().samples, "${patch.name}: the same bytes after a round trip")
            val soft = Velocity.atVelocity(patch, .5f).samples
            assertContentEquals(patch.copy(velocity = .5f).render().samples, soft, "${patch.name}: velocity re-strikes the same recipe")
            assertFalse(soft.contentEquals(rendered(patch)), "${patch.name}: a softer strike is another sound")
        }
    }

    // ---------- names: unique, caps, from the kit, and never a maker's ----------

    @Test fun `preset names are unique across the bank, caps, and say which kit they came from`() {
        val names = bank.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
        for (patch in bank) {
            assertEquals(patch.name.uppercase(), patch.name, "${patch.name}: caps like every chip")
            assertTrue(Kit.entries.any { patch.name.startsWith(it.title.uppercase() + " ") }, "${patch.name}: named after its kit")
            assertTrue(patch.name.length <= 20, "${patch.name}: short enough for a list")
        }
    }

    @Test fun `no preset name borrows a maker's name`() {
        val offenders = bank.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // ---------- distinctness: no two presets of a voice are one sound ----------

    @Test fun `every two presets of a voice are different recipes and different sounds`() {
        for (voice in ScuffVoice.entries) {
            val presets = ScuffPresets.forVoice(voice)
            for (i in presets.indices) for (j in i + 1 until presets.size) {
                val a = presets[i]; val b = presets[j]
                assertNotEquals(a.macros, b.macros, "${a.name} and ${b.name} are one recipe")
                assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .02, "${a.name} and ${b.name} sit on top of each other")
                assertFalse(rendered(a).contentEquals(rendered(b)), "${a.name} and ${b.name} render the same bytes")
            }
        }
    }
}
