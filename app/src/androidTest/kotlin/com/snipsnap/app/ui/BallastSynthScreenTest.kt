package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.BallastPadMaker
import com.snipsnap.synth.Ballast
import com.snipsnap.synth.BallastVoice
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** The phone's MAKE INSTRUMENT path for BALLAST exports the edited sound as thirteen held zones. */
@RunWith(AndroidJUnit4::class)
class BallastSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val written = AtomicBoolean(false)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "ballast-synth-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(voice: BallastVoice, macros: Map<String, Float>) {
        settings["engine"] = "BALLAST"
        settings["voice"] = voice.name
        for ((macro, value) in macros) settings["macro.BALLAST.${voice.name}.$macro"] = value.toString()
        setPhoneContent {
            SynthScreen(entry = null, shelfRoot = shelf, onToast = {}, onKitUpdated = {},
                onShelfAssetWritten = { written.set(true) }, onDroneToLoop = { _, _, _ -> },
                onOpenSurface = {}, appScope = appScope, settings = settings)
        }
    }

    private fun click(label: String) {
        val node = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun dial(label: String, value: Float) {
        val node = compose.onNode(hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        val action = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("no slider for $label")
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    @Test
    fun make_instrument_exports_thirteen_held_zones_of_the_edited_sound() {
        val voice = BallastVoice.ROOT
        val macros = Ballast.defaults(voice) + ("GLASS" to .6f)
        show(voice, macros)
        click("MAKE INSTRUMENT")
        compose.onNodeWithText("THEN HOLDS THE SETTLED BASS.", substring = true).assertExists()
        dial("RELEASE", .5f)
        click("MAKE")
        // Real offline DSP is deliberately not tied to the Compose frame clock.
        waitFor("BALLAST instrument package", timeoutMillis = 300_000) { written.get() }

        val (sidecar, instrument) = InstrumentStore.list(File(shelf, KitShelf.INSTRUMENTS_DIR)).single()
        val spec = BallastPadMaker.spec(voice, macros, .5f)
        assertEquals(spec.releaseSeconds, instrument.release, 1e-6f)
        assertEquals(BallastPadMaker.zoneMidis(spec), instrument.zones.map { it.rootNote })
        assertTrue("every zone holds", instrument.zones.all { it.loopStartFrame > 0 })
        val midi = 42
        val zone = instrument.zones.single { it.rootNote == midi }
        val expected = BallastPadMaker.renderZone(spec, midi)
        val actual = WavReader.read(File(sidecar.parentFile, zone.sample))
        assertEquals(expected.loopStartFrame, zone.loopStartFrame)
        assertEquals(expected.snip.frameCount, actual.frameCount)
        assertTrue("phone MAKE keeps the edited GLASS within PCM24 rounding",
            actual.samples.indices.all { abs(actual.samples[it] - expected.snip.samples[it]) < 2e-7f })
    }
}
