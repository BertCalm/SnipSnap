package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.MeridianPadMaker
import com.snipsnap.shell.MeridianSynth
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Phone glue only: DSP, cycle closure and native package contents have their own JVM tests. */
@RunWith(AndroidJUnit4::class)
class MeridianSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val folders = mutableListOf<File>()
    private var generation by mutableIntStateOf(0)
    private var entry by mutableStateOf<KitShelf.Entry?>(null)

    @After
    fun cleanUp() {
        appScope.cancel()
        folders.forEach { it.deleteRecursively() }
    }

    private fun show(fromPreviousEngine: Boolean = false, saved: MeridianPatch? = null): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "meridian-synth-ui-${System.nanoTime()}").apply { mkdirs(); folders += this }
        val directory = File(root, "kit").apply { mkdirs() }
        val kit = Kit(name = "MERIDIAN UI", pads = emptyList())
        KitStore.save(kit, directory)
        entry = KitShelf.Entry(directory, kit)
        settings["engine"] = if (fromPreviousEngine) "OHM" else "MERIDIAN"
        settings["voice"] = if (fromPreviousEngine) "STRING" else "SWEEP"
        if (saved != null) {
            UserPresets.save(root, saved, 1L)
            settings["preset.MERIDIAN.SWEEP"] = saved.name
        }
        setPhoneContent {
            key(generation) {
                SynthScreen(
                    entry = entry, shelfRoot = root, onToast = {},
                    onKitUpdated = { entry = KitShelf.Entry(directory, it) },
                    onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> },
                    onOpenSurface = {}, appScope = appScope, settings = settings,
                )
            }
        }
        return root
    }

    // Accessibility actions reach below-fold controls without animated scrolls
    // fighting ComposeScreenTest's deliberately hand-driven frame clock.
    private fun press(label: String) {
        val node = compose.onAllNodes(hasContentDescription(label) and hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull() ?: error("No click action for $label")
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action ?: error("No click action for $label")
        compose.runOnIdle { assertTrue("$label refused its click", action.invoke()) }
        pump()
    }

    private fun slider(label: String) = compose.onNode(
        hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
        useUnmergedTree = true,
    )

    private fun dial(label: String, value: Float) {
        val action = slider(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("No slider action for $label")
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    private fun progress(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current ?: error("No progress for $label")

    private fun draft(): MeridianPatch? = SynthDrafts.read(settings).filterIsInstance<MeridianPatch>().singleOrNull()

    private fun loadFactory(name: String): MeridianPatch {
        press("PRESET $name")
        waitFor("the complete $name draft") { draft()?.name == name }
        return MeridianPresets.all().single { it.name == name }
    }

    private fun enterThroughEnginePicker() {
        // Do not assume Meridian's neighbor or cap the growing engine roster at 64.
        repeat(256) {
            val selected = settings["engine"] ?: error("The picker did not retain its engine")
            if (selected == "MERIDIAN") return
            press("NEXT ENGINE")
        }
        error("MERIDIAN never appeared in the engine picker")
    }

    private fun saveAs(name: String, root: File): MeridianPatch {
        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        pump()
        compose.onNodeWithContentDescription("SAVE").assertIsEnabled()
        press("SAVE")
        waitFor("the saved Meridian preset", timeoutMillis = 30_000) { UserPresets.read(root).any { it.name == name } }
        return UserPresets.read(root).single { it.name == name }.patch as MeridianPatch
    }

    @Test
    fun picker_exposes_eight_presets_seven_macros_and_the_actual_hold_threshold() {
        show(fromPreviousEngine = true)
        enterThroughEnginePicker()
        compose.onNodeWithContentDescription("ENGINE MERIDIAN. CHOOSE ENGINE").assertExists()
        compose.onNodeWithContentDescription("SWEEP").assertExists()
        assertReadsInFull("MERIDIAN ▾")
        for (name in listOf("TUNE", "LATITUDE", "LENGTH", "REACH", "SURFACE", "MEMORY", "HOLD", "ROOT", "VELOCITY")) {
            slider(name).assertExists()
        }
        for (factory in MeridianPresets.all()) {
            loadFactory(factory.name)
            assertEquals(factory, draft())
            for ((name, value) in factory.macros) assertEquals(value, progress(name), 1e-6f)
        }
        assertReadsInFull("FIXED LOOP")
        dial("HOLD", .974f)
        compose.onNodeWithText("FINITE").assertExists()
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertIsNotEnabled()
        dial("HOLD", .975f)
        compose.onNodeWithText("FIXED LOOP").assertExists()
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertIsEnabled()
    }

    @Test
    fun native_edits_save_restore_and_land_held_sound_as_a_finite_authored_pad() {
        val root = show()
        val factory = loadFactory("ROUND THREAD")
        dial("ROOT", (60 - 36) / 60f)
        dial("VELOCITY", .43f)
        dial("TUNE", .75f)
        dial("MEMORY", .70f)
        val saved = saveAs("MY MERIDIAN", root)
        assertEquals(factory.copy(name = saved.name, midi = 60, velocity = .43f,
            macros = factory.macros + mapOf("TUNE" to .75f, "MEMORY" to .70f)), saved)
        compose.runOnIdle { generation++ }
        pump()
        waitFor("restored native root") { progress("ROOT") == (60 - 36) / 60f }
        assertEquals(.43f, progress("VELOCITY"), 1e-6f)
        assertEquals(.75f, progress("TUNE"), 1e-6f)
        assertEquals(.70f, progress("MEMORY"), 1e-6f)
        assertEquals(factory.seedContext, draft()?.seedContext)
        assertEquals(factory.model, draft()?.model)
        press("SEND TO PAD")
        press("A01, EMPTY")
        val kitDirectory = requireNotNull(entry).dir
        waitFor("the finite Meridian pad", timeoutMillis = 120_000) { KitStore.load(kitDirectory).pad(1) != null }
        val pad = KitStore.load(kitDirectory).pad(1)!!
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val placed = recipe.patch as MeridianPatch
        assertEquals(saved.copy(name = placed.name), placed)
        assertEquals(20f, recipe.terminalFadeMs)
        assertNull(recipe.fx)
        assertTrue(pad.oneShot)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertEquals("66", pad.source["rootMidi"])
        assertEquals("authored", pad.source["rootOrigin"])
        assertEquals("FINITE_HELD_CAPTURE", pad.source["playback"])
        assertEquals(0, pad.tuneFine)
        assertNull(WavReader.readSmpl(File(kitDirectory, pad.sampleFile))?.loop)
    }

    @Test
    fun legacy_hold_stays_finite_and_only_current_held_sound_opens_the_instrument_sheet() {
        val legacy = MeridianSynth.defaultPatch().copy(name = "ORIGINAL SWING", model = 1,
            midi = 51, velocity = .61f, seedContext = 93, macros = MeridianSynth.defaults() + ("HOLD" to 1f))
        val root = show(saved = legacy)
        waitFor("the original renderer recipe", timeoutMillis = 30_000) { draft()?.model == 1 }
        for ((macro, value) in legacy.macros) assertEquals("restored $macro", value, progress(macro), 0f)
        compose.onNodeWithText("FINITE").assertExists()
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertIsNotEnabled()
        compose.onNodeWithText("THIS ORIGINAL SOUND IS FINITE. LOAD A CURRENT PRESET FOR SUSTAIN.").assertExists()
        dial("REACH", .48f)
        val saved = saveAs("MY OLD SWING", root)
        assertEquals(legacy.copy(name = saved.name, macros = legacy.macros + ("REACH" to .48f)), saved)

        loadFactory("ROUND THREAD")
        press("MAKE INSTRUMENT")
        slider("RELEASE").assertExists()
        assertEquals(MeridianPadMaker.RELEASE.defaultFraction, progress("RELEASE"), 1e-6f)
        compose.onNodeWithContentDescription("ATTACK").assertDoesNotExist()
        compose.onNodeWithText("FIXED-ALIGNMENT SWING · C2–C5 · SOFT, MEDIUM AND HARD TOUCH.").assertExists()
        compose.onNodeWithContentDescription("MAKE").assertIsEnabled()
        press("CANCEL")
        slider("HOLD").assertExists()
    }
}
