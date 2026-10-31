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
import com.snipsnap.synth.Eq
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmGesture
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import com.snipsnap.synth.Onset
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetVoice
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
    fun legacyEncounterRetainsItsIdentityThroughEditsAndNavigation() {
        val source = OhmPatch("MY FAMILY", Ohm.defaults() + ("LENGTH" to .1f), midi = 57,
            velocity = .65f, seed = -905, gesture = OhmGesture.GATHER, model = 1)
        settings["patch.OHM.STRING"] = source.toJsonText()
        settings["preset.OHM.STRING"] = "Gather"
        show()
        button("OHM GATHER").assertIsSelected()
        assertEquals((57 - 48) / 24f, progress("NOTE"), .0001f)

        adjust("NOTE", (64 - 48) / 24f)
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
        assertEquals((64 - 48) / 24f, progress("NOTE"), .0001f)
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
        button("SCRAMBLE").performSemanticsAction(SemanticsActions.OnClick) { it() }
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
    fun foreignRecipeInTheMakeBagFallsBackToANewModelTwoEncounter() {
        settings["patch.OHM.STRING"] = """{"engine":"THUMP","version":1,"name":"OTHER","voice":"KICK","macros":{}}"""
        show()
        button("OHM STEADY").assertIsSelected()
        adjust("VELOCITY", .4f)
        compose.runOnIdle {
            val patch = OhmPatch.fromJsonText(settings.getValue("patch.OHM.STRING"))
            assertEquals(2, patch.model)
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
                    onToast = {},
                    onKitUpdated = { kit ->
                        updatedKit.set(kit)
                        currentEntry = currentEntry?.copy(kit = kit)
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
    private fun leaveAndReturn(entry: KitShelf.Entry? = currentEntry) {
        compose.runOnIdle { screenVisible = false }
        pump()
        button("ENGINE ONSET. CHOOSE ENGINE").assertDoesNotExist()
        compose.runOnIdle {
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

    @Test
    fun onset_follows_skin_and_all_four_voices_expose_their_six_live_controls() {
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
        )
        for ((label, voice) in voices) {
            tap(label)
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
        waitFor("the edited ONSET pad to be saved", timeoutMillis = 30_000) { updatedKit.get() != null }

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
        waitFor("the restored ONSET pad to be saved again", timeoutMillis = 30_000) { updatedKit.get() != null }
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
