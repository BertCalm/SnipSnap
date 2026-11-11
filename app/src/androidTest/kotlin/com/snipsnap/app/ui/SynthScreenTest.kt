package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.OhmPadMaker
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Eq
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmGesture
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import com.snipsnap.synth.Onset
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetPresets
import com.snipsnap.synth.OnsetVoice
import com.snipsnap.synth.SkinPatch
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The ONSET adapter and the real REOPEN → UPDATE path, driven through Compose.
 * The update fixture carries an older renderer, a rack and pad metadata so a
 * fresh-patch reconstruction or a plain SEND replacement cannot pass unnoticed.
 * JVM suites cover the codecs; these tests require a device or emulator.
 * The OHM editor tests also exercise the real editor bindings and recreate its
 * remember-state from the MAKE bag.
 */
@RunWith(AndroidJUnit4::class)
class SynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val roots = mutableListOf<File>()
    private val ohmShelfRoot = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "ohm-editor-${System.nanoTime()}").apply { mkdirs() }
    private var generation by mutableStateOf(0)
    private var currentEntry by mutableStateOf<KitShelf.Entry?>(null)
    private var screenVisible by mutableStateOf(true)
    private val settings = mutableMapOf<String, String>()
    private val updatedKit = AtomicReference<Kit?>(null)
    private val recordedToasts = CopyOnWriteArrayList<String>()

    @After
    fun cleanUp() {
        appScope.cancel()
        ohmShelfRoot.deleteRecursively()
        roots.forEach { it.deleteRecursively() }
    }

    /** Mount SYNTH on the OHM picker entry, the way the MAKE bag would have left it. */
    private fun show() {
        settings.putIfAbsent("engine", "OHM")
        settings.putIfAbsent("voice", "STRING")
        setPhoneContent {
            key(generation) {
                Box(Modifier.testTag("ohm-synth-generation-$generation")) {
                    SynthScreen(entry = null, shelfRoot = ohmShelfRoot, onToast = {}, onKitUpdated = {},
                        onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                        appScope = appScope, settings = settings)
                }
            }
        }
    }

    // Invoke the same accessibility actions as TalkBack. No animated scrolling is
    // needed on ComposeScreenTest's deliberately hand-driven frame clock.
    private fun adjust(label: String, value: Float) {
        button(label).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        pump()
    }

    private fun progress(label: String) =
        button(label).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test
    fun backroomSnareKeepsItsControlsAcrossNavigationAndReturnsToOriginalExplicitly() {
        settings["engine"] = "SKIN"
        settings["voice"] = "SNARE"
        show()
        activate("BACKROOM · MORE SNAP")
        for (name in listOf("ATTACK", "BODY", "WIRE", "BRIGHTNESS"))
            assertEquals(.5f, progress(name), 0f)
        button("TUNE").assertDoesNotExist()
        adjust("BODY", .8f)
        adjust("WIRE", .25f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(.8f, progress("BODY"), 0f)
        assertEquals(.25f, progress("WIRE"), 0f)
        val saved = SynthDrafts.read(settings).filterIsInstance<SkinPatch>().single()
        assertEquals(2, saved.model)
        activate("RANDOMISE")
        val scrambled = SynthDrafts.read(settings).filterIsInstance<SkinPatch>().single()
        assertEquals(setOf("ATTACK", "BODY", "WIRE", "BRIGHTNESS"), scrambled.macros.keys)
        activate("ORIGINAL")
        button("TUNE").assertExists()
        button("BRIGHTNESS").assertDoesNotExist()
        assertEquals(1, SynthDrafts.read(settings).filterIsInstance<SkinPatch>().single().model)
    }

    @Test
    fun backroomPadSavesAndReopensItsModelWithoutAddingTheTreatmentTwice() {
        val root = shelfRoot()
        val dir = File(root, "BACKROOM KIT").apply { check(mkdirs()) }
        val kit = Kit("BACKROOM KIT", emptyList())
        KitStore.save(kit, dir)
        settings["engine"] = "SKIN"
        settings["voice"] = "SNARE"
        show(root, KitShelf.Entry(dir, kit))
        activate("BACKROOM · MORE SNAP")
        adjust("ATTACK", .75f)
        adjust("BRIGHTNESS", .8f)
        activate("SEND TO PAD")
        activate("${PadBanks.tag(1)}, EMPTY")
        waitForPadWrite("the Backroom snare", timeoutMillis = 60_000) { updatedKit.get()?.pad(1)?.recipe != null }
        val pad = checkNotNull(KitStore.load(dir).pad(1))
        val recipe = PadRecipe.fromJsonValue(checkNotNull(pad.recipe))
        val saved = recipe.patch as SkinPatch
        assertEquals(2, saved.model)
        assertEquals(.75f, saved.macros.getValue("ATTACK"), 0f)
        assertEquals(.8f, saved.macros.getValue("BRIGHTNESS"), 0f)
        assertTrue(recipe.fx == null)
        assertTrue(recipe.alias)
        assertEquals(DrumClass.SNARE, pad.drumClass)
        assertTrue(File(dir, pad.sampleFile).length() > 44)
        activate("REOPEN PAD RECIPE")
        activate("REOPEN ${PadBanks.tag(pad.slot)} ${pad.displayName}")
        assertEquals(.75f, progress("ATTACK"), 0f)
        assertEquals(.8f, progress("BRIGHTNESS"), 0f)
        adjust("WIRE", .25f)
        leaveAndReturn()
        assertEquals(.25f, progress("WIRE"), 0f)
        button("UPDATE PAD ${PadBanks.tag(pad.slot)}").assertExists()
    }

    @Test
    fun freshEncounterStartsAtApprovedRepliesAndKeepsThemThroughScrambleAndNavigation() {
        show()
        val initial = SynthDrafts.read(settings).filterIsInstance<OhmPatch>().single()
        assertEquals(Ohm.replyRingPatch(), initial)
        assertEquals(.5f, progress("REPLY"), 0f)
        assertEquals(.5f, progress("RING"), 0f)
        for ((macro, value) in initial.macros) assertEquals(value, progress(macro), 0f)
        button("OHM GATHER").assertDoesNotExist()
        button("HOLD").assertDoesNotExist()
        button("MAKE INSTRUMENT").assertExists()

        adjust("REPLY", .25f)
        adjust("RING", .75f)
        adjust("VELOCITY", .6f)
        activate("MAKE INSTRUMENT")
        adjust("RELEASE", .7f)
        // The sheet's scrim and its visible button both expose CANCEL.
        val cancel = compose.onAllNodesWithContentDescription("CANCEL", useUnmergedTree = true)
            .fetchSemanticsNodes().last()
        compose.runOnIdle { assertTrue(requireNotNull(cancel.config[SemanticsActions.OnClick].action).invoke()) }
        pump()
        button("RANDOMISE").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        val expected = OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING"))
        assertEquals(initial.copy(reply = .25f, ring = .75f, velocity = .6f,
            releaseSeconds = OhmPadMaker.RELEASE.value(.7f), macros = expected.macros), expected)
        button("NEXT ENGINE").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        button("PREVIOUS ENGINE").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        assertEquals(.25f, progress("REPLY"), 0f)
        assertEquals(.75f, progress("RING"), 0f)
        compose.runOnIdle { generation++ }
        pump()
        compose.runOnIdle { assertEquals(expected, SynthDrafts.read(settings).filterIsInstance<OhmPatch>().single()) }
        assertEquals(.25f, progress("REPLY"), 0f)
        assertEquals(.75f, progress("RING"), 0f)
    }

    @Test
    fun macroOnlyEncounterKeepsItsModelTwoControls() {
        settings["macro.OHM.STRING.COMMUNE"] = ".73"
        show()
        button("OHM STEADY").assertIsSelected()
        button("REPLY").assertDoesNotExist()
        assertEquals(.73f, progress("COMMUNE"), 0f)
        adjust("VELOCITY", .4f)
        val saved = OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING"))
        assertEquals(2, saved.model)
        assertEquals(.73f, saved.macros.getValue("COMMUNE"), 0f)
    }

    @Test
    fun naturalRepliesCanBeSelectedFromAnOldEncounter() {
        val original = OhmPatch("KEPT FINITE NOTE", Ohm.defaults(), model = 2)
        settings["patch.OHM.STRING"] = original.toJsonText()
        show()
        button("OHM STEADY").assertIsSelected()
        activate("PRESET Natural Replies")
        val expected = Ohm.replyRingPatch()
        assertEquals(expected, OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING")))
        button("OHM GATHER").assertDoesNotExist()
    }

    @Test
    fun namedNaturalRepliesRestoreTheirApprovedMacrosAndRenderer() {
        settings["preset.OHM.STRING"] = "Natural Replies"
        show()
        val expected = Ohm.replyRingPatch()
        for ((macro, value) in expected.macros) assertEquals(value, progress(macro), 0f)
        assertEquals(.5f, progress("REPLY"), 0f)
        assertEquals(.5f, progress("RING"), 0f)
        adjust("VELOCITY", .4f)
        assertEquals(expected.copy(velocity = .4f), OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING")))
    }

    @Test
    fun replyRingPadKeepsItsCompleteFiniteRecipeWhenSentAndReopened() {
        val root = shelfRoot()
        val dir = File(root, "REPLY KIT").apply { check(mkdirs()) }
        val kit = Kit("REPLY KIT", emptyList())
        KitStore.save(kit, dir)
        settings["engine"] = "OHM"
        settings["voice"] = "STRING"
        show(root, KitShelf.Entry(dir, kit))
        adjust("REPLY", .25f)
        adjust("RING", .75f)
        adjust("VELOCITY", .6f)
        val expected = Ohm.replyRingPatch().copy(reply = .25f, ring = .75f, velocity = .6f)
        activate("SEND TO PAD")
        activate("${PadBanks.tag(1)}, EMPTY")
        waitForPadWrite("the finite Reply/Ring pad", timeoutMillis = 60_000) { updatedKit.get()?.pad(1)?.recipe != null }
        val pad = checkNotNull(KitStore.load(dir).pad(1))
        val saved = PadRecipe.fromJsonValue(checkNotNull(pad.recipe)).patch as OhmPatch
        assertEquals(expected.copy(name = saved.name), saved)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertTrue(pad.oneShot && !pad.loop)
        assertTrue(File(dir, pad.sampleFile).length() > 44)

        activate("REOPEN PAD RECIPE")
        activate("REOPEN ${PadBanks.tag(pad.slot)} ${pad.displayName}")
        adjust("REPLY", .7f)
        activate("NEXT ENGINE")
        activate("PREVIOUS ENGINE")
        assertEquals(.7f, progress("REPLY"), 0f)
        assertEquals(.75f, progress("RING"), 0f)
        button("UPDATE PAD ${PadBanks.tag(pad.slot)}").assertExists()
        val reopened = OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING"))
        assertEquals(saved.copy(reply = .7f), reopened)
    }

    @Test
    fun legacyEncounterRetainsItsIdentityThroughEditsAndNavigation() {
        val source = OhmPatch("MY FAMILY", Ohm.defaults() + ("LENGTH" to .1f), midi = 57,
            velocity = .65f, seed = -905, gesture = OhmGesture.GATHER, model = 1)
        settings["patch.OHM.STRING"] = source.toJsonText()
        settings["preset.OHM.STRING"] = "Gather"
        show()
        button("OHM GATHER").assertIsSelected()
        assertEquals((57 - 48) / 24f, progress("ROOT"), .0001f)

        adjust("ROOT", (64 - 48) / 24f)
        adjust("VELOCITY", .35f)
        adjust("COMMUNE", .73f)
        button("OHM STEADY").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        val expected = source.copy(midi = 64, velocity = .35f, gesture = OhmGesture.STEADY,
            macros = source.macros + ("COMMUNE" to .73f))
        compose.runOnIdle {
            assertEquals(expected, OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING")))
            assertFalse(settings.containsKey("preset.OHM.STRING"))
            generation++
        }
        pump()
        waitFor("the recreated OHM editor", timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("ohm-synth-generation-$generation").fetchSemanticsNodes().size == 1
        }
        button("OHM STEADY").assertIsSelected()
        assertEquals((64 - 48) / 24f, progress("ROOT"), .0001f)
        assertEquals(.35f, progress("VELOCITY"), .0001f)
        assertEquals(.73f, progress("COMMUNE"), .0001f)
        compose.runOnIdle {
            assertEquals(expected, OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING")))
        }
    }

    @Test
    fun expandedEncounterRetainsHoldSourcesAndReleaseThroughScrambleAndNavigation() {
        assertExpandedEncounterSurvivesEditsAndNavigation(updateSources = false)
    }

    @Test
    fun updatedEncounterRetainsHoldSourcesAndReleaseThroughScrambleAndNavigation() {
        assertExpandedEncounterSurvivesEditsAndNavigation(updateSources = true)
    }

    private fun assertExpandedEncounterSurvivesEditsAndNavigation(updateSources: Boolean) {
        val source = OhmPatch("HELD FAMILY", Ohm.defaults(3) + ("HOLD" to .6f), midi = 57,
            velocity = .65f, seed = -905, gesture = OhmGesture.GATHER, model = 3,
            options = OhmOptions(exciter = OhmExciter.BOW, elder = .25f, grains = .35f, freeze = .4f),
            releaseSeconds = .8f)
        settings["patch.OHM.STRING"] = source.toJsonText()
        show()
        if (updateSources) {
            button("OHM UPDATE SOURCES").performSemanticsAction(SemanticsActions.OnClick) { it() }
            pump()
        }
        adjust("ELDER", .7f)
        adjust("COMMUNE", .73f)
        button("RANDOMISE").performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
        val expected = OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING"))
        compose.runOnIdle {
            assertEquals(if (updateSources) 4 else source.model, expected.model)
            assertEquals(source.midi, expected.midi)
            assertEquals(source.velocity, expected.velocity)
            assertEquals(source.seed, expected.seed)
            assertEquals(source.gesture, expected.gesture)
            assertEquals(source.releaseSeconds, expected.releaseSeconds)
            assertEquals(source.options.copy(elder = .7f), expected.options)
            assertEquals(.6f, expected.macros.getValue("HOLD"))
            generation++
        }
        pump()
        waitFor("the recreated expanded OHM editor", timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("ohm-synth-generation-$generation").fetchSemanticsNodes().size == 1
        }
        button("OHM EXCITER BOW").assertIsSelected()
        compose.runOnIdle {
            assertEquals(expected, OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING")))
        }
    }

    @Test
    fun foreignRecipeInTheMakeBagFallsBackToANewReplyRingEncounter() {
        settings["patch.OHM.STRING"] = """{"engine":"THUMP","version":1,"name":"OTHER","voice":"KICK","macros":{}}"""
        show()
        assertEquals(.5f, progress("REPLY"), 0f)
        adjust("VELOCITY", .4f)
        compose.runOnIdle {
            val patch = OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING"))
            assertEquals(5, patch.model)
            assertEquals(60, patch.midi)
            assertEquals(.4f, patch.velocity)
            assertEquals(OhmGesture.STEADY, patch.gesture)
        }
    }

    private fun shelfRoot(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "synth-screen-test-${System.nanoTime()}").apply {
            check(mkdirs())
            roots += this
        }
    }

    private fun show(root: File, entry: KitShelf.Entry? = null) {
        currentEntry = entry
        setPhoneContent {
            if (screenVisible) {
                SynthScreen(
                    entry = currentEntry,
                    shelfRoot = root,
                    onToast = { recordedToasts += it },
                    onKitUpdated = { kit ->
                        // The entry first, then the signal: a test that sees
                        // updatedKit must find the state write already made,
                        // so waitForPadWrite's pump always draws the new kit.
                        currentEntry = currentEntry?.copy(kit = kit)
                        updatedKit.set(kit)
                    },
                    onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> },
                    onOpenSurface = {},
                    appScope = appScope,
                    settings = settings,
                )
            }
        }
    }

    /** Remove the screen's remember state, then mount it with the same navigation bag. */
    private fun leaveAndReturn(entry: KitShelf.Entry? = currentEntry, beforeReturn: () -> Unit = {}) {
        compose.runOnIdle { screenVisible = false }
        pump()
        button("ENGINE ONSET. CHOOSE ENGINE").assertDoesNotExist()
        compose.runOnIdle {
            beforeReturn()
            currentEntry = entry
            screenVisible = true
        }
        pump()
    }

    private fun macroNode(label: String) =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)

    private fun macroValue(label: String): Float =
        macroNode(label).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current
            ?: error("$label has no adjustable progress")

    private fun dialMacro(label: String, value: Float) {
        val action = macroNode(label).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("$label has no SetProgress action")
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    /**
     * REOPEN and UPDATE can sit below the phone's fold. Invoke their real
     * accessibility actions directly, like PadSheetScreenTest's steppers:
     * no animated ScrollBy can wait forever on the harness's frozen clock.
     */
    private fun activate(label: String) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        assertFalse("$label is disabled", node.config.getOrNull(SemanticsProperties.Disabled) != null)
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("$label has no click action")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    /**
     * Keep write failures visible: a refused pad must not look like an
     * unexplained timeout. Then give the screen one pump to draw the
     * finished send.
     *
     * The pump is needed because the clock here is frozen. sendToSlot hands
     * over the kit (onKitUpdated, which [condition] watches) and clears
     * padChooser.sendBusy in its finally block, both in the same main-thread
     * step. But waitFor returns the moment [condition] holds, with no frame
     * drawn since. The semantics still show the send in flight: REOPEN PAD
     * RECIPE disabled, and UPDATE PAD, where it is shown, disabled and named
     * "…". [activate] rightly refuses a disabled control, so a test that
     * reopens a pad straight after sending or updating one failed on that
     * stale frame, not on the screen. The frames the pump advances run on
     * the main thread, queued behind that step, so they show the send done.
     */
    private fun waitForPadWrite(
        what: String,
        timeoutMillis: Long = 30_000,
        condition: () -> Boolean = { updatedKit.get() != null },
    ) {
        try {
            waitFor(what, timeoutMillis, condition)
        } catch (failure: AssertionError) {
            val messages = recordedToasts.joinToString(" | ").ifBlank { "<none>" }
            throw AssertionError("$what did not complete within ${timeoutMillis}ms. Captured SYNTH toasts: $messages", failure)
        }
        pump()
    }

    @Test
    fun onset_follows_skin_and_all_eight_voices_expose_their_six_live_controls() {
        show(shelfRoot())
        button("ENGINE THUMP. CHOOSE ENGINE").assertExists()
        tap("NEXT ENGINE")
        button("ENGINE SCANNED CHAIN. CHOOSE ENGINE").assertExists()
        tap("NEXT ENGINE")
        button("ENGINE SKIN. CHOOSE ENGINE").assertExists()
        tap("NEXT ENGINE")
        button("ENGINE ONSET. CHOOSE ENGINE").assertExists()

        val voices = listOf(
            "KICK" to OnsetVoice.KICK,
            "SNARE" to OnsetVoice.SNARE,
            "HAT CL" to OnsetVoice.HAT_CLOSED,
            "HAT OP" to OnsetVoice.HAT_OPEN,
            "TOM" to OnsetVoice.TOM,
            "PLUCK" to OnsetVoice.PERC_PLUCK,
            "WOOD" to OnsetVoice.PERC_WOOD,
            "METAL" to OnsetVoice.PERC_METAL,
        )
        for ((label, voice) in voices) {
            activate(label)
            button(label).assertIsSelected()
            val controls = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertEquals("$voice control count", 6, controls.size)
            for ((macro, value) in Onset.defaults(voice)) {
                assertEquals("$voice $macro default", value, macroValue(macro), 0f)
            }
        }

        // Visiting a fresh voice freezes its renderer without requiring a
        // preset selection, so a later app version can restore this sound.
        compose.runOnIdle {
            val draft = SynthDrafts.read(settings).filterIsInstance<OnsetPatch>().single { it.voice == OnsetVoice.PERC_METAL }
            assertEquals(4, draft.modelVersion)
            assertEquals(Onset.defaults(OnsetVoice.PERC_METAL), draft.macros)
        }
        leaveAndReturn()
        button("METAL").assertIsSelected()
        for ((macro, value) in Onset.defaults(OnsetVoice.PERC_METAL)) assertEquals(value, macroValue(macro), 0f)

        // Changing a knob reaches the adapter's per-voice state, and returning
        // from another voice restores the edit rather than reseeding defaults.
        tap("KICK")
        dialMacro("BLEND", .73f)
        assertEquals(.73f, macroValue("BLEND"), 0f)
        tap("SNARE")
        assertEquals(.5f, macroValue("BLEND"), 0f)
        tap("KICK")
        assertEquals(.73f, macroValue("BLEND"), 0f)
    }

    @Test
    fun restoring_a_named_user_preset_finishes_loading_its_saved_onset_renderer() {
        val root = shelfRoot()
        val original = OnsetPatch("MY OLD KICK", OnsetVoice.KICK,
            Onset.defaults(OnsetVoice.KICK) + ("TUNE" to .72f), modelVersion = 1)
        UserPresets.save(root, original, nowMillis = 1L)
        settings["engine"] = "ONSET"
        settings["voice"] = "KICK"
        settings["preset.ONSET.KICK"] = original.name
        // No macro bag: the asynchronously loaded YOURS recipe is the whole sound.
        show(root)
        waitFor("the historical YOURS recipe to finish loading") {
            compose.runOnIdle {
                SynthDrafts.read(settings).filterIsInstance<OnsetPatch>().any {
                    it.voice == OnsetVoice.KICK && it.modelVersion == 1
                }
            }
        }
        for ((macro, value) in original.macros) assertEquals("restored YOURS $macro", value, macroValue(macro), 0f)
        dialMacro("BODY", .69f)
        leaveAndReturn()
        assertEquals(.69f, macroValue("BODY"), 0f)
        compose.runOnIdle {
            val restored = SynthDrafts.read(settings).filterIsInstance<OnsetPatch>().single { it.voice == OnsetVoice.KICK }
            assertEquals(original.copy(macros = original.macros + ("BODY" to .69f)), restored)
        }
    }

    @Test
    fun new_onset_voices_land_as_native_recipes_and_reopen_without_losing_their_model() {
        val root = shelfRoot()
        val dir = File(root, "NEW ONSET KIT").apply { check(mkdirs()) }
        val kit = Kit("NEW ONSET KIT", emptyList())
        KitStore.save(kit, dir)
        settings["engine"] = "ONSET"
        settings["voice"] = "TOM"
        show(root, KitShelf.Entry(dir, kit))
        val voices = listOf(
            "TOM" to OnsetVoice.TOM,
            "PLUCK" to OnsetVoice.PERC_PLUCK,
            "WOOD" to OnsetVoice.PERC_WOOD,
            "METAL" to OnsetVoice.PERC_METAL,
        )
        for ((index, pair) in voices.withIndex()) {
            val (label, voice) = pair
            activate(label)
            val preset = OnsetPresets.forVoice(voice).last()
            activate("PRESET ${preset.name}")
            for ((macro, value) in preset.macros) assertEquals(value, macroValue(macro), 0f)
            dialMacro("BLEND", .63f)
            dialMacro("DECAY", .42f)
            // The draft itself carries the latest controls and the renderer,
            // independently of the separate macro bag or preset highlight.
            compose.runOnIdle {
                val draft = SynthDrafts.read(settings).filterIsInstance<OnsetPatch>().single { it.voice == voice }
                assertEquals(4, draft.modelVersion)
                assertEquals(.63f, draft.macros.getValue("BLEND"), 0f)
            }
            leaveAndReturn(beforeReturn = {
                // A complete native draft wins even when the separate legacy
                // slider bag missed the last edit before navigation.
                settings["macro.ONSET.${voice.name}.BLEND"] = ".01"
                settings["macro.ONSET.${voice.name}.DECAY"] = ".99"
            })
            button(label).assertIsSelected()
            assertEquals(.63f, macroValue("BLEND"), 0f)
            assertEquals(.42f, macroValue("DECAY"), 0f)
            val slot = index + 1
            updatedKit.set(null)
            activate("SEND TO PAD")
            activate("${PadBanks.tag(slot)}, EMPTY")
            waitForPadWrite("the native $voice pad", timeoutMillis = 60_000) { updatedKit.get()?.pad(slot)?.recipe != null }
            val pad = checkNotNull(KitStore.load(dir).pad(slot))
            val recipe = PadRecipe.fromJsonValue(checkNotNull(pad.recipe))
            val saved = recipe.patch as OnsetPatch
            assertEquals(voice, saved.voice)
            assertEquals(4, saved.modelVersion)
            assertEquals(preset.macros + ("BLEND" to .63f) + ("DECAY" to .42f), saved.macros)
            assertEquals(if (voice == OnsetVoice.TOM) DrumClass.TOM else DrumClass.PERC, pad.drumClass)
            assertEquals(0, pad.muteGroup)
            assertTrue(pad.oneShot && !pad.loop)
            assertTrue(recipe.fx == null)
            val expected = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }.toByteArray()
            assertArrayEquals(expected, File(dir, pad.sampleFile).readBytes())
        }

        val wood = checkNotNull(KitStore.load(dir).pad(3))
        activate("REOPEN PAD RECIPE")
        activate("REOPEN ${PadBanks.tag(wood.slot)} ${wood.displayName}")
        button("WOOD").assertIsSelected()
        dialMacro("BODY", .71f)
        leaveAndReturn()
        updatedKit.set(null)
        activate("UPDATE PAD ${PadBanks.tag(wood.slot)}")
        waitForPadWrite("the reopened native wood pad", timeoutMillis = 60_000)
        val updated = PadRecipe.fromJsonValue(checkNotNull(KitStore.load(dir).pad(3)?.recipe)).patch as OnsetPatch
        assertEquals(OnsetVoice.PERC_WOOD, updated.voice)
        assertEquals(4, updated.modelVersion)
        assertEquals(.71f, updated.macros.getValue("BODY"), 0f)
    }

    @Test
    fun original_and_model_three_recipes_keep_their_renderers_when_reopened_after_model_four() {
        val root = shelfRoot()
        val dir = File(root, "HISTORIC ONSET").apply { check(mkdirs()) }
        val patches = listOf(
            OnsetPatch("ORIGINAL KICK", OnsetVoice.KICK, modelVersion = 1),
            OnsetPatch("KEPT SNARE", OnsetVoice.SNARE, modelVersion = 3),
        )
        val recipes = patches.map { PadRecipe(it, FxChain(reverse = true), treatment = "KEPT", amount = .31f, alias = true) }
        val pads = recipes.mapIndexed { index, recipe ->
            val file = "historic-${index + 1}.wav"
            WavWriter.write(File(dir, file), recipe.render())
            KitPad(index + 1, file, displayName = patches[index].name,
                drumClass = if (index == 0) DrumClass.KICK else DrumClass.SNARE, recipe = recipe.toJsonValue())
        }
        val kit = Kit("HISTORIC ONSET", pads)
        KitStore.save(kit, dir)
        show(root, KitShelf.Entry(dir, kit))
        for ((index, pad) in pads.withIndex()) {
            activate("REOPEN PAD RECIPE")
            activate("REOPEN ${PadBanks.tag(pad.slot)} ${pad.displayName}")
            dialMacro("BLEND", .82f)
            val expected = recipes[index].copy(patch = patches[index].copy(macros = patches[index].macros + ("BLEND" to .82f)))
            leaveAndReturn()
            updatedKit.set(null)
            activate("UPDATE PAD ${PadBanks.tag(pad.slot)}")
            waitForPadWrite("the historical model-${patches[index].modelVersion} pad", timeoutMillis = 60_000)
            val storedPad = checkNotNull(KitStore.load(dir).pad(pad.slot))
            val stored = PadRecipe.fromJsonValue(checkNotNull(storedPad.recipe))
            assertEquals(expected, stored)
            assertEquals(patches[index].modelVersion, (stored.patch as OnsetPatch).modelVersion)
            val expectedWav = ByteArrayOutputStream().also { WavWriter.write(it, expected.render()) }.toByteArray()
            assertArrayEquals(expectedWav, File(dir, storedPad.sampleFile).readBytes())
        }
    }

    @Test
    fun reopening_and_updating_a_saved_onset_pad_keeps_its_renderer_rack_and_pad_settings() {
        val root = shelfRoot()
        val dir = File(root, "KEPT KIT").apply { check(mkdirs()) }
        val patch = OnsetPatch(
            "KEPT KICK",
            OnsetVoice.KICK,
            Onset.defaults(OnsetVoice.KICK) + ("BLEND" to .25f) + ("TUNE" to .61f),
            modelVersion = 2,
        )
        val recipe = PadRecipe(
            patch = patch,
            fx = FxChain(reverse = true, eq = Eq.defaults() + ("AIR" to .7f)),
            treatment = "KEPT RACK",
            amount = .42f,
            alias = true,
        )
        val pad = KitPad(
            slot = 17,
            sampleFile = "kept.wav",
            displayName = "MY KICK",
            drumClass = DrumClass.KICK,
            colorHex = "#123456",
            level = .63f,
            pan = .2f,
            tuneCoarse = 3,
            tuneFine = -7,
            muteGroup = 7,
            oneShot = false,
            source = mapOf("origin" to "imported audition", "rawSha256" to "previous-wave-fingerprint"),
            recipe = recipe.toJsonValue(),
            attack = .13f,
            decay = .7f,
            cutoff = .6f,
            resonance = .2f,
            humanize = .17f,
        )
        val kit = Kit("KEPT KIT", listOf(pad), tempoBpm = 104f)
        val sample = WavWriter.write(File(dir, pad.sampleFile), recipe.render())
        val before = sample.readBytes()
        KitStore.save(kit, dir)
        show(root, KitShelf.Entry(dir, kit))

        // Start on THUMP and let the imported recipe select ONSET itself.
        activate("REOPEN PAD RECIPE")
        activate("REOPEN ${PadBanks.tag(pad.slot)} ${pad.displayName}")
        button("ENGINE ONSET. CHOOSE ENGINE").assertExists()
        button("KICK").assertIsSelected()
        for ((macro, value) in patch.macros) {
            assertEquals("reopened $macro", value, macroValue(macro), 0f)
        }
        dialMacro("BLEND", .85f)
        dialMacro("DECAY", .6f)
        val updateLabel = "UPDATE PAD ${PadBanks.tag(pad.slot)}"
        val expectedPatch = patch.copy(macros = patch.macros + ("BLEND" to .85f) + ("DECAY" to .6f))
        val expectedRecipe = recipe.copy(patch = expectedPatch)

        // Leaving SYNTH must flush the complete working recipe, not just its
        // sliders. The UPDATE target and legacy rack survive a fresh mount.
        leaveAndReturn()
        button("ENGINE ONSET. CHOOSE ENGINE").assertExists()
        button("KICK").assertIsSelected()
        button(updateLabel).assertExists()
        for ((macro, value) in expectedPatch.macros) {
            assertEquals("restored $macro", value, macroValue(macro), 0f)
        }
        activate(updateLabel)
        waitForPadWrite("the edited ONSET pad to be saved")

        val saved = checkNotNull(updatedKit.get())
        val savedPad = checkNotNull(saved.pad(pad.slot))
        assertEquals(expectedRecipe, PadRecipe.fromJsonValue(checkNotNull(savedPad.recipe)))
        assertEquals(
            pad.copy(recipe = expectedRecipe.toJsonValue(), source = pad.source - "rawSha256"),
            savedPad,
        )
        assertEquals(saved, KitStore.load(dir))
        val written = sample.readBytes()
        assertFalse("the macro edit must regenerate the WAV", before.contentEquals(written))
        val expectedWav = ByteArrayOutputStream().also { WavWriter.write(it, expectedRecipe.render()) }.toByteArray()
        assertArrayEquals("updated audio must include the saved rack and model-2 renderer", expectedWav, written)

        // A second visit after the save must still know the source pad. An
        // unchanged update proves the restored rack/model produce the same WAV.
        updatedKit.set(null)
        leaveAndReturn()
        button(updateLabel).assertExists()
        activate(updateLabel)
        waitForPadWrite("the restored ONSET pad to be saved again")
        assertEquals(saved, updatedKit.get())
        assertEquals(saved, KitStore.load(dir))
        assertArrayEquals("a restored unchanged recipe must regenerate identically", expectedWav, sample.readBytes())

        // The shelf-relative source identity must never become the same slot
        // in a different kit merely because the player navigated there.
        val otherDir = File(root, "OTHER KIT").apply { check(mkdirs()) }
        val otherKit = Kit("OTHER KIT", emptyList())
        KitStore.save(otherKit, otherDir)
        leaveAndReturn(KitShelf.Entry(otherDir, otherKit))
        button("ENGINE ONSET. CHOOSE ENGINE").assertExists()
        button(updateLabel).assertDoesNotExist()
        assertEquals(otherKit, KitStore.load(otherDir))
        assertEquals(saved, KitStore.load(dir))
    }
}
