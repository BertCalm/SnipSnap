package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saved musical and microphone state travels through the actual recipe/export pipeline. */
class RevelPatchTest {
    private val configuration = RevelConfig(
        micCount = 3,
        phraseTempo = 117f,
        phraseBeats = 3,
        trajectories = listOf(RevelTrajectory.CIRCLE, RevelTrajectory.ECCENTRIC, RevelTrajectory.SPIRO),
        phaseOffsets = listOf(.13f, .48f, .82f),
        directions = listOf(1, -1, 1),
        speedRatios = listOf(1f, .5f, 1.5f),
        seed = Long.MAX_VALUE,
    )
    private val patch = RevelPatch(
        "Saved Circle", RevelVoice.CIRCLE,
        Revel.defaults(RevelVoice.CIRCLE) + ("TUNE" to .75f),
        configuration = configuration,
        velocity = .4f,
    )

    @Test
    fun `every voice roundtrips requested note velocity and every microphone field`() {
        for (voice in RevelVoice.entries) {
            val source = patch.copy(voice = voice, macros = Revel.defaults(voice) + ("TUNE" to .75f))
            val text = source.toJsonText()
            assertEquals(source, RevelPatch.fromJsonText(text))
            assertEquals(source, Patches.fromJsonText(text))
            assertEquals(text, Patches.fromJsonText(text).toJsonText())
            assertEquals(setOf("TUNE", "PLAY", "SKIN", "ORBIT", "WEAVE", "REACH", "HOLD"), source.macros.keys)
        }
        for (seed in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 9_007_199_254_740_993L)) {
            val source = patch.copy(configuration = configuration.copy(seed = seed))
            assertEquals(source, RevelPatch.fromJsonText(source.toJsonText()), "seed $seed lost bits")
        }
    }

    @Test
    fun `old minimal patches default optional state and ignore unknown metadata`() {
        val text = """{"engine":"REVEL","version":1,"name":"Circle","voice":"CIRCLE","macros":{"PLAY":0.2},"future":true}"""
        val restored = RevelPatch.fromJsonText(text)
        assertEquals(RevelConfig(), restored.configuration)
        assertEquals(1f, restored.velocity)
        assertEquals(mapOf("PLAY" to .2f), restored.macros)
        assertEquals(restored, Patches.fromJsonText(text))
    }

    @Test
    fun `editing and an fx recipe retain the performance and microphone configuration`() {
        val edited = Patches.edited(patch, "My Circle", patch.macros + ("WEAVE" to .9f)) as RevelPatch
        assertEquals(patch.copy(name = "My Circle", macros = patch.macros + ("WEAVE" to .9f)), edited)
        val recipe = PadRecipe(edited, FxChain(reverse = true))
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        val dry = edited.render()
        val expected = requireNotNull(recipe.fx).process(dry)
        assertContentEquals(expected.samples, restored.render().samples)
        assertFalse(dry.samples.contentEquals(expected.samples))
    }

    @Test
    fun `invalid patch and microphone configuration are rejected before rendering`() {
        fun changed(key: String, value: JsonValue) =
            JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).apply { put(key, value) })
        // Every refusal names its guard: the decode and the constructor chain throw the same two exception types for
        // every cause, so a type alone would pass whichever guard refused first.
        val engine = assertFailsWith<JsonException> { RevelPatch.fromJsonValue(changed("engine", JsonValue.Str("THUMP"))) }
        assertEquals("not a REVEL patch: THUMP", engine.message, "engine THUMP")
        val version = assertFailsWith<JsonException> { RevelPatch.fromJsonValue(changed("version", JsonValue.Num(2.0))) }
        assertEquals("unsupported patch version 2", version.message, "version 2")
        val voice = assertFailsWith<JsonException> { RevelPatch.fromJsonValue(changed("voice", JsonValue.Str("KICK"))) }
        assertEquals("unknown voice KICK", voice.message, "voice KICK")
        val blank = assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
        assertEquals("patch name must not be blank", blank.message, "blank name")
        val unknown = assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("DRIVE" to .5f)) }
        assertEquals("unknown macro DRIVE for CIRCLE (knows [TUNE, PLAY, SKIN, ORBIT, WEAVE, REACH, HOLD])", unknown.message, "macro DRIVE")
        for (value in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val macro = assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("PLAY" to value)) }
            assertEquals("macro PLAY out of 0..1: $value", macro.message, "PLAY $value")
            val velocity = assertFailsWith<IllegalArgumentException> { patch.copy(velocity = value) }
            assertEquals("REVEL velocity out of 0..1: $value", velocity.message, "velocity $value")
        }
        for (count in listOf(0, 4)) {
            val refused = assertFailsWith<IllegalArgumentException> { patch.copy(configuration = configuration.copy(micCount = count)) }
            assertEquals("REVEL has one to three microphones", refused.message, "micCount $count")
        }
        // Each field guard below is reached alone: the fixture is valid, one field is broken at the fixture's own
        // three-entry roster size, and only the configuration is built. The patch's roster-size check never runs,
        // and every other configuration guard sees a valid value, so the field under test is the only cause left.
        // RevelConfig's field guards carry no message of their own; the microphone-count guard and the patch's
        // roster check do, and a refusal from either would not read as the bare requirement failure.
        fun refusedField(label: String, build: () -> RevelConfig) {
            val refused = assertFailsWith<IllegalArgumentException>(label) { build() }
            assertEquals("Failed requirement.", refused.message, label)
        }
        refusedField("tempo NaN") { configuration.copy(phraseTempo = Float.NaN) }
        refusedField("tempo 59 BPM") { configuration.copy(phraseTempo = 59f) }
        refusedField("tempo 201 BPM") { configuration.copy(phraseTempo = 201f) }
        refusedField("zero beats") { configuration.copy(phraseBeats = 0) }
        refusedField("nine beats") { configuration.copy(phraseBeats = 9) }
        refusedField("direction 0") { configuration.copy(directions = listOf(1, 0, 1)) }
        refusedField("direction 2") { configuration.copy(directions = listOf(1, -1, 2)) }
        refusedField("speed ratio NaN") { configuration.copy(speedRatios = listOf(1f, Float.NaN, 1.5f)) }
        refusedField("speed ratio .75") { configuration.copy(speedRatios = listOf(1f, .75f, 1.5f)) }
        refusedField("phase offset NaN") { configuration.copy(phaseOffsets = listOf(.13f, Float.NaN, .82f)) }
        refusedField("phase offset past 32 pi") { configuration.copy(phaseOffsets = listOf(.13f, 101f, .82f)) }
        // Four entries with no microphone count set: nothing but the three-entry cap can refuse these.
        refusedField("four trajectories") { RevelConfig(trajectories = List(4) { RevelTrajectory.CIRCLE }) }
        refusedField("four phase offsets") { RevelConfig(phaseOffsets = List(4) { .5f }) }
        refusedField("four directions") { RevelConfig(directions = List(4) { 1 }) }
        refusedField("four speed ratios") { RevelConfig(speedRatios = List(4) { 1f }) }
        // Valid configurations whose roster is longer than the microphone count: only the patch can refuse them,
        // and it names the roster. CIRCLE resolves an unset count to one microphone.
        for ((field, mismatched) in listOf(
            "trajectories" to RevelConfig(micCount = 2, trajectories = configuration.trajectories),
            "phaseOffsets" to RevelConfig(micCount = 2, phaseOffsets = configuration.phaseOffsets),
            "directions" to RevelConfig(micCount = 2, directions = configuration.directions),
            "speedRatios" to RevelConfig(micCount = 2, speedRatios = configuration.speedRatios),
        )) {
            val refused = assertFailsWith<IllegalArgumentException>(field) { patch.copy(configuration = mismatched) }
            assertEquals("REVEL $field needs 2 entries, got 3", refused.message)
        }
        val unset = assertFailsWith<IllegalArgumentException> { patch.copy(configuration = RevelConfig(directions = listOf(1, -1))) }
        assertEquals("REVEL directions needs 1 entries, got 2", unset.message)
        val malformed = """{"engine":"REVEL","version":1,"name":"Circle","voice":"CIRCLE","macros":{},"configuration":{"micCount":1.5}}"""
        val fractional = assertFailsWith<JsonException> { RevelPatch.fromJsonText(malformed) }
        assertEquals("expected an integer, got 1.5", fractional.message, "micCount 1.5")
    }

    @Test
    fun `the ten named presets cover all voices and retain their configuration`() {
        val presets = RevelPresets.all()
        assertEquals(
            setOf("Inner Circle", "Skin Conversation", "Close Pass", "Two Directions", "Three Listeners",
                "Flower Path", "Elastic Answer", "Rolling Floor", "Deep Gathering", "Held Revel"),
            presets.map { it.name }.toSet(),
        )
        assertEquals(10, presets.size)
        assertEquals(RevelVoice.entries.toSet(), presets.map { it.voice }.toSet())
        assertTrue(Presets.all().containsAll(presets))
        for (voice in RevelVoice.entries) assertEquals(RevelPresets.forVoice(voice), Presets.forVoice("REVEL", voice.name))
        for (preset in presets) {
            assertEquals(Revel.defaults(preset.voice).keys, preset.macros.keys, preset.name)
            assertEquals(preset, Patches.fromJsonText(preset.toJsonText()), preset.name)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name), preset.name)
        }
        assertTrue(Revel.isLoop(presets.first { it.name == "Held Revel" }.macros.getValue("HOLD")))
    }

    @Test
    fun `the dry sixteen pad kit roundtrips recipes and exports native MPC audio`() {
        val temp = java.nio.file.Files.createTempDirectory("revel-kit-test").toFile()
        try {
            val pads = SynthKits.revel()
            assertEquals(16, pads.size)
            val patches = pads.mapIndexed { index, pad ->
                requireNotNull(pad) { "pad ${index + 1} missing" }
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
                assertEquals(null, recipe.fx, "pad ${index + 1} needs dry core identity")
                assertTrue(pad.drumClass in setOf(DrumClass.TONAL, DrumClass.LOOP))
                recipe.patch as? RevelPatch ?: error("pad ${index + 1} has no REVEL patch")
            }
            assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map {
                Revel.midiFor(it.voice, it.macros.getValue("TUNE"))
            })
            assertTrue(patches.take(8).all { it.voice == RevelVoice.CIRCLE })
            for (index in listOf(0, 8, 15)) {
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pads[index]?.recipe))
                assertContentEquals(requireNotNull(pads[index]).snip.samples, recipe.render().samples, "pad ${index + 1}")
            }
            val directory = File(temp, "kit")
            val kit = KitAssembler.assembleArranged("Revel", pads, directory)
            assertEquals(kit, KitStore.load(directory))
            val findings = Preflight.check(kit, directory)
            assertFalse(findings.blocked(), findings.toString())
            val result = KitExporter.exportProgramFolder(kit, directory, File(temp, "mpc"))
            assertEquals(16, result.samples.size)
            for (wav in result.samples) {
                val info = WavInfo.read(wav)
                assertEquals(44_100, info.sampleRate)
                assertEquals(24, info.bitsPerSample)
            }
            assertTrue(result.program.isFile)
        } finally {
            temp.deleteRecursively()
        }
    }
}
