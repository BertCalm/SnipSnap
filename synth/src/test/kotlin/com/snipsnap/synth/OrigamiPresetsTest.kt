package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The accepted R4 voices enter the same factory, editing, note and velocity doors as other synths. */
class OrigamiPresetsTest {
    @Test
    fun `factory registry exposes twelve complete recipes with accepted references first`() {
        val all = OrigamiPresets.all()
        assertEquals(12, all.size)
        assertEquals(12, all.map { it.name }.toSet().size)
        assertEquals(OrigamiPresets.references() + OrigamiPresets.performance(), all)
        assertEquals(all, Presets.all().filter { it.engine == OrigamiPatch.ENGINE })
        val reference = mapOf("TUNE" to .5f, "CREASE" to .5f, "FOLD" to .75f,
            "TURN" to .5f, "WARP" to .5f, "MOTION" to 0f)
        for (voice in OrigamiVoice.entries) {
            val presets = OrigamiPresets.forVoice(voice)
            assertEquals(4, presets.size, voice.name)
            assertEquals(OrigamiPresets.references().filter { it.voice == voice }, presets.take(2))
            assertEquals(OrigamiPresets.performance().filter { it.voice == voice }, presets.drop(2))
            assertEquals(presets, Presets.forVoice(OrigamiPatch.ENGINE, voice.name))
            assertEquals(reference, presets.first().macros, "$voice starts with the accepted audition recipe")
            assertTrue(presets[0].macros != presets[1].macros, "$voice second preset repeats the same sound")
            for (patch in presets) {
                assertEquals(4, patch.model, patch.name)
                assertEquals(20261012L, patch.seed, "${patch.name} changed the accepted excitation")
                assertEquals(.85f, patch.velocity)
                assertNull(patch.midi, "${patch.name} must follow the player's TUNE control")
                assertEquals(Origami.macrosFor(voice).map { it.name }.toSet(), patch.macros.keys)
                assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
                assertEquals(patch, Presets.byName(OrigamiPatch.ENGINE, voice.name, patch.name))
                assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
                assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros), "${patch.name} must land dry")
            }
        }
        assertTrue(Presets.forVoice(OrigamiPatch.ENGINE, "UNKNOWN").isEmpty())
    }

    @Test
    fun `original six saved factory recipes retain names controls and legacy articulation`() {
        val expected = listOf(
            Triple(OrigamiVoice.CRANE, "Paper Key", .75f to 0f),
            Triple(OrigamiVoice.CRANE, "Light Crease", .48f to .08f),
            Triple(OrigamiVoice.FAN, "Warm Fan", .75f to 0f),
            Triple(OrigamiVoice.FAN, "Soft Fifth", .42f to .12f),
            Triple(OrigamiVoice.STILL, "Tenth Bloom", .75f to 0f),
            Triple(OrigamiVoice.STILL, "Quiet Paper", .46f to .12f),
        )
        val references = OrigamiPresets.references()
        assertEquals(expected.size, references.size)
        for ((patch, saved) in references.zip(expected)) {
            assertEquals(saved.first, patch.voice)
            assertEquals(saved.second, patch.name)
            assertEquals(mapOf("TUNE" to .5f, "CREASE" to .5f, "FOLD" to saved.third.first,
                "TURN" to .5f, "WARP" to .5f, "MOTION" to saved.third.second), patch.macros)
            assertEquals(4, patch.model)
            assertEquals(20261012L, patch.seed)
            assertEquals(.85f, patch.velocity)
            assertNull(patch.midi)
            assertEquals(OrigamiGesture.STEADY, patch.gesture)
            assertEquals(OrigamiExpression.LEVEL, patch.expression)
            assertFalse("gesture" in patch.toJsonValue().entries, "${patch.name} changed its old recipe")
            assertFalse("expression" in patch.toJsonValue().entries, "${patch.name} changed its old recipe")
        }
    }

    @Test
    fun `every factory preset produces finite audible mono one shot audio`() {
        for (patch in OrigamiPresets.all()) {
            val snip = patch.render()
            assertEquals(1, snip.channels)
            assertEquals(44100, snip.sampleRate)
            assertTrue(snip.durationSeconds in 1f..5f, "${patch.name} finite duration")
            assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, patch.name)
            assertTrue(snip.peak() > .05f, "${patch.name} is effectively silent")
            assertTrue(abs(snip.samples.last()) < .001f, "${patch.name} unfinished release")
        }
    }

    @Test
    fun `factory tune edits produce C3 C4 and C5 through saved patch rendering`() {
        for (voice in OrigamiVoice.entries) for ((tune, midi) in listOf(0f to 48, .5f to 60, 1f to 72)) {
            val original = OrigamiPresets.forVoice(voice).first()
            val edited = assertIs<OrigamiPatch>(Patches.edited(original, original.name,
                original.macros + ("TUNE" to tune)))
            assertNull(edited.midi)
            assertEquals(original.seed, edited.seed)
            assertEquals(original.velocity, edited.velocity)
            assertEquals(4, edited.model)
            val wanted = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val rendered = edited.render()
            val measured = FineTuning.measuredHz(rendered, wanted.toFloat(), .12f, .5f)
            val cents = FineTuning.cents(measured, wanted)
            assertTrue(abs(cents) < 3.0, "$voice TUNE$tune expected MIDI$midi but root is ${cents}c away")
        }
    }

    @Test
    fun `an explicit saved MIDI note takes priority over factory tune without losing recipe state`() {
        val saved = OrigamiPresets.forVoice(OrigamiVoice.FAN).first().copy(
            name = "Saved Fan", midi = 72, velocity = .61f, seed = Long.MIN_VALUE + 23L)
        val low = assertIs<OrigamiPatch>(Patches.edited(saved, saved.name, saved.macros + ("TUNE" to 0f)))
        val high = assertIs<OrigamiPatch>(Patches.edited(saved, saved.name, saved.macros + ("TUNE" to 1f)))
        assertEquals(72, low.midi)
        assertEquals(saved.seed, low.seed)
        assertEquals(saved.velocity, low.velocity)
        assertEquals(4, low.model)
        assertContentEquals(low.render().samples, high.render().samples,
            "generic macro editing must preserve an explicitly saved absolute note")
    }

    @Test
    fun `factory native velocity is quieter and preserves the exact authored recipe`() {
        fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        for (voice in OrigamiVoice.entries) {
            val patch = OrigamiPresets.forVoice(voice).first()
            assertNull(Velocity.brightnessSpec(patch), "$voice spectral FOLD is not a brightness proxy")
            val main = patch.render()
            val soft = Velocity.atVelocity(patch, .35f)
            assertContentEquals(patch.copy(velocity = .35f).render().samples, soft.samples, voice.name)
            assertTrue(rms(soft.samples) < rms(main.samples) * .8, "$voice soft strike is not softer")
            assertTrue(Velocity.atVelocity(patch, 0f).samples.all { it == 0f }, "$voice velocity zero is audible")
        }
    }

    @Test
    fun `expanding sparse legacy controls for the editor preserves raw and conditioned audio`() {
        for (model in listOf(2, 3, 4)) for (voice in listOf(OrigamiVoice.FAN, OrigamiVoice.STILL)) {
            val sparse = OrigamiPatch("Sparse paper", voice, mapOf("TUNE" to .25f, "FOLD" to .43f),
                midi = if (voice == OrigamiVoice.FAN) 67 else null,
                velocity = .62f, seed = Long.MIN_VALUE + 17L, model = model)
            val macros = sparse.resolvedMacros()
            assertEquals(Origami.macrosFor(voice).map { it.name }.toSet(), macros.keys)
            assertEquals(.25f, macros.getValue("TUNE"))
            assertEquals(.43f, macros.getValue("FOLD"))
            val crease = if (model == 4) .5f else if (voice == OrigamiVoice.FAN) .63f else .68f
            assertEquals(crease, macros.getValue("CREASE"), "$voice model$model editor defaults")
            val expanded = sparse.copy(macros = macros)
            assertContentEquals(Origami.renderRaw(voice, sparse.macros, sparse.velocity, sparse.midi, sparse.seed, model = model),
                Origami.renderRaw(voice, expanded.macros, expanded.velocity, expanded.midi, expanded.seed, model = model),
                "$voice model$model editor expansion changed the unlevelled synthesis")
            assertContentEquals(sparse.render().samples, expanded.render().samples,
                "$voice model$model editor expansion changed the saved sound")
        }
    }
}
