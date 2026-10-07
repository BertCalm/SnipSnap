package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saved recipes, factory routing and the public kit and velocity doors. */
class SuturePatchTest {

    @Test
    fun `all controls and voices round-trip through the shared patch dispatcher`() {
        for (voice in SutureVoice.entries) {
            val patch = SuturePatch("Saved $voice", voice, Suture.defaults(voice) + ("TUNE" to 7 / 24f))
            assertEquals(patch, SuturePatch.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val edited = Patches.edited(patch, "Edited $voice", patch.macros + ("GAP" to 0.2f))
            assertEquals(patch.copy(name = "Edited $voice", macros = patch.macros + ("GAP" to 0.2f)), edited)
            assertEquals(7 / 24f, edited.macros.getValue("TUNE"))
        }
        val partial = SuturePatch.fromJsonText(
            """{"engine":"SUTURE","version":1,"name":"Partial","voice":"THREAD","macros":{"CORD":0.6},"extra":"ignored"}""",
        )
        assertContentEquals(
            Suture.render(partial.voice, Suture.defaults(partial.voice) + partial.macros).samples,
            partial.render().samples,
        )
    }

    @Test
    fun `bad tags versions voices names and macro values are refused`() {
        val patch = SuturePatch("Saved", SutureVoice.BLOOM, Suture.defaults(SutureVoice.BLOOM))
        fun changed(key: String, value: JsonValue): JsonValue.Obj = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).apply { put(key, value) },
        )
        assertFailsWith<JsonException> { SuturePatch.fromJsonValue(changed("engine", JsonValue.Str("THAW"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("voice", JsonValue.Str("KICK"))) }
        assertFailsWith<IllegalArgumentException> { SuturePatch(" ", SutureVoice.BLOOM, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { SuturePatch("Saved", SutureVoice.BLOOM, mapOf("VOLUME" to 0.5f)) }
        for (value in listOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { SuturePatch("Saved", SutureVoice.BLOOM, mapOf("GAP" to value)) }
        }
    }

    @Test
    fun `the twelve named starting sounds expose complete controls and dry landings`() {
        val expectedNames = setOf(
            "OPEN BRONZE", "SOFT THREAD", "WOODEN EYE", "SLOW TAKE-UP", "CLOSING SHELL", "FINE SEAM",
            "RESISTED STITCH", "TIGHT CORD", "DEEP VESSEL", "QUIET MURMUR", "STRAINED EDGE", "RETURNING GAP",
        )
        val all = SuturePresets.all()
        assertEquals(12, all.size)
        assertEquals(expectedNames, all.map { it.name }.toSet())
        assertTrue(Presets.all().containsAll(all))
        assertTrue(Presets.forVoice("SUTURE", "UNKNOWN").isEmpty())
        for (voice in SutureVoice.entries) {
            assertEquals(2, SuturePresets.forVoice(voice).size)
            assertEquals(SuturePresets.forVoice(voice), Presets.forVoice("SUTURE", voice.name))
        }
        for (patch in all) {
            assertEquals(Suture.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys, patch.name)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(null, Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
            val semitone = patch.macros.getValue("TUNE") * Suture.TUNE_SEMITONES
            assertTrue(abs(semitone - semitone.roundToInt()) < 1e-5f, "${patch.name}: semitone tuning")
            assertTrue(patch.name.length <= 18)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
        }
        assertEquals(2, all.count { Suture.isLoop(it.macros.getValue("HOLD")) })
    }

    @Test
    fun `factory sounds retain pitched routing under cord and seam roughness`() {
        for (patch in SuturePresets.all()) {
            val filed = Suture.drumClassFor(patch.voice, patch.macros)
            val classified = Classifier.classify(patch.render()).drumClass
            assertTrue(filed !in DRUMS, "${patch.name}: filed $filed")
            assertTrue(classified !in DRUMS, "${patch.name}: classified $classified")
            if (Suture.isLoop(patch.macros.getValue("HOLD"))) assertEquals(DrumClass.LOOP, filed)
        }
    }

    @Test
    fun `a vessel recipe regenerates through the shared effects chain`() {
        val patch = SuturePresets.forVoice(SutureVoice.THREAD).first()
        val fx = FxChain(crunch = mapOf("BITS" to 0.4f, "RATE" to 0.25f))
        val recipe = PadRecipe(patch, fx)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        assertTrue(restored.alias)
        assertContentEquals(fx.process(patch.render()).samples, restored.render().samples)
    }

    @Test
    fun `the dry kit carries a pentatonic walk all voices and two settled loops`() {
        val kit = SynthKits.suture()
        assertEquals(16, kit.size)
        val voices = mutableSetOf<SutureVoice>()
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        for ((i, maybePad) in kit.withIndex()) {
            val pad = requireNotNull(maybePad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
            val patch = recipe.patch as SuturePatch
            voices += patch.voice
            assertEquals(null, recipe.fx, "A${i + 1} lands dry")
            assertFalse(recipe.alias)
            assertEquals(Suture.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            assertTrue(pad.drumClass !in DRUMS)
            if (i < walk.size) {
                assertEquals(SutureVoice.BLOOM, patch.voice)
                assertEquals(Suture.ROOT_MIDI + walk[i], Suture.midiFor(patch.voice, patch.macros.getValue("TUNE")))
            }
            if (i in listOf(0, 8, 14, 15)) {
                assertContentEquals(pad.snip.samples, recipe.render().samples, "A${i + 1} regenerates exactly")
            }
        }
        assertEquals(SutureVoice.entries.toSet(), voices)
        assertEquals(listOf(DrumClass.LOOP, DrumClass.LOOP), kit.takeLast(2).map { it!!.drumClass })
    }

    @Test
    fun `velocity renders opening energy while preserving the selected vessel recipe`() {
        val patch = SuturePresets.forVoice(SutureVoice.STRAIN).first()
        val macros = patch.macros.toMap()
        val soft = Velocity.atVelocity(patch, 0.25f)
        assertContentEquals(Suture.render(patch.voice, macros, velocity = 0.25f).samples, soft.samples)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples))
        assertEquals(macros, patch.macros)
    }

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
