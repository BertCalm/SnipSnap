package com.snipsnap.synth

import com.snipsnap.synth.ServoKits.Kit
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
class ServoPresetsTest {
    private val bank = ServoPresets.all()

    private companion object {
        /** Renders are seconds for the rolls: each preset renders once for the whole class. */
        val renders = java.util.concurrent.ConcurrentHashMap<ServoPatch, FloatArray>()
    }

    private fun rendered(p: ServoPatch): FloatArray = renders.getOrPut(p) { p.render().samples }
    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** Normalised correlation over the first [n] samples: the same shape at any level measures 1. */
    private fun correlation(a: FloatArray, b: FloatArray, n: Int): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until n) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return ab / sqrt(aa * bb)
    }

    // ---------- identity: the bank is the four approved kits, pad for pad ----------

    @Test fun `the bank is every hit of the three approved kits at the pad's own settings`() {
        assertEquals(Kit.entries.flatMap(ServoPresets::kitPatches), bank)
        assertEquals(Kit.entries.size * ServoKits.HITS, bank.size, "fourteen hits a kit, three kits; the roll and riser are phrases")
        for (kit in Kit.entries) {
            val parts = ServoKits.parts(kit).take(ServoKits.HITS)
            val patches = ServoPresets.kitPatches(kit)
            assertEquals(parts.map { it.voice }, patches.map { it.voice }, "${kit.name}: pad order")
            for ((part, patch) in parts.zip(patches)) {
                assertEquals(Servo.settled(part.voice, part.macros), patch.macros, "${patch.name}: the pad's own macros")
                assertEquals(Servo.macrosFor(part.voice).map { it.name }.toSet(), patch.macros.keys, "${patch.name}: a complete map")
                assertEquals(1f, patch.velocity, "${patch.name}: struck full")
                assertEquals(ServoPresets.TAKE, patch.take, "${patch.name}: the kit grid's first take")
                assertEquals(Servo.MODEL_VERSION, patch.model)
            }
        }
        // Every voice but CRASH is on a pad; CRASH plays only inside the phrases, so it has no preset (disclosed).
        val missing = ServoVoice.entries.filter { ServoPresets.forVoice(it).isEmpty() }
        assertEquals(listOf(ServoVoice.CRASH), missing)
    }

    @Test fun `every preset is the pad the owner heard`() {
        // The kit's hard zone, first take, is the engine's render of the pad's own macros, through the kit's finish
        // and level law; the preset is that render at the engine's peak match, byte for byte.
        for (kit in Kit.entries) for ((part, patch) in ServoKits.parts(kit).take(ServoKits.HITS).zip(ServoPresets.kitPatches(kit))) {
            assertContentEquals(Servo.render(part.voice, part.macros, 1f, 0).samples, rendered(patch), patch.name)
        }
        // And through the kit itself, on a long kick, a hat and a bass stab: the same strike at another level once
        // the preset takes the kit's own finish (the mean out, a 20 Hz high-pass, the silent tail cut). Unfinished,
        // ELECTRO's 40-odd Hz boom correlates only 0.93: the high-pass shifts its phase, not its sound.
        for ((kit, role) in listOf(Kit.ELECTRO to 0, Kit.JIT to 2, Kit.NEURO to 13)) {
            val patch = ServoPresets.kitPatches(kit)[role]
            val preset = KitStrikes.finish(rendered(patch).copyOf(), 1f, ceiling = Float.MAX_VALUE).samples
            val pad = ServoKits.render(kit, role, 1f, 0).samples
            val n = min(preset.size, pad.size)
            val c = correlation(preset, pad, n)
            assertTrue(c > .999, "${patch.name}: the preset is the kit's pad, correlation $c")
        }
    }

    // ---------- registration: the roster serves the bank and CALABASH is no longer rosterless ----------

    @Test fun `the roster serves the bank and nothing else of CALABASH`() {
        assertFalse(ServoPatch.ENGINE in PatchTypes.ROSTERLESS, "CALABASH left the rosterless list with the owner's approval")
        assertFalse(ServoPatch.ENGINE in PatchTypes.PROTOTYPES)
        assertEquals(bank, Presets.all().filterIsInstance<ServoPatch>())
        for (voice in ServoVoice.entries) assertEquals(ServoPresets.forVoice(voice), Presets.forVoice(ServoPatch.ENGINE, voice.name))
        assertTrue(Presets.forVoice(ServoPatch.ENGINE, "UNKNOWN").isEmpty())
        for (patch in bank) assertEquals(patch, Presets.byName(ServoPatch.ENGINE, patch.voiceName, patch.name))
    }

    // ---------- sanity: every preset is a real, clean, finished sound ----------

    @Test fun `every preset renders finite, bounded, at the engine's peak, centred and faded out`() {
        for (patch in bank) {
            val x = rendered(patch)
            assertTrue(x.size > 0.050f * Dsp.RATE, "${patch.name}: longer than 50 ms")
            assertTrue(x.size < 1.5f * Dsp.RATE, "${patch.name}: a one-shot, under the loop line")
            assertTrue(x.all { it.isFinite() }, "${patch.name}: finite")
            assertEquals(Servo.PEAK, peak(x), 1e-4f, "${patch.name}: the engine's peak match")
            assertTrue(abs(x.last()) < 1e-7f, "${patch.name}: fades to nothing, ends at ${x.last()}")
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
        // Velocity re-strikes the same recipe; one pad of every voice in the bank.
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
        assertEquals(listOf("ELECTRO", "JIT", "NEURO"), Kit.entries.map(ServoPresets::tag))
        for (kit in Kit.entries) for ((part, patch) in ServoKits.parts(kit).take(ServoKits.HITS).zip(ServoPresets.kitPatches(kit))) {
            assertEquals("${ServoPresets.tag(kit)} ${part.name}".uppercase(), patch.name)
            // The house limit for a preset menu (CLUTTER's, CONTOUR's, DRUMLINE's).
            assertTrue(patch.name.length <= 24, "${patch.name}: short enough for a list")
        }
    }

    @Test fun `no preset name borrows a maker's name`() {
        val offenders = bank.filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real machine: ${offenders.map { it.name }}")
    }

    // ---------- distinctness: no two presets of a voice are one sound ----------

    @Test fun `every two presets of a voice are different recipes and different sounds`() {
        for (voice in ServoVoice.entries) {
            val presets = ServoPresets.forVoice(voice)
            for (i in presets.indices) for (j in i + 1 until presets.size) {
                val a = presets[i]; val b = presets[j]
                assertNotEquals(a.macros, b.macros, "${a.name} and ${b.name} are one recipe")
                assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .02, "${a.name} and ${b.name} sit on top of each other")
                assertFalse(rendered(a).contentEquals(rendered(b)), "${a.name} and ${b.name} render the same bytes")
            }
        }
    }
}
