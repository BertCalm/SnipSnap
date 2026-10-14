package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.OrphicaPadMaker
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Orphica
import com.snipsnap.synth.OrphicaConfig
import com.snipsnap.synth.OrphicaMaterial
import com.snipsnap.synth.OrphicaMode
import com.snipsnap.synth.OrphicaPatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The phone's preset/edit/save and held-instrument paths, beyond the renderer's JVM tests. */
@RunWith(AndroidJUnit4::class)
class OrphicaScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelfRoot = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "orphica-screen-${System.nanoTime()}",
    ).apply { mkdirs() }

    @After
    fun cleanUp() {
        appScope.cancel()
        shelfRoot.deleteRecursively()
    }

    private fun show() {
        setPhoneContent {
            SynthScreen(
                entry = null,
                shelfRoot = shelfRoot,
                onToast = {},
                onKitUpdated = {},
                onShelfAssetWritten = {},
                onDroneToLoop = { _, _, _ -> },
                onOpenSurface = {},
                appScope = appScope,
            )
        }
        // Invoke semantics directly, as PadSheetScreenTest does for sliders:
        // controls below the fold need no animated scroll on the frozen clock.
        for (engine in listOf("THUMP", "SKIN", "TINES", "VELVET", "VOX", "PLUCK")) {
            click(button("ENGINE $engine · TAP FOR NEXT"))
        }
        button("ENGINE ORPHICA · TAP FOR NEXT").assertExists()
    }

    private fun click(node: SemanticsNodeInteraction) {
        val action = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("control has no click action")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump(32)
    }

    private fun click(label: String) = click(button(label))

    private fun slider(label: String) = compose.onNodeWithContentDescription(label, useUnmergedTree = true)

    private fun value(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)!!.current

    private fun dial(label: String, target: Float) {
        val action = slider(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("$label has no progress action")
        compose.runOnIdle { assertTrue(action.invoke(target)) }
        pump(32)
    }

    private fun load(patch: OrphicaPatch) {
        UserPresets.save(shelfRoot, patch, System.currentTimeMillis())
        show()
        waitFor("the saved ORPHICA preset") {
            compose.onAllNodesWithText(patch.name).fetchSemanticsNodes().isNotEmpty()
        }
        click(compose.onNodeWithText(patch.name))
    }

    private fun saveAs(name: String): OrphicaPatch {
        click("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        pump(32)
        click("SAVE")
        waitFor("the edited ORPHICA preset on disk") { UserPresets.read(shelfRoot).any { it.name == name } }
        return UserPresets.read(shelfRoot).single { it.name == name }.patch as OrphicaPatch
    }

    @Test
    fun picker_and_factory_presets_use_the_authored_mode_and_physical_controls() {
        show()
        for (material in OrphicaMaterial.entries) button(material.name).assertExists()
        for (mode in OrphicaMode.entries) {
            click("MODE ${mode.name}")
            button("MODE ${mode.name}").assertIsSelected()
            assertReadsInFull(mode.name)
        }
        click("PRESET Clear Harp")
        button("MODE CLEAR").assertIsSelected()
        assertEquals(0f, value("FRACTURE"), 0f)
        click("PRESET Captured Sustain")
        button("MODE FREEZE").assertIsSelected()
        assertEquals(.8f, value("FRACTURE"), 0f)
        assertEquals((85f - 8f) / 112f, value("SIZE"), 0f)
        dial("HOLD", 1f)
        compose.onNodeWithText("LOOP", useUnmergedTree = true).assertExists()
    }

    @Test
    fun editing_and_scrambling_keep_the_complete_saved_recipe() {
        val original = OrphicaPatch("OWN HARP", OrphicaConfig(
            midi = 67, mode = OrphicaMode.FREEZE, velocity = .37f,
            pluck = .21f, fracture = .72f, damping = .43f,
            grainSizeMs = 66.06575775146484f, grainRateHz = 43f, scatter = .81f,
            seconds = .7f, captureSeconds = .23f, seed = Long.MAX_VALUE - 17,
        ))
        load(original)
        assertEquals(original.config.pluck, value("PLUCK"), 0f)
        assertEquals(original.config.fracture, value("FRACTURE"), 0f)
        // Saving an untouched custom recipe must not round SIZE back into
        // a physical setting one frame shorter than the original.
        val unchanged = saveAs("EXACT HARP")
        assertEquals(original.config, unchanged.config)
        assertEquals(original.macros, unchanged.macros)
        click("MODE REVERSE")
        click("ROOT UP")
        dial("FRACTURE", .55f)
        click("SCRAMBLE")
        val saved = saveAs("EDITED HARP")
        assertEquals(original.config.copy(midi = 68, mode = OrphicaMode.REVERSE), saved.config)
        assertTrue(saved.macros.values.all { it in 0f..1f })
        val knownControls = Orphica.macrosFor(original.config.material).map { it.name }.toSet()
        assertTrue(saved.macros.keys.all { it in knownControls })
        assertTrue(saved.macros.getValue("HOLD") < .99f)
    }

    @Test
    fun make_instrument_uses_the_selected_root_and_exports_a_stereo_held_loop() {
        load(OrphicaPatch("HELD HARP", OrphicaConfig(
            midi = 72, mode = OrphicaMode.REVERSE, seconds = .7f, seed = 97L,
        )))
        click("MAKE INSTRUMENT")
        slider("RELEASE").assertExists()
        slider("ATTACK").assertDoesNotExist()
        dial("RELEASE", .25f)
        click("MAKE")
        val instruments = File(shelfRoot, KitShelf.INSTRUMENTS_DIR)
        waitFor("the held ORPHICA package", timeoutMillis = 60_000) { InstrumentStore.list(instruments).isNotEmpty() }
        val (sidecar, instrument) = InstrumentStore.list(instruments).single()
        assertEquals(OrphicaPadMaker.RELEASE.value(.25f), instrument.release, .00001f)
        assertEquals(60, instrument.zones.minOf { it.lowNote })
        assertEquals(84, instrument.zones.maxOf { it.highNote })
        assertTrue(instrument.zones.all { it.loopStartFrame > 0 })
        for (zone in instrument.zones) {
            val sample = WavReader.read(File(sidecar.parentFile, zone.sample))
            assertEquals(2, sample.channels)
            assertTrue(zone.loopStartFrame < sample.frameCount)
        }
    }
}
