package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Apsis
import com.snipsnap.synth.ApsisConfig
import com.snipsnap.synth.ApsisPatch
import com.snipsnap.synth.ApsisPresets
import com.snipsnap.synth.FilamentVoice
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Real picker, playing controls, saved recipes and pad delivery; no listening verdict. */
@RunWith(AndroidJUnit4::class)
class ApsisSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val folders = mutableListOf<File>()
    private var visit by mutableIntStateOf(0)
    private var currentEntry by mutableStateOf<KitShelf.Entry?>(null)

    @After fun cleanUp() {
        appScope.cancel()
        folders.forEach { it.deleteRecursively() }
    }

    private fun folder(name: String): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "apsis-$name-${System.nanoTime()}").apply { mkdirs(); folders += this }
    }

    private fun show(patch: ApsisPatch? = null, kit: Boolean = false, fromPicker: Boolean = false): File {
        val shelf = folder("shelf")
        if (kit) {
            val dir = File(shelf, "kit").apply { mkdirs() }
            val empty = Kit("APSIS UI", emptyList())
            KitStore.save(empty, dir)
            currentEntry = KitShelf.Entry(dir, empty)
        }
        settings["engine"] = if (fromPicker) "FILAMENT" else "APSIS"
        settings["voice"] = if (fromPicker) FilamentVoice.entries.first().name else "PASSAGE"
        if (patch != null) {
            settings["recipe.APSIS.PASSAGE"] = patch.toJsonText()
            for ((name, value) in patch.macros) settings["macro.APSIS.PASSAGE.$name"] = value.toString()
        }
        setPhoneContent {
            key(visit) {
                SynthScreen(currentEntry, shelf, onToast = {}, onKitUpdated = { updated ->
                    currentEntry = currentEntry?.let { KitShelf.Entry(it.dir, updated) }
                }, onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> },
                    onOpenSurface = {}, appScope = appScope, settings = settings)
            }
        }
        return shelf
    }

    private fun press(label: String) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        pump()
    }

    private fun dial(label: String, value: Float) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(node.config.getOrNull(SemanticsActions.SetProgress)?.action?.invoke(value) == true)
        pump()
    }

    private fun progress(label: String): Float = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun save(shelf: File): ApsisPatch {
        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY APSIS")
        pump()
        press("SAVE")
        waitFor("saved Apsis recipe") { UserPresets.read(shelf).any { it.name == "MY APSIS" } }
        waitFor("saved Apsis preset selection") {
            compose.onAllNodesWithText("MY APSIS").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Selected) == true
            }
        }
        return UserPresets.read(shelf).single { it.name == "MY APSIS" }.patch as ApsisPatch
    }

    @Test fun picker_exposes_six_presets_and_independent_playing_controls() {
        show(fromPicker = true)
        press("ENGINE FILAMENT · TAP FOR NEXT")
        compose.onNodeWithContentDescription("ENGINE APSIS · TAP FOR NEXT").assertExists()
        for (preset in ApsisPresets.all()) {
            press("PRESET ${preset.name}")
            assertEquals(.5f, progress("ROOT"), 1e-6f)
            assertEquals(preset.velocity, progress("VELOCITY"), 1e-6f)
            for ((name, value) in preset.macros) assertEquals(value, progress(name), 1e-6f)
        }
        press("STOP AUDITION")
        assertEquals(1f, progress("HOLD"), 1e-6f)
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertDoesNotExist()
    }

    @Test fun legacy_model_and_seed_survive_edit_scramble_return_save_reload_and_pad_delivery() {
        val original = ApsisPresets.all()[1].copy(config = ApsisConfig(1, -37))
        val shelf = show(original, kit = true)
        dial("STRETCH", .31f)
        dial("COUPLE", .73f)
        dial("ROOT", .25f) // MIDI 48.
        dial("VELOCITY", .43f)
        press("SCRAMBLE")
        val values = Apsis.macrosFor(original.voice).associate { it.name to progress(it.name) }
        compose.runOnIdle { visit++ }
        pump()
        assertEquals(.25f, progress("ROOT"), 1e-6f)
        assertEquals(.43f, progress("VELOCITY"), 1e-6f)
        for ((name, value) in values) assertEquals(value, progress(name), 1e-6f)
        val saved = save(shelf)
        assertEquals(original.copy(name = "MY APSIS", midi = 48, velocity = .43f, macros = values), saved)

        press("PRESET Slow Apse")
        val mine = compose.onNodeWithText("MY APSIS").fetchSemanticsNode()
        assertTrue(mine.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        pump()
        assertEquals(.25f, progress("ROOT"), 1e-6f)
        press("SEND TO PAD")
        press("A01, EMPTY")
        val dir = requireNotNull(currentEntry).dir
        waitFor("finite Apsis pad", timeoutMillis = 60_000) { KitStore.load(dir).pad(1)?.recipe != null }
        val landed = requireNotNull(KitStore.load(dir).pad(1))
        val patch = PadRecipe.fromJsonValue(requireNotNull(landed.recipe)).patch as ApsisPatch
        assertEquals(saved.copy(name = patch.name), patch)
        assertEquals(DrumClass.TONAL, landed.drumClass)
        assertTrue(landed.oneShot)
        assertFalse(landed.loop)
        assertEquals("48", landed.source["rootMidi"])
    }

    @Test fun held_recipe_lands_as_a_gated_full_cycle_and_can_be_reopened() {
        val held = ApsisPresets.all().last().copy(midi = 55, velocity = .61f, config = ApsisConfig(3, 91))
        show(held, kit = true)
        press("SEND TO PAD")
        press("A01, EMPTY")
        val dir = requireNotNull(currentEntry).dir
        waitFor("held Apsis pad", timeoutMillis = 180_000) { KitStore.load(dir).pad(1)?.loop == true }
        val pad = requireNotNull(KitStore.load(dir).pad(1))
        assertFalse(pad.oneShot)
        assertEquals(DrumClass.LOOP, pad.drumClass)
        val patch = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as ApsisPatch
        assertEquals(held.copy(name = patch.name), patch)
        press("REOPEN PAD RECIPE")
        press("REOPEN A01 ${pad.displayName}")
        assertEquals((55 - 36) / 48f, progress("ROOT"), 1e-6f)
        assertEquals(.61f, progress("VELOCITY"), 1e-6f)
        assertEquals(1f, progress("HOLD"), 1e-6f)
    }
}
