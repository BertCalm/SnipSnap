package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.FlockPadMaker
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.FlockPlayback
import com.snipsnap.synth.FlockVoice
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real SYNTH bindings: mode/release never replace a saved pitch, full-width seed or old model. */
@RunWith(AndroidJUnit4::class)
class FlockSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "flock-synth-${System.nanoTime()}").apply { mkdirs() }
    private val settings = mutableMapOf("engine" to "FLOCK", "voice" to "RIBBON")
    private var generation by mutableStateOf(0)
    private var entry by mutableStateOf<KitShelf.Entry?>(null)
    private var kitUpdates = 0

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(source: FlockPatch = FlockPatch("SAVED RIBBON", FlockVoice.RIBBON,
        Flock.defaults(FlockVoice.RIBBON), midi = 67, velocity = .42f,
        seed = 9_007_199_254_740_993L, model = 1)) {
        SynthWork.replace(settings, listOf(SynthWork.Working(PadRecipe(patch = source), null)))
        val dir = File(shelf, "kit").apply { mkdirs() }
        val kit = Kit(name = "FLOCK TEST", pads = emptyList())
        KitStore.save(kit, dir)
        entry = KitShelf.Entry(dir, kit)
        setPhoneContent {
            key(generation) {
                SynthScreen(
                    entry = entry,
                    shelfRoot = shelf,
                    onToast = {},
                    onKitUpdated = { updated -> entry = entry?.copy(kit = updated); kitUpdates++ },
                    onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> },
                    onOpenSurface = {},
                    appScope = appScope,
                    settings = settings,
                )
            }
        }
    }

    // Semantics are also the production accessibility actions. They reach controls
    // below the phone's fold without animating a scroll on the harness's frozen clock.
    private fun activate(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        pump(16)
    }

    private fun dial(label: String, value: Float) {
        button(label).performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
        pump(16)
    }

    private fun progress(label: String): Float = button(label).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun workingRibbon(): FlockPatch = SynthWork.read(settings).single {
        it.recipe.patch?.voiceName == "RIBBON"
    }.recipe.patch as FlockPatch

    @Test
    fun onlyRibbonHasAnExplicitHeldChoiceAndFiniteHoldKeepsItsMeaning() {
        show()
        button("RIBBON FINITE").assertIsSelected()
        button("HOLD").assertExists()
        button("RELEASE").assertDoesNotExist()
        button("MAKE INSTRUMENT").assertDoesNotExist()
        activate("RIBBON HELD")
        button("RIBBON HELD").assertIsSelected()
        button("HOLD").assertDoesNotExist()
        button("RELEASE").assertExists()
        button("MAKE INSTRUMENT").assertExists()
        for (voice in listOf("GATHER", "SPLIT")) {
            activate(voice)
            button("HOLD").assertExists()
            button("RIBBON HELD").assertDoesNotExist()
            button("MAKE INSTRUMENT").assertDoesNotExist()
        }
    }

    @Test
    fun heldRecipeSurvivesEditsVoiceSwitchNavigationSavingAndOneShotSend() {
        show()
        activate("RIBBON HELD")
        dial("ROOT", (64 - Flock.MIN_MIDI).toFloat() / (Flock.MAX_MIDI - Flock.MIN_MIDI))
        dial("VELOCITY", .31f)
        dial("FLOW", .22f)
        dial("RELEASE", .83f)
        val release = FlockPadMaker.RELEASE.value(.83f)
        activate("GATHER")
        activate("RIBBON")
        button("RIBBON HELD").assertIsSelected()
        assertEquals(.83f, progress("RELEASE"), .0001f)
        compose.runOnIdle { generation++ }
        pump(16)
        button("RIBBON HELD").assertIsSelected()
        assertEquals(.22f, progress("FLOW"), .0001f)
        assertEquals(.83f, progress("RELEASE"), .0001f)

        activate("SAVE PRESET")
        activate("SAVE")
        waitFor("saved held RIBBON") { UserPresets.read(shelf).isNotEmpty() }
        val saved = UserPresets.read(shelf).single().patch as FlockPatch
        assertPreserved(saved, release)

        activate("SEND TO PAD")
        activate("A01, EMPTY")
        waitFor("held RIBBON one-shot delivery", timeoutMillis = 180_000) { kitUpdates > 0 }
        val pad = KitStore.load(entry!!.dir).pad(1)!!
        val delivered = PadRecipe.fromJsonValue(pad.recipe!!)
        assertTrue(pad.oneShot)
        assertEquals(20f, delivered.terminalFadeMs)
        assertPreserved(delivered.patch as FlockPatch, release)
    }

    @Test
    fun instrumentSheetReleaseIsSavedAndSurvivesFiniteRoundTrip() {
        show()
        activate("RIBBON HELD")
        activate("MAKE INSTRUMENT")
        // The sheet has its own release control in addition to the obscured bench.
        val controls = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("RELEASE"))
        controls[controls.fetchSemanticsNodes().lastIndex]
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
        pump(16)
        compose.runOnIdle { assertEquals(1.5f, workingRibbon().releaseSeconds, .00001f) }
        // CANCEL is shared by the scrim and action; invoke the action's visible text.
        val cancels = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("CANCEL"))
        cancels[cancels.fetchSemanticsNodes().lastIndex]
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        pump(16)
        activate("RIBBON FINITE")
        button("HOLD").assertExists()
        assertFalse(workingRibbon().playback == FlockPlayback.HELD)
        compose.runOnIdle { generation++ }
        pump(16)
        activate("RIBBON HELD")
        assertEquals(1f, progress("RELEASE"), .0001f)
        compose.runOnIdle { assertEquals(1.5f, workingRibbon().releaseSeconds, .00001f) }
    }

    @Test
    fun nativePreviewCachesTheCaptureAndReleasesOnTheNextAccessibleClick() {
        var patch by mutableStateOf(FlockPatch("CACHE TEST", FlockVoice.RIBBON,
            Flock.defaults(FlockVoice.RIBBON), playback = FlockPlayback.HELD))
        var preview: FlockHeldPreviewState? = null
        var failed = false
        setPhoneContent {
            val held = rememberFlockHeldPreview(patch) { failed = true }!!
            preview = held
            FlockHeldAudition(held, androidx.compose.ui.graphics.Color.Cyan,
                com.snipsnap.shell.Schemes.DEFAULT)
        }
        waitFor("native held preview", timeoutMillis = 180_000) { failed || preview?.ready == true }
        assertFalse("held capture failed", failed)
        val capture = preview!!.snip
        val playerState = preview
        compose.runOnIdle {
            patch = patch.copy(releaseSeconds = .1f, macros = patch.macros + ("HOLD" to .99f))
        }
        pump(16)
        assertSame("release and finite HOLD do not recapture PCM", playerState, preview)
        assertSame(capture, preview!!.snip)
        activate("AUDITION")
        assertTrue("the prepared native sample player starts on a press", preview!!.sounding)
        activate("AUDITION")
        assertFalse("accessible release sends note-off", preview!!.sounding)
        assertSame("note-on/off never recapture PCM", capture, preview!!.snip)
    }

    private fun assertPreserved(patch: FlockPatch, release: Float) {
        assertEquals(FlockPlayback.HELD, patch.playback)
        assertEquals(64, patch.midi)
        assertEquals(.31f, patch.velocity)
        assertEquals(9_007_199_254_740_993L, patch.seed)
        assertEquals(1, patch.model)
        assertEquals(.22f, patch.macros.getValue("FLOW"))
        assertEquals(release, patch.releaseSeconds, .00001f)
    }
}
