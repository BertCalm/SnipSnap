package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkirlPatchTest {
    private fun authored(voice: SkirlVoice = SkirlVoice.ROLL) = SkirlPatch(
        "Saved rolling rim", voice,
        Skirl.defaults(voice) + mapOf("LEAN" to .62f, "EDGE" to .78f, "PLATE" to .68f),
        midi = 57, velocity = .42f,
    )

    @Test
    fun `saved runouts retain performance model and seed context through recipe edits`() {
        for (voice in SkirlVoice.entries) {
            val patch = authored(voice)
            val restored = assertIs<SkirlPatch>(Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, restored)
            assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            val macros = patch.macros + ("BRAKE" to .82f)
            assertEquals(patch.copy(macros = macros), patch.withMacros(macros))
            assertEquals(patch.copy(name = "Edited", macros = macros), Patches.edited(patch, "Edited", macros))
            assertContentEquals(patch.render().samples, restored.render().samples,
                "$voice saved performance and derived seed context must regenerate exact audio")
        }
    }

    @Test
    fun `velocity dispatch rerenders contact without replacing the authored runout or note`() {
        for (voice in SkirlVoice.entries) {
            val patch = authored(voice)
            assertContentEquals(patch.copy(velocity = .25f).render().samples, Velocity.atVelocity(patch, .25f).samples)
            assertEquals(.62f, patch.macros["LEAN"])
            assertEquals(.78f, patch.macros["EDGE"])
            assertEquals(57, patch.midi)
            assertEquals(Skirl.MODEL_VERSION, patch.model)
        }
    }

    @Test
    fun `finite runout metadata cannot silently inherit an unsupported model or performance`() {
        val patch = authored()
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(patch.toJsonValue().entries + (key to value))
        for (key in listOf("model", "midi", "velocity")) {
            assertFailsWith<JsonException>("missing $key") {
                SkirlPatch.fromJsonValue(JsonValue.Obj(patch.toJsonValue().entries - key))
            }
            assertFailsWith<JsonException>("null $key") { SkirlPatch.fromJsonValue(changed(key, JsonValue.Null)) }
        }
        for (bad in listOf(35.0, 85.0, 60.25, Double.NaN)) {
            assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("midi", JsonValue.Num(bad))) }
        }
        for (bad in listOf(0.0, 1.5, 4.0)) {
            assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("model", JsonValue.Num(bad))) }
        }
        for (bad in listOf(-.000000001, 1.000000001, Double.NaN)) {
            assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("velocity", JsonValue.Num(bad))) }
            assertFailsWith<JsonException> {
                SkirlPatch.fromJsonValue(changed("macros", JsonValue.Obj(mapOf("EDGE" to JsonValue.Num(bad)))))
            }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("UNKNOWN" to .5f)) }
        val legacy = patch.copy(model = Skirl.LEGACY_MODEL_VERSION)
        assertFailsWith<IllegalArgumentException> { legacy.copy(macros = mapOf("HOLD" to .01f)) }
        assertFailsWith<JsonException> {
            SkirlPatch.fromJsonValue(JsonValue.Obj(legacy.toJsonValue().entries +
                ("macros" to JsonValue.Obj(mapOf("HOLD" to JsonValue.Num(1e-100))))))
        }
        assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("engine", JsonValue.Str("PITCHWHEEL"))) }
        assertFailsWith<JsonException> { SkirlPatch.fromJsonValue(changed("voice", JsonValue.Str("SCRAPE"))) }
    }

    @Test
    fun `model one keeps its finite surface while current recipes save captured HOLD`() {
        for (voice in SkirlVoice.entries) {
            val legacy = authored(voice).copy(model = 1)
            assertEquals(legacy, Patches.fromJsonText(legacy.toJsonText()))
            assertContentEquals(legacy.render().samples, legacy.copy(model = 2).render().samples)
            assertContentEquals(legacy.render().samples, legacy.copy(model = 3).render().samples)
            for (hold in listOf(.5f, 1f)) {
                val captured = authored(voice).copy(macros = authored(voice).macros + ("HOLD" to hold))
                val restored = assertIs<SkirlPatch>(Patches.fromJsonText(captured.toJsonText()))
                assertEquals(captured, restored)
                assertContentEquals(captured.render().samples, restored.render().samples)
                assertContentEquals(captured.copy(velocity = .25f).render().samples,
                    Velocity.atVelocity(captured, .25f).samples)
            }
        }
    }

    private data class LegacyFixture(val voice: SkirlVoice, val midi: Int, val velocity: Float,
        val hold: Float, val retainAttack: Boolean, val frames: Int, val marker: Long, val sha256: String)

    private fun sampleHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        for (sample in samples) {
            val bits = sample.toRawBits()
            for (index in 0..3) bytes[index] = (bits ushr (24 - index * 8)).toByte()
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun `published model two finite captures and held markers retain their frozen float bytes`() {
        // Collected independently from immutable commit 4b00ed1, including ten
        // published audition clips. These hashes detect a changed legacy cut,
        // seed, filter history or helper dispatch, not just a JSON round trip.
        val fixtures = listOf(
            LegacyFixture(SkirlVoice.ROLL, 48, 1.0f, 0.0f, false, 239036, 0L, "bfd7bb42622a39fce8570176e2a1b288e316b95049d037e5a414d31ad6676a39"),
            LegacyFixture(SkirlVoice.ROLL, 48, 1.0f, 0.5f, false, 317924, 0L, "053ab6a3de60fcb03a6a6ae1323bc497d9906f5f51776a4ef1b6f74548d5072b"),
            LegacyFixture(SkirlVoice.ROLL, 48, 1.0f, 1.0f, false, 26296, 0L, "a87048e54646080b31d4d15dd9f1cc699e7f0c80abad634ef9b670e3d407ae0f"),
            LegacyFixture(SkirlVoice.ROLL, 48, 1.0f, 1.0f, true, 152487, 126191L, "92984ef8ce2b5b64328cbdeec122572602ca0a28c1440b795018794b21228573"),
            LegacyFixture(SkirlVoice.ROLL, 36, 0.25f, 1.0f, false, 26296, 0L, "be2a201d073c3228a523b48a0c76aaf9ab3ce870878ed3989004f5fa2dde5bde"),
            LegacyFixture(SkirlVoice.ROLL, 36, 0.25f, 1.0f, true, 152487, 126191L, "cafbf5823a2a610dcbc40187dcbb52d6c12a55c2e91ba622e15dcde3de888a42"),
            LegacyFixture(SkirlVoice.WHIRR, 48, 1.0f, 0.0f, false, 282743, 0L, "9fb0329adc81e2ebc05770ccb71c1a72de7dc65eb756fac569e18edbb5d15e31"),
            LegacyFixture(SkirlVoice.WHIRR, 48, 1.0f, 0.5f, false, 361631, 0L, "616bcaca9a14b89425705df80df9f2f9228a004c8b1fdcefae64953d39b2355e"),
            LegacyFixture(SkirlVoice.WHIRR, 48, 1.0f, 1.0f, false, 26296, 0L, "2e9e6611e6346667ec5ee43e5597f13093d0c4863e9ba7b49ea86cdcffe7b644"),
            LegacyFixture(SkirlVoice.WHIRR, 48, 1.0f, 1.0f, true, 172433, 146137L, "3db5639ef8efa03b2c7ddc8b48cca8f3822a8a08fc7b49e58a882f41be9e78eb"),
            LegacyFixture(SkirlVoice.WHIRR, 36, 0.25f, 1.0f, false, 26296, 0L, "d48b479895d6ea2b6327251e2e142d0cd2c2d5718b47cfa12335e0084e91cc94"),
            LegacyFixture(SkirlVoice.WHIRR, 36, 0.25f, 1.0f, true, 172433, 146137L, "05bd9295e1de19a6cd8812e85e42e6cdf692f2dbe7593683e6dbbafbeb731a33"),
        )
        for (fixture in fixtures) {
            val macros = Skirl.defaults(fixture.voice) + ("HOLD" to fixture.hold)
            val note = if (fixture.retainAttack) Skirl.renderHeld(fixture.voice, macros,
                fixture.midi, fixture.velocity, model = 2) else KeyNote(
                    Skirl.render(fixture.voice, macros, fixture.midi, fixture.velocity, model = 2))
            assertEquals(fixture.frames, note.snip.frameCount, fixture.toString())
            assertEquals(fixture.marker, note.loopStartFrame, fixture.toString())
            assertEquals(fixture.sha256, sampleHash(note.snip.samples), fixture.toString())
            if (!fixture.retainAttack) {
                val patch = SkirlPatch("Frozen capture", fixture.voice, macros,
                    fixture.midi, fixture.velocity, model = 2)
                assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
                assertContentEquals(note.snip.samples, patch.render().samples)
            }
        }
    }

    @Test
    fun `native listening recipes remain finite outside the factory roster`() {
        val recipes = SkirlPresets.all()
        assertEquals(SkirlVoice.entries.size, recipes.size)
        assertEquals(recipes.size, recipes.map { it.name }.distinct().size)
        assertTrue(SkirlPatch.ENGINE in PatchTypes.PROTOTYPES)
        assertTrue(Presets.all().none { it.engine == SkirlPatch.ENGINE })
        for (voice in SkirlVoice.entries) {
            val recipe = SkirlPresets.forVoice(voice).single()
            assertEquals(setOf("TUNE", "LEAN", "BRAKE", "EDGE", "PLATE", "COLLAPSE", "HOLD"), recipe.macros.keys)
            assertEquals(0f, recipe.macros.getValue("HOLD"))
            assertTrue(Presets.forVoice(SkirlPatch.ENGINE, voice.name).isEmpty())
            assertEquals(recipe, Patches.fromJsonText(recipe.toJsonText()))
            assertNull(PadRecipe(recipe).fx)
        }
    }

    @Test
    fun `pitched runouts retain their class through recipe dispatch`() {
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        for (voice in SkirlVoice.entries) {
            val patch = authored(voice)
            val restored = assertIs<SkirlPatch>(PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
            assertTrue(Skirl.drumClassFor(voice, patch.macros) !in drums)
            val snip = restored.render()
            assertTrue(Classifier.classify(snip).drumClass !in drums,
                "$voice must remain pitched material after actual rendering and recipe dispatch")
        }
    }
}
