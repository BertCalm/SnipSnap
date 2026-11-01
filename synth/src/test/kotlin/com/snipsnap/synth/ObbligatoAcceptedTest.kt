package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Promote the heard renderer without rewriting an existing saved instrument. */
class ObbligatoAcceptedTest {
    private fun fixture(resource: String) = assertNotNull(javaClass.getResourceAsStream(resource))
        .bufferedReader().use { Json.parse(it.readText()).obj() }

    @Test
    fun `the new default retains the exact approved sum stems and complete PCM exports`() {
        val captured = fixture("/obbligato/model10-sha256.json")
        assertEquals("69c0312d4fdfec69cd305bdac0b635a851483207", captured.getValue("originalCommit").str())
        val provenance = captured.getValue("sourceGitBlobs").obj()
        assertTrue(provenance.size >= 10 && provenance.values.all { it.str().matches(Regex("[a-f0-9]{40}")) })
        val cases = captured.getValue("cases").arr()
        assertEquals(5, cases.size)
        for (value in cases) {
            val case = value.obj()
            val approved = ObbligatoPatch.fromJsonValue(case.getValue("recipe"))
            assertEquals(10, approved.model)
            val current = ObbligatoPatch(approved.name, approved.macros, approved.midi, approved.velocity, approved.seed)
            assertEquals(approved, current, "a new patch does not select the approved renderer")
            val raw = Obbligato.probe(current.macros, current.midi, current.velocity, current.seed)
            assertEquals(case.getValue("rawFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.snip.samples), "${current.name}: approved sum changed")
            assertEquals(case.getValue("airFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.air), "${current.name}: approved air changed")
            assertEquals(case.getValue("reedFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.reed), "${current.name}: approved reed changed")
            assertEquals(case.getValue("finalPcm24WavSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.pcmHash(current), "${current.name}: approved PCM export changed")
        }
    }

    @Test
    fun `missing renderer fields retain old audio through patch and pad recipe loading`() {
        assertEquals(5, Obbligato.LEGACY_MODEL)
        val cases = fixture("/obbligato/models1-5-sha256.json").getValue("cases").arr()
            .map { it.obj() }.filter { it.getValue("recipe").obj().getValue("model").int() == 5 }
        assertEquals(6, cases.size)
        for (case in cases) {
            val old = ObbligatoPatch.fromJsonValue(case.getValue("recipe"))
            val noModel = JsonValue.Obj(LinkedHashMap(old.toJsonValue().entries).apply { remove("model") })
            val loaded = assertIs<ObbligatoPatch>(Patches.fromJsonValue(noModel))
            assertEquals(old, loaded, "loading an omitted renderer field silently upgrades a saved patch")
            val padValue = JsonValue.Obj(LinkedHashMap(PadRecipe(old).toJsonValue().entries).apply { this["patch"] = noModel })
            val pad = PadRecipe.fromJsonValue(padValue)
            assertEquals(old, pad.patch, "nested pad loading silently upgrades the saved renderer")
            val raw = Obbligato.probe(loaded.macros, loaded.midi, loaded.velocity, loaded.seed, model = loaded.model)
            assertEquals(case.getValue("rawFloat32LittleEndianSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.floatHash(raw.snip.samples), "${old.name}: omitted renderer changed old audio")
            assertEquals(case.getValue("finalPcm24WavSha256").str(),
                ObbligatoCompatibilityFixtureGenerator.pcmHash(loaded), "${old.name}: omitted renderer changed old PCM export")
            assertEquals(5, assertIs<ObbligatoPatch>(PadRecipe.fromJsonText(pad.toJsonText()).patch).model)
            val edited = assertIs<ObbligatoPatch>(Patches.edited(loaded, "Edited old duet", loaded.macros + ("HOLD" to .2f)))
            assertEquals(5, edited.model, "editing an old patch silently selects the new factory renderer")
        }
    }

    @Test
    fun `new instruments presets and kit rows select the accepted renderer explicitly`() {
        assertEquals(10, Obbligato.MODEL)
        assertEquals(Obbligato.MORE_OVERLAP_MODEL, Obbligato.MODEL)
        assertEquals(Obbligato.MODEL, ObbligatoKits.MODEL)
        assertEquals(10, ObbligatoPatch("New duet", Obbligato.defaults()).model)
        val presets = ObbligatoPresets.all()
        assertEquals(4, presets.size)
        assertTrue(presets.all { it.model == 10 })
        val patches = ObbligatoKits.patches()
        assertEquals(16, patches.size)
        assertTrue(patches.all { it.model == 10 })
        for (preset in presets) {
            val recalled = assertIs<ObbligatoPatch>(Patches.fromJsonText(preset.toJsonText()))
            assertEquals(preset, recalled)
            val recipe = PadRecipe.fromJsonText(PadRecipe(preset).toJsonText())
            assertEquals(preset, recipe.patch)
            assertContentEquals(preset.render().samples,
                Obbligato.render(preset.macros, preset.midi, preset.velocity, preset.seed).samples,
                "${preset.name}: direct default rendering differs from the factory instrument")
            assertEquals(10, assertIs<ObbligatoPatch>(Patches.edited(preset, "Edited new duet", preset.macros)).model)
        }
    }
}
