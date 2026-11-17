package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.Ballast
import com.snipsnap.synth.BackwashPatch
import com.snipsnap.synth.BallastPatch
import com.snipsnap.synth.BallastVoice
import com.snipsnap.synth.Hiccup
import com.snipsnap.synth.HiccupPatch
import com.snipsnap.synth.Soot
import com.snipsnap.synth.SootPatch
import com.snipsnap.synth.Scuff
import com.snipsnap.synth.ScuffPatch
import com.snipsnap.synth.ScuffPresets
import com.snipsnap.synth.Clutter
import com.snipsnap.synth.ClutterPatch
import com.snipsnap.synth.ClutterPresets
import com.snipsnap.synth.Calabash
import com.snipsnap.synth.CalabashPatch
import com.snipsnap.synth.CalabashPresets
import com.snipsnap.synth.Servo
import com.snipsnap.synth.ServoPatch
import com.snipsnap.synth.ServoPresets
import com.snipsnap.synth.Kindred
import com.snipsnap.synth.KindredPatch
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

/** The real SYNTH editor, including restoring the previously roster-only engine selections. */
@RunWith(AndroidJUnit4::class)
class NewEngineSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "new-engine-editor-${System.nanoTime()}").apply { mkdirs() }
    private val settings = mutableMapOf<String, String>()
    private var generation by mutableStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(engine: String, voice: String) {
        settings["engine"] = engine
        settings["voice"] = voice
        setPhoneContent {
            key(generation) {
                SynthScreen(entry = null, shelfRoot = shelf, onToast = {}, onKitUpdated = {},
                    onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = appScope, settings = settings)
            }
        }
        button("ENGINE $engine. CHOOSE ENGINE").assertExists()
    }

    /** A macro slider by its label. A voice chip can share the name (SOOT's COIL voice and its snare's COIL). */
    private fun control(label: String) = compose.onNode(
        hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    private fun adjust(label: String, value: Float) {
        control(label).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        pump()
    }

    private fun progress(label: String) =
        control(label).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun showsLoop() = compose.onAllNodesWithText("LOOP", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun backwashRestoresItsDrumVoiceAndSixControls() {
        show("BACKWASH", "SNARE")
        button("SNARE").assertIsSelected()
        button("PRESET Tight Snare").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.9f, progress("DAMP"), .0001f)
        for (label in listOf("TUNE", "DAMP", "SWAY", "TRAIL", "PRESS", "DRIVE")) button(label).assertExists()
        adjust("TRAIL", .8f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("BACKWASH", settings["engine"])
        assertEquals(.8f, progress("TRAIL"), .0001f)
        assertFalse(showsLoop())
        val original = SynthDrafts.read(settings).single { it.engine == "BACKWASH" && it.voiceName == "SNARE" } as BackwashPatch
        assertEquals(3, original.model)
        assertEquals(.8f, original.macros.getValue("TRAIL"), .0001f)
        button("PRESET Pushed Snare").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        for (label in listOf("SWAY", "TRAIL", "PRESS", "DRIVE")) assertEquals(1f, progress(label), .0001f)
        adjust("PRESS", .72f)
        compose.runOnIdle { generation++ }
        pump()
        for (label in listOf("SWAY", "TRAIL", "DRIVE")) assertEquals(1f, progress(label), .0001f)
        assertEquals(.72f, progress("PRESS"), .0001f)
        val pushed = SynthDrafts.read(settings).single { it.engine == "BACKWASH" && it.voiceName == "SNARE" } as BackwashPatch
        assertEquals(4, pushed.model)
        assertEquals(.72f, pushed.macros.getValue("PRESS"), .0001f)
    }

    @Test
    fun drumlineKeepsASectionEditAndItsModel() {
        show("DRUMLINE", "SNARE")
        button("PRESET FIELD SNARE").assertExists()
        button("PRESET STREET SNARE").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.32f, progress("TUNE"), .0001f)
        assertEquals(.28f, progress("DAMP"), .0001f)
        for (label in listOf("TUNE", "STRIKE", "DAMP", "PLAYERS", "TIGHT")) control(label).assertExists()
        adjust("DAMP", .7f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(.7f, progress("DAMP"), .0001f)
        assertFalse(showsLoop())
        val draft = SynthDrafts.read(settings).single { it.engine == "DRUMLINE" && it.voiceName == "SNARE" } as com.snipsnap.synth.DrumlinePatch
        assertEquals(4, draft.model)
        assertEquals(.32f, draft.macros.getValue("TUNE"), .0001f)
    }

    /**
     * SOOT on the picker (2026-11-13): a prototype outside the factory presets, so its voices and controls
     * with no preset chips. The snare opens at the owner's pick (EDGE 1, the hot cassette), an edit survives
     * a rebuild, and the saved draft keeps its renderer model.
     */
    @Test
    fun sootOpensItsSnareAtThePickAndKeepsAnEditAndItsModel() {
        show("SOOT", "SNARE")
        button("SNARE").assertIsSelected()
        for (label in listOf("TUNE", "COIL", "EDGE", "BENCH", "GRIME")) control(label).assertExists()
        // COIL is both a voice chip and the snare's spring send: two nodes, one of them the slider.
        compose.onAllNodesWithContentDescription("COIL").assertCountEquals(2)
        assertEquals(1f, progress("EDGE"), .0001f)
        adjust("COIL", .8f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("SOOT", settings["engine"])
        assertEquals(.8f, progress("COIL"), .0001f)
        assertFalse(showsLoop())
        val draft = SynthDrafts.read(settings).single { it.engine == "SOOT" && it.voiceName == "SNARE" } as SootPatch
        assertEquals(Soot.MODEL_VERSION, draft.model)
        assertEquals(.8f, draft.macros.getValue("COIL"), .0001f)
    }

    /**
     * TERRA on the picker (2026-11-14): the kept classic kit's pads are its factory presets. DJEMBE opens, its
     * kept open tone loads with STROKE at 1, an edit survives a rebuild, and the retired membrane is not offered.
     */
    @Test
    fun terraLoadsTheKeptDjembeOpenAndKeepsAnEdit() {
        show("TERRA", "DJEMBE")
        button("DJEMBE").assertIsSelected()
        button("MEMBRANE").assertDoesNotExist()
        for (label in listOf("TUNE", "DECAY", "FORCE", "STROKE", "THUMP")) control(label).assertExists()
        button("PRESET Djembe Open").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(1f, progress("STROKE"), .0001f)
        adjust("THUMP", .7f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("TERRA", settings["engine"])
        assertEquals("DJEMBE", settings["voice"])
        assertEquals(.7f, progress("THUMP"), .0001f)
        assertEquals(1f, progress("STROKE"), .0001f)
        assertFalse(showsLoop())
    }

    /**
     * SCUFF on the picker (2026-11-14, round 5), mirroring SOOT with a roster: the kick opens with its
     * five controls (the chain knobs a kick does not take stay off the panel), its preset chips are the
     * two accepted kits' kicks, FLINT KICK lands with the kit's own TUNE, DECAY and CLICK, an edit survives
     * a rebuild, and the saved draft keeps the preset's take and renderer model.
     */
    @Test
    fun scuffOpensItsKickLoadsAKitPresetAndKeepsAnEditWithItsTakeAndModel() {
        show("SCUFF", "KICK")
        button("KICK").assertIsSelected()
        for (label in listOf("TUNE", "DECAY", "CLICK", "SQUARE", "SLAM")) button(label).assertExists()
        compose.onAllNodesWithContentDescription("GRIT").assertCountEquals(0)
        button("PRESET FLINT KICK").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.6f, progress("TUNE"), .0001f)
        assertEquals(.3f, progress("DECAY"), .0001f)
        assertEquals(.8f, progress("CLICK"), .0001f)
        adjust("DECAY", .5f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("SCUFF", settings["engine"])
        assertEquals(.5f, progress("DECAY"), .0001f)
        assertEquals(.6f, progress("TUNE"), .0001f)
        assertFalse(showsLoop())
        val draft = SynthDrafts.read(settings).single { it.engine == "SCUFF" && it.voiceName == "KICK" } as ScuffPatch
        assertEquals(Scuff.MODEL_VERSION, draft.model)
        assertEquals(ScuffPresets.TAKE, draft.take)
        assertEquals(1f, draft.velocity, .0001f)
        assertEquals(.5f, draft.macros.getValue("DECAY"), .0001f)
        assertEquals(.6f, draft.macros.getValue("TUNE"), .0001f)
    }

    /**
     * CLUTTER on the picker (2026-11-14, round 4), mirroring SCUFF's entry: the bucket opens with its five
     * controls, LOFI SOFT BUCKET (the KITCHEN LO-FI kick) lands with the pad's own TUNE, DAMP and MIC, an edit
     * survives a rebuild, and the saved draft keeps the preset's take, full velocity and renderer model.
     */
    @Test
    fun clutterOpensItsBucketLoadsAKitPresetAndKeepsAnEditWithItsTakeAndModel() {
        show("CLUTTER", "BUCKET")
        button("BUCKET").assertIsSelected()
        for (label in listOf("TUNE", "TOOL", "RATTLE", "DAMP", "MIC")) control(label).assertExists()
        button("PRESET LOFI SOFT BUCKET").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.42f, progress("TUNE"), .0001f)
        assertEquals(.45f, progress("DAMP"), .0001f)
        assertEquals(.35f, progress("MIC"), .0001f)
        adjust("DAMP", .6f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("CLUTTER", settings["engine"])
        assertEquals(.6f, progress("DAMP"), .0001f)
        assertEquals(.42f, progress("TUNE"), .0001f)
        assertFalse(showsLoop())
        val draft = SynthDrafts.read(settings).single { it.engine == "CLUTTER" && it.voiceName == "BUCKET" } as ClutterPatch
        assertEquals(Clutter.MODEL_VERSION, draft.model)
        assertEquals(ClutterPresets.TAKE, draft.take)
        assertEquals(1f, draft.velocity, .0001f)
        assertEquals(.6f, draft.macros.getValue("DAMP"), .0001f)
        assertEquals(.42f, draft.macros.getValue("TUNE"), .0001f)
    }

    /**
     * HICCUP on the picker (2026-11-15), with its presets in the factory bank. The snare opens with its six
     * controls at their defaults, an edit survives a rebuild, and the saved draft keeps its renderer model.
     */
    @Test
    fun hiccupOpensItsSnareAtItsDefaultsAndKeepsAnEditAndItsModel() {
        show("HICCUP", "SNARE")
        button("SNARE").assertIsSelected()
        for (label in listOf("TUNE", "DECAY", "TOY", "FAULT", "CHOP", "CRUSH")) control(label).assertExists()
        assertEquals(Hiccup.defaults(com.snipsnap.synth.HiccupVoice.SNARE).getValue("FAULT"), progress("FAULT"), .0001f)
        adjust("FAULT", .8f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("HICCUP", settings["engine"])
        assertEquals(.8f, progress("FAULT"), .0001f)
        assertFalse(showsLoop())
        val draft = SynthDrafts.read(settings).single { it.engine == "HICCUP" && it.voiceName == "SNARE" } as HiccupPatch
        assertEquals(Hiccup.MODEL_VERSION, draft.model)
        assertEquals(.8f, draft.macros.getValue("FAULT"), .0001f)
    }

    /**
     * CALABASH on the picker (2026-11-15, round 4), mirroring CLUTTER's entry: the conga opens with its five controls,
     * AFROBEAT CONGA OPEN (the first of its presets, the AFROBEAT kit's A11) lands with the pad's ROOM, AGE and open
     * STROKE, an edit survives a rebuild, and the saved draft keeps the preset's take, full velocity and model.
     */
    @Test
    fun calabashOpensItsCongaLoadsAKitPresetAndKeepsAnEditWithItsTakeAndModel() {
        show("CALABASH", "CONGA")
        button("CONGA").assertIsSelected()
        for (label in listOf("TUNE", "DAMP", "STROKE", "ROOM", "AGE")) control(label).assertExists()
        button("PRESET AFROBEAT CONGA OPEN").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.3f, progress("ROOM"), .0001f)
        assertEquals(.55f, progress("AGE"), .0001f)
        assertEquals(1f / 3f, progress("STROKE"), .0001f)
        adjust("DAMP", .6f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("CALABASH", settings["engine"])
        assertEquals(.6f, progress("DAMP"), .0001f)
        assertEquals(.3f, progress("ROOM"), .0001f)
        assertFalse(showsLoop())
        val draft = SynthDrafts.read(settings).single { it.engine == "CALABASH" && it.voiceName == "CONGA" } as CalabashPatch
        assertEquals(Calabash.MODEL_VERSION, draft.model)
        assertEquals(CalabashPresets.TAKE, draft.take)
        assertEquals(1f, draft.velocity, .0001f)
        assertEquals(.6f, draft.macros.getValue("DAMP"), .0001f)
        assertEquals(.55f, draft.macros.getValue("AGE"), .0001f)
    }

    /**
     * SERVO on the picker (2026-11-16), mirroring CALABASH's entry: the kick opens with its six controls, ELECTRO BOOM
     * KICK (the first of its presets, the ELECTRO kit's A01) lands with the pad's SAG, GROWL and ZAP, an edit
     * survives a rebuild, and the saved draft keeps the preset's take, full velocity and model.
     */
    @Test
    fun servoOpensItsKickLoadsAKitPresetAndKeepsAnEditWithItsTakeAndModel() {
        show("SERVO", "KICK")
        button("KICK").assertIsSelected()
        for (label in listOf("TUNE", "DECAY", "ZAP", "SAG", "GROWL", "HEAT")) control(label).assertExists()
        button("PRESET ELECTRO BOOM KICK").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.3f, progress("SAG"), .0001f)
        assertEquals(.15f, progress("GROWL"), .0001f)
        assertEquals(.5f, progress("ZAP"), .0001f)
        adjust("HEAT", .6f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("SERVO", settings["engine"])
        assertEquals(.6f, progress("HEAT"), .0001f)
        assertEquals(.3f, progress("SAG"), .0001f)
        assertFalse(showsLoop())
        val draft = SynthDrafts.read(settings).single { it.engine == "SERVO" && it.voiceName == "KICK" } as ServoPatch
        assertEquals(Servo.MODEL_VERSION, draft.model)
        assertEquals(ServoPresets.TAKE, draft.take)
        assertEquals(1f, draft.velocity, .0001f)
        assertEquals(.6f, draft.macros.getValue("HEAT"), .0001f)
        assertEquals(.15f, draft.macros.getValue("GROWL"), .0001f)
    }

    /**
     * KINDRED on the picker (2026-11-17), mirroring SERVO's entry: the kick opens with its six controls, TECHNO KICK
     * (the first of its provisional presets, the TECHNO kit's A01) lands with the pad's LENGTH, an edit survives a
     * rebuild, and the saved draft keeps the pad's kit recipe, take 0, full velocity and model.
     */
    @Test
    fun kindredOpensItsKickLoadsAKitPadAndKeepsAnEditWithItsRecipeAndModel() {
        show("KINDRED", "KICK")
        button("KICK").assertIsSelected()
        for (label in listOf("TUNE", "DIVE", "CLANG", "NOISE", "TONE", "LENGTH")) control(label).assertExists()
        button("PRESET TECHNO KICK").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.42f, progress("LENGTH"), .0001f)
        assertEquals(.5f, progress("DIVE"), .0001f)
        adjust("TONE", .6f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("KINDRED", settings["engine"])
        assertEquals(.6f, progress("TONE"), .0001f)
        assertEquals(.42f, progress("LENGTH"), .0001f)
        assertFalse(showsLoop())
        val draft = SynthDrafts.read(settings).single { it.engine == "KINDRED" && it.voiceName == "KICK" } as KindredPatch
        assertEquals(Kindred.MODEL_VERSION, draft.model)
        assertEquals("TECHNO_A01", draft.recipe)
        assertEquals(0, draft.take)
        assertEquals(1f, draft.velocity, .0001f)
        assertEquals(.6f, draft.macros.getValue("TONE"), .0001f)
        assertEquals(.42f, draft.macros.getValue("LENGTH"), .0001f)
    }

    @Test
    fun chitinLoadsCandidateBassAndKeepsMaximumHoldFinite() {
        show("CHITIN", "SHELL")
        button("SHELL").assertIsSelected()
        button("PRESET FOUNDATION").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.18f, progress("BITE"), .0001f)
        adjust("HOLD", 1f)
        assertTrue(compose.onAllNodesWithText("LONG NOTE", useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("CHITIN", settings["engine"])
        assertEquals(1f, progress("HOLD"), .0001f)
    }

    @Test
    fun aerostatOpensItsApprovedPresetAndItsActualControls() {
        show("AEROSTAT", "FLOAT")
        button("FLOAT").assertIsSelected()
        button("PRESET SOFT CATCH").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        // This is the listener-approved dry PVC recipe, not the original full-air recipe.
        assertEquals(.10f, progress("STRIKE"), .0001f)
        assertEquals(.18f, progress("PRESSURE"), .0001f)
        assertEquals(0f, progress("LIFT"), .0001f)
        adjust("HOLD", 1f)
        assertTrue(showsLoop())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("AEROSTAT", settings["engine"])
        assertEquals(1f, progress("HOLD"), .0001f)
        assertEquals(.10f, progress("STRIKE"), .0001f)
    }

    @Test
    fun tremorKeepsTheEditedVoiceAndShowsItsHalfwayLoopThreshold() {
        show("TREMOR", "WIRE")
        button("WIRE").assertIsSelected()
        adjust("CAGE", .73f)
        adjust("HOLD", .49f)
        assertFalse(showsLoop())
        adjust("HOLD", .5f)
        assertTrue(showsLoop())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("TREMOR", settings["engine"])
        assertEquals("WIRE", settings["voice"])
        button("WIRE").assertIsSelected()
        assertEquals(.73f, progress("CAGE"), .0001f)
        assertEquals(.5f, progress("HOLD"), .0001f)
    }

    @Test
    fun ballastDisplaysTilesButKeepsTheStoredGlintVoice() {
        val patch = BallastPatch("MY TILES", BallastVoice.GLINT,
            Ballast.defaults(BallastVoice.GLINT) + ("SPAN" to .33f))
        SynthDrafts.write(settings, listOf(patch))
        for ((name, value) in patch.macros) settings["macro.BALLAST.GLINT.$name"] = value.toString()
        show("BALLAST", "GLINT")
        button("TILES").assertIsSelected()
        button("GLINT").assertDoesNotExist()
        adjust("SPAN", .77f)
        adjust("HOLD", 1f)
        assertTrue(showsLoop())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals("BALLAST", settings["engine"])
        assertEquals("GLINT", settings["voice"])
        compose.runOnIdle {
            assertEquals("GLINT", SynthDrafts.read(settings).single { it.engine == "BALLAST" }.voiceName)
        }
        button("TILES").assertIsSelected()
        assertEquals(.77f, progress("SPAN"), .0001f)
        assertEquals(1f, progress("HOLD"), .0001f)
    }
}
