package com.snipsnap.shell

import com.snipsnap.synth.AlloyMaterialPresets
import com.snipsnap.synth.AlloyPatch
import com.snipsnap.synth.AlloyPresets
import com.snipsnap.synth.AlloyVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** Registration and persistence only: no source renderer is called here. */
class AlloyMaterialPickerTest {
    private val approvedNames = listOf("Rounded Body", "Prism Body", "Velvet Body")

    @Test
    fun `historical factory JSON and per voice order survive the added material choices`() {
        val fixtureRoot = File(System.getProperty("alloy.approved.fixtures",
            "../synth/src/test/resources/alloy-approved-roles"))
        val historical = AlloyVoice.entries.flatMap { voice ->
            listOf("baseline", "01", "02", "03").map { role ->
                AlloyPatch.fromJsonText(File(fixtureRoot, "${voice.name.lowercase()}_$role.json").readText())
                    .let { if (it.name == "Elastic Contact") it.copy(name = "Elastic Touch") else it }
            }
        }
        assertEquals(24, historical.size)
        assertEquals(historical.map { it.toJsonText() }, AlloyPresets.all().map { it.toJsonText() })
        for (voice in AlloyVoice.entries) {
            val old = historical.filter { it.voice == voice }
            val selected = Presets.forVoice("ALLOY", voice.name)
            assertEquals(old.map { it.toJsonText() }, selected.take(old.size).map { it.toJsonText() })
            assertEquals(if (voice in setOf(AlloyVoice.BASS, AlloyVoice.KEY, AlloyVoice.SOFT)) 5 else 4,
                selected.size, "No extra role may be silently opted in")
            for (patch in old) assertEquals(patch, Presets.byName("ALLOY", voice.name, patch.name))
        }
        assertTrue(Presets.forVoice("ALLOY", "UNKNOWN").isEmpty())
    }

    @Test
    fun `global roster ends with three uniquely named material choices`() {
        val global = Presets.all()
        assertEquals(approvedNames, global.takeLast(3).map { it.name }, "Global registration is required")
        assertTrue(approvedNames.all { name -> global.count { it.name == name } == 1 },
            "A new label must not collide with an existing global label")
        val alloy = global.filterIsInstance<AlloyPatch>()
        assertEquals(27, alloy.size)
        assertEquals(AlloyPresets.all().map { it.toJsonText() }, alloy.take(24).map { it.toJsonText() })
        assertEquals(3, alloy.count { it.materialResponse != null })
        assertTrue(global.takeLast(3).all { it.name.length <= 14 }, "Keep the existing factory strip limit")
    }

    @Test
    fun `picker selections retain approved anchors through edit user save and native recipe reopen`() {
        val dir = createTempDirectory("alloy-picker").toFile()
        try {
            for ((source, label) in AlloyMaterialPresets.all().zip(approvedNames)) {
                val selected = assertIs<AlloyPatch>(Presets.byName("ALLOY", source.voice.name, label))
                assertEquals(source.copy(name = label), selected, "Only the separate picker label may differ")
                assertSame(source.config, selected.config)
                assertEquals(.5f, selected.macros.getValue("MATERIAL"))
                assertSame(selected.config, selected.effectiveConfig())
                val edited = assertIs<AlloyPatch>(Patches.edited(selected, "BODY ${source.voice}",
                    selected.macros + ("MATERIAL" to .25f)))
                UserPresets.save(dir, edited, 1L)
                val reopened = assertIs<AlloyPatch>(UserPresets.read(dir).single { it.name == edited.name }.patch)
                assertEquals(edited, reopened)
                assertEquals(source.config, reopened.config)
                assertEquals(source.materialResponse, reopened.materialResponse)
                assertEquals(source.copy(macros = edited.macros).effectiveConfig(), reopened.effectiveConfig())
                val touched = reopened.copy(midi = 36, velocity = .35f)
                assertEquals(touched, PadRecipe.fromJsonText(PadRecipe(touched).toJsonText()).patch)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `new factory labels protect user saves without changing the original mapping bank`() {
        val dir = createTempDirectory("alloy-picker-names").toFile()
        try {
            assertEquals(listOf("Rounded Plug", "Long Prism", "Velvet Rise"),
                AlloyMaterialPresets.all().map { it.name })
            for (source in AlloyMaterialPresets.pickerAdditions()) {
                val factoryName = source.copy(name = UserPresets.normalize(source.name))
                assertFailsWith<IllegalArgumentException> { UserPresets.save(dir, factoryName, 1L) }
            }
            assertTrue(UserPresets.read(dir).isEmpty())
            assertTrue(AlloyPresets.all().all { it.materialResponse == null })
            assertTrue(AlloyPresets.heldReferences().all { it.materialResponse == null })
        } finally { dir.deleteRecursively() }
    }
}
