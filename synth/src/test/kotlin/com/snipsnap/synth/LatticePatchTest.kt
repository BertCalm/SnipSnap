package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Native persistence and host routing, separate from the accepted projection fixtures. */
class LatticePatchTest {
    private val originalVoices = listOf(LatticeVoice.WARM, LatticeVoice.PLUCK, LatticeVoice.HOLLOW)
    private val controls = listOf("TUNE", "TURN", "STAGES", "TWIST", "COLOR", "REJECT", "HOLD")

    @Test
    fun `original source voices keep their mechanism pitch and deliberate loop controls`() {
        assertEquals(listOf("WARM", "PLUCK", "HOLLOW", "TRACE"), LatticeVoice.entries.map { it.name })
        for (voice in originalVoices) {
            assertEquals(controls, Lattice.macrosFor(voice).map { it.name }, "$voice controls")
            assertEquals(controls.toSet(), Lattice.defaults(voice).keys)
            assertEquals(.5f, Lattice.defaults(voice).getValue("TUNE"))
            assertEquals(0f, Lattice.defaults(voice).getValue("HOLD"))
            assertEquals(DrumClass.TONAL, Lattice.drumClassFor(voice))
            assertEquals(DrumClass.LOOP, Lattice.drumClassFor(voice, mapOf("HOLD" to 1f)))
            assertEquals(36, Lattice.midiFor(voice, 0f, midi = 48))
            assertEquals(48, Lattice.midiFor(voice, .5f, midi = 48))
            assertEquals(60, Lattice.midiFor(voice, 1f, midi = 48))
            assertEquals(Lattice.MIDI_MIN, Lattice.midiFor(voice, 0f, midi = Lattice.MIDI_MIN))
            assertEquals(Lattice.MIDI_MAX, Lattice.midiFor(voice, 1f, midi = Lattice.MIDI_MAX))
        }
    }

    @Test
    fun `native patch JSON preserves model note excitation and partial controls`() {
        for (voice in originalVoices) {
            val patch = LatticePatch("Saved $voice", voice, mapOf("COLOR" to .37f), midi = 55, velocity = .63f)
            val fields = patch.toJsonValue().entries
            assertEquals("LATTICE", fields.getValue("engine").str())
            assertEquals(Patches.VERSION, fields.getValue("version").int())
            assertEquals(2, fields.getValue("model").int())
            assertEquals(55, fields.getValue("midi").int())
            assertEquals(.63f, fields.getValue("velocity").num().toFloat())
            assertEquals(setOf("COLOR"), fields.getValue("macros").obj().keys)
            assertEquals(patch, LatticePatch.fromJsonText(patch.toJsonText()))
            assertEquals(patch, assertIs<LatticePatch>(Patches.fromJsonText(patch.toJsonText())))

            val common = fields - setOf("midi", "velocity", "model")
            for (saved in listOf(common, common + mapOf(
                "midi" to JsonValue.Null, "velocity" to JsonValue.Null, "model" to JsonValue.Null,
            ))) {
                val reopened = assertIs<LatticePatch>(Patches.fromJsonValue(JsonValue.Obj(saved)))
                assertEquals(48, reopened.midi)
                assertEquals(.75f, reopened.velocity)
                assertEquals(2, reopened.model, "the first native render model remains explicit")
                assertEquals(patch.macros, reopened.macros)
            }
        }
    }

    @Test
    fun `invalid native identities performance and macro fields are refused before rendering`() {
        val patch = LatticePatch("Valid", LatticeVoice.WARM, emptyMap())
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        for (model in listOf(1, 3)) assertFailsWith<IllegalArgumentException> { patch.copy(model = model) }
        for (midi in listOf(35, 85)) assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("UNKNOWN" to .5f)) }
        for (bad in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = bad) }
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("TURN" to bad)) }
        }
        val fields = patch.toJsonValue().entries
        val invalid = listOf(
            "model" to JsonValue.Num(1.0), "model" to JsonValue.Num(2.5),
            "midi" to JsonValue.Num(35.0), "midi" to JsonValue.Num(85.0),
            "midi" to JsonValue.Num(55.5), "velocity" to JsonValue.Num(-.1),
            "velocity" to JsonValue.Num(1.1), "velocity" to JsonValue.Num(Double.NaN),
            "velocity" to JsonValue.Bool(true), "version" to JsonValue.Num(99.0),
            "voice" to JsonValue.Str("UNKNOWN"),
            "macros" to JsonValue.Arr(emptyList()),
            "macros" to JsonValue.Obj(mapOf("UNKNOWN" to JsonValue.Num(.5))),
            "macros" to JsonValue.Obj(mapOf("TURN" to JsonValue.Num(1.1))),
        )
        for ((key, value) in invalid) {
            assertFailsWith<JsonException>("malformed $key $value") {
                Patches.fromJsonValue(JsonValue.Obj(fields + (key to value)))
            }
        }
        assertFailsWith<JsonException> {
            LatticePatch.fromJsonValue(JsonValue.Obj(fields + ("engine" to JsonValue.Str("PRISM"))))
        }
    }

    @Test
    fun `editing and saving finite or held pads retains complete native state and exact audio`() {
        for (hold in listOf(0f, 1f)) {
            val source = LatticePatch("Low outline", LatticeVoice.PLUCK,
                mapOf("TUNE" to .625f, "COLOR" to .54f, "REJECT" to .27f, "HOLD" to hold),
                midi = 43, velocity = .54f)
            val moved = source.macros + ("TWIST" to .71f)
            assertEquals(source.copy(macros = moved), source.withMacros(moved))
            val edited = assertIs<LatticePatch>(Patches.edited(source, "Moved outline", moved))
            assertEquals(source.copy(name = "Moved outline", macros = moved), edited)
            val saved = PadRecipe(edited)
            val reopened = PadRecipe.fromJsonText(saved.toJsonText())
            assertEquals(saved, reopened)
            assertNull(reopened.fx, "the native source and projection chain stay dry")
            assertContentEquals(edited.render().samples, reopened.render().samples,
                "saved MIDI, velocity, model and controls reproduce HOLD $hold")
        }
    }

    @Test
    fun `host velocity reaches source excitation without spending a mechanism control or saved note`() {
        val source = LatticePatch("Touch outline", LatticeVoice.HOLLOW,
            mapOf("TUNE" to .625f, "TURN" to .88f, "STAGES" to .38f,
                "TWIST" to .72f, "COLOR" to .63f, "REJECT" to .28f),
            midi = 55, velocity = .9f)
        val document = source.toJsonText()
        val expected = source.copy(velocity = .35f).render().samples
        assertContentEquals(expected, Velocity.atVelocity(source, .35f).samples)
        assertContentEquals(expected, Velocity.atVelocity(source, .35f, spec = null).samples,
            "cached brightness lookup must not bypass native excitation")
        assertFalse(expected.contentEquals(source.render().samples), "velocity did not reach the source")
        assertFalse(expected.contentEquals(source.copy(midi = 48, velocity = .35f).render().samples),
            "the saved root was replaced by a default note")
        assertEquals(document, source.toJsonText(), "rendering a layer must not edit the saved patch")
    }

    @Test
    fun `native COLOR changes the rendered phrase body after removing overall level`() {
        val source = LatticePatch("Color fixture", LatticeVoice.PLUCK,
            mapOf("TURN" to .90f, "STAGES" to 0f, "COLOR" to 0f, "REJECT" to 0f),
            midi = 48, velocity = .62f)
        val a = source.render().samples
        val b = source.withMacros(source.macros + ("COLOR" to 1f)).render().samples
        assertEquals(a.size, b.size)
        val aa = a.sumOf { it.toDouble() * it }
        val bb = b.sumOf { it.toDouble() * it }
        val ab = a.indices.sumOf { a[it].toDouble() * b[it] }
        assertTrue(aa > 1e-8 && bb > 1e-8)
        val residual = sqrt((1.0 - ab * ab / (aa * bb)).coerceIn(0.0, 1.0))
        assertTrue(residual > .2, "native mapping hid COLOR behind an overall gain: $residual")
    }

    @Test
    fun `the twelve factory candidates are discoverable complete dry and non silent`() {
        val all = LatticePresets.all()
        val bank = all.filter { it.voice in originalVoices }
        assertEquals(12, bank.size)
        assertEquals(bank.size, bank.map { it.name }.toSet().size)
        assertEquals(all, Presets.all().filter { it.engine == LatticePatch.ENGINE })
        for (voice in originalVoices) {
            val presets = LatticePresets.patchesForVoice(voice)
            assertEquals(4, presets.size)
            assertEquals(bank.filter { it.voice == voice }, presets)
            assertEquals(presets, Presets.forVoice(LatticePatch.ENGINE, voice.name))
            assertEquals(1, presets.count { Lattice.isLoop(it.macros.getValue("HOLD")) })
            assertEquals(presets.size, presets.map { it.macros - setOf("TUNE", "HOLD") }.toSet().size,
                "$voice should offer distinct geometry, rather than only renamed pitch or HOLD settings")
        }
        for (patch in bank) {
            assertEquals(controls.toSet(), patch.macros.keys, patch.name)
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
            val rendered = patch.render()
            assertEquals(1, rendered.channels)
            assertEquals(44_100, rendered.sampleRate)
            assertTrue(rendered.samples.all { it.isFinite() && abs(it) < .99f }, patch.name)
            assertTrue(rendered.samples.any { abs(it) > 1e-5f }, "${patch.name} is a silent factory candidate")
        }
        assertTrue(Presets.forVoice(LatticePatch.ENGINE, "UNKNOWN").isEmpty())
    }

    @Test
    fun `TRACE factory patches save their speed and finite hold without legacy geometry controls`() {
        val all = LatticePresets.all()
        val trace = LatticePresets.forVoice(LatticeVoice.TRACE)
        assertEquals(15, all.size)
        assertEquals(all.size, all.map { it.name }.toSet().size)
        assertEquals(listOf("Ringing Trace", "Slow Trace", "Quick Trace"), trace.map { it.name })
        assertEquals(listOf(.5f, .25f, .75f), trace.map { it.macros.getValue("SPEED") })
        assertEquals(listOf("TUNE", "SPEED", "HOLD"), Lattice.macrosFor(LatticeVoice.TRACE).map { it.name })
        assertEquals(Lattice.defaults(LatticeVoice.TRACE), trace.first().macros)
        assertEquals(trace, Presets.forVoice(LatticePatch.ENGINE, "TRACE"))
        for (patch in trace) {
            assertEquals(setOf("TUNE", "SPEED", "HOLD"), patch.macros.keys)
            assertFalse(Lattice.isLoop(patch.voice, patch.macros.getValue("HOLD")))
            assertEquals(DrumClass.TONAL, Lattice.drumClassFor(patch.voice, patch.macros))
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
        }
        val saved = trace.first().copy(name = "Saved moving trace", midi = 55, velocity = .43f,
            macros = mapOf("TUNE" to .625f, "SPEED" to .73f, "HOLD" to .2f))
        val edited = assertIs<LatticePatch>(Patches.edited(saved, "Moved trace", saved.macros + ("SPEED" to .31f)))
        assertEquals(saved.midi, edited.midi)
        assertEquals(saved.velocity, edited.velocity)
        assertEquals(saved.model, edited.model)
        val reopened = PadRecipe.fromJsonText(PadRecipe(edited).toJsonText())
        assertEquals(edited, reopened.patch)
        assertContentEquals(edited.render().samples, reopened.render().samples)
        assertContentEquals(edited.copy(velocity = .35f).render().samples, Velocity.atVelocity(edited, .35f).samples)
        assertFailsWith<IllegalArgumentException> { saved.copy(macros = saved.macros + ("COLOR" to .4f)) }
    }

}
