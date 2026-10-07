package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercise saved sounds through the same recipe, rack, kit and velocity doors as the app. */
class NimbusIntegrationTest {

    @Test
    fun `all voices survive save edit and reopen with their independent geometry controls`() {
        val controls = setOf("TUNE", "EXCITE", "SPACING", "HEIGHT", "FIELD", "FUNNEL", "HOLD")
        for (voice in NimbusVoice.entries) {
            val macros = Nimbus.defaults(voice) + mapOf("TUNE" to 7 / 24f, "SPACING" to .21f, "HEIGHT" to .83f)
            val patch = NimbusPatch("Saved $voice", voice, macros)
            assertEquals(controls, Nimbus.macrosFor(voice).map { it.name }.toSet())
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, NimbusPatch.fromJsonText(patch.toJsonText()))
            val edited = Patches.edited(patch, "Edited $voice", macros + ("FIELD" to .72f))
            assertEquals(patch.copy(name = "Edited $voice", macros = macros + ("FIELD" to .72f)), edited)
            assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
        }
        val partial = NimbusPatch("Partial stack", NimbusVoice.GATHER, mapOf("SPACING" to .23f))
        val restored = Patches.fromJsonText(partial.toJsonText())
        assertContentEquals(partial.render().samples, restored.render().samples)
        assertContentEquals(
            Nimbus.render(partial.voice, Nimbus.defaults(partial.voice) + partial.macros).samples,
            restored.render().samples,
        )
    }

    @Test
    fun `invalid recipes are refused before they reach rendering`() {
        val patch = NimbusPatch("Saved", NimbusVoice.RING, emptyMap())
        fun changed(key: String, value: JsonValue): JsonValue.Obj = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).apply { put(key, value) },
        )
        assertFailsWith<JsonException> { NimbusPatch.fromJsonValue(changed("engine", JsonValue.Str("MURK"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("version", JsonValue.Num(99.0))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("voice", JsonValue.Str("BELL"))) }
        assertFailsWith<IllegalArgumentException> { NimbusPatch(" ", NimbusVoice.RING, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { NimbusPatch("Saved", NimbusVoice.RING, mapOf("VOLUME" to .5f)) }
        for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { NimbusPatch("Saved", NimbusVoice.RING, mapOf("FIELD" to value)) }
        }
    }

    @Test
    fun `the nine factory sounds are discoverable complete dry recipes`() {
        val expected = setOf(
            "SIX RINGS", "THIN CROWN", "DARK PLATE", "GATHERING STACK", "NARROW THROAT",
            "WIDE MOUTH", "SOFT FIELD", "RIM KISS", "HELD METAL",
        )
        val all = NimbusPresets.all()
        assertEquals(9, all.size)
        assertEquals(expected, all.map { it.name }.toSet())
        assertTrue(Presets.all().containsAll(all))
        for (voice in NimbusVoice.entries) {
            val presets = Presets.forVoice("NIMBUS", voice.name)
            assertEquals(NimbusPresets.forVoice(voice), presets)
            assertTrue(presets.isNotEmpty(), "$voice needs a selectable starting sound")
        }
        assertTrue(Presets.forVoice("NIMBUS", "UNKNOWN").isEmpty())
        for (patch in all) {
            assertEquals(Nimbus.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys, patch.name)
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
            assertTrue(patch.name.length <= 18)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
        }
        assertEquals(1, all.count { Nimbus.isLoop(it.macros.getValue("HOLD")) })
    }

    @Test
    fun `pitched factory rings and contact stay outside the drum routing guards`() {
        for (patch in NimbusPresets.all()) {
            val filed = Nimbus.drumClassFor(patch.voice, patch.macros)
            assertTrue(filed !in DRUMS, "${patch.name}: filed $filed")
            assertTrue(Classifier.classify(patch.render()).drumClass !in DRUMS, "${patch.name}: classified as drums")
            assertEquals(if (Nimbus.isLoop(patch.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL, filed)
        }
    }

    @Test
    fun `a saved metal recipe regenerates through the shared rack`() {
        val patch = NimbusPresets.forVoice(NimbusVoice.THROAT).first()
        val fx = FxChain(crunch = mapOf("BITS" to .4f, "RATE" to .25f))
        val recipe = PadRecipe(patch, fx)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        assertTrue(restored.alias)
        assertContentEquals(fx.process(patch.render()).samples, restored.render().samples)
    }

    @Test
    fun `the dry kit preserves the pentatonic walk six voices and held metal`() {
        val kit = SynthKits.nimbus()
        assertEquals(16, kit.size)
        val voices = mutableSetOf<NimbusVoice>()
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        for ((i, maybePad) in kit.withIndex()) {
            val pad = requireNotNull(maybePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = assertIs<NimbusPatch>(recipe.patch)
            voices += patch.voice
            assertNull(recipe.fx, "A${i + 1} lands dry")
            assertFalse(recipe.alias)
            assertEquals(Nimbus.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            assertTrue(pad.drumClass !in DRUMS)
            if (i < walk.size) {
                assertEquals(NimbusVoice.RING, patch.voice)
                assertEquals(48 + walk[i], Nimbus.midiFor(patch.voice, patch.macros.getValue("TUNE")))
            }
            if (i == 0 || i == kit.lastIndex) {
                assertContentEquals(pad.snip.samples, recipe.render().samples, "A${i + 1} regenerates exactly")
            }
        }
        assertEquals(NimbusVoice.entries.toSet(), voices)
        assertEquals(DrumClass.LOOP, kit.last()!!.drumClass)
    }

    @Test
    fun `host velocity changes event energy without editing the contact character`() {
        val patch = NimbusPresets.forVoice(NimbusVoice.CONTACT).first()
        val originalMacros = patch.macros.toMap()
        val soft = Velocity.atVelocity(patch, .25f)
        assertContentEquals(Nimbus.render(patch.voice, patch.macros, velocity = .25f).samples, soft.samples)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples))
        assertEquals(originalMacros, patch.macros)
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
