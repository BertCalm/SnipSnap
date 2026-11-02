package com.snipsnap.app.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.InstrumentStore
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    private val instrumentUpdates = AtomicInteger()
    private val latestToast = AtomicReference("")

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
                    onToast = { latestToast.set(it) },
                    onKitUpdated = { updated -> entry = entry?.copy(kit = updated); kitUpdates++ },
                    onShelfAssetWritten = { instrumentUpdates.incrementAndGet() },
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

    private fun cancelInstrumentSheet() {
        // CANCEL is shared by the scrim and action; use the action's final node.
        val cancels = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("CANCEL"))
        cancels[cancels.fetchSemanticsNodes().lastIndex]
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        pump(16)
    }

    /** The DSP follows wall time, not the manual Compose clock. Limit this local
     * long-render poll to one frame per 40 ms so it does not spin UI work against
     * the serial capture worker on a two-core emulator. A total deadline always
     * bounds the wait; a caller may also bound time without actual progress. */
    private fun waitForBackgroundRender(
        what: String,
        timeoutMillis: Long,
        progressTimeoutMillis: Long? = null,
        diagnostic: () -> String,
        condition: () -> Boolean,
    ) {
        val started = SystemClock.elapsedRealtime()
        var lastProgress = diagnostic()
        var lastChangedAt = started
        var nextDiagnosticAt = started + 1_000
        val history = mutableListOf("0 ms: $lastProgress")
        while (!condition()) {
            val now = SystemClock.elapsedRealtime()
            val timedOut = now - started >= timeoutMillis
            val stalled = progressTimeoutMillis?.let { now - lastChangedAt >= it } == true
            if (timedOut || stalled) {
                val reason = if (stalled) "no progress for $progressTimeoutMillis ms"
                    else "total deadline of $timeoutMillis ms reached"
                throw AssertionError("$what never came after ${now - started} ms ($reason); " +
                    "last progress: $lastProgress (unchanged for ${now - lastChangedAt} ms); " +
                    "progress history: ${history.joinToString("; ")}; latest toast: ${latestToast.get()}")
            }
            SystemClock.sleep(40)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val sampledAt = SystemClock.elapsedRealtime()
            if (sampledAt >= nextDiagnosticAt) {
                val progress = diagnostic()
                if (progress != lastProgress) {
                    lastProgress = progress
                    lastChangedAt = sampledAt
                    val event = "${sampledAt - started} ms: $progress"
                    history += event
                    Log.i("FlockSynthScreenTest", "$what: $event")
                }
                nextDiagnosticAt = sampledAt + 1_000
            }
        }
        Log.i("FlockSynthScreenTest", "$what completed in ${SystemClock.elapsedRealtime() - started} ms")
    }

    private fun instrumentRenderProgress(): String {
        val progress = compose.onAllNodes(SemanticsMatcher("instrument render counter") { node ->
            node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null &&
                node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { it.startsWith("RENDERING ") } == true
        }).fetchSemanticsNodes().singleOrNull()
        return progress?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: "no instrument render counter on screen"
    }

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
        rootTo(64)
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
        cancelInstrumentSheet()
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
    fun ribbonTouchLayersOpenEnabledCanReturnToSingleAndLeaveTheSourceUntouched() {
        show()
        activate("RIBBON HELD")
        val source = workingRibbon().toJsonText()
        activate("MAKE INSTRUMENT")
        button("TOUCH LAYERS").assertIsSelected()
        assertReadsInFull("TOUCH LAYERS ON")
        button("PREVIEW").assertExists()
        button("INSTRUMENT AUDITION").assertDoesNotExist()
        compose.onNodeWithText("ON: SOFT, MID AND HARD TOUCH CHANGE FLOCK MOTION. VELOCITY SETS THE HARD TOUCH. THREE SAMPLES PER NOTE; ABOUT 3× THE TIME AND STORAGE.")
            .fetchSemanticsNode()
        activate("TOUCH LAYERS")
        button("TOUCH LAYERS").assertIsNotSelected()
        assertReadsInFull("TOUCH LAYERS OFF")
        button("PREVIEW").assertDoesNotExist()
        button("INSTRUMENT AUDITION").assertExists()
        assertEquals(source, workingRibbon().toJsonText())
        val instruments = File(shelf, KitShelf.INSTRUMENTS_DIR)
        assertTrue("choosing dynamics must not write an instrument", !instruments.exists() || instruments.listFiles().isNullOrEmpty())
        cancelInstrumentSheet()
        activate("MAKE INSTRUMENT")
        button("TOUCH LAYERS").assertIsNotSelected()
        activate("TOUCH LAYERS")
        button("TOUCH LAYERS").assertIsSelected()
        assertEquals(source, workingRibbon().toJsonText())
    }

    @Test
    fun makeWritesThreeIndependentVelocityLayersAndPreservesTheAuthoredRecipe() {
        val source = FlockPatch("VELOCITY RIBBON", FlockVoice.RIBBON,
            Flock.defaults(FlockVoice.RIBBON) + ("FLOW" to .22f), midi = 67, velocity = .42f,
            seed = 9_007_199_254_740_993L, model = 1, playback = FlockPlayback.HELD,
            releaseSeconds = .37f)
        show(source)
        activate("MAKE INSTRUMENT")
        button("TOUCH LAYERS").assertIsSelected()
        activate("MAKE")
        // Android 14 debug CI reached 24/27 at 236.159 s, completing each
        // three-sample root in about 28.4 s. Nine roots project to about 265 s.
        // Keep the incoming 420 s bank/write budget, while 75 s with no
        // displayed progress still fails a stalled capture promptly.
        waitForBackgroundRender("three-layer RIBBON instrument", timeoutMillis = 420_000,
            progressTimeoutMillis = 75_000,
            diagnostic = ::instrumentRenderProgress) { instrumentUpdates.get() > 0 }
        val dir = File(shelf, KitShelf.INSTRUMENTS_DIR)
        val sidecar = dir.listFiles()!!.single { it.name.endsWith(InstrumentStore.SUFFIX) }
        val instrument = InstrumentStore.read(sidecar)
        assertEquals(9, instrument.zones.size)
        assertEquals(27, instrument.sampleFiles.size)
        assertEquals(.37f, instrument.release, .00001f)
        for (zone in instrument.zones) {
            assertEquals(listOf(1..42, 43..84, 85..127), zone.playableLayers.map { it.velStart..it.velEnd })
            assertEquals(3, zone.playableLayers.map { it.sample }.distinct().size)
            for (layer in zone.playableLayers) {
                assertTrue("layer keeps its source onset", layer.loopStartFrame > 0)
                assertTrue("layer has a nonempty sustain", layer.loopStartFrame < layer.frameCount - 1)
                assertTrue("every velocity sample is exported", File(dir, layer.sample).isFile)
            }
        }
        val recipe = FlockPatch.fromJsonText(File(dir, "${instrument.name}.patch.json").readText())
        assertEquals(source.copy(name = recipe.name), recipe)
        assertEquals(source.copy(name = workingRibbon().name), workingRibbon())
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
        waitForBackgroundRender("native held preview", timeoutMillis = 180_000,
            diagnostic = { "rendering=${preview?.rendering}, ready=${preview?.ready}, failed=$failed" }) {
            failed || preview?.ready == true
        }
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
