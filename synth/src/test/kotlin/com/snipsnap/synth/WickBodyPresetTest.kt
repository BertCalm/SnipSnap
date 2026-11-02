package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accepted bodies become ordinary editable recipes without changing their diagnostic sound. */
class WickBodyPresetTest {
    private val bodies = listOf(
        "Warm Wire" to Wick.RattleSource.WARM_WIRE,
        "Hollow Reed" to Wick.RattleSource.HOLLOW_REED,
        "Rubbed Glass" to Wick.RattleSource.RUBBED_GLASS,
    )

    @Test
    fun `ordinary body patches reproduce the accepted finite and held diagnostic renders`() {
        val defaults = Wick.defaults(WickVoice.RATTLE)
        assertEquals(Wick.RattleSource.WARM_WIRE, WickPatch("New Rattle", WickVoice.RATTLE, defaults).rattleSource)
        for ((name, source) in bodies) for (hold in listOf(0f, 1f)) {
            val controls = defaults + ("HOLD" to hold)
            val patch = WickPatch(name, WickVoice.RATTLE, controls, rattleSource = source)
            val diagnostic = Wick.inspect(WickVoice.RATTLE, controls, options = Wick.Options(rattleSource = source))
            val ordinary = Wick.inspect(WickVoice.RATTLE, controls, rattleSource = source)
            assertContentEquals(diagnostic.raw, ordinary.raw, "$name HOLD$hold accepted raw body")
            assertContentEquals(diagnostic.samples, patch.render().samples, "$name HOLD$hold normal patch body")
            assertEquals(diagnostic.diagnostics, ordinary.diagnostics, "$name HOLD$hold retained gate movement")
            if (source == Wick.RattleSource.WARM_WIRE) {
                assertContentEquals(diagnostic.samples, Wick.render(WickVoice.RATTLE, controls).samples,
                    "Warm wire must be the ordinary default at HOLD$hold")
            }
        }
    }

    @Test
    fun `bright rubbed glass keeps the requested octave in finite and held saved bodies`() {
        var acceptedBody: FloatArray? = null
        var brightestBody: FloatArray? = null
        for (midi in listOf(48, 60, 72)) for (hold in listOf(0f, 1f)) for (timbre in listOf(.45f, .70f, 1f)) {
            val controls = Wick.defaults(WickVoice.RATTLE) + mapOf("TIMBRE" to timbre, "HOLD" to hold)
            val patch = WickPatch("Bright Glass", WickVoice.RATTLE, controls, midi = midi,
                rattleSource = Wick.RattleSource.RUBBED_GLASS)
            val probe = Wick.inspect(WickVoice.RATTLE, controls, midi,
                rattleSource = Wick.RattleSource.RUBBED_GLASS)
            val rendered = patch.render()
            val label = "Rubbed Glass MIDI$midi TIMBRE$timbre HOLD$hold"
            assertContentEquals(probe.samples, rendered.samples, "$label saved body path")
            val estimate = assertNotNull(Pitch.detect(Snip(rendered.samples, 1, Dsp.RATE), .05f, .25f),
                "$label opening pitch")
            val cents = FineTuning.cents(estimate.hz.toDouble(), Keys.midiHz(midi).toDouble())
            // A root-only projection can remain correct while half-order partials make
            // the detector prefer an octave lower. Require the complete pitch estimate.
            assertTrue(abs(cents) < 35.0 && estimate.confidence > .3f,
                "$label chose the wrong octave: $cents cents, confidence ${estimate.confidence}")
            if (midi == 60 && hold == 0f) {
                if (timbre == .45f) acceptedBody = probe.raw
                if (timbre == 1f) brightestBody = probe.raw
            }
        }
        val distance = TangleMeasure.spectralDistance(assertNotNull(acceptedBody), assertNotNull(brightestBody))
        println("WICK Rubbed Glass C4 TIMBRE .45 to 1 normalized twelve-band distance $distance")
        assertTrue(distance > .001,
            "Rubbed Glass upper TIMBRE range only changes gain after its octave guard: $distance")
    }

    @Test
    fun `body selection survives patch pad editing and native velocity routes`() {
        for ((name, source) in bodies) {
            val patch = WickPatch(name, WickVoice.RATTLE, Wick.defaults(WickVoice.RATTLE),
                midi = 57, velocity = .7f, rattleSource = source)
            assertEquals(JsonValue.Str(source.name), patch.toJsonValue().entries["rattleSource"])
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val pad = PadRecipe(patch)
            assertEquals(pad, PadRecipe.fromJsonText(pad.toJsonText()))
            val macros = patch.macros + ("TIMBRE" to .8f)
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
            assertEquals(patch.copy(name = "Edited", macros = macros), Patches.edited(patch, "Edited", macros))
            assertContentEquals(patch.copy(velocity = .3f).render().samples, Velocity.atVelocity(patch, .3f).samples,
                "$name native velocity path lost the saved body")
        }
        for (voice in WickVoice.entries.filter { it != WickVoice.RATTLE }) {
            val patch = WickPatch("Unchanged $voice", voice, Wick.defaults(voice))
            assertNull(patch.toJsonValue().entries["rattleSource"], "$voice JSON gains no body field")
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        }
    }

    @Test
    fun `old recipes retain folded FM while malformed body selections are refused`() {
        val patch = WickPatch("Legacy", WickVoice.RATTLE, Wick.defaults(WickVoice.RATTLE), midi = 57, velocity = .7f)
        val legacyEntries = patch.toJsonValue().entries - "rattleSource"
        for (value in listOf(JsonValue.Obj(legacyEntries), JsonValue.Obj(legacyEntries + ("rattleSource" to JsonValue.Null)))) {
            val restored = assertIs<WickPatch>(Patches.fromJsonValue(value))
            assertEquals(Wick.RattleSource.CURRENT, restored.rattleSource)
            val old = Wick.inspect(WickVoice.RATTLE, restored.macros, restored.midi, restored.velocity,
                Wick.Options(rattleSource = Wick.RattleSource.CURRENT))
            assertContentEquals(old.samples, restored.render().samples, "missing/null body must regenerate old saved audio")
        }
        for (value in listOf(JsonValue.Str("MISSING"), JsonValue.Str(""), JsonValue.Num(1.0), JsonValue.Bool(true))) {
            assertFailsWith<JsonException> {
                WickPatch.fromJsonValue(JsonValue.Obj(legacyEntries + ("rattleSource" to value)))
            }
        }
        val wrongVoice = WickPatch("Pluck", WickVoice.PLUCK, Wick.defaults(WickVoice.PLUCK))
        assertFailsWith<IllegalArgumentException> { wrongVoice.copy(rattleSource = Wick.RattleSource.HOLLOW_REED) }
    }

    @Test
    fun `playable kit keeps the ten accepted notes and supplies all three saved RATTLE bodies`() {
        val bank = WickPresets.all()
        assertEquals(27, bank.size)
        val rattle = WickPresets.forVoice(WickVoice.RATTLE)
        assertEquals(bodies.map { it.first }, rattle.take(3).map { it.name })
        assertEquals(bodies.map { it.second }, rattle.take(3).map { it.rattleSource })
        assertTrue(rattle.take(3).all { it.macros == Wick.defaults(WickVoice.RATTLE) && it.midi == 60 && it.velocity == 1f })
        val pads = SynthKits.wick().map { assertNotNull(it) }
        assertEquals(16, pads.size)
        val recipes = pads.map { PadRecipe.fromJsonValue(assertNotNull(it.recipe)) }
        val patches = recipes.map { assertIs<WickPatch>(it.patch) }
        val previousNotes = listOf(WickVoice.PLUCK, WickVoice.WOOD, WickVoice.BUBBLE, WickVoice.BREATH, WickVoice.FLICKER)
            .flatMap { voice -> listOf(48, 60).map { midi -> voice to midi } }
        assertEquals(previousNotes, patches.take(10).map { it.voice to it.midi })
        for (patch in patches.take(10)) assertEquals(Wick.defaults(patch.voice), patch.macros)
        assertEquals(bodies.map { it.first }, patches.subList(10, 13).map { it.name })
        assertEquals(bodies.map { it.first }, pads.subList(10, 13).map { it.displayName })
        assertEquals(bodies.map { it.second }, patches.subList(10, 13).map { it.rattleSource })
        assertTrue(patches.subList(10, 13).all { it.voice == WickVoice.RATTLE && it.midi == 60 })
        assertEquals(listOf("Held Bubbles", "Held Breath", "Held Rattle"), patches.takeLast(3).map { it.name })
        assertEquals(Wick.RattleSource.WARM_WIRE, patches.last().rattleSource)
        assertTrue(patches.takeLast(3).all { Wick.isLoop(it.macros.getValue("HOLD")) })
        assertTrue(recipes.all { it.fx == null && it.board == null && it.pedals == null })
        for (index in 10..12) {
            assertEquals(patches[index], PadRecipe.fromJsonText(recipes[index].toJsonText()).patch)
            assertContentEquals(patches[index].render().samples, pads[index].snip.samples,
                "kit pad ${index + 1} saved body must regenerate its actual sample")
        }
        assertTrue(bank.any { it.name == "Held Flicker" }, "Held Flicker remains available outside the sixteen-pad selection")
    }
}
