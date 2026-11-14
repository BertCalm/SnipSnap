package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Saved voices must retain their performance, including the state behind the six knobs. */
class OsteriaPatchTest {
    private val voice = OsteriaVoice.QUARTET
    private val patch get() = OsteriaPatch(
        "Evening Return", voice, Osteria.defaults(voice) + ("MEMORY" to .79f),
        midi = 36, velocity = .37f,
        config = Osteria.configurationFor(voice).copy(
            initialShores = listOf(.15, 1.8, 3.2, 3.95),
            roleLevels = listOf(.83, .54, .38, .26),
            seed = 8241, seconds = 5.5, tavernShore = 2.75, direction = -1,
        ),
    )

    @Test
    fun `all six voice names preserve complete saved recipes and legacy phrases stay Note only`() {
        assertEquals(setOf("QUARTET", "BASS", "PLUCK", "REED", "PAD", "BOWED"),
            OsteriaVoice.entries.map { it.name }.toSet())
        for (family in OsteriaVoice.entries) {
            val source = OsteriaPatch(
                "SAVED ${family.name}", family,
                Osteria.defaults(family) + mapOf("MEMORY" to .86f, "WANDER" to .72f),
                midi = 55, velocity = .43f,
                config = Osteria.configurationFor(family).copy(
                    initialShores = listOf(.23, 1.4, 2.56, 3.72),
                    roleLevels = listOf(.72, .63, .54, .45),
                    seed = 7901, seconds = 2.0, attackSeconds = .16, releaseSeconds = .4,
                    tavernShore = 1.68, direction = -1,
                ),
            )
            val back = Patches.fromJsonText(source.toJsonText()) as OsteriaPatch
            assertEquals(source, back, family.name)
            assertEquals(source, PadRecipe.fromJsonText(PadRecipe(patch = source).toJsonText()).patch)
            val edited = Patches.edited(back, "EDIT ${family.name}", back.macros + ("SHORE" to .93f)) as OsteriaPatch
            assertEquals(source.config, edited.config, family.name)
            assertEquals(source.midi, edited.midi, family.name)
            assertEquals(source.velocity, edited.velocity, family.name)
            assertEquals(edited, Patches.fromJsonText(edited.toJsonText()), family.name)
            if (family != OsteriaVoice.QUARTET) {
                val legacyConfig = source.config.copy(model = 1, seconds = 5.5, attackSeconds = .035, releaseSeconds = .8)
                assertFailsWith<IllegalArgumentException>(family.name) { source.copy(config = legacyConfig) }
                val saved = source.toJsonValue()
                val config = saved.entries.getValue("config").obj() + mapOf(
                    "model" to JsonValue.Num(1.0),
                    "seconds" to JsonValue.Num(5.5),
                    "attackSeconds" to JsonValue.Num(.035),
                    "releaseSeconds" to JsonValue.Num(.8),
                )
                assertFailsWith<JsonException>(family.name) {
                    Patches.fromJsonValue(JsonValue.Obj(saved.entries + ("config" to JsonValue.Obj(config))))
                }
            }
        }
    }

    @Test
    fun `patch and pad recipe replay bit for bit and macro editing retains the saved performance`() {
        val source = patch
        val text = source.toJsonText()
        val back = OsteriaPatch.fromJsonText(text)
        assertEquals(source, back)
        assertEquals(source, Patches.fromJsonText(text))
        assertEquals(text, back.toJsonText())
        val recipe = PadRecipe(patch = source)
        val savedRecipe = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, savedRecipe)
        val expected = source.render()
        assertContentEquals(expected.samples, back.render().samples)
        assertContentEquals(expected.samples, savedRecipe.render().samples)
        assertEquals(expected.channels, savedRecipe.render().channels)

        val macros = source.macros + mapOf("WANDER" to .92f, "ROOM" to .3f)
        val edited = Patches.edited(back, "After Midnight", macros) as OsteriaPatch
        assertEquals(source.copy(name = "After Midnight", macros = macros), edited)
        assertEquals(source.config, edited.config)
        assertEquals(source.midi, edited.midi)
        assertEquals(source.velocity, edited.velocity)
        assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
        assertContentEquals(
            Osteria.render(voice, macros, source.midi, source.velocity, source.config).samples,
            PadRecipe.fromJsonText(PadRecipe(patch = edited).toJsonText()).render().samples,
        )
    }

    @Test
    fun `saved model one remains explicit and a short model two note keeps its duration`() {
        for (source in listOf(patch.copy(config = patch.config.copy(model = 1)),
            patch.copy(config = patch.config.copy(model = 2, seconds = 1.0)))) {
            val back = OsteriaPatch.fromJsonText(source.toJsonText())
            assertEquals(source, back)
            assertEquals(source.config.model, back.config.model)
            assertEquals(source.config.seconds, back.config.seconds)
            assertContentEquals(source.render().samples, back.render().samples)
        }
    }

    @Test
    fun `optional articulation retains old canonical recipes and saves edited touch exactly`() {
        val source = patch
        val oldText = source.toJsonText()
        val oldConfig = source.toJsonValue().entries.getValue("config").obj()
        assertTrue("attackSeconds" !in oldConfig && "releaseSeconds" !in oldConfig)
        val oldBack = OsteriaPatch.fromJsonText(oldText)
        assertEquals(.035, oldBack.config.attackSeconds)
        assertEquals(.8, oldBack.config.releaseSeconds)
        assertEquals(oldText, oldBack.toJsonText())

        val touched = source.copy(config = source.config.copy(attackSeconds = .18, releaseSeconds = .35))
        val touchedBack = OsteriaPatch.fromJsonText(touched.toJsonText())
        assertEquals(touched, touchedBack)
        assertEquals(.18, touchedBack.config.attackSeconds)
        assertEquals(.35, touchedBack.config.releaseSeconds)
        assertContentEquals(touched.render().samples, touchedBack.render().samples)
        assertEquals(touched.config,
            (touched.withMacros(touched.macros + ("SHORE" to .9f)) as OsteriaPatch).config)
        val savedRecipe = PadRecipe.fromJsonText(PadRecipe(patch = touched).toJsonText())
        assertEquals(touched, savedRecipe.patch)
    }

    @Test
    fun `unsupported models missing saved state and invalid numeric values are refused`() {
        val source = patch
        fun altered(field: String, value: JsonValue): JsonValue.Obj =
            JsonValue.Obj(source.toJsonValue().entries + (field to value))
        fun alteredConfig(field: String, value: JsonValue): JsonValue.Obj {
            val config = source.toJsonValue().entries.getValue("config").obj()
            return altered("config", JsonValue.Obj(config + (field to value)))
        }
        val invalid = linkedMapOf<String, JsonValue>(
            "patch version" to altered("version", JsonValue.Num(2.0)),
            "voice" to altered("voice", JsonValue.Str("SOLO")),
            "blank name" to altered("name", JsonValue.Str(" ")),
            "low root" to altered("midi", JsonValue.Num((Osteria.MIN_MIDI - 1).toDouble())),
            "high root" to altered("midi", JsonValue.Num((Osteria.MAX_MIDI + 1).toDouble())),
            "fractional root" to altered("midi", JsonValue.Num(48.5)),
            "negative velocity" to altered("velocity", JsonValue.Num(-.01)),
            "velocity rounds into range" to altered("velocity", JsonValue.Num(1.00000001)),
            "nonfinite velocity" to altered("velocity", JsonValue.Num(Double.NaN)),
            "unknown macro" to altered("macros", JsonValue.Obj(mapOf("UNISON" to JsonValue.Num(.5)))),
            "macro rounds into range" to altered("macros", JsonValue.Obj(mapOf("SHORE" to JsonValue.Num(1.00000001)))),
            "nonfinite macro" to altered("macros", JsonValue.Obj(mapOf("MEMORY" to JsonValue.Num(Double.POSITIVE_INFINITY)))),
            "unsupported model" to alteredConfig("model", JsonValue.Num(3.0)),
            "direction" to alteredConfig("direction", JsonValue.Num(0.0)),
            "duration" to alteredConfig("seconds", JsonValue.Num(9.0)),
            "shorter than a tap" to alteredConfig("seconds", JsonValue.Num(.9)),
            "nonfinite duration" to alteredConfig("seconds", JsonValue.Num(Double.NaN)),
            "short attack" to alteredConfig("attackSeconds", JsonValue.Num(.004)),
            "long attack" to alteredConfig("attackSeconds", JsonValue.Num(1.01)),
            "nonfinite attack" to alteredConfig("attackSeconds", JsonValue.Num(Double.NaN)),
            "short release" to alteredConfig("releaseSeconds", JsonValue.Num(.019)),
            "long release" to alteredConfig("releaseSeconds", JsonValue.Num(2.01)),
            "nonfinite release" to alteredConfig("releaseSeconds", JsonValue.Num(Double.POSITIVE_INFINITY)),
            "release exceeds capture" to altered("config", JsonValue.Obj(
                source.toJsonValue().entries.getValue("config").obj() + mapOf(
                    "seconds" to JsonValue.Num(1.0), "releaseSeconds" to JsonValue.Num(.96)))),
            "legacy articulation" to altered("config", JsonValue.Obj(
                source.toJsonValue().entries.getValue("config").obj() + mapOf(
                    "model" to JsonValue.Num(1.0), "attackSeconds" to JsonValue.Num(.18)))),
            "nonfinite room shore" to alteredConfig("tavernShore", JsonValue.Num(Double.NEGATIVE_INFINITY)),
            "three players" to alteredConfig("initialShores", JsonValue.Arr(List(3) { JsonValue.Num(1.0) })),
            "shore out of range" to alteredConfig("initialShores", JsonValue.Arr(List(4) { JsonValue.Num(4.1) })),
            "nonfinite shore" to alteredConfig("initialShores", JsonValue.Arr(List(4) { JsonValue.Num(Double.NaN) })),
            "level out of range" to alteredConfig("roleLevels", JsonValue.Arr(List(4) { JsonValue.Num(-.1) })),
            "nonfinite level" to alteredConfig("roleLevels", JsonValue.Arr(List(4) { JsonValue.Num(Double.NaN) })),
        )
        for ((label, value) in invalid) {
            assertFailsWith<JsonException>(label) { Patches.fromJsonValue(value) }
        }
        for (field in listOf("midi", "velocity", "macros", "config")) {
            assertFailsWith<JsonException>("missing $field") {
                Patches.fromJsonValue(JsonValue.Obj(source.toJsonValue().entries - field))
            }
        }
        val savedConfig = source.toJsonValue().entries.getValue("config").obj()
        for (field in savedConfig.keys) {
            assertFailsWith<JsonException>("missing config.$field") {
                Patches.fromJsonValue(altered("config", JsonValue.Obj(savedConfig - field)))
            }
        }
        assertFailsWith<IllegalArgumentException> { source.copy(velocity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { source.copy(velocity = 1.01f) }
        assertFailsWith<IllegalArgumentException> { source.copy(midi = Osteria.MAX_MIDI + 1) }
        assertFailsWith<IllegalArgumentException> { source.copy(macros = mapOf("SHORE" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { source.config.copy(model = 0) }
        assertFailsWith<IllegalArgumentException> { source.config.copy(initialShores = listOf(0.0)) }
        assertFailsWith<IllegalArgumentException> { source.config.copy(attackSeconds = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { source.config.copy(releaseSeconds = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { source.config.copy(model = 1, releaseSeconds = .2) }
        assertEquals(2.05, source.config.copy(seconds = 2.05, releaseSeconds = 2.0).seconds)
    }

    @Test
    fun `sparse macros replay with defaults and the dry first listens share a saved performance`() {
        val sparse = patch.copy(macros = mapOf("MEMORY" to .83f))
        val back = OsteriaPatch.fromJsonText(sparse.toJsonText())
        assertEquals(sparse, back)
        assertEquals(setOf("MEMORY"), back.macros.keys)
        assertContentEquals(
            sparse.copy(macros = Osteria.defaults(voice) + sparse.macros).render().samples,
            back.render().samples,
        )
        val presets = OsteriaPresets.original()
        assertEquals(3, presets.size)
        assertTrue(presets.all { it.voice == voice && it.macros.getValue("ROOM") == 0f && it.macros.getValue("HOLD") == 0f })
        assertEquals(1, presets.map { Triple(it.midi, it.velocity, it.config) }.distinct().size)
        val wandering = presets.single { it.name == "Evolving Note" }
        val lingering = presets.single { it.name == "Lingering Color" }
        assertEquals(wandering.macros - "MEMORY", lingering.macros - "MEMORY")
        assertEquals(0f, wandering.macros.getValue("MEMORY"))
        assertTrue(lingering.macros.getValue("MEMORY") > .8f)
    }
}
