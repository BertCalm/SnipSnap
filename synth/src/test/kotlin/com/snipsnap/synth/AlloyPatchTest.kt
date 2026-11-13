package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AlloyPatchTest {
    private fun custom(voice: AlloyVoice = AlloyVoice.KEY): AlloyPatch {
        val base = Alloy.configFor(voice)
        return AlloyPatch("My Alloy", voice, Alloy.defaults(voice), midi = 55, velocity = .6f,
            config = base.copy(fuseRadians = 1.3f,
                routing = listOf(AlloyConfig.Route(3, 1, .7f), AlloyConfig.Route(1, 0, 1.2f)),
                held = base.held.copy(drive = .04f)))
    }

    @Test
    fun `complete material and modulation recipes regenerate through patch and pad JSON`() {
        for (voice in AlloyVoice.entries) {
            val source = custom(voice)
            val restored = Patches.fromJsonText(source.toJsonText())
            assertEquals(source, restored, "$voice lost its root energy or generated recipe")
            assertEquals(source.toJsonText(), restored.toJsonText())
            val recipe = PadRecipe(source)
            val again = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, again)
            assertNull(again.fx)
            assertContentEquals(source.render().samples, again.render().samples, "$voice is not regenerable")
        }
    }

    @Test
    fun `macro editing and native velocity retain custom routing material and note`() {
        val source = custom()
        val macros = source.macros + ("MATERIAL" to .8f) + ("FUSE" to .7f)
        val edited = Patches.edited(source, "Edited Alloy", macros)
        assertEquals(source.copy(name = "Edited Alloy", macros = macros), edited)
        assertEquals(source.copy(macros = macros), source.withMacros(macros))
        val expected = source.copy(velocity = .3f).render()
        val native = Velocity.atVelocity(source, .3f)
        assertContentEquals(expected.samples, native.samples, "velocity drops the saved config or moves an unrelated macro")
        assertTrue(!native.samples.contentEquals(Velocity.soften(source.render(), .7f).samples),
            "native excitation must differ from filtering a frozen sample")
    }

    @Test
    fun `old minimal recipes fill optional note and generated configuration fields`() {
        for (optional in listOf("", "\"midi\":null,\"velocity\":null,\"model\":null,\"config\":null,")) {
            val restored = AlloyPatch.fromJsonText(
                """{"engine":"ALLOY","version":1,"name":"Alloy","voice":"KEY",$optional"macros":{"FUSE":0.6},"extra":"ignored"}""",
            )
            assertEquals(Alloy.DEFAULT_MIDI, restored.midi)
            assertEquals(1f, restored.velocity)
            assertEquals(Alloy.MODEL_VERSION, restored.model)
            assertEquals(Alloy.configFor(AlloyVoice.KEY), restored.config)
            assertEquals(mapOf("FUSE" to .6f), restored.macros)
        }
    }

    @Test
    fun `invalid notes models macros and cyclic modulation recipes are refused`() {
        val patch = custom()
        fun changed(name: String, value: JsonValue) = JsonValue.Obj(
            LinkedHashMap(patch.toJsonValue().entries).also { it[name] = value })
        for ((field, value) in listOf(
            "model" to JsonValue.Num(2.0), "version" to JsonValue.Num(2.0),
            "voice" to JsonValue.Str("KICK"), "engine" to JsonValue.Str("RESIN"),
            "midi" to JsonValue.Num(48.5), "midi" to JsonValue.Num(97.0),
            "velocity" to JsonValue.Num(1.1), "config" to JsonValue.Obj(emptyMap()),
        )) assertFailsWith<JsonException>(field) { AlloyPatch.fromJsonValue(changed(field, value)) }
        val backwards = JsonValue.Obj(LinkedHashMap(patch.config.toJsonValue().entries).also {
            it["routing"] = JsonValue.Arr(listOf(JsonValue.Obj(linkedMapOf(
                "from" to JsonValue.Num(0.0), "to" to JsonValue.Num(3.0), "radians" to JsonValue.Num(1.0),
            ))))
        })
        assertFailsWith<JsonException> { AlloyPatch.fromJsonValue(changed("config", backwards)) }
        assertFailsWith<IllegalArgumentException> { AlloyConfig.Route(0, 3, 1f) }
        assertFailsWith<IllegalArgumentException> { patch.config.copy(routing = listOf(
            AlloyConfig.Route(3, 0, 1f), AlloyConfig.Route(3, 0, .5f))) }
        assertFailsWith<IllegalArgumentException> { patch.config.copy(operators = patch.config.operators.take(3)) }
        for (midi in listOf(23, 97)) assertFailsWith<IllegalArgumentException> { patch.copy(midi = midi) }
        for (v in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { patch.copy(velocity = v) }
        }
        for (macros in listOf(mapOf("REVERB" to .5f), mapOf("BODY" to 1.1f), mapOf("FUSE" to Float.NaN))) {
            assertFailsWith<IllegalArgumentException> { patch.copy(macros = macros) }
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(name = " ") }
    }

    @Test
    fun `twenty four factory presets cover every voice and expose dry pitched routing`() {
        val presets = AlloyPresets.all()
        val additions = approvedMaterialChoices()
        assertEquals(24, presets.size)
        assertEquals(24, presets.map { it.name }.distinct().size)
        assertTrue(Presets.all().containsAll(presets))
        for (voice in AlloyVoice.entries) {
            val group = AlloyPresets.forVoice(voice)
            assertEquals(4, group.size)
            assertEquals(group + additions.filter { it.voice == voice }, Presets.forVoice("ALLOY", voice.name))
            for (preset in group) {
                assertEquals(preset, Presets.byName("ALLOY", voice.name, preset.name))
                assertTrue(preset.name.length <= 14, "${preset.name} does not fit the preset strip")
                assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(preset.name))
                assertEquals(Alloy.macrosFor(voice).map { it.name }.toSet(), preset.macros.keys,
                    "${preset.name} must store a complete macro vector")
                assertEquals(.5f, preset.macros.getValue("TUNE"), "${preset.name} moves the neutral root")
                assertEquals(preset, Patches.fromJsonText(preset.toJsonText()))
                assertNull(Presets.landingFor("ALLOY", voice.name, preset.macros))
                val expected = if (Alloy.isLoop(preset.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
                assertEquals(expected, Alloy.drumClassFor(voice, preset.macros))
            }
            for (i in group.indices) for (j in i + 1 until group.size) {
                assertTrue(PresetTestSupport.rmsDistance(group[i].macros, group[j].macros) >= .10f,
                    "$voice ${group[i].name} / ${group[j].name} need a useful contrast")
            }
            assertEquals(0, group.count { Alloy.isLoop(it.macros.getValue("HOLD")) },
                "$voice factory choices must preserve the approved finite roles")
            assertEquals(1, AlloyPresets.heldReferences().count {
                it.voice == voice && Alloy.isLoop(it.macros.getValue("HOLD"))
            }, "$voice must retain its settled held reference outside the active bank")
        }
        assertTrue(Presets.forVoice("ALLOY", "UNKNOWN").isEmpty())
    }

    @Test
    fun `public material choices retain the three exact approved recipes without replacing factory roles`() {
        val expected = approvedMaterialChoices()
        assertEquals(listOf(AlloyVoice.BASS, AlloyVoice.KEY, AlloyVoice.PLUCK,
            AlloyVoice.BRASS, AlloyVoice.STRING, AlloyVoice.SOFT), AlloyVoice.entries)
        val global = Presets.all().filterIsInstance<AlloyPatch>()
        assertEquals(AlloyPresets.all() + expected, global,
            "the public roster must preserve the original 24 in order and append only the three approved choices")
        assertEquals(27, global.map { it.name }.distinct().size)
        assertTrue(AlloyPresets.all().all { it.materialResponse == null })
        assertTrue(AlloyPresets.heldReferences().all { it.materialResponse == null })
        for (approved in expected) {
            val selected = assertIs<AlloyPatch>(Presets.byName("ALLOY", approved.voice.name, approved.name))
            assertEquals(approved, selected, "${approved.name} differs from the approved baseline or body descriptor")
            assertTrue(selected.name.length <= 14)
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(selected.name))
            assertEquals(Alloy.macrosFor(selected.voice).map { it.name }.toSet(), selected.macros.keys)
            assertEquals(.5f, selected.macros.getValue("TUNE"))
            assertEquals(.5f, selected.macros.getValue("MATERIAL"))
            assertEquals(selected, Patches.fromJsonText(selected.toJsonText()))
            assertNull(Presets.landingFor("ALLOY", selected.voice.name, selected.macros))
            assertTrue(!Alloy.isLoop(selected.macros.getValue("HOLD")))
            assertEquals(DrumClass.TONAL, Alloy.drumClassFor(selected.voice, selected.macros))
            assertSame(selected.config, selected.effectiveConfig(), "the loaded midpoint must return the exact saved anchor")
        }
    }

    /** Read the accepted authoring data rather than deriving expectations from the production preset helper. */
    private fun approvedMaterialChoices(): List<AlloyPatch> {
        val fixtures = File("src/test/resources/alloy-material-body")
        val designs = Json.parse(File(fixtures, "final-designs.json").readText()).obj()
        return listOf("rounded_plug" to "Rounded Body", "long_prism" to "Prism Body", "velvet_rise" to "Velvet Body")
            .map { (role, label) ->
                val baseline = AlloyPatch.fromJsonText(File(fixtures, "baselines/$role.json").readText())
                val design = designs.getValue(role).obj()
                val wood = design.getValue("wood").obj()
                val response = AlloyMaterialResponse(
                    version = 1,
                    originalWeightPower = design.getValue("originalWeightPower").int(),
                    operators = design.getValue("operators").arr().map { item -> item.obj().let {
                        AlloyMaterialResponse.Operator(it.getValue("gain").num(), it.getValue("decay").num(), it.getValue("sustain").num())
                    } },
                    routing = design.getValue("routing").arr().map { item -> item.obj().let {
                        AlloyMaterialResponse.Route(it.getValue("from_").int(), it.getValue("to").int(), it.getValue("radians").num())
                    } },
                    woodLosses = wood.getValue("losses").arr().map { it.num() },
                    woodGains = wood.getValue("gains").arr().map { it.num() },
                    cutoffRatio = design.getValue("cutoffRatio").num(),
                )
                baseline.copy(name = label, macros = baseline.macros + ("MATERIAL" to .5f), materialResponse = response)
            }
    }
}
