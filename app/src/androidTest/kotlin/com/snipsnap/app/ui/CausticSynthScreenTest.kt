package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticPresets
import com.snipsnap.synth.CausticVoice
import com.snipsnap.synth.PadRecipe
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

/** Phone recipe plumbing: finite CAUSTIC keeps pitch, strike and model beside its macros. */
@RunWith(AndroidJUnit4::class)
class CausticSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directories = mutableListOf<File>()
    private var generation by mutableStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        directories.forEach { it.deleteRecursively() }
    }

    private fun shelf(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "caustic-synth-test-${System.nanoTime()}",
    ).apply { mkdirs(); directories += this }

    private fun show(root: File, settings: MutableMap<String, String>, entry: KitShelf.Entry? = null) {
        setPhoneContent {
            key(generation) {
                SynthScreen(
                    entry = entry, shelfRoot = root,
                    onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = appScope, settings = settings,
                )
            }
        }
    }

    private fun press(label: String) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("No click action for $label")
        compose.runOnIdle { assertTrue("Click refused for $label", action.invoke()) }
        pump()
    }

    private fun dial(label: String, value: Float) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("No slider action for $label")
        compose.runOnIdle { assertTrue("Slider refused for $label", action.invoke(value)) }
        pump()
    }

    private fun progress(label: String): Float = compose
        .onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun assertFiniteControls() {
        compose.onNodeWithText("ORIGINAL FINITE NOTE · MOVE HOLD OR MOTION TO EXPLORE FURTHER.").assertExists()
        compose.onNodeWithContentDescription("HOLD", useUnmergedTree = true).assertIsEnabled()
        compose.onNodeWithContentDescription("MOTION", useUnmergedTree = true).assertIsEnabled()
        val hold = compose.onNodeWithContentDescription("HOLD", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(hold.config.getOrNull(SemanticsActions.SetProgress) != null)
        assertEquals(0f, progress("HOLD"), .00001f)
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertIsEnabled()
        compose.onNodeWithContentDescription("DRONE TO LOOP").assertDoesNotExist()
    }

    @Test
    fun a_deliberate_motion_edit_expands_only_the_working_recipe_and_survives_immediate_recreation() {
        val root = shelf()
        val original = CausticPatch("Original ring", CausticVoice.RUBBED_RING,
            mapOf("BODY" to .6f, "LENGTH" to 0f), midi = 55, velocity = .37f, model = 1)
        val before = original.toJsonText()
        val settings = mutableMapOf("engine" to "CAUSTIC", "voice" to original.voiceName)
        SynthDrafts.write(settings, listOf(original))
        show(root, settings)
        assertFiniteControls()
        val adjust = compose.onNodeWithContentDescription("MOTION", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        compose.runOnIdle { assertTrue(adjust(.55f)); generation++ }
        pump()
        waitFor("expanded Caustic motion draft") {
            SynthDrafts.read(settings).any { it is CausticPatch && it.model == 2 && it.macros["MOTION"] == .55f }
        }
        assertEquals(.55f, progress("MOTION"), .00001f)
        assertEquals(.37f, progress("VELOCITY"), .00001f)
        assertEquals((55 - 36) / 48f, progress("ROOT"), .00001f)
        assertEquals(before, original.toJsonText())
    }

    @Test
    fun making_keys_is_an_explicit_expansion_and_opening_the_sheet_writes_no_package() {
        val root = shelf()
        val original = CausticPatch("Original keys", CausticVoice.ALLOY,
            mapOf("LENGTH" to 0f), midi = 55, velocity = .4f, model = 1)
        val settings = mutableMapOf("engine" to "CAUSTIC", "voice" to "ALLOY")
        SynthDrafts.write(settings, listOf(original))
        show(root, settings)
        press("MAKE INSTRUMENT")
        compose.onNodeWithContentDescription("RELEASE").assertIsEnabled()
        compose.onNodeWithContentDescription("ATTACK").assertDoesNotExist()
        compose.onNodeWithContentDescription("MAKE").assertIsEnabled()
        compose.onNodeWithText("KEEPS THE ARRIVAL, THEN REPEATS THE VESSEL WHILE HELD. KEY TOUCH CHANGES VOLUME.").assertExists()
        waitFor("working source expanded for keys") { SynthDrafts.read(settings).any { it is CausticPatch && it.model == 2 } }
        assertEquals(1, original.model)
        val instruments = File(root, KitShelf.INSTRUMENTS_DIR)
        assertTrue(!instruments.exists() || instruments.listFiles().isNullOrEmpty())
    }

    @Test
    fun explicit_hold_places_a_gated_whole_cycle_with_the_complete_saved_recipe() {
        val root = shelf()
        val kitDir = File(root, "held-kit").apply { mkdirs() }
        val kit = Kit(name = "CAUSTIC HELD", pads = emptyList())
        KitStore.save(kit, kitDir)
        val patch = CausticPatch("Held vessel", CausticVoice.ALLOY,
            mapOf("HOLD" to 1f, "MOTION" to .4f, "LENGTH" to 0f), midi = 60, velocity = .43f, model = 2)
        val settings = mutableMapOf("engine" to "CAUSTIC", "voice" to "ALLOY")
        SynthDrafts.write(settings, listOf(patch))
        show(root, settings, KitShelf.Entry(kitDir, kit))
        press("SEND TO PAD")
        press("A01, EMPTY")
        waitFor("gated Caustic cycle", timeoutMillis = 180_000) {
            KitBuilderModel.open(kitDir).pad(1)?.let { it.loop && !it.oneShot } == true
        }
        val pad = KitBuilderModel.open(kitDir).pad(1)!!
        val placed = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as CausticPatch
        assertEquals(2, placed.model)
        assertEquals(60, placed.midi)
        assertEquals(.43f, placed.velocity)
        assertEquals(1f, placed.macros.getValue("HOLD"))
        assertEquals(.4f, placed.macros.getValue("MOTION"))
        assertEquals(DrumClass.LOOP, pad.drumClass)
        assertTrue(pad.loop && !pad.oneShot)
    }

    @Test
    fun all_four_voice_chips_keep_their_own_preset_root_strike_and_body_after_recreation() {
        val root = shelf()
        val settings = mutableMapOf("engine" to "CAUSTIC", "voice" to "ALLOY")
        show(root, settings)
        val labels = mapOf(CausticVoice.ALLOY to "ALLOY", CausticVoice.HOLLOW_MALLET to "Hollow Mallet",
            CausticVoice.RUBBED_RING to "Rubbed Ring", CausticVoice.TRAVELLING_CATCHES to "Travelling Catches")
        for (label in labels.values) compose.onNodeWithContentDescription(label).assertIsDisplayed()
        for ((index, voice) in CausticVoice.entries.withIndex()) {
            press(labels.getValue(voice))
            val preset = CausticPresets.forVoice(voice).first()
            press("PRESET ${preset.name}")
            assertFiniteControls()
            assertEquals(preset.macros.getValue("BODY"), progress("BODY"), .00001f)
            val midi = 48 + index * 3
            val velocity = .3f + index * .1f
            dial("ROOT", (midi - CausticInstrument.MIDI_MIN).toFloat() /
                (CausticInstrument.MIDI_MAX - CausticInstrument.MIDI_MIN))
            dial("VELOCITY", velocity)
            dial("LENGTH", 0f)
            waitFor("independent $voice draft") {
                SynthDrafts.read(settings).any { it is CausticPatch && it.voice == voice &&
                    it.midi == midi && it.velocity == velocity && it.macros["LENGTH"] == 0f }
            }
        }
        compose.runOnIdle { generation++ }
        pump()
        for ((index, voice) in CausticVoice.entries.withIndex()) {
            press(labels.getValue(voice))
            val midi = 48 + index * 3
            assertEquals((midi - CausticInstrument.MIDI_MIN).toFloat() /
                (CausticInstrument.MIDI_MAX - CausticInstrument.MIDI_MIN), progress("ROOT"), .00001f)
            assertEquals(.3f + index * .1f, progress("VELOCITY"), .00001f)
            assertEquals(CausticPresets.forVoice(voice).first().macros.getValue("BODY"), progress("BODY"), .00001f)
            assertEquals(0f, progress("LENGTH"), .00001f)
            assertFiniteControls()
        }
    }

    @Test
    fun picker_edits_save_recreation_and_pad_keep_the_finite_native_recipe() {
        val root = shelf()
        val kitDir = File(root, "kit").apply { mkdirs() }
        val kit = Kit(name = "CAUSTIC UI", pads = emptyList(), key = KeySpec.parse("Cminpent"))
        KitStore.save(kit, kitDir)
        val settings = mutableMapOf("engine" to "OHM", "voice" to "STRING")
        show(root, settings, KitShelf.Entry(kitDir, kit))

        // The picker reads OHM, MERCURY, CAUSTIC: #643 put MERCURY after OHM.
        compose.onNodeWithContentDescription("ENGINE OHM. CHOOSE ENGINE").assertExists()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE MERCURY. CHOOSE ENGINE").assertExists()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE CAUSTIC. CHOOSE ENGINE").assertExists()
        compose.onNodeWithContentDescription("ALLOY").assertExists()
        val factory = CausticPresets.forVoice(CausticVoice.ALLOY).first()
        press("PRESET ${factory.name}")
        assertFiniteControls()
        for (macro in listOf("SOURCE", "FOCUS", "BODY", "LENGTH")) {
            assertEquals(factory.macros.getValue(macro), progress(macro), .00001f)
        }

        dial("ROOT", (57 - CausticInstrument.MIDI_MIN).toFloat() /
            (CausticInstrument.MIDI_MAX - CausticInstrument.MIDI_MIN))
        dial("VELOCITY", .43f)
        dial("SOURCE", .68f)
        dial("BODY", .62f)
        dial("LENGTH", .1f)
        val changed = factory.copy(midi = 57, velocity = .43f,
            macros = factory.macros + mapOf("SOURCE" to .68f, "BODY" to .62f, "LENGTH" to .1f))

        // Macro edits remove the preset highlight. The independent draft still
        // carries ROOT, VELOCITY and the native model when MAKE is recreated.
        compose.runOnIdle { generation++ }
        pump()
        waitFor("the restored Caustic root") { progress("ROOT") == (57 - 36) / 48f }
        assertEquals(.43f, progress("VELOCITY"), .00001f)
        assertEquals(.68f, progress("SOURCE"), .00001f)
        assertFiniteControls()

        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY CAUSTIC")
        pump()
        press("SAVE")
        waitFor("saved Caustic preset", timeoutMillis = 30_000) {
            UserPresets.read(root).any { it.name == "MY CAUSTIC" }
        }
        waitFor("selected Caustic preset") {
            compose.onAllNodesWithText("MY CAUSTIC").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Selected) == true
            }
        }
        val saved = UserPresets.read(root).single { it.name == "MY CAUSTIC" }.patch as CausticPatch
        assertEquals(changed.copy(name = "MY CAUSTIC"), saved)

        press("SEND TO PAD")
        press("A01, EMPTY")
        waitFor("the finite Caustic pad", timeoutMillis = 180_000) {
            KitBuilderModel.open(kitDir).pad(1) != null
        }
        val pad = KitBuilderModel.open(kitDir).pad(1)!!
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val placed = recipe.patch as CausticPatch
        assertEquals(saved.copy(name = placed.name), placed)
        assertEquals(CausticInstrument.drumClassFor(saved.voice, saved.macros), pad.drumClass)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertEquals("57", pad.source["rootMidi"])
        assertEquals(Scales.nameOf(saved.midi), pad.source["rootNote"])
        assertEquals("authored", pad.source["rootOrigin"])
        assertEquals("SYNTH", pad.source["from"])
        // A3 is outside C minor pentatonic: key placement moves it one
        // semitone to Bb3, while the saved source stays MIDI57 with no cent correction.
        val expectedTune = KitBuilderModel.open(kitDir).tuningForKnownRoot(saved.midi)
        assertEquals(1 to 0, expectedTune)
        assertEquals(expectedTune.first, pad.tuneCoarse)
        assertEquals(0, pad.tuneFine)
        assertTrue("a finite note remains a one-shot", pad.oneShot)
        assertEquals(null, recipe.fx)
        assertTrue(File(kitDir, pad.sampleFile).length() > 44)
    }

    @Test
    fun a_macro_edit_survives_recreation_in_the_same_ui_event() {
        val root = shelf()
        val settings = mutableMapOf("engine" to "CAUSTIC", "voice" to "ALLOY")
        show(root, settings)
        val adjust = compose.onNodeWithContentDescription("SOURCE", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!

        // Navigation can dispose the screen before its persistence effect runs.
        // Do not pump a frame between the final edit and recreating the bench.
        compose.runOnIdle {
            assertTrue(adjust(.68f))
            generation++
        }
        pump()
        assertEquals(.68f, progress("SOURCE"), .00001f)
    }

    @Test
    fun a_stale_reserved_hold_setting_cannot_trap_a_restored_note() {
        val root = shelf()
        val patch = CausticPatch("Saved root", CausticVoice.ALLOY,
            mapOf("BODY" to .7f), midi = 69, velocity = .37f, model = 1)
        val settings = mutableMapOf("engine" to "CAUSTIC", "voice" to "ALLOY",
            "macro.CAUSTIC.ALLOY.SOURCE" to ".33", "macro.CAUSTIC.ALLOY.HOLD" to "1.0")
        SynthDrafts.write(settings, listOf(patch))
        show(root, settings)
        waitFor("restored explicit Caustic root") { progress("ROOT") == (69 - 36) / 48f }
        assertEquals(.37f, progress("VELOCITY"), .00001f)
        assertEquals(.33f, progress("SOURCE"), .00001f)
        assertFiniteControls()
        waitFor("reserved HOLD reset") { settings["macro.CAUSTIC.ALLOY.HOLD"] == "0.0" }
    }
}
