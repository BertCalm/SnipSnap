package com.snipsnap.synth

import com.snipsnap.synth.ClutterKits.Kit
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
 * CLUTTER's factory bank: the four accepted genre kits' pads as patches (round 3, after the owner approved the
 * kits' audition on 2026-11-14). The six preset contracts, as SCUFF's round 4 set them: identity (each preset is
 * a pad the owner heard), sanity, round trip, names, the naming rule, distinctness; and the roster's
 * registration, which `PresetsTest` reflects over.
 */
class ClutterPresetsTest {
    private val bank = ClutterPresets.all()
    private val renders = HashMap<ClutterPatch, FloatArray>()
    private fun rendered(p: ClutterPatch): FloatArray = renders.getOrPut(p) { p.render().samples }
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** Normalised correlation over the first [n] samples: the same shape at any level measures 1. */
    private fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until n) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return ab / sqrt(aa * bb)
    }

    // ---------- identity: the bank is the four accepted kits, pad for pad ----------

    @Test fun `the bank is every pad of the four accepted kits at the pad's own settings`() {
        assertEquals(Kit.entries.flatMap(ClutterPresets::kitPatches), bank)
        assertEquals(Kit.entries.size * ClutterKits.PADS, bank.size, "sixteen pads a kit, four kits")
        for (kit in Kit.entries) {
            val pads = ClutterKits.pads(kit)
            val patches = ClutterPresets.kitPatches(kit)
            assertEquals(pads.map { it.voice }, patches.map { it.voice }, "${kit.name}: pad order")
            for ((pad, patch) in pads.zip(patches)) {
                assertEquals(Clutter.settled(pad.voice, pad.macros), patch.macros, "${patch.name}: the pad's own macros")
                assertEquals(Clutter.macrosFor(pad.voice).map { it.name }.toSet(), patch.macros.keys, "${patch.name}: a complete map")
                assertEquals(1f, patch.velocity, "${patch.name}: struck full")
                assertEquals(ClutterPresets.TAKE, patch.take, "${patch.name}: the kit grid's first take")
                assertEquals(Clutter.MODEL_VERSION, patch.model)
            }
        }
        // The four kits between them play every object at least twice (the bucket five times).
        for (voice in ClutterVoice.entries) assertTrue(ClutterPresets.forVoice(voice).size >= 2, "$voice is in two kits")
    }

    @Test fun `every preset is the pad the owner heard, through the kit's own finish`() {
        for (kit in Kit.entries) for ((role, patch) in ClutterPresets.kitPatches(kit).withIndex()) {
            val preset = rendered(patch)
            assertContentEquals(preset, Clutter.render(patch.voice, patch.macros, patch.velocity, patch.take).samples, patch.name)
            // The kit's pad is the same strike scaled by the kit's level law instead of the engine's peak match,
            // then through the kit's finish (a second mean and 20 Hz block, the ending 80 dB down, a 4 ms fade).
            // Every step of that finish is linear, so the preset sent through it is the pad's shape at another
            // level; compare short of the fades.
            val pad = ClutterKits.render(kit, role, 1f, 0).samples
            val finished = KitStrikes.finish(preset.copyOf(), 1f).samples
            val n = min(finished.size, pad.size) - (0.005f * Dsp.RATE).toInt()
            assertTrue(n > 0.010f * Dsp.RATE, "${patch.name}: enough to compare")
            val c = correlation(finished, pad, n)
            assertTrue(c > .9999, "${patch.name}: the preset is the kit's pad, correlation $c")
        }
    }

    // ---------- registration: the roster serves the bank and CLUTTER is no longer rosterless ----------

    @Test fun `the roster serves the bank and nothing else of CLUTTER`() {
        assertFalse(ClutterPatch.ENGINE in PatchTypes.ROSTERLESS, "CLUTTER left the rosterless list with the owner's approval")
        assertFalse(ClutterPatch.ENGINE in PatchTypes.PROTOTYPES)
        assertEquals(bank, Presets.all().filterIsInstance<ClutterPatch>())
        for (voice in ClutterVoice.entries) assertEquals(ClutterPresets.forVoice(voice), Presets.forVoice(ClutterPatch.ENGINE, voice.name))
        assertTrue(Presets.forVoice(ClutterPatch.ENGINE, "UNKNOWN").isEmpty())
        for (patch in bank) assertEquals(patch, Presets.byName(ClutterPatch.ENGINE, patch.voiceName, patch.name))
    }

    // ---------- sanity: every preset is a real, clean, finished sound ----------

    @Test fun `every preset renders finite, bounded, at the engine's peak, centred and faded out`() {
        for (patch in bank) {
            val x = rendered(patch)
            assertTrue(x.size > 0.050f * Dsp.RATE, "${patch.name}: longer than 50 ms")
            assertTrue(x.all { it.isFinite() }, "${patch.name}: finite")
            assertEquals(Clutter.PEAK, peak(x), 1e-4f, "${patch.name}: the engine's peak match")
            // The engine's ending: a squared fade over its last 25 ms, so the last sample is zero and the last
            // millisecond is faint.
            assertTrue(x.last() == 0f, "${patch.name}: fades to nothing, ends at ${x.last()}")
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
            val soft = Velocity.atVelocity(patch, .5f).samples
            assertContentEquals(patch.copy(velocity = .5f).render().samples, soft, "${patch.name}: velocity re-strikes the same recipe")
            assertFalse(soft.contentEquals(rendered(patch)), "${patch.name}: a softer strike is another sound")
        }
    }

    // ---------- names: unique, caps, from the kit, and never a maker's ----------

    @Test fun `preset names are unique across the bank, caps, and say which kit they came from`() {
        val names = bank.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
        assertEquals(listOf("LOFI", "STREET", "BOOMBAP", "MICRO"), Kit.entries.map(ClutterPresets::tag))
        for (kit in Kit.entries) for (patch in ClutterPresets.kitPatches(kit)) {
            assertEquals(patch.name.uppercase(), patch.name, "${patch.name}: caps like every chip")
            assertTrue(patch.name.startsWith(ClutterPresets.tag(kit) + " "), "${patch.name}: named after its kit")
            // CONTOUR's limit for a preset menu; the longest, BOOMBAP PAPER BAG CRACK, is 23.
            assertTrue(patch.name.length <= 24, "${patch.name}: short enough for a list")
        }
    }

    @Test fun `no preset name borrows a maker's name`() {
        val offenders = bank.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // ---------- distinctness: no two presets of an object are one sound ----------

    @Test fun `every two presets of an object are different recipes and different sounds`() {
        for (voice in ClutterVoice.entries) {
            val presets = ClutterPresets.forVoice(voice)
            for (i in presets.indices) for (j in i + 1 until presets.size) {
                val a = presets[i]; val b = presets[j]
                assertNotEquals(a.macros, b.macros, "${a.name} and ${b.name} are one recipe")
                assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .02, "${a.name} and ${b.name} sit on top of each other")
                assertFalse(rendered(a).contentEquals(rendered(b)), "${a.name} and ${b.name} render the same bytes")
            }
        }
    }
}
