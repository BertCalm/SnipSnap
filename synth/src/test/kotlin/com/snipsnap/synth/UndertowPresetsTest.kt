package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Product contracts: the named roster, saved recipes, pitched filing, kit layout and velocity path. */
class UndertowPresetsTest {

    @Test
    fun `the suggested twelve sounds name every control and round-trip every voice`() {
        val names = setOf(
            "FIRST DRAW", "CERAMIC RIM", "SOFT INLET", "HOLLOW BREATH", "LOOSE LEATHER", "ALTERNATING SEAL",
            "DEEP SPIRAL", "HEAVY CATCH", "GENTLE BYPASS", "FLUTTER CHAMBER", "RETURNING AIR", "HELD SUCTION",
        )
        val controls = setOf("TUNE", "DRAW", "FLAP", "WEIGHT", "SPIRAL", "LEAK", "HOLD")
        val all = UndertowPresets.all()
        assertEquals(names, all.map { it.name }.toSet())
        assertEquals(12, all.size)
        for (voice in UndertowVoice.entries) {
            assertEquals(2, UndertowPresets.forVoice(voice).size, "$voice has two shell gestures")
            assertEquals(UndertowPresets.forVoice(voice), Presets.forVoice("UNDERTOW", voice.name))
        }
        assertEquals(setOf("FIRST DRAW", "CERAMIC RIM"), UndertowPresets.forVoice(UndertowVoice.KNOCK).map { it.name }.toSet())
        assertTrue(Presets.all().containsAll(all))
        assertTrue(Presets.forVoice("UNDERTOW", "NOT_A_VOICE").isEmpty())
        for (preset in all) {
            assertEquals(controls, preset.macros.keys, preset.name)
            assertEquals(Undertow.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, preset.name)
            assertTrue(preset.name.length <= 18, preset.name)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name), preset.name)
            val semitone = preset.macros.getValue("TUNE") * Undertow.TUNE_SEMITONES
            assertTrue(abs(semitone - semitone.roundToInt()) < 1e-5f, "${preset.name}: semitone tuning")
            assertEquals(preset, Patches.fromJsonText(preset.toJsonText()), preset.name)
            assertEquals(preset, Presets.byName(preset.engine, preset.voiceName, preset.name))
            assertEquals(null, Presets.landingFor(preset.engine, preset.voiceName, preset.macros))
        }
    }

    @Test
    fun `factory shell gestures preserve pitched tails and route by duration`() {
        for (preset in UndertowPresets.all()) {
            val filed = Undertow.drumClassFor(preset.voice, preset.macros)
            val heard = Classifier.classify(preset.render()).drumClass
            assertTrue(filed !in DRUMS, "${preset.voice}/${preset.name}: filed $filed")
            assertTrue(heard !in DRUMS, "${preset.voice}/${preset.name}: classified $heard")
            assertEquals(DrumClass.LOOP, filed, "${preset.voice}/${preset.name}: the shell gesture exceeds one-shot routing duration")
            assertEquals(filed, heard, "${preset.voice}/${preset.name}: duration-aware routing")
        }
    }

    @Test
    fun `a saved shell recipe regenerates through the existing effects chain`() {
        val patch = UndertowPresets.forVoice(UndertowVoice.BREATH).first()
        val fx = FxChain(crunch = mapOf("BITS" to 0.40f, "RATE" to 0.25f))
        val recipe = PadRecipe(patch, fx)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        assertTrue(restored.alias, "the existing deliberate-converter metadata survives")
        assertContentEquals(fx.process(patch.render()).samples, restored.render().samples)
    }

    @Test
    fun `the dry kit carries regenerable recipes and a pentatonic pitch walk`() {
        val kit = SynthKits.undertow()
        assertEquals(16, kit.size)
        val voices = mutableSetOf<UndertowVoice>()
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        for ((i, maybePad) in kit.withIndex()) {
            val pad = requireNotNull(maybePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = recipe.patch as UndertowPatch
            voices += patch.voice
            assertEquals(null, recipe.fx, "A${i + 1} is dry")
            assertFalse(recipe.alias)
            assertEquals(Undertow.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            assertContentEquals(pad.snip.samples, recipe.render().samples, "A${i + 1} regenerates exactly")
            if (i < walk.size) {
                assertEquals(UndertowVoice.KNOCK, patch.voice)
                assertEquals(48 + walk[i], Undertow.midiFor(patch.voice, patch.macros.getValue("TUNE")))
            }
        }
        assertEquals(UndertowVoice.entries.toSet(), voices)
        val held = PadRecipe.fromJsonValue(requireNotNull(kit.last()!!.recipe)).patch as UndertowPatch
        assertEquals("HELD SUCTION", held.name)
        assertEquals(1f, held.macros.getValue("HOLD"))
        assertEquals(DrumClass.LOOP, kit.last()!!.drumClass)
    }

    @Test
    fun `velocity changes the powered draw without editing the saved shell character`() {
        val patch = UndertowPresets.forVoice(UndertowVoice.KNOCK).first()
        val original = patch.macros.toMap()
        val soft = Velocity.atVelocity(patch, 0.25f)
        assertContentEquals(Undertow.render(patch.voice, patch.macros, velocity = 0.25f).samples, soft.samples)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples), "soft draw changes synthesis")
        assertEquals(original, patch.macros, "the saved sound does not move")
    }

    @Test
    fun `saved shell recipes refuse unknown and nonfinite controls before rendering`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.01f, 1.01f)) {
            val refused = assertFailsWith<IllegalArgumentException> {
                UndertowPatch("Invalid draw", UndertowVoice.KNOCK, mapOf("DRAW" to value))
            }
            assertTrue("DRAW" in refused.message.orEmpty(), "the refusal names the bad control")
        }
        assertFailsWith<IllegalArgumentException> {
            UndertowPatch("Unknown control", UndertowVoice.KNOCK, mapOf("PRESSURE" to .5f))
        }
        assertFailsWith<IllegalArgumentException> {
            UndertowPatch(" ", UndertowVoice.KNOCK, emptyMap())
        }
        val text = UndertowPresets.forVoice(UndertowVoice.KNOCK).first().toJsonText()
        assertFailsWith<JsonException> {
            Patches.fromJsonText(text.replace("\"KNOCK\"", "\"NOT_A_VOICE\""))
        }
    }

    @Test
    fun `direct rendering sanitizes hostile values to the same finite playable recipe`() {
        val voice = UndertowVoice.BREATH
        val hostile = mapOf(
            "TUNE" to Float.NaN, "DRAW" to Float.NaN, "FLAP" to Float.POSITIVE_INFINITY,
            "WEIGHT" to Float.NEGATIVE_INFINITY, "SPIRAL" to 2f, "LEAK" to -.1f,
            "HOLD" to Float.NaN,
        )
        val expected = Undertow.defaults(voice) + mapOf("SPIRAL" to 1f, "LEAK" to 0f)
        val rendered = Undertow.render(voice, hostile, velocity = Float.NaN)
        assertTrue(rendered.samples.isNotEmpty() && rendered.samples.all { it.isFinite() })
        assertContentEquals(Undertow.render(voice, expected, velocity = 1f).samples, rendered.samples)
        assertEquals(60, Undertow.midiFor(voice, Float.NaN))
        assertEquals(48, Undertow.midiFor(voice, -1f))
        assertEquals(72, Undertow.midiFor(voice, 2f))
        assertFalse(Undertow.isLoop(Float.NaN))
        assertFalse(Undertow.isLoop(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `cancelled held rendering stops during preroll and public renders honor interruption`() {
        var checks = 0
        assertFailsWith<CancellationException> {
            Undertow.renderLoop(UndertowVoice.SURGE, Undertow.defaults(UndertowVoice.SURGE)) {
                ++checks >= 2
            }
        }
        assertEquals(2, checks, "cancellation was checked after rendering began")
        val current = Thread.currentThread()
        val alreadyInterrupted = current.isInterrupted
        try {
            current.interrupt()
            assertFailsWith<CancellationException> { UndertowPresets.all().first().render() }
        } finally {
            Thread.interrupted()
            if (alreadyInterrupted) current.interrupt()
        }
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
