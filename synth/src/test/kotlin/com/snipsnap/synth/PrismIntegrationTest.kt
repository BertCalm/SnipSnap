package com.snipsnap.synth

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

/** Saved additive recipes travel through the same editing, rack, kit and velocity doors as the app. */
class PrismIntegrationTest {
    @Test
    fun `voice note energy and template survive save edit and reopen`() {
        for (voice in PrismVoice.entries) {
            val template = Prism.templateFor(voice).copy(
                ratioOverrides = listOf(1f, 2f, 3f),
                amplitudeOverrides = listOf(.9f, .4f, .2f),
                groupProfiles = List(5) { i -> PrismGroupProfile(attackScale = 1f + i * .1f,
                    decayScale = 1f + i * .05f, delaySeconds = i * .01f,
                    motionCycles = i + 1, motionDepth = .5f) },
                attackSeconds = .015f, decaySeconds = 1.2f, loopSeconds = 2f,
            )
            val patch = PrismPatch("Saved facets", voice, Prism.defaults(voice) + ("TUNE" to .25f),
                velocity = .65f, template = template)
            assertEquals(patch, PrismPatch.fromJsonText(patch.toJsonText()), voice.name)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), voice.name)
            val editedMacros = patch.macros + ("SHAPE" to .72f)
            val edited = Patches.edited(patch, "Edited facets", editedMacros)
            assertEquals(patch.copy(name = "Edited facets", macros = editedMacros), edited)
            assertEquals(patch.copy(macros = editedMacros), patch.withMacros(editedMacros))
            assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
            val automaticContours = patch.copy(template = template.copy(attackSeconds = null, decaySeconds = null))
            assertEquals(automaticContours, Patches.fromJsonText(automaticContours.toJsonText()),
                "explicit automatic contour times survive as null")
        }
        val sparse = PrismPatch("Partial recipe", PrismVoice.KEY, mapOf("GRAIN" to .23f))
        assertEquals(sparse, Patches.fromJsonText(sparse.toJsonText()), "omitted controls remain implicit")
        val oldFormat = JsonValue.Obj(sparse.toJsonValue().entries - setOf("velocity", "template", "model"))
        assertEquals(sparse, Patches.fromJsonValue(oldFormat), "omitted optional fields select established defaults")
        val glass = PrismPatch("Bell map", PrismVoice.GLASS, Prism.defaults(PrismVoice.GLASS),
            template = Prism.templateFor(PrismVoice.GLASS).copy(spectralId = PrismSpectrum.BELL,
                envelopeId = PrismContour.RINGING, ratioOverrides = listOf(1f, 2.7f, 4.3f)))
        assertEquals(glass, Patches.fromJsonText(glass.toJsonText()), "bounded bell ratios are part of the recipe")
    }

    @Test
    fun `legacy glass missing null and empty templates retain the original model`() {
        val legacy = PrismPatch("Legacy glass", PrismVoice.GLASS, Prism.defaults(PrismVoice.GLASS),
            template = PrismTemplate(PrismSpectrum.BELL, PrismContour.RINGING))
        val sparse = legacy.toJsonValue().entries - setOf("template", "velocity", "model")
        val originalAudio = legacy.render().samples
        val recipes = listOf(
            JsonValue.Obj(sparse),
            JsonValue.Obj(sparse + ("template" to JsonValue.Null)),
            JsonValue.Obj(sparse + ("template" to JsonValue.Obj(emptyMap()))),
        )
        for (recipe in recipes) {
            val reopened = assertIs<PrismPatch>(Patches.fromJsonValue(recipe))
            assertEquals(legacy, reopened, "missing legacy data selects the original model-1 defaults")
            assertContentEquals(originalAudio, reopened.render().samples,
                "factory calibration never changes the replay of a legacy sparse recipe")
        }
    }

    @Test
    fun `sparse legacy glass templates preserve a custom harmonic spectrum`() {
        for (spectrum in PrismSpectrum.entries.filter { it != PrismSpectrum.BELL }) {
            val expected = PrismPatch("Custom glass", PrismVoice.GLASS, Prism.defaults(PrismVoice.GLASS),
                template = PrismTemplate(spectrum, PrismContour.RINGING))
            val fields = mapOf("spectralId" to JsonValue.Str(spectrum.name))
            for (template in listOf(
                JsonValue.Obj(fields),
                JsonValue.Obj(fields + mapOf("ratioOverrides" to JsonValue.Null, "amplitudeOverrides" to JsonValue.Null)),
            )) {
                val recipe = JsonValue.Obj(expected.toJsonValue().entries + ("template" to template))
                assertEquals(expected, Patches.fromJsonValue(recipe),
                    "$spectrum uses its original harmonic ratios and weights when overrides are omitted")
            }
        }
    }

    @Test
    fun `explicit empty legacy bell templates round trip and replay exactly`() {
        for (hold in listOf(0f, 1f)) {
            val original = PrismPatch("Original bell", PrismVoice.GLASS,
                Prism.defaults(PrismVoice.GLASS) + ("HOLD" to hold), velocity = .65f,
                template = PrismTemplate(PrismSpectrum.BELL, PrismContour.RINGING, loopSeconds = 1f))
            val reopened = PrismPatch.fromJsonText(original.toJsonText())
            assertEquals(original, reopened)
            assertTrue(reopened.template.ratioOverrides.isEmpty())
            assertTrue(reopened.template.amplitudeOverrides.isEmpty())
            assertContentEquals(original.render().samples, reopened.render().samples,
                "an explicit original BELL recipe replays exactly for HOLD $hold")
        }
    }

    @Test
    fun `calibrated glass saves its complete model through edits and reopening`() {
        val patch = PrismPatch("Calibrated glass", PrismVoice.GLASS, Prism.defaults(PrismVoice.GLASS), velocity = .7f)
        assertEquals(Prism.MODEL_VERSION, patch.model)
        assertEquals(Prism.PARTIALS, patch.template.ratioOverrides.size)
        assertEquals(Prism.PARTIALS, patch.template.amplitudeOverrides.size)
        val reopened = PrismPatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, reopened)
        assertContentEquals(patch.render().samples, reopened.render().samples)

        val macros = patch.macros + ("SHAPE" to .72f)
        val expected = patch.copy(name = "Edited glass", macros = macros)
        val edited = assertIs<PrismPatch>(Patches.edited(reopened, expected.name, macros))
        val restored = assertIs<PrismPatch>(PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
        assertEquals(expected, edited)
        assertEquals(expected, restored)
        assertContentEquals(expected.render().samples, restored.render().samples,
            "the calibrated ratios and weights remain explicit model-1 data after editing")
    }

    @Test
    fun `invalid saved identities and controls are refused before rendering`() {
        val patch = PrismPatch("Facets", PrismVoice.KEY, emptyMap())
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).apply { put(key, value) },
        )
        assertFailsWith<JsonException> { PrismPatch.fromJsonValue(changed("engine", JsonValue.Str("NIMBUS"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("version", JsonValue.Num(99.0))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("voice", JsonValue.Str("KICK"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("model", JsonValue.Num(99.0))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("model", JsonValue.Num(1.5))) }
        val savedTemplate = patch.toJsonValue().entries.getValue("template") as JsonValue.Obj
        val wrongSpectrum = JsonValue.Obj(savedTemplate.entries + ("spectralId" to JsonValue.Str("UNKNOWN")))
        val wrongContour = JsonValue.Obj(savedTemplate.entries + ("envelopeId" to JsonValue.Str("UNKNOWN")))
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("template", wrongSpectrum)) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("template", wrongContour)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("REVERB" to .5f)) }
        for (bad in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("GRAIN" to bad)) }
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = bad) }
            assertFailsWith<IllegalArgumentException> { Patches.fromJsonValue(changed("velocity", JsonValue.Num(bad.toDouble()))) }
        }
        for (field in listOf("attackSeconds", "decaySeconds", "loopSeconds")) {
            val nonFinite = JsonValue.Obj(savedTemplate.entries + (field to JsonValue.Num(Double.POSITIVE_INFINITY)))
            assertFailsWith<IllegalArgumentException> { Patches.fromJsonValue(changed("template", nonFinite)) }
        }
        for (field in listOf("ratioOverrides", "amplitudeOverrides")) {
            val overflow = JsonValue.Obj(savedTemplate.entries + (field to JsonValue.Arr(listOf(JsonValue.Num(1e40)))))
            assertFailsWith<IllegalArgumentException> { Patches.fromJsonValue(changed("template", overflow)) }
        }
    }

    @Test
    fun `dry factory recipes are discoverable through the common roster`() {
        val presets = PrismPresets.all()
        assertTrue(presets.isNotEmpty())
        assertEquals(presets.size, presets.map { it.name }.toSet().size, "every authored sound has its own name")
        assertEquals(presets, Presets.all().filter { it.engine == PrismPatch.ENGINE })
        for (voice in PrismVoice.entries) {
            val group = PrismPresets.forVoice(voice)
            assertTrue(group.isNotEmpty(), "$voice has a selectable dry starting sound")
            assertEquals(group, Presets.forVoice(PrismPatch.ENGINE, voice.name))
            for (preset in group) {
                assertEquals(Prism.macrosFor(voice).map { it.name }.toSet(), preset.macros.keys, preset.name)
                assertEquals(preset, Presets.byName(preset.engine, preset.voiceName, preset.name))
                assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
                assertNull(Presets.landingFor(preset.engine, preset.voiceName, preset.macros))
                assertTrue(preset.name.length <= 18, preset.name)
                assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name), preset.name)
            }
        }
        assertTrue(Presets.forVoice(PrismPatch.ENGINE, "UNKNOWN").isEmpty())
    }

    @Test
    fun `native host velocity preserves note macros model and custom template`() {
        val patch = PrismPatch("Touch facets", PrismVoice.PLUCK,
            Prism.defaults(PrismVoice.PLUCK) + mapOf("TUNE" to .375f, "STRIKE" to .8f), velocity = .9f,
            template = Prism.templateFor(PrismVoice.PLUCK).copy(attackSeconds = .008f, decaySeconds = .9f))
        val original = patch.toJsonText()
        val soft = Velocity.atVelocity(patch, .25f)
        assertContentEquals(patch.copy(velocity = .25f).render().samples, soft.samples,
            "the shared velocity path must render the complete saved additive recipe")
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples))
        assertEquals(original, patch.toJsonText(), "velocity layers never edit the source recipe")
    }

    @Test
    fun `held zones map exact notes and carry a seamless nonzero loop marker`() {
        for (voice in PrismVoice.entries) {
            assertEquals((0..24 step 3).map { Prism.rootMidi(voice) + it }, Keys.prismPadMidis(voice))
        }
        val voice = PrismVoice.GLASS
        val midi = Prism.rootMidi(voice) + 7
        val macros = Prism.defaults(voice) + mapOf("TUNE" to 0f, "GRAIN" to .8f, "HOLD" to 0f)
        val template = Prism.templateFor(voice).copy(loopSeconds = 2f)
        val note = Keys.prismPad(voice, macros, midi, velocity = .35f, template = template)
        val marker = note.loopStartFrame.toInt()
        assertTrue(marker > 0)
        assertEquals(marker * 2, note.snip.frameCount)
        assertContentEquals(note.snip.samples.copyOfRange(0, marker), note.snip.samples.copyOfRange(marker, marker * 2))
        assertTrue(Keys.seamError(note.snip.samples, marker) < Keys.MAX_SEAM_ERROR)
        val expected = Prism.renderLoop(voice, macros + mapOf(
            "TUNE" to 7 / Prism.TUNE_SEMITONES.toFloat(), "HOLD" to 1f), velocity = .35f, template = template)
        assertContentEquals(expected, note.snip.samples.copyOfRange(marker, marker * 2),
            "held zones preserve note energy and the saved spectral template")
        val silent = Keys.prismPad(voice, macros, midi, velocity = 0f, template = template)
        assertEquals(note.loopStartFrame, silent.loopStartFrame)
        assertEquals(note.snip.frameCount, silent.snip.frameCount)
        assertTrue(silent.snip.samples.all { it == 0f }, "zero velocity exports a silent held zone")
        assertFailsWith<IllegalArgumentException> { Keys.prismPad(voice, macros, Prism.rootMidi(voice) - 1) }
        assertFailsWith<IllegalArgumentException> { Keys.prismPad(voice, macros, Prism.rootMidi(voice) + 25) }
    }

    @Test
    fun `pitched kit retains all six voices dry recipes and its declared pentatonic key`() {
        val kit = SynthKits.prism()
        assertEquals(16, kit.size)
        val patches = kit.mapIndexed { i, nullable ->
            val pad = requireNotNull(nullable)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = assertIs<PrismPatch>(recipe.patch)
            assertNull(recipe.fx, "A${i + 1} lands dry")
            assertFalse(recipe.alias)
            assertEquals(Prism.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            assertTrue(pad.drumClass == DrumClass.TONAL || pad.drumClass == DrumClass.LOOP)
            assertTrue(pad.snip.peak() > 0f, "A${i + 1} carries audio")
            assertTrue(Prism.midiFor(patch.voice, patch.macros.getValue("TUNE")) % 12 in setOf(0, 3, 5, 7, 10),
                "A${i + 1} stays in C minor pentatonic")
            if (i == 0 || i == kit.lastIndex) assertContentEquals(pad.snip.samples, recipe.render().samples)
            patch
        }
        assertEquals(PrismVoice.entries.toSet(), patches.map { it.voice }.toSet())
        assertEquals(1, kit.count { it!!.drumClass == DrumClass.LOOP })
        assertEquals(DrumClass.LOOP, kit.last()!!.drumClass)
    }
}
