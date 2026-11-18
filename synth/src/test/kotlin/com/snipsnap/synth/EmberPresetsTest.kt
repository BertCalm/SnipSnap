package com.snipsnap.synth

import com.snipsnap.synth.EmberKits.Kit
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
 * EMBER's factory bank: the five approved kits' pads as patches (round 4, after the owner approved all five kits
 * from the app's MPC 3 export on 2026-11-16). CLUTTER's six preset contracts: identity (each preset is a pad the
 * owner heard), sanity, round trip, names, the naming rule, distinctness; and the roster's registration.
 */
class EmberPresetsTest {
    private val bank = EmberPresets.all()

    private companion object {
        /** EMBER's long voices are seconds a render: the whole bank renders once, in parallel, for the class. */
        val renders: Map<EmberPatch, FloatArray> by lazy {
            EmberPresets.all().parallelStream().map { it to it.render().samples }.toList().toMap()
        }
    }

    private fun rendered(p: EmberPatch): FloatArray = renders.getValue(p)
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** Normalised correlation over the first [n] samples: the same shape at any level measures 1. */
    private fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until n) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return ab / sqrt(aa * bb)
    }

    // ---------- identity: the bank is the five approved kits, pad for pad ----------

    @Test fun `the bank is every pad of the five approved kits at the pad's own settings`() {
        assertEquals(Kit.entries.flatMap(EmberPresets::kitPatches), bank)
        assertEquals(Kit.entries.size * EmberKits.PADS, bank.size, "sixteen pads a kit, five kits")
        for (kit in Kit.entries) {
            val pads = EmberKits.pads(kit)
            val patches = EmberPresets.kitPatches(kit)
            assertEquals(pads.map { it.voice }, patches.map { it.voice }, "${kit.name}: pad order")
            for ((pad, patch) in pads.zip(patches)) {
                assertEquals(Ember.settled(pad.voice, pad.macros), patch.macros, "${patch.name}: the pad's own macros")
                assertEquals(Ember.macrosFor(pad.voice).map { it.name }.toSet(), patch.macros.keys, "${patch.name}: a complete map")
                assertEquals(1f, patch.velocity, "${patch.name}: struck full")
                assertEquals(EmberPresets.TAKE, patch.take, "${patch.name}: the kit grid's first take")
                assertEquals(Ember.MODEL_VERSION, patch.model)
            }
        }
        // Every voice is on a pad, so every voice has a preset.
        assertEquals(emptyList(), EmberVoice.entries.filter { EmberPresets.forVoice(it).isEmpty() })
    }

    @Test fun `every preset is the pad the owner heard`() {
        // The kit's hard zone, first take, is the engine's render of the pad's own macros through the kit's finish
        // and level law; the preset is that render at the engine's peak match, byte for byte.
        for (kit in Kit.entries) for ((pad, patch) in EmberKits.pads(kit).zip(EmberPresets.kitPatches(kit))) {
            assertContentEquals(Ember.render(pad.voice, pad.macros, 1f, 0).samples, rendered(patch), patch.name)
        }
        // And through the kit itself, on a kick, a brush sweep, a roll, the bow and the guiro: the same strike at
        // another level once the preset takes the kit's own finish (the mean out, a 20 Hz high-pass, the silent
        // tail cut), as SERVO's test does it.
        for ((kit, role) in listOf(Kit.CLUB to 0, Kit.AFTER_HOURS to 3, Kit.SOUL to 14, Kit.FREE to 4, Kit.LATIN to 5)) {
            val patch = EmberPresets.kitPatches(kit)[role]
            val preset = KitStrikes.finish(rendered(patch).copyOf(), 1f, ceiling = Float.MAX_VALUE).samples
            val pad = EmberKits.render(kit, role, 1f, 0).samples
            val c = correlation(preset, pad, min(preset.size, pad.size))
            assertTrue(c > .999, "${patch.name}: the preset is the kit's pad, correlation $c")
        }
    }

    // ---------- registration: the roster serves the bank and EMBER is no longer rosterless ----------

    @Test fun `the roster serves the bank and nothing else of EMBER`() {
        assertFalse(EmberPatch.ENGINE in PatchTypes.ROSTERLESS, "EMBER left the rosterless list with the owner's approval")
        assertFalse(EmberPatch.ENGINE in PatchTypes.PROTOTYPES)
        assertEquals(bank, Presets.all().filterIsInstance<EmberPatch>())
        for (voice in EmberVoice.entries) assertEquals(EmberPresets.forVoice(voice), Presets.forVoice(EmberPatch.ENGINE, voice.name))
        assertTrue(Presets.forVoice(EmberPatch.ENGINE, "UNKNOWN").isEmpty())
        for (patch in bank) assertEquals(patch, Presets.byName(EmberPatch.ENGINE, patch.voiceName, patch.name))
    }

    // ---------- sanity: every preset is a real, clean, finished sound ----------

    @Test fun `every preset renders finite, at the engine's peak, as long as its voice says, centred and ending quiet`() {
        for (patch in bank) {
            val x = rendered(patch)
            assertEquals((Ember.secondsFor(patch.voice, patch.macros) * Ember.RATE).toInt(), x.size, "${patch.name}: its voice's length")
            assertTrue(x.all { it.isFinite() }, "${patch.name}: finite")
            assertEquals(Ember.PEAK, peak(x), 1e-4f, "${patch.name}: the engine's peak match")
            assertTrue(abs(x.last()) < 1e-4f, "${patch.name}: ends in silence, at ${x.last()}")
            assertTrue(abs(x.average()) < .005, "${patch.name}: centred, mean ${x.average()}")
        }
    }

    // ---------- round trip: a preset is a Patch, with its strike outside the macro map ----------

    @Test fun `every preset round-trips through JSON with its velocity, take and model, and re-strikes by velocity`() {
        for (patch in bank) {
            val restored = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored, "${patch.name}: equal after a round trip")
        }
        // The same bytes after a round trip, and velocity re-strikes the same recipe: one pad of every voice.
        for (patch in bank.distinctBy { it.voice }) {
            assertContentEquals(rendered(patch), Patches.fromJsonText(patch.toJsonText()).render().samples, "${patch.name}: the same bytes after a round trip")
            val soft = Velocity.atVelocity(patch, .5f).samples
            assertContentEquals(patch.copy(velocity = .5f).render().samples, soft, "${patch.name}: velocity re-strikes the same recipe")
            assertFalse(soft.contentEquals(rendered(patch)), "${patch.name}: a softer strike is another sound")
        }
    }

    // ---------- names: unique, caps, from the kit, and never a maker's ----------

    @Test fun `preset names are unique across the bank, caps, and say which kit they came from`() {
        val names = bank.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
        assertEquals(listOf("CLUB", "AFTER HOURS", "SOUL JAZZ", "FREE", "LATIN JAZZ"), Kit.entries.map(EmberPresets::tag))
        val shortened = mutableListOf<String>()
        for (kit in Kit.entries) for ((pad, patch) in EmberKits.pads(kit).zip(EmberPresets.kitPatches(kit))) {
            assertEquals(patch.name.uppercase(), patch.name, "${patch.name}: caps like every chip")
            assertTrue(patch.name.startsWith(EmberPresets.tag(kit) + " "), "${patch.name}: named after its kit")
            // The house limit for a preset menu (CLUTTER's, CONTOUR's, DRUMLINE's).
            assertTrue(patch.name.length <= 24, "${patch.name}: short enough for a list")
            if (patch.name != "${EmberPresets.tag(kit)} ${pad.name}".uppercase()) shortened += patch.name
        }
        // Only the four that would not fit, and the timbale that pairs with one of them.
        assertEquals(
            listOf("AFTER HOURS SOFT CROSS", "AFTER HOURS BRUSH FLOOR", "FREE FLOOR, SNARES ON", "LATIN JAZZ TIMBAL MACHO", "LATIN JAZZ TIMBAL HEMBRA"),
            shortened,
        )
    }

    @Test fun `no preset name borrows a maker's name`() {
        val offenders = bank.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // ---------- distinctness: no two presets of a voice are one sound ----------

    @Test fun `every two presets of a voice are different recipes and different sounds`() {
        for (voice in EmberVoice.entries) {
            val presets = EmberPresets.forVoice(voice)
            for (i in presets.indices) for (j in i + 1 until presets.size) {
                val a = presets[i]; val b = presets[j]
                assertNotEquals(a.macros, b.macros, "${a.name} and ${b.name} are one recipe")
                assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .02, "${a.name} and ${b.name} sit on top of each other")
                assertFalse(rendered(a).contentEquals(rendered(b)), "${a.name} and ${b.name} render the same bytes")
            }
        }
    }
}
